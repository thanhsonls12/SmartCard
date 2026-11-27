import {
  BadRequestException,
  ConflictException,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../database/database.service.js';
import { Prisma } from '../generated/prisma/client.js';
import { buildProfileMessage, buildProfilePlaintext } from './profile-message.js';
import { ProfileUpdateService } from './profile-update.service.js';

const CARD_ID = Buffer.from('a1b2c3d41122ef07', 'hex');
const CARD_ID_HEX = CARD_ID.toString('hex');
const OPERATION_ID = '11111111-1111-4111-8111-111111111111';
const REPLACEMENT_ID = '66666666-6666-4666-8666-666666666666';
const ADMIN_SESSION_ID = '22222222-2222-4222-8222-222222222222';
const OTHER_SESSION_ID = '33333333-3333-4333-8333-333333333333';
const NONCE = Buffer.from('bb'.repeat(32), 'hex');
const EXPECTED_VERSION_HEX = 'cc'.repeat(12);
const STALE_VERSION_HEX = 'dd'.repeat(12);

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

type RowState = 'PENDING' | 'COMPLETED' | 'EXPIRED' | 'SUPERSEDED';

interface Row {
  id: string;
  cardId: Uint8Array;
  studentId: string;
  displayName: string;
  classCode: string | null;
  expectedVersion: Uint8Array;
  nonce: Uint8Array;
  adminSessionId: string;
  state: RowState;
  expiresAt: Date;
  completedAt: Date | null;
  resultMajor: string | null;
  resultStudentId: string | null;
  resultClassCode: string | null;
}

type RowInput = Omit<Row, 'id' | 'state' | 'completedAt' | 'resultMajor' | 'resultStudentId' | 'resultClassCode'>;

interface RowFilter {
  id?: string;
  cardId?: Uint8Array;
  state?: RowState;
  nonce?: Uint8Array;
  expiresAt?: { lte?: Date; gt?: Date };
}

interface FindArgs {
  where: RowFilter;
  select?: Record<string, boolean>;
  orderBy?: Record<string, string>;
}

function pendingRow(overrides: Partial<Row> = {}): Row {
  return {
    id: OPERATION_ID,
    cardId: new Uint8Array(CARD_ID),
    studentId: 'CT2026001',
    displayName: 'Nguyễn Văn Bình',
    classCode: 'CNTT1',
    expectedVersion: new Uint8Array(Buffer.from(EXPECTED_VERSION_HEX, 'hex')),
    nonce: new Uint8Array(NONCE),
    adminSessionId: ADMIN_SESSION_ID,
    state: 'PENDING',
    expiresAt: new Date(Date.now() + 5 * 60 * 1000),
    completedAt: null,
    resultMajor: null,
    resultStudentId: null,
    resultClassCode: null,
    ...overrides,
  };
}

function completedRow(overrides: Partial<Row> = {}): Row {
  return pendingRow({
    state: 'COMPLETED',
    completedAt: new Date('2026-11-22T09:30:00.000Z'),
    resultMajor: 'Công nghệ thông tin',
    resultStudentId: 'CT2026001',
    resultClassCode: 'CNTT1',
    ...overrides,
  });
}

function expiredPendingRow(overrides: Partial<Row> = {}): Row {
  return pendingRow({ id: '44444444-4444-4444-8444-444444444444', expiresAt: new Date(Date.now() - 1000), ...overrides });
}

function signOperation(operation: Row, nonce: Buffer = Buffer.from(operation.nonce)): string {
  const plaintext = buildProfilePlaintext({
    studentId: operation.studentId,
    displayName: operation.displayName,
  });
  const message = buildProfileMessage({
    cardId: Buffer.from(operation.cardId),
    publicKeyHash: createHash('sha256').update(cardKey.point).digest(),
    nonce,
    profileHash: createHash('sha256').update(plaintext).digest(),
  });
  return sign('sha256', message, cardKey.privateKey).toString('hex');
}

function matchesFilter(row: Row, where: RowFilter): boolean {
  if (where.id !== undefined && row.id !== where.id) {
    return false;
  }
  if (where.cardId !== undefined && !Buffer.from(row.cardId).equals(Buffer.from(where.cardId))) {
    return false;
  }
  if (where.state !== undefined && row.state !== where.state) {
    return false;
  }
  if (where.nonce !== undefined && !Buffer.from(row.nonce).equals(Buffer.from(where.nonce))) {
    return false;
  }
  if (where.expiresAt?.lte !== undefined && row.expiresAt.getTime() > where.expiresAt.lte.getTime()) {
    return false;
  }
  if (where.expiresAt?.gt !== undefined && row.expiresAt.getTime() <= where.expiresAt.gt.getTime()) {
    return false;
  }
  return true;
}

interface DbStub {
  service: ProfileUpdateService;
  rows: Row[];
  student: { id: string; studentId: string; major: string | null; classCode: string | null };
  profileUpdate: {
    create: ReturnType<typeof vi.fn>;
    findUnique: ReturnType<typeof vi.fn>;
    findFirst: ReturnType<typeof vi.fn>;
    updateMany: ReturnType<typeof vi.fn>;
  };
  studentFindUnique: ReturnType<typeof vi.fn>;
  studentUpdate: ReturnType<typeof vi.fn>;
  transactionRunner: ReturnType<typeof vi.fn>;
}

function makeDb(
  options: {
    rows?: Row[];
    student?: { id: string; publicKey?: Uint8Array } | null;
    claimCount?: number;
    createdId?: string;
  } = {},
): DbStub {
  const rows = options.rows ?? [];
  const student = { id: 'student-1', studentId: 'CT2026001', major: 'Công nghệ thông tin', classCode: 'CNTT1' };

  const profileUpdate = {
    create: vi.fn(async (args: { data: RowInput; select?: Record<string, boolean> }) => {
      const id = options.createdId ?? OPERATION_ID;
      rows.push({
        id,
        ...args.data,
        state: 'PENDING',
        completedAt: null,
        resultMajor: null,
        resultStudentId: null,
        resultClassCode: null,
      });
      return { id };
    }),
    findUnique: vi.fn(async (args: FindArgs) => rows.find((row) => matchesFilter(row, args.where)) ?? null),
    findFirst: vi.fn(async (args: FindArgs) => rows.find((row) => matchesFilter(row, args.where)) ?? null),
    updateMany: vi.fn(async (args: { where: RowFilter; data: Partial<Row> }) => {
      if (options.claimCount !== undefined && args.where.id !== undefined) {
        return { count: options.claimCount };
      }
      const targets = rows.filter((row) => matchesFilter(row, args.where));
      for (const target of targets) {
        Object.assign(target, args.data);
      }
      return { count: targets.length };
    }),
  };

  const studentFindUnique = vi
    .fn()
    .mockResolvedValue(options.student === undefined ? { id: 'student-1', publicKey: cardKey.point } : options.student);
  const studentUpdate = vi.fn(async (args: { data: Partial<Row> }) => {
    Object.assign(student, args.data);
    return { studentId: student.studentId, major: student.major, classCode: student.classCode };
  });
  const transactionRunner = vi.fn(async (callback: (client: unknown) => unknown) =>
    callback({ profileUpdate, student: { update: studentUpdate } }),
  );

  const database = {
    profileUpdate,
    student: { findUnique: studentFindUnique, update: studentUpdate },
    $transaction: transactionRunner,
  } as unknown as DatabaseService;

  return {
    service: new ProfileUpdateService(database),
    rows,
    student,
    profileUpdate,
    studentFindUnique,
    studentUpdate,
    transactionRunner,
  };
}

function createBody(overrides: Record<string, unknown> = {}) {
  return {
    cardId: CARD_ID_HEX,
    studentId: 'CT2026001',
    displayName: 'Nguyễn Văn Bình',
    classCode: 'CNTT1',
    expectedVersion: EXPECTED_VERSION_HEX,
    ...overrides,
  };
}

function replaceBody(overrides: Record<string, unknown> = {}) {
  return {
    studentId: 'DT2024003',
    displayName: 'Trần Văn C',
    classCode: 'DT20A',
    expectedVersion: STALE_VERSION_HEX,
    ...overrides,
  };
}

describe('ProfileUpdateService pending writes', () => {
  it.each(['create', 'replace'] as const)('maps a unique violation from the %s transaction to conflict', async (mode) => {
    const db = makeDb();
    db.transactionRunner.mockRejectedValueOnce(
      new Prisma.PrismaClientKnownRequestError('unique violation', { code: 'P2002', clientVersion: 'test' }),
    );

    const operation = mode === 'create'
      ? db.service.createRequest(ADMIN_SESSION_ID, createBody())
      : db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, createBody());
    await expect(operation).rejects.toThrow('cardId already has a pending profile update');
    await expect(operation).rejects.toBeInstanceOf(ConflictException);
  });
});

