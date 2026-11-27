import {
  ConflictException,
  ForbiddenException,
  NotFoundException,
  UnauthorizedException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../database/database.service.js';
import { buildProfileMessage } from '../profile/profile-message.js';
import { AdminSessionService } from './admin-session.service.js';

const CARD_ID = Buffer.from('a1b2c3d41122ef07', 'hex');
const OTHER_CARD_ID = Buffer.from('b1b2c3d41122ef07', 'hex');
const SESSION_ID = '22222222-2222-4222-8222-222222222222';
const STAFF_SESSION_ID = '33333333-3333-4333-8333-333333333333';
const SESSION_NONCE = Buffer.from('bb'.repeat(32), 'hex');
const PROFILE_HASH = Buffer.from('ee'.repeat(32), 'hex');

function makeCardKey() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' }) as { x: string; y: string };
  const point = Buffer.concat([
    Buffer.from([0x04]),
    Buffer.from(jwk.x, 'base64url'),
    Buffer.from(jwk.y, 'base64url'),
  ]);
  return { point, privateKey };
}

const cardKey = makeCardKey();
const otherKey = makeCardKey();

interface SessionRow {
  id: string;
  staffSessionId: string;
  nonce: Uint8Array;
  cardId: Uint8Array | null;
  activatedAt: Date | null;
  expiresAt: Date;
  revokedAt: Date | null;
}

function sessionRow(overrides: Partial<SessionRow> = {}): SessionRow {
  return {
    id: SESSION_ID,
    staffSessionId: STAFF_SESSION_ID,
    nonce: new Uint8Array(SESSION_NONCE),
    cardId: null,
    activatedAt: null,
    expiresAt: new Date(Date.now() + 30 * 60 * 1000),
    revokedAt: null,
    ...overrides,
  };
}

function signActivation(cardId: Buffer, nonce: Buffer, key = cardKey, profileHash = PROFILE_HASH): string {
  const message = buildProfileMessage({
    cardId,
    publicKeyHash: createHash('sha256').update(cardKey.point).digest(),
    nonce,
    profileHash,
  });
  return sign('sha256', message, key.privateKey).toString('hex');
}

function activationBody(overrides: Record<string, unknown> = {}) {
  return {
    cardId: CARD_ID.toString('hex'),
    profileHash: PROFILE_HASH.toString('hex'),
    signature: signActivation(CARD_ID, SESSION_NONCE),
    ...overrides,
  };
}

function makeDb(options: { rows?: SessionRow[]; publicKey?: Uint8Array | null } = {}) {
  const rows = options.rows ?? [];
  const adminSession = {
    create: vi.fn(async (args: { data: { nonce: Uint8Array } }) => {
      void args;
      return { id: SESSION_ID, expiresAt: new Date(Date.now() + 30 * 60 * 1000) };
    }),
    findUnique: vi.fn(async (args: { where: { id: string } }) => rows.find((row) => row.id === args.where.id) ?? null),
    updateMany: vi.fn(async (args: { where: { id: string; activatedAt: null }; data: Partial<SessionRow> }) => {
      const targets = rows.filter((row) => row.id === args.where.id && row.activatedAt === null);
      for (const target of targets) {
        Object.assign(target, args.data);
      }
      return { count: targets.length };
    }),
  };
  const publicKey = options.publicKey === undefined ? cardKey.point : options.publicKey;
  const student = {
    findUnique: vi.fn(async () => (publicKey === null ? null : { publicKey })),
  };

  const database = { adminSession, student } as unknown as DatabaseService;
  return { service: new AdminSessionService(database), rows, adminSession, student };
}

describe('AdminSessionService.issueSession', () => {
  it('issues a session with a fresh 32-byte challenge nonce', async () => {
    const db = makeDb();

    const issued = await db.service.issueSession(STAFF_SESSION_ID);

    expect(issued.sessionId).toBe(SESSION_ID);
    expect(issued.nonce).toMatch(/^[0-9a-f]{64}$/);
    expect(db.adminSession.create).toHaveBeenCalledOnce();
    expect(db.adminSession.create.mock.calls[0]?.[0].data.nonce).toHaveLength(32);
  });
});

