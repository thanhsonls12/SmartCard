import { BadRequestException, ConflictException, Injectable, NotFoundException, UnauthorizedException, UnprocessableEntityException } from '@nestjs/common';
import { randomBytes } from 'node:crypto';
import { Prisma } from '../generated/prisma/client.js';
import { DatabaseService } from '../database/database.service.js';
import { requireP256PublicKey, sha256PublicKey, verifyEnrollmentSignature } from './enrollment-crypto.js';
import { parseBody, parseHexBytes, parseSignatureHex, parseStudentId, parseUuid } from './enrollment-input.js';
import { buildEnrollmentMessage, CARD_ID_BYTES, NONCE_BYTES, PUBLIC_KEY_BYTES } from './enrollment-message.js';

export interface EnrollmentChallengeResponse {
  challengeId: string;
  nonce: string;
  expiresAt: Date;
}

export interface EnrollmentResult {
  cardId: string;
  studentId: string;
  enrolledAt: Date;
}

const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const UNIQUE_VIOLATION_CODE = 'P2002';

function toStoredBytes(value: Buffer): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(value.byteLength);
  bytes.set(value);
  return bytes;
}

@Injectable()
export class EnrollmentService {
  constructor(private readonly database: DatabaseService) {}

  async createChallenge(adminSessionId: string, body: unknown): Promise<EnrollmentChallengeResponse> {
    const payload = parseBody(body);
    const cardId = parseHexBytes(payload['cardId'], CARD_ID_BYTES, 'cardId');
    const studentId = parseStudentId(payload['studentId']);
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

    try {
      return await this.database.$transaction(async (transaction) => {
        const consumed = await transaction.enrollmentChallenge.updateMany({
          where: { id: challenge.id, usedAt: null, expiresAt: { gt: new Date() } },
          data: { usedAt: new Date() },
        });
        if (consumed.count !== 1) {
          throw new ConflictException('enrollment challenge is no longer usable');
        }

        const student = await transaction.student.create({
          data: { cardId: toStoredBytes(cardId), studentId: challenge.studentId, publicKey: toStoredBytes(publicKey) },
          select: { enrolledAt: true },
        });
        await transaction.cardLedger.create({
          data: { cardId: toStoredBytes(cardId), lastCounter: 0n, lastBalance: 0n, activeVisitId: null },
        });

        return { cardId: cardId.toString('hex'), studentId: challenge.studentId, enrolledAt: student.enrolledAt };
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === UNIQUE_VIOLATION_CODE) {
        throw new ConflictException('cardId is already enrolled');
      }
      throw error;
    }
  }

  private requireEnrollablePublicKey(publicKey: Buffer): void {
    try {
      requireP256PublicKey(publicKey);
    } catch {
      throw new BadRequestException('publicKey is not a valid uncompressed P-256 point');
    }
  }
}