describe('ProfileUpdateService.createRequest', () => {
  it('answers 409 for a stale pending request so the operator reopens it instead of replacing it', async () => {
    const db = makeDb({ rows: [expiredPendingRow()] });

    await expect(db.service.createRequest(ADMIN_SESSION_ID, createBody())).rejects.toBeInstanceOf(ConflictException);
    expect(db.rows).toHaveLength(1);
    expect(db.rows[0]).toMatchObject({ id: '44444444-4444-4444-8444-444444444444', state: 'PENDING' });
    expect(db.transactionRunner).toHaveBeenCalledOnce();
  });

  it('creates the request with the normalized content when the card has none', async () => {
    const db = makeDb();

    const result = await db.service.createRequest(ADMIN_SESSION_ID, createBody({
      cardId: CARD_ID_HEX.toUpperCase(),
      studentId: 'ct2026001',
      displayName: '  Nguyễn Văn Bình  ',
      classCode: '  cntt1 ',
      expectedVersion: EXPECTED_VERSION_HEX.toUpperCase(),
    }));

    expect(result.operationId).toBe(OPERATION_ID);
    expect(result.nonce).toMatch(/^[0-9a-f]{64}$/);
    expect(result.expectedVersion).toBe(EXPECTED_VERSION_HEX);
    const after = Date.now();
    expect(result.expiresAt.getTime()).toBeGreaterThanOrEqual(after + 4 * 60 * 1000);
    expect(result.expiresAt.getTime()).toBeLessThanOrEqual(after + 5 * 60 * 1000);

    expect(db.rows).toHaveLength(1);
    expect(db.rows[0]).toMatchObject({
      state: 'PENDING',
      cardId: new Uint8Array(CARD_ID),
      studentId: 'CT2026001',
      displayName: 'Nguyễn Văn Bình',
      classCode: 'CNTT1',
      expectedVersion: new Uint8Array(Buffer.from(EXPECTED_VERSION_HEX, 'hex')),
      adminSessionId: ADMIN_SESSION_ID,
    });
    expect(db.rows[0]?.nonce).toHaveLength(32);
    expect(db.transactionRunner).toHaveBeenCalledOnce();
  });

  it('answers 409 without storing anything while the pending request is still live', async () => {
    const db = makeDb({ rows: [pendingRow()] });

    await expect(db.service.createRequest(ADMIN_SESSION_ID, createBody())).rejects.toBeInstanceOf(ConflictException);
    expect(db.rows).toHaveLength(1);
    expect(db.rows[0]).toMatchObject({ state: 'PENDING' });
  });

  it('answers 404 without storing anything when the card is not enrolled', async () => {
    const db = makeDb({ student: null });

    await expect(db.service.createRequest(ADMIN_SESSION_ID, createBody())).rejects.toBeInstanceOf(NotFoundException);
    expect(db.rows).toHaveLength(0);
    expect(db.transactionRunner).not.toHaveBeenCalled();
  });

  it('rejects a malformed request before reading the database', async () => {
    const db = makeDb();

    await expect(db.service.createRequest(ADMIN_SESSION_ID, createBody({ classCode: 'CN-TT' }))).rejects.toBeInstanceOf(
      BadRequestException,
    );
    await expect(
      db.service.createRequest(ADMIN_SESSION_ID, createBody({ expectedVersion: 'cc'.repeat(11) })),
    ).rejects.toBeInstanceOf(BadRequestException);
    expect(db.studentFindUnique).not.toHaveBeenCalled();
    expect(db.rows).toHaveLength(0);
  });
});