describe('AdminSessionService.activateSession', () => {
  it('binds the session to the card that signed the challenge', async () => {
    const row = sessionRow();
    const db = makeDb({ rows: [row] });

    await expect(db.service.activateSession(SESSION_ID, activationBody(), STAFF_SESSION_ID)).resolves.toMatchObject({
      sessionId: SESSION_ID,
      cardId: CARD_ID.toString('hex'),
    });
    expect(row.activatedAt).toBeInstanceOf(Date);
    expect(Buffer.from(row.cardId!)).toEqual(CARD_ID);
  });

  it('rejects a signature from another card without touching the session', async () => {
    const row = sessionRow();
    const db = makeDb({ rows: [row] });

    await expect(
      db.service.activateSession(SESSION_ID, activationBody({
        signature: signActivation(CARD_ID, SESSION_NONCE, otherKey),
      }), STAFF_SESSION_ID),
    ).rejects.toBeInstanceOf(UnprocessableEntityException);
    expect(row.activatedAt).toBeNull();
    expect(row.cardId).toBeNull();
  });

  it('rejects a signature over a different profile hash without touching the session', async () => {
    const row = sessionRow();
    const db = makeDb({ rows: [row] });

    await expect(
      db.service.activateSession(SESSION_ID, activationBody({ profileHash: 'dd'.repeat(32) }), STAFF_SESSION_ID),
    ).rejects.toBeInstanceOf(UnprocessableEntityException);
    expect(row.activatedAt).toBeNull();
  });

  it('rejects a card that is not enrolled', async () => {
    const db = makeDb({ rows: [sessionRow()], publicKey: null });

    await expect(db.service.activateSession(SESSION_ID, activationBody(), STAFF_SESSION_ID)).rejects.toBeInstanceOf(NotFoundException);
  });

  it('rejects a second activation of the same session', async () => {
    const row = sessionRow({ activatedAt: new Date(), cardId: new Uint8Array(CARD_ID) });
    const db = makeDb({ rows: [row] });

    await expect(db.service.activateSession(SESSION_ID, activationBody(), STAFF_SESSION_ID)).rejects.toBeInstanceOf(ConflictException);
  });

  it('rejects an unknown or expired session before verifying anything', async () => {
    const db = makeDb();

    await expect(db.service.activateSession(SESSION_ID, activationBody(), STAFF_SESSION_ID)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
    await expect(db.service.activateSession('not-a-uuid', activationBody(), STAFF_SESSION_ID)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
    expect(db.student.findUnique).not.toHaveBeenCalled();
  });
});

describe('AdminSessionService.requireCardSession', () => {
  it('accepts an activated session bound to the same card', async () => {
    const db = makeDb({ rows: [sessionRow({ activatedAt: new Date(), cardId: new Uint8Array(CARD_ID) })] });

    await expect(db.service.requireCardSession(SESSION_ID, CARD_ID.toString('hex'), STAFF_SESSION_ID)).resolves.toBe(SESSION_ID);
  });

  it('refuses a session that has not been activated by a card', async () => {
    const db = makeDb({ rows: [sessionRow()] });

    await expect(db.service.requireCardSession(SESSION_ID, CARD_ID.toString('hex'), STAFF_SESSION_ID)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
  });

  it('refuses a session activated by another card', async () => {
    const db = makeDb({ rows: [sessionRow({ activatedAt: new Date(), cardId: new Uint8Array(OTHER_CARD_ID) })] });

    await expect(db.service.requireCardSession(SESSION_ID, CARD_ID.toString('hex'), STAFF_SESSION_ID)).rejects.toBeInstanceOf(
      ForbiddenException,
    );
  });

  it('refuses a revoked or expired session', async () => {
    const revoked = makeDb({ rows: [sessionRow({ activatedAt: new Date(), cardId: new Uint8Array(CARD_ID), revokedAt: new Date() })] });
    await expect(revoked.service.requireCardSession(SESSION_ID, CARD_ID.toString('hex'), STAFF_SESSION_ID)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );

    const expired = makeDb({ rows: [sessionRow({ activatedAt: new Date(), cardId: new Uint8Array(CARD_ID), expiresAt: new Date(Date.now() - 1000) })] });
    await expect(expired.service.requireCardSession(SESSION_ID, CARD_ID.toString('hex'), STAFF_SESSION_ID)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
  });
});
