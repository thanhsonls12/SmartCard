import { BadRequestException, ConflictException, Injectable, NotFoundException, UnauthorizedException, UnprocessableEntityException } from '@nestjs/common';
import { randomBytes } from 'node:crypto';
import { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import { requireP256PublicKey, sha256PublicKey, verifyEnrollmentSignature } from './enrollment-crypto.js';
import { parseBody, parseHexBytes, parseOptionalClassCode, parseSignatureHex, parseStudentId, parseUuid } from './enrollment-input.js';
import { buildEnrollmentMessage, CARD_ID_BYTES, NONCE_BYTES, PUBLIC_KEY_BYTES } from './enrollment-message.js';
import { recordEnrollment, type EnrollmentResult } from './record-enrollment.js';

export type { EnrollmentResult } from './record-enrollment.js';

export interface EnrollmentChallengeResponse {
  challengeId: string;
  nonce: string;
  expiresAt: Date;
}

export interface EnrolledCard {
  cardId: string;
  publicKey: string;
}

const CHALLENGE_TTL_MS = 5 * 60 * 1000;

@Injectable()
export class EnrollmentService {
  constructor(private readonly database: DatabaseService) {}

  async readEnrolledCard(rawCardId: string): Promise<EnrolledCard> {
    const cardId = parseHexBytes(rawCardId, CARD_ID_BYTES, 'cardId');
    const enrolled = await this.database.student.findUnique({
      where: { cardId: toStoredBytes(cardId) },
      select: { publicKey: true },
    });
    if (enrolled === null) {
      throw new NotFoundException('cardId is not enrolled');
    }
    const publicKey = Buffer.from(enrolled.publicKey);
    try {
      requireP256PublicKey(publicKey);
    } catch {
      throw new UnprocessableEntityException('enrolled publicKey is not a P-256 point');
    }
    return { cardId: cardId.toString('hex'), publicKey: publicKey.toString('hex') };
  }

  async createChallenge(adminSessionId: string, body: unknown): Promise<EnrollmentChallengeResponse> {
    const payload = parseBody(body);
    const cardId = parseHexBytes(payload['cardId'], CARD_ID_BYTES, 'cardId');
    const studentId = parseStudentId(payload['studentId']);
    const classCode = parseOptionalClassCode(payload['classCode']);
    const publicKey = parseHexBytes(payload['publicKey'], PUBLIC_KEY_BYTES, 'publicKey');
    this.requireEnrollablePublicKey(publicKey);

    const enrolled = await this.database.student.findUnique({ where: { cardId: toStoredBytes(cardId) }, select: { id: true } });
    if (enrolled !== null) {
      throw new ConflictException('cardId is already enrolled');
    }

    const nonce = randomBytes(NONCE_BYTES);
    const expiresAt = new Date(Date.now() + CHALLENGE_TTL_MS);
    const challenge = await this.database.enrollmentChallenge.create({
      data: {
        nonce: toStoredBytes(nonce),
        cardId: toStoredBytes(cardId),
        studentId,
        classCode: classCode ?? null,
        publicKey: toStoredBytes(publicKey),
        publicKeyHash: toStoredBytes(sha256PublicKey(publicKey)),
        adminSessionId,
        expiresAt,
      },
      select: { id: true },
    });

    return { challengeId: challenge.id, nonce: nonce.toString('hex'), expiresAt };
  }

  async completeEnrollment(adminSessionId: string, body: unknown): Promise<EnrollmentResult> {
    const payload = parseBody(body);
    const challengeId = parseUuid(payload['challengeId'], 'challengeId');
    const signature = parseSignatureHex(payload['signature']);

    const challenge = await this.database.enrollmentChallenge.findUnique({
      where: { id: challengeId },
      select: {
        id: true,
        nonce: true,
        cardId: true,
        studentId: true,
        classCode: true,
        publicKey: true,
        publicKeyHash: true,
        adminSessionId: true,
        expiresAt: true,
        usedAt: true,
      },
    });

    if (challenge === null) {
      throw new NotFoundException('enrollment challenge not found');
    }
    if (challenge.adminSessionId !== adminSessionId) {
      throw new UnauthorizedException('enrollment challenge belongs to another admin session');
    }
    if (challenge.usedAt !== null) {
      throw new ConflictException('enrollment challenge was already used');
    }
    if (challenge.expiresAt.getTime() <= Date.now()) {
      throw new ConflictException('enrollment challenge expired');
    }

    const cardId = Buffer.from(challenge.cardId);
    const publicKey = Buffer.from(challenge.publicKey);
    const message = buildEnrollmentMessage({
      cardId,
      publicKeyHash: Buffer.from(challenge.publicKeyHash),
      nonce: Buffer.from(challenge.nonce),
    });

    if (!verifyEnrollmentSignature({ publicKey, message, signature })) {
      throw new UnprocessableEntityException('enrollment signature does not verify');
    }

    return recordEnrollment(this.database, challenge, cardId, publicKey);
  }

  private requireEnrollablePublicKey(publicKey: Buffer): void {
    try {
      requireP256PublicKey(publicKey);
    } catch {
      throw new BadRequestException('publicKey is not a valid uncompressed P-256 point');
    }
  }
}