describe('ProfileUpdateService.replaceRequest', () => {
  it('supersedes the stale request and creates the replacement in one transaction', async () => {
    const stale = pendingRow();
    const db = makeDb({ rows: [stale], createdId: REPLACEMENT_ID });

    const result = await db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody({
      studentId: 'dt2024003',
      displayName: '  Trần Văn C  ',
      classCode: ' dt20a ',
      expectedVersion: STALE_VERSION_HEX.toUpperCase(),
    }));

    expect(result).toEqual({
      operationId: REPLACEMENT_ID,
      nonce: expect.stringMatching(/^[0-9a-f]{64}$/),
      expectedVersion: STALE_VERSION_HEX,
      expiresAt: expect.any(Date),
    });
    expect(db.transactionRunner).toHaveBeenCalledOnce();
    expect(db.rows).toHaveLength(2);
    expect(db.rows[0]).toMatchObject({ id: OPERATION_ID, state: 'SUPERSEDED' });
    expect(db.rows[1]).toMatchObject({
      id: REPLACEMENT_ID,
      cardId: new Uint8Array(CARD_ID),
      studentId: 'DT2024003',
      displayName: 'Trần Văn C',
      classCode: 'DT20A',
      expectedVersion: new Uint8Array(Buffer.from(STALE_VERSION_HEX, 'hex')),
      adminSessionId: ADMIN_SESSION_ID,
      state: 'PENDING',
    });
    expect(db.rows[1]?.nonce).toHaveLength(32);
    expect(Buffer.from(db.rows[1]!.nonce).toString('hex')).toBe(result.nonce);
    expect(result.expiresAt.getTime()).toBeGreaterThan(Date.now());
  });

  it('answers 409 when the request is no longer pending', async () => {
    const db = makeDb({ rows: [completedRow()] });

    await expect(
      db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody()),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(db.transactionRunner).toHaveBeenCalledOnce();
    expect(db.rows).toHaveLength(1);
    expect(db.rows[0]).toMatchObject({ state: 'COMPLETED' });
  });

  it('answers 409 when the expected version did not change so the request must be completed', async () => {
    const db = makeDb({ rows: [pendingRow()] });

    await expect(
      db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody({
        expectedVersion: EXPECTED_VERSION_HEX,
      })),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(db.rows).toHaveLength(1);
    expect(db.rows[0]).toMatchObject({ id: OPERATION_ID, state: 'PENDING' });
    expect(db.profileUpdate.create).not.toHaveBeenCalled();
  });

  it('answers 409 when the supersede compare-and-swap loses the row to another round', async () => {
    const db = makeDb({ rows: [pendingRow()], claimCount: 0 });

    await expect(
      db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody()),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(db.profileUpdate.updateMany).toHaveBeenCalledExactlyOnceWith({
      where: { id: OPERATION_ID, state: 'PENDING', nonce: new Uint8Array(NONCE) },
      data: { state: 'SUPERSEDED' },
    });
    expect(db.profileUpdate.create).not.toHaveBeenCalled();
    expect(db.rows).toHaveLength(1);
    expect(db.rows[0]).toMatchObject({ state: 'PENDING' });
  });

  it('answers 404 when the operation does not exist', async () => {
    const db = makeDb();

    await expect(
      db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody()),
    ).rejects.toBeInstanceOf(NotFoundException);
    expect(db.rows).toHaveLength(0);
    expect(db.profileUpdate.create).not.toHaveBeenCalled();
  });

  it('rejects a malformed replacement body before reading the database', async () => {
    const db = makeDb({ rows: [pendingRow()] });

    await expect(
      db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody({ classCode: 'CN-TT' })),
    ).rejects.toBeInstanceOf(BadRequestException);
    await expect(
      db.service.replaceRequest(ADMIN_SESSION_ID, OPERATION_ID, replaceBody({ expectedVersion: 'dd'.repeat(11) })),
    ).rejects.toBeInstanceOf(BadRequestException);
    expect(db.transactionRunner).not.toHaveBeenCalled();
  });
});

