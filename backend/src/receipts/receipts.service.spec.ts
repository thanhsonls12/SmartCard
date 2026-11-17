import {
  BadRequestException,
  ConflictException,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { createPrivateKey, generateKeyPairSync, sign } from 'node:crypto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../database/database.service.js';
import { receiptMessage, RECEIPT_PAYLOAD_BYTES } from './receipt-canonical.js';
import { ReceiptsService } from './receipts.service.js';

const CARD_ID = Buffer.from('a1b2c3d41122ef07', 'hex');

function buildGrantPayload(counter = 1, delta = 5, balance = 5): Buffer {
  const bytes = Buffer.alloc(RECEIPT_PAYLOAD_BYTES);
  bytes.writeUInt8(0x01, 0);
  CARD_ID.copy(bytes, 1);
  bytes.writeUInt32BE(counter, 9);
  bytes.writeUInt8(0x01, 13);
  bytes.writeInt16BE(delta, 14);
  bytes.writeUInt16BE(balance, 16);
  return bytes;
}

function makeKeyPair() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' }) as { x: string; y: string };
  const point = Buffer.concat([
    Buffer.from([0x04]),
    Buffer.from(jwk.x, 'base64url'),
    Buffer.from(jwk.y, 'base64url'),
  ]);
  return { point, privateKey };
}

const { point, privateKey } = makeKeyPair();

function signPayload(payload: Buffer): Buffer {
  return sign('sha256', receiptMessage(payload), { key: privateKey, dsaEncoding: 'der' });
}

interface TxStub {
  $queryRaw: ReturnType<typeof vi.fn>;
  cardLedger: { findUniqueOrThrow: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn> };
  transaction: { findUnique: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };
}

interface DbStub {
  service: ReceiptsService;
  tx: TxStub;
  studentFindUnique: ReturnType<typeof vi.fn>;
  transactionRunner: ReturnType<typeof vi.fn>;
}

function makeDb(options: {
  student?: { publicKey: Uint8Array } | null;
  ledger?: { lastCounter: bigint; lastBalance: bigint; activeVisitId: Uint8Array | null } | null;
  existing?: {
    counterAfter: bigint;
    operation: 'GRANT' | 'START' | 'END';
    delta: bigint;
    balanceAfter: bigint;
    visitId: Uint8Array | null;
    payload: Uint8Array;
    recordedAt: Date;
  } | null;
} = {}): DbStub {
  const tx: TxStub = {
    $queryRaw: vi.fn().mockResolvedValue([{ ok: 1 }]),
    cardLedger: {
      findUniqueOrThrow: vi
        .fn()
        .mockResolvedValue(options.ledger ?? { lastCounter: 0n, lastBalance: 0n, activeVisitId: null }),
      update: vi.fn().mockResolvedValue({}),
    },
    transaction: {
      findUnique: vi.fn().mockResolvedValue(options.existing ?? null),
      create: vi.fn().mockResolvedValue({ recordedAt: new Date('2026-11-12T08:00:00.000Z') }),
    },
  };
  const studentFindUnique = vi
    .fn()
    .mockResolvedValue(options.student === undefined ? { publicKey: point } : options.student);
  const transactionRunner = vi.fn(async (fn: (client: TxStub) => unknown) => fn(tx));

  const database = {
    student: { findUnique: studentFindUnique },
    $transaction: transactionRunner,
  } as unknown as DatabaseService;

  return { service: new ReceiptsService(database), tx, studentFindUnique, transactionRunner };
}

