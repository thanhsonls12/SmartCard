import {
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
  UnauthorizedException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { randomBytes } from 'node:crypto';
import { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import { buildProfileMessage, PROFILE_HASH_BYTES } from '../profile/profile-message.js';
import { requireP256PublicKey, sha256PublicKey, verifyEnrollmentSignature } from './enrollment-crypto.js';
import { isUuid, parseBody, parseHexBytes, parseSignatureHex } from './enrollment-input.js';
import { CARD_ID_BYTES, NONCE_BYTES } from './enrollment-message.js';

export interface IssuedAdminSession {
  sessionId: string;
  nonce: string;
  expiresAt: Date;
}

export interface ActivatedAdminSession {
  sessionId: string;
  cardId: string;
  expiresAt: Date;
}

const SESSION_TTL_MS = 30 * 60 * 1000;

@Injectable()
export class AdminSessionService {
  constructor(private readonly database: DatabaseService) {}

  async issueSession(staffSessionId: string): Promise<IssuedAdminSession> {
    const nonce = randomBytes(NONCE_BYTES);
    const session = await this.database.adminSession.create({
      data: { nonce: toStoredBytes(nonce), expiresAt: new Date(Date.now() + SESSION_TTL_MS), staffSessionId },
      select: { id: true, expiresAt: true },
    });

    return { sessionId: session.id, nonce: nonce.toString('hex'), expiresAt: session.expiresAt };
  }

  async activateSession(sessionId: unknown, body: unknown, staffSessionId: string): Promise<ActivatedAdminSession> {
    const session = await this.requireSessionRow(sessionId, staffSessionId);
    if (session.activatedAt !== null) {
      throw new ConflictException('admin session is already activated');
    }

    const payload = parseBody(body);
    const cardId = parseHexBytes(payload['cardId'], CARD_ID_BYTES, 'cardId');
    const profileHash = parseHexBytes(payload['profileHash'], PROFILE_HASH_BYTES, 'profileHash');
    const signature = parseSignatureHex(payload['signature']);

    const student = await this.database.student.findUnique({
      where: { cardId: toStoredBytes(cardId) },
      select: { publicKey: true },
    });
    if (student === null) {
      throw new NotFoundException('cardId is not enrolled');
    }

    const publicKey = Buffer.from(student.publicKey);
    try {
      requireP256PublicKey(publicKey);
    } catch {
      throw new UnprocessableEntityException('enrolled publicKey is not a P-256 point');
    }

    const message = buildProfileMessage({
      cardId,
      publicKeyHash: sha256PublicKey(publicKey),
      nonce: Buffer.from(session.nonce),
      profileHash,
    });
    if (!verifyEnrollmentSignature({ publicKey, message, signature })) {
      throw new UnprocessableEntityException('admin session signature does not verify');
    }

    const activated = await this.database.adminSession.updateMany({
      where: { id: session.id, activatedAt: null },
      data: { activatedAt: new Date(), cardId: toStoredBytes(cardId) },
    });
    if (activated.count !== 1) {
      throw new ConflictException('admin session is already activated');
    }

    return { sessionId: session.id, cardId: cardId.toString('hex'), expiresAt: session.expiresAt };
  }

  async requireActiveSession(headerValue: unknown, staffSessionId: string): Promise<string> {
    const session = await this.requireSessionRow(headerValue, staffSessionId);
    return session.id;
  }

  async requireCardSession(headerValue: unknown, rawCardId: string, staffSessionId: string): Promise<string> {
    const session = await this.requireSessionRow(headerValue, staffSessionId);
    if (session.activatedAt === null || session.cardId === null) {
      throw new UnauthorizedException('admin session is not activated with a card');
    }

    const cardId = parseHexBytes(rawCardId, CARD_ID_BYTES, 'cardId');
    if (!Buffer.from(session.cardId).equals(cardId)) {
      throw new ForbiddenException('admin session is bound to another card');
    }
    return session.id;
  }

  private async requireSessionRow(rawSessionId: unknown, staffSessionId: string) {
    if (!isUuid(rawSessionId)) {
      throw new UnauthorizedException('admin session must be a uuid');
    }

    const session = await this.database.adminSession.findUnique({
      where: { id: rawSessionId },
      select: {
        id: true,
        nonce: true,
        cardId: true,
        activatedAt: true,
        expiresAt: true,
        revokedAt: true,
        staffSessionId: true,
      },
    });

    if (session === null || session.revokedAt !== null || session.expiresAt.getTime() <= Date.now()) {
      throw new UnauthorizedException('admin session is not active');
    }
    if (session.staffSessionId !== staffSessionId) {
      throw new ForbiddenException('admin session belongs to another staff session');
    }
    return session;
  }
}