describe('ProfileUpdateService.findOpenRequest', () => {
  it('returns the pending request for the card', async () => {
    const row = pendingRow();
    const db = makeDb({ rows: [row] });

    await expect(db.service.findOpenRequest(CARD_ID_HEX.toUpperCase())).resolves.toEqual({
      state: 'PENDING',
      operationId: OPERATION_ID,
      cardId: CARD_ID_HEX,
      studentId: 'CT2026001',
      displayName: 'Nguyễn Văn Bình',
      classCode: 'CNTT1',
      expectedVersion: EXPECTED_VERSION_HEX,
      nonce: NONCE.toString('hex'),
      expiresAt: row.expiresAt,
    });
  });

  it('renews the challenge in place when the stored one expired', async () => {
    const db = makeDb({ rows: [expiredPendingRow()] });

    const found = await db.service.findOpenRequest(CARD_ID_HEX);

    expect(found.state).toBe('PENDING');
    if (found.state !== 'PENDING') {
      throw new Error('expected a pending request');
    }
    expect(found.operationId).toBe('44444444-4444-4444-8444-444444444444');
    expect(found.nonce).toMatch(/^[0-9a-f]{64}$/);
    expect(found.nonce).not.toBe(NONCE.toString('hex'));
    expect(found.expiresAt.getTime()).toBeGreaterThan(Date.now());
    expect(found.expectedVersion).toBe(EXPECTED_VERSION_HEX);
    expect(db.rows[0]).toMatchObject({ state: 'PENDING', displayName: 'Nguyễn Văn Bình' });
    expect(db.rows[0]?.expiresAt.getTime()).toBe(found.expiresAt.getTime());
    expect(Buffer.from(db.rows[0]!.nonce).toString('hex')).toBe(found.nonce);
  });

  it('returns the stored confirmation once the request completed', async () => {
    const row = completedRow();
    const db = makeDb({ rows: [row] });

    await expect(db.service.findOpenRequest(CARD_ID_HEX)).resolves.toEqual({
      state: 'COMPLETED',
      operationId: OPERATION_ID,
      cardId: CARD_ID_HEX,
      studentId: 'CT2026001',
      major: 'Công nghệ thông tin',
      classCode: 'CNTT1',
      completedAt: row.completedAt,
    });
  });

  it('answers 404 when the card has no request at all', async () => {
    const db = makeDb();

    await expect(db.service.findOpenRequest(CARD_ID_HEX)).rejects.toBeInstanceOf(NotFoundException);
  });

  it('finds the same request by operation id', async () => {
    const row = pendingRow();
    const db = makeDb({ rows: [row] });

    await expect(db.service.findByOperationId(OPERATION_ID)).resolves.toMatchObject({
      state: 'PENDING',
      operationId: OPERATION_ID,
      cardId: CARD_ID_HEX,
    });
    await expect(db.service.findByOperationId('55555555-5555-4555-8555-555555555555')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });

  it('reports the card of an operation for authorization', async () => {
    const db = makeDb({ rows: [pendingRow()] });

    await expect(db.service.cardIdOf(OPERATION_ID)).resolves.toBe(CARD_ID_HEX);
    await expect(db.service.cardIdOf('55555555-5555-4555-8555-555555555555')).rejects.toBeInstanceOf(
      NotFoundException,
    );
  });
});

describe('ProfileUpdateService challenge renewal', () => {
  it('renews the expired challenge once and answers the same nonce to the next round', async () => {
    const db = makeDb({ rows: [expiredPendingRow()] });

    const first = await db.service.findOpenRequest(CARD_ID_HEX);
    const second = await db.service.findOpenRequest(CARD_ID_HEX);

    expect(first.state).toBe('PENDING');
    expect(second.state).toBe('PENDING');
    if (first.state !== 'PENDING' || second.state !== 'PENDING') {
      throw new Error('expected two pending requests');
    }
    expect(first.nonce).toMatch(/^[0-9a-f]{64}$/);
    expect(first.nonce).not.toBe(NONCE.toString('hex'));
    expect(second.nonce).toBe(first.nonce);
    expect(second.expiresAt).toEqual(first.expiresAt);
    expect(db.profileUpdate.updateMany).toHaveBeenCalledOnce();
    expect(db.profileUpdate.updateMany).toHaveBeenCalledExactlyOnceWith({
      where: { id: '44444444-4444-4444-8444-444444444444', state: 'PENDING', nonce: new Uint8Array(NONCE) },
      data: { nonce: new Uint8Array(Buffer.from(first.nonce, 'hex')), expiresAt: first.expiresAt },
    });
    expect(db.rows).toHaveLength(1);
    expect(Buffer.from(db.rows[0]!.nonce).toString('hex')).toBe(first.nonce);
  });

  it('keeps the current nonce instead of overwriting it when another round renewed first', async () => {
    const db = makeDb({ rows: [expiredPendingRow()], claimCount: 0 });

    const found = await db.service.findOpenRequest(CARD_ID_HEX);

    expect(found.state).toBe('PENDING');
    if (found.state !== 'PENDING') {
      throw new Error('expected a pending request');
    }
    expect(found.nonce).toBe(NONCE.toString('hex'));
    expect(found.operationId).toBe('44444444-4444-4444-8444-444444444444');
    expect(found.expectedVersion).toBe(EXPECTED_VERSION_HEX);
    expect(db.profileUpdate.updateMany).toHaveBeenCalledOnce();
    expect(Buffer.from(db.rows[0]!.nonce).toString('hex')).toBe(NONCE.toString('hex'));
  });
});

describe('ProfileUpdateService.completeRequest', () => {
  it('updates the student and stores the result in one transaction', async () => {
    const operation = pendingRow();
    const db = makeDb({ rows: [operation] });

    const result = await db.service.completeRequest(OPERATION_ID, {
      signature: signOperation(operation),
    });

    expect(result).toEqual({
      operationId: OPERATION_ID,
      cardId: CARD_ID_HEX,
      studentId: 'CT2026001',
      major: 'Công nghệ thông tin',
      classCode: 'CNTT1',
      completedAt: expect.any(Date),
      alreadyCompleted: false,
    });
    expect(db.rows[0]).toMatchObject({
      state: 'COMPLETED',
      completedAt: expect.any(Date),
      resultMajor: 'Công nghệ thông tin',
      resultStudentId: 'CT2026001',
      resultClassCode: 'CNTT1',
    });
    expect(db.studentUpdate).toHaveBeenCalledExactlyOnceWith({
      where: { cardId: new Uint8Array(CARD_ID) },
      data: { studentId: 'CT2026001', major: 'Công nghệ thông tin', classCode: 'CNTT1' },
    });
    expect(db.student).toMatchObject({ studentId: 'CT2026001', classCode: 'CNTT1' });
  });

  it('answers 422 for a signature over a different nonce without writing', async () => {
    const operation = pendingRow();
    const db = makeDb({ rows: [operation] });

    await expect(
      db.service.completeRequest(OPERATION_ID, {
        signature: signOperation(operation, Buffer.from('dd'.repeat(32), 'hex')),
      }),
    ).rejects.toBeInstanceOf(UnprocessableEntityException);
    expect(db.transactionRunner).not.toHaveBeenCalled();
    expect(db.rows[0]).toMatchObject({ state: 'PENDING' });
  });

  it('completes a request created by another admin session', async () => {
    const operation = pendingRow({ adminSessionId: OTHER_SESSION_ID });
    const db = makeDb({ rows: [operation] });

    const result = await db.service.completeRequest(OPERATION_ID, { signature: signOperation(operation) });

    expect(result).toMatchObject({ operationId: OPERATION_ID, alreadyCompleted: false });
    expect(db.rows[0]).toMatchObject({ state: 'COMPLETED' });
    expect(db.studentUpdate).toHaveBeenCalledOnce();
  });

  it('answers 422 for an invalid signature whatever session created the request', async () => {
    const operation = pendingRow({ adminSessionId: OTHER_SESSION_ID });
    const db = makeDb({ rows: [operation] });

    await expect(db.service.completeRequest(OPERATION_ID, { signature: '00'.repeat(8) })).rejects.toBeInstanceOf(
      UnprocessableEntityException,
    );
    expect(db.rows[0]).toMatchObject({ state: 'PENDING' });
    expect(db.studentUpdate).not.toHaveBeenCalled();
  });

  it('answers 409 and keeps the request pending when the challenge expired', async () => {
    const operation = pendingRow({ expiresAt: new Date(Date.now() - 1000) });
    const db = makeDb({ rows: [operation] });

    await expect(
      db.service.completeRequest(OPERATION_ID, { signature: signOperation(operation) }),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(db.rows[0]).toMatchObject({ state: 'PENDING' });
    expect(db.transactionRunner).not.toHaveBeenCalled();
    expect(db.studentUpdate).not.toHaveBeenCalled();
  });

  it('answers 404 for an unknown operation', async () => {
    const db = makeDb();

    await expect(
      db.service.completeRequest(OPERATION_ID, { signature: signOperation(pendingRow()) }),
    ).rejects.toBeInstanceOf(NotFoundException);
  });

  it('returns the stored result with alreadyCompleted for a repeated completion', async () => {
    const operation = completedRow();
    const db = makeDb({ rows: [operation] });

    const result = await db.service.completeRequest(OPERATION_ID, { signature: '00'.repeat(8) });

    expect(result).toEqual({
      operationId: OPERATION_ID,
      cardId: CARD_ID_HEX,
      studentId: 'CT2026001',
      major: 'Công nghệ thông tin',
      classCode: 'CNTT1',
      completedAt: operation.completedAt,
      alreadyCompleted: true,
    });
    expect(db.transactionRunner).not.toHaveBeenCalled();
    expect(db.studentUpdate).not.toHaveBeenCalled();
  });

  it('answers 409 without touching the student when the pending row was claimed concurrently', async () => {
    const operation = pendingRow();
    const db = makeDb({ rows: [operation], claimCount: 0 });

    await expect(
      db.service.completeRequest(OPERATION_ID, { signature: signOperation(operation) }),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(db.studentUpdate).not.toHaveBeenCalled();
  });

  it('claims the challenge only while the verified nonce and its expiry are still current', async () => {
    const operation = pendingRow();
    const db = makeDb({ rows: [operation] });

    await db.service.completeRequest(OPERATION_ID, { signature: signOperation(operation) });

    expect(db.profileUpdate.updateMany).toHaveBeenCalledExactlyOnceWith({
      where: {
        id: OPERATION_ID,
        state: 'PENDING',
        nonce: new Uint8Array(NONCE),
        expiresAt: { gt: expect.any(Date) },
      },
      data: expect.objectContaining({ state: 'COMPLETED', resultStudentId: 'CT2026001' }),
    });
  });

  it('answers 409 and leaves the student alone when the nonce it verified was renewed before the claim', async () => {
    const operation = pendingRow();
    const db = makeDb({ rows: [operation], claimCount: 0 });

    const failure = await db.service
      .completeRequest(OPERATION_ID, { signature: signOperation(operation) })
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ConflictException);
    expect((failure as ConflictException).message).toContain('no longer the current one');
    expect(db.studentUpdate).not.toHaveBeenCalled();
    expect(db.rows[0]).toMatchObject({ state: 'PENDING', nonce: new Uint8Array(NONCE) });
  });

  it('answers 409 when the nonce is renewed while the row stays pending', async () => {
    const operation = pendingRow();
    const db = makeDb({ rows: [operation] });
    const renewedNonce = new Uint8Array(Buffer.from('ee'.repeat(32), 'hex'));
    db.profileUpdate.updateMany.mockImplementationOnce(async (args: { where: RowFilter; data: Partial<Row> }) => {
      Object.assign(operation, { nonce: renewedNonce });
      const targets = db.rows.filter((row) => matchesFilter(row, args.where));
      for (const target of targets) {
        Object.assign(target, args.data);
      }
      return { count: targets.length };
    });

    const failure = await db.service
      .completeRequest(OPERATION_ID, { signature: signOperation(operation) })
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ConflictException);
    expect((failure as ConflictException).message).toBe('profile update challenge is no longer the current one');
    expect(db.rows[0]).toMatchObject({ state: 'PENDING', nonce: renewedNonce });
    expect(db.studentUpdate).not.toHaveBeenCalled();
  });
});

describe('ProfileUpdateService.requestedCardId', () => {
  it('reports the card of a create body and rejects a malformed one', () => {
    const db = makeDb();

    expect(db.service.requestedCardId(createBody({ cardId: CARD_ID_HEX.toUpperCase() }))).toBe(CARD_ID_HEX);
    expect(() => db.service.requestedCardId(createBody({ cardId: 'a1b2' }))).toThrow(BadRequestException);
  });
});