describe('ReceiptsService.verifyReceipt', () => {
  let db: DbStub;

  beforeEach(() => {
    db = makeDb();
  });

  it('records a valid GRANT and advances the ledger in the same transaction', async () => {
    const payload = buildGrantPayload();
    const result = await db.service.verifyReceipt({
      payload: payload.toString('hex'),
      signature: signPayload(payload).toString('hex'),
    });

    expect(result).toMatchObject({ status: 'recorded', counterAfter: 1, operation: 'GRANT', delta: 5, balanceAfter: 5 });
    expect(db.tx.cardLedger.update).toHaveBeenCalledOnce();
    expect(db.tx.transaction.create).toHaveBeenCalledOnce();
    const update = db.tx.cardLedger.update.mock.calls[0][0];
    expect(update.data).toMatchObject({ lastCounter: 1n, lastBalance: 5n, activeVisitId: null });
  });

  it('returns the stored record for the same canonical payload even when the signature bytes differ', async () => {
    const payload = buildGrantPayload();
    const secondSignature = signPayload(payload);
    const firstSignature = signPayload(payload);
    expect(secondSignature.equals(firstSignature)).toBe(false);

    db = makeDb({
      existing: {
        counterAfter: 1n,
        operation: 'GRANT',
        delta: 5n,
        balanceAfter: 5n,
        visitId: null,
        payload: new Uint8Array(payload),
        recordedAt: new Date('2026-11-12T07:00:00.000Z'),
      },
    });

    const result = await db.service.verifyReceipt({
      payload: payload.toString('hex'),
      signature: secondSignature.toString('hex'),
    });

    expect(result).toMatchObject({ status: 'duplicate', counterAfter: 1 });
    expect(db.tx.transaction.create).not.toHaveBeenCalled();
    expect(db.tx.cardLedger.update).not.toHaveBeenCalled();
  });

  it('reports a conflict when the same counter arrives with a different canonical payload', async () => {
    const payload = buildGrantPayload();
    db = makeDb({
      existing: {
        counterAfter: 1n,
        operation: 'GRANT',
        delta: 5n,
        balanceAfter: 5n,
        visitId: null,
        payload: new Uint8Array(buildGrantPayload(1, 7, 7)),
        recordedAt: new Date('2026-11-12T07:00:00.000Z'),
      },
    });

    await expect(
      db.service.verifyReceipt({ payload: payload.toString('hex'), signature: signPayload(payload).toString('hex') }),
    ).rejects.toBeInstanceOf(ConflictException);
  });

  it('rejects a gap instead of filling it in', async () => {
    const payload = buildGrantPayload(3, 5, 5);
    await expect(
      db.service.verifyReceipt({ payload: payload.toString('hex'), signature: signPayload(payload).toString('hex') }),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(db.tx.transaction.create).not.toHaveBeenCalled();
  });

  it('rejects a receipt whose balance does not match the ledger', async () => {
    db = makeDb({ ledger: { lastCounter: 0n, lastBalance: 3n, activeVisitId: null } });
    const payload = buildGrantPayload();
    await expect(
      db.service.verifyReceipt({ payload: payload.toString('hex'), signature: signPayload(payload).toString('hex') }),
    ).rejects.toBeInstanceOf(ConflictException);
  });

  it('rejects a forged signature before touching the ledger', async () => {
    const payload = buildGrantPayload();
    const forged = Buffer.from(signPayload(payload));
    forged[forged.length - 1] ^= 0xff;

    await expect(
      db.service.verifyReceipt({ payload: payload.toString('hex'), signature: forged.toString('hex') }),
    ).rejects.toBeInstanceOf(UnprocessableEntityException);
    expect(db.transactionRunner).not.toHaveBeenCalled();
  });

  it('rejects a card that is not enrolled', async () => {
    db = makeDb({ student: null });
    const payload = buildGrantPayload();
    await expect(
      db.service.verifyReceipt({ payload: payload.toString('hex'), signature: signPayload(payload).toString('hex') }),
    ).rejects.toBeInstanceOf(UnprocessableEntityException);
    expect(db.transactionRunner).not.toHaveBeenCalled();
  });
});

describe('ReceiptsService.readLedger', () => {
  function makeLedgerDb(row: unknown): ReceiptsService {
    const database = {
      cardLedger: { findUnique: vi.fn().mockResolvedValue(row) },
    } as unknown as DatabaseService;
    return new ReceiptsService(database);
  }

  it('returns the ledger milestone for the requested card', async () => {
    const service = makeLedgerDb({ lastCounter: 6n, lastBalance: 8n, activeVisitId: null });
    await expect(service.readLedger('a1b2c3d41122ef07')).resolves.toEqual({
      cardId: 'a1b2c3d41122ef07',
      lastCounter: 6,
      lastBalance: 8,
      activeVisitId: null,
    });
  });

  it('returns the open visit as hex when the ledger holds one', async () => {
    const visit = Buffer.from('b0d02b116516e28763bb30910ff7fc60', 'hex');
    const service = makeLedgerDb({
      lastCounter: 4n,
      lastBalance: 3n,
      activeVisitId: new Uint8Array(visit),
    });
    await expect(service.readLedger('a1b2c3d41122ef07')).resolves.toMatchObject({
      activeVisitId: 'b0d02b116516e28763bb30910ff7fc60',
    });
  });

  it('answers 404 instead of inventing a baseline when the card has no ledger', async () => {
    await expect(makeLedgerDb(null).readLedger('a1b2c3d41122ef07')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });

  it('refuses a malformed cardId', async () => {
    await expect(makeLedgerDb(null).readLedger('xyz')).rejects.toBeInstanceOf(BadRequestException);
  });

  it('refuses a lastCounter outside the canonical range', async () => {
    const service = makeLedgerDb({ lastCounter: 4294967296n, lastBalance: 0n, activeVisitId: null });
    await expect(service.readLedger('a1b2c3d41122ef07')).rejects.toBeInstanceOf(ConflictException);
  });

  it('refuses a lastBalance outside the quota ceiling', async () => {
    const service = makeLedgerDb({ lastCounter: 1n, lastBalance: 10000n, activeVisitId: null });
    await expect(service.readLedger('a1b2c3d41122ef07')).rejects.toBeInstanceOf(ConflictException);
  });
});

describe('ReceiptsService.listTransactions', () => {
  function makeHistoryDb(options: {
    enrolled?: boolean;
    rows?: {
      id: string;
      counterAfter: bigint;
      operation: 'GRANT' | 'START' | 'END';
      delta: bigint;
      balanceAfter: bigint;
      recordedAt: Date;
    }[];
  }) {
    const findMany = vi.fn().mockResolvedValue(options.rows ?? []);
    const database = {
      student: { findUnique: vi.fn().mockResolvedValue(options.enrolled === false ? null : { cardId: CARD_ID }) },
      transaction: { findMany },
    } as unknown as DatabaseService;
    return { service: new ReceiptsService(database), findMany };
  }

  it('returns enrolled history in counter order with verified status', async () => {
    const { service, findMany } = makeHistoryDb({
      rows: [
        { id: '11111111-1111-1111-1111-111111111111', counterAfter: 1n, operation: 'GRANT', delta: 5n, balanceAfter: 5n, recordedAt: new Date('2026-11-12T08:00:00Z') },
        { id: '22222222-2222-2222-2222-222222222222', counterAfter: 2n, operation: 'START', delta: -1n, balanceAfter: 4n, recordedAt: new Date('2026-11-12T09:00:00Z') },
        { id: '33333333-3333-3333-3333-333333333333', counterAfter: 3n, operation: 'END', delta: 0n, balanceAfter: 4n, recordedAt: new Date('2026-11-12T10:00:00Z') },
      ],
    });
    await expect(service.listTransactions('A1B2C3D41122EF07')).resolves.toEqual({
      cardId: 'a1b2c3d41122ef07',
      transactions: [
        { id: '11111111-1111-1111-1111-111111111111', counterAfter: 1, operation: 'GRANT', delta: 5, balanceAfter: 5, verifyStatus: 'verified', recordedAt: new Date('2026-11-12T08:00:00Z') },
        { id: '22222222-2222-2222-2222-222222222222', counterAfter: 2, operation: 'START', delta: -1, balanceAfter: 4, verifyStatus: 'verified', recordedAt: new Date('2026-11-12T09:00:00Z') },
        { id: '33333333-3333-3333-3333-333333333333', counterAfter: 3, operation: 'END', delta: 0, balanceAfter: 4, verifyStatus: 'verified', recordedAt: new Date('2026-11-12T10:00:00Z') },
      ],
    });
    expect(findMany).toHaveBeenCalledWith(expect.objectContaining({ orderBy: { counterAfter: 'asc' } }));
  });

  it('answers 404 when the card is not enrolled', async () => {
    const { service, findMany } = makeHistoryDb({ enrolled: false });
    await expect(service.listTransactions('a1b2c3d41122ef07')).rejects.toBeInstanceOf(NotFoundException);
    expect(findMany).not.toHaveBeenCalled();
  });

  it('refuses a malformed cardId before reading rows', async () => {
    const { service, findMany } = makeHistoryDb({});
    await expect(service.listTransactions('xyz')).rejects.toBeInstanceOf(BadRequestException);
    expect(findMany).not.toHaveBeenCalled();
  });
});

