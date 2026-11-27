import { type INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import request from 'supertest';
import type { App } from 'supertest/types.js';
import { AppModule } from '../src/app.module.js';
import { DatabaseService } from '../src/database/database.service.js';
import { hashPassword } from '../src/staff-auth/password.js';
import { receiptMessage } from '../src/receipts/receipt-canonical.js';
import { ReceiptsService } from '../src/receipts/receipts.service.js';
import { toStoredBytes } from '../src/database/stored-bytes.js';

describe('Staff audit (e2e)', { timeout: 30000 }, () => {
  let app: INestApplication<App>;
  let db: DatabaseService;
  const staffIds: string[] = [];
  const cardIds: Uint8Array<ArrayBuffer>[] = [];

  beforeAll(() => {
    const url = process.env.DATABASE_URL ?? '';
    if (!URL.canParse(url) || new URL(url).pathname !== '/labaccess_test') {
      throw new Error('staff audit e2e requires labaccess_test');
    }
  });

  beforeEach(async () => {
    app = (await Test.createTestingModule({ imports: [AppModule] }).compile()).createNestApplication();
    await app.init();
    db = app.get(DatabaseService);
    staffIds.length = 0;
    cardIds.length = 0;
  });

  afterEach(async () => {
    const operations = { staffSession: { staffId: { in: staffIds } } };
    await db.staffAuditEvent.deleteMany({ where: { operation: operations } });
    await db.transaction.deleteMany({ where: { cardId: { in: cardIds } } });
    await db.staffAuditOperation.deleteMany({ where: { ...operations, originId: { not: null } } });
    await db.staffAuditOperation.deleteMany({ where: operations });
    await db.cardLedger.deleteMany({ where: { cardId: { in: cardIds } } });
    await db.student.deleteMany({ where: { cardId: { in: cardIds } } });
    await db.staffSession.deleteMany({ where: { staffId: { in: staffIds } } });
    await db.staff.deleteMany({ where: { id: { in: staffIds } } });
    await app.close();
  });

  async function account() {
    const suffix = randomBytes(8).toString('hex');
    const username = 'audit-' + suffix;
    const password = randomBytes(24).toString('base64url');
    const staff = await db.staff.create({ data: { staffCode: 'A' + suffix.toUpperCase(), username, displayName: 'Audit test',
      passwordHash: await hashPassword(password) } });
    staffIds.push(staff.id);
    const login = await request(app.getHttpServer()).post('/staff/auth/login').send({ username, password }).expect(200);
    return { auth: 'Bearer ' + String(login.body.token), code: staff.staffCode };
  }

  async function card() {
    const bytes = randomBytes(8);
    const cardId = toStoredBytes(bytes);
    cardIds.push(cardId);
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const jwk = publicKey.export({ format: 'jwk' }) as { x: string; y: string };
    const point = Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
    await db.student.create({ data: { cardId, studentId: 'S' + bytes.toString('hex'), publicKey: toStoredBytes(point) } });
    await db.cardLedger.create({ data: { cardId } });
    const payload = Buffer.alloc(34);
    payload[0] = 1; bytes.copy(payload, 1); payload.writeUInt32BE(1, 9);
    payload[13] = 1; payload.writeInt16BE(5, 14); payload.writeUInt16BE(5, 16);
    return { cardId, hex: bytes.toString('hex'), payload: payload.toString('hex'),
      signature: sign('sha256', receiptMessage(payload), privateKey).toString('hex') };
  }

  async function begin(auth: string, data: Record<string, unknown>) {
    const response = await request(app.getHttpServer()).post('/staff/audit').set('Authorization', auth).send(data).expect(201);
    return String(response.body.operationId);
  }

  function postReceipt(auth: string, id: string, receipt: { payload: string; signature: string }) {
    return request(app.getHttpServer()).post('/receipts/staff/' + id + '/verify')
      .set('Authorization', auth).send({ payload: receipt.payload, signature: receipt.signature });
  }

  it('protects audit API, derives identity from session and does not accept forged receipt outcomes', async () => {
    await request(app.getHttpServer()).get('/staff/audit').expect(401);
    await request(app.getHttpServer()).post('/staff/audit').expect(401);
    const a = await account();
    const b = await account();
    const id = await begin(a.auth, { action: 'PERSONALIZE', staffCode: b.code });
    await request(app.getHttpServer()).post('/staff/audit/' + id + '/outcome').set('Authorization', b.auth)
      .send({ outcome: 'CLIENT_CONFIRMED' }).expect(403);
    await request(app.getHttpServer()).post('/staff/audit/' + id + '/outcome').set('Authorization', a.auth)
      .send({ outcome: 'RECEIPT_VERIFIED' }).expect(400);
    const cardId = randomBytes(8).toString('hex');
    await request(app.getHttpServer()).post('/staff/audit/' + id + '/outcome').set('Authorization', a.auth)
      .send({ outcome: 'CLIENT_CONFIRMED', cardId }).expect(204);
    const response = await request(app.getHttpServer()).get('/staff/audit').set('Authorization', a.auth)
      .query({ cardId, staffCode: a.code }).expect(200);
    expect(response.body).toHaveLength(1);
    expect(response.body[0]).toMatchObject({ operationId: id, staff: { staffCode: a.code }, outcome: 'CLIENT_CONFIRMED' });
    expect(JSON.stringify(response.body)).not.toContain('tokenHash');
  });

  it('preserves original grant initiator when another staff reconciles a lost receipt, idempotently', async () => {
    const a = await account();
    const b = await account();
    const c = await card();
    const originId = await begin(a.auth, { action: 'GRANT', cardId: c.hex, expectedPayload: c.payload });
    const pending = await request(app.getHttpServer()).get('/staff/audit').set('Authorization', a.auth)
      .query({ cardId: c.hex }).expect(200);
    expect(pending.body[0].outcome).toBe('UNKNOWN');
    await request(app.getHttpServer()).post('/staff/auth/logout').set('Authorization', a.auth).expect(204);
    await postReceipt(a.auth, originId, c).expect(401);
    await postReceipt(b.auth, originId, c).expect(403);
    const recoveryId = await begin(b.auth, { action: 'RECONCILE', cardId: c.hex, originId });
    const first = await postReceipt(b.auth, recoveryId, c).expect(201);
    expect(first.body.status).toBe('recorded');
    const replay = await postReceipt(b.auth, recoveryId, c).expect(201);
    expect(replay.body.status).toBe('duplicate');
    const rows = await db.transaction.findMany({ where: { cardId: c.cardId } });
    expect(rows).toHaveLength(1);
    expect(rows[0]?.grantAuditId).toBe(originId);
    expect(await db.staffAuditEvent.count({ where: { transactionId: rows[0]?.id ?? '' } })).toBe(2);
    const audit = await request(app.getHttpServer()).get('/staff/audit').set('Authorization', b.auth)
      .query({ cardId: c.hex }).expect(200);
    expect(audit.body).toEqual(expect.arrayContaining([
      expect.objectContaining({ operationId: originId, staff: { staffCode: a.code, displayName: 'Audit test' }, outcome: 'RECEIPT_VERIFIED' }),
      expect.objectContaining({ operationId: recoveryId, originId, staff: { staffCode: b.code, displayName: 'Audit test' },
        originStaff: { staffCode: a.code, displayName: 'Audit test' }, outcome: 'RECEIPT_VERIFIED' }),
    ]));
    const filtered = await request(app.getHttpServer()).get('/staff/audit').set('Authorization', b.auth)
      .query({ cardId: c.hex, staffCode: b.code }).expect(200);
    expect(filtered.body).toHaveLength(1);
    expect(filtered.body[0]).toMatchObject({ operationId: recoveryId, originId,
      originStaff: { staffCode: a.code }, staff: { staffCode: b.code } });
  });

  it('does not retroactively attribute an existing legacy receipt to a grant intent', async () => {
    const a = await account();
    const b = await account();
    const c = await card();
    const originId = await begin(a.auth, { action: 'GRANT', cardId: c.hex, expectedPayload: c.payload });
    await request(app.getHttpServer()).post('/receipts/verify').send({ payload: c.payload, signature: c.signature }).expect(401);
    await app.get(ReceiptsService).verifyReceipt({ payload: c.payload, signature: c.signature });
    await postReceipt(a.auth, originId, c).expect(409);
    const recoveryId = await begin(b.auth, { action: 'RECONCILE', cardId: c.hex, originId });
    await postReceipt(b.auth, recoveryId, c).expect(201);
    const row = await db.transaction.findFirstOrThrow({ where: { cardId: c.cardId } });
    expect(row.grantAuditId).toBeNull();
    expect(await db.staffAuditEvent.count({ where: { operationId: originId } })).toBe(0);
    expect(await db.staffAuditEvent.count({ where: { operationId: recoveryId } })).toBe(1);
  });
});
