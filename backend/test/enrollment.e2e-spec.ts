import { INestApplication } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import request from 'supertest';
import { App } from 'supertest/types.js';
import { createHash, generateKeyPairSync, randomBytes, sign, type KeyObject } from 'node:crypto';
import { AppModule } from './../src/app.module.js';
import { DatabaseService } from './../src/database/database.service.js';
import { buildEnrollmentMessage } from './../src/enrollment/enrollment-message.js';

const STUDENT_ID = 'SVENROLL01';

interface CardKey {
  pointHex: string;
  privateKey: KeyObject;
}

function newCardKey(): CardKey {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' }) as { x: string; y: string };
  const point = Buffer.concat([Buffer.from([0x04]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
  return { pointHex: point.toString('hex'), privateKey };
}

function bytesOf(hex: string): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(hex.length / 2);
  bytes.set(Buffer.from(hex, 'hex'));
  return bytes;
}

function signChallenge(privateKey: KeyObject, cardIdHex: string, pointHex: string, nonceHex: string): string {
  const message = buildEnrollmentMessage({
    cardId: Buffer.from(cardIdHex, 'hex'),
    publicKeyHash: createHash('sha256').update(Buffer.from(pointHex, 'hex')).digest(),
    nonce: Buffer.from(nonceHex, 'hex'),
  });
  return sign('sha256', message, privateKey).toString('hex');
}

describe('Enrollment (e2e)', () => {
  let app: INestApplication<App>;
  let database: DatabaseService;
  const sessionIds: string[] = [];
  const cardIds: string[] = [];

  beforeAll(() => {
    const configured = process.env.DATABASE_URL ?? '';
    const databaseName = URL.canParse(configured) ? new URL(configured).pathname.slice(1) : '';
    if (databaseName !== 'labaccess_test') {
      throw new Error(`enrollment e2e requires DATABASE_URL for labaccess_test, received "${databaseName}"`);
    }
  });

  beforeEach(async () => {
    const moduleFixture: TestingModule = await Test.createTestingModule({ imports: [AppModule] }).compile();
    app = moduleFixture.createNestApplication();
    await app.init();
    database = app.get(DatabaseService);
    sessionIds.length = 0;
    cardIds.length = 0;
  });

  afterEach(async () => {
    const cards = cardIds.map((cardIdHex) => bytesOf(cardIdHex));
    await database.cardLedger.deleteMany({ where: { cardId: { in: cards } } });
    await database.student.deleteMany({ where: { cardId: { in: cards } } });
    await database.enrollmentChallenge.deleteMany({ where: { adminSessionId: { in: sessionIds } } });
    await database.adminSession.deleteMany({ where: { id: { in: sessionIds } } });
    await app.close();
  });

  async function issueSession(): Promise<string> {
    const response = await request(app.getHttpServer()).post('/admin/sessions').expect(201);
    sessionIds.push(response.body.sessionId);
    return response.body.sessionId;
  }

  function createChallenge(sessionId: string, cardIdHex: string, card: CardKey, extras: Record<string, unknown> = {}) {
    cardIds.push(cardIdHex);
    return request(app.getHttpServer())
      .post('/enrollments/challenges')
      .set('x-admin-session', sessionId)
      .send({ cardId: cardIdHex, studentId: STUDENT_ID.toLowerCase(), publicKey: card.pointHex, ...extras });
  }

  function completeChallenge(sessionId: string, challengeId: string, signature: string, extras: Record<string, unknown> = {}) {
    return request(app.getHttpServer())
      .post('/enrollments/complete')
      .set('x-admin-session', sessionId)
      .send({ challengeId, signature, ...extras });
  }

  async function snapshotEnrollment(label: string, cardIdHex: string) {
    const [snapshot] = await database.$queryRaw<Record<string, unknown>[]>`
      SELECT jsonb_build_object(
        'students', COALESCE((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM students s WHERE s."cardId" = decode(${cardIdHex}, 'hex')), '[]'::jsonb),
        'card_ledgers', COALESCE((SELECT jsonb_agg(to_jsonb(l)) FROM card_ledgers l WHERE l."cardId" = decode(${cardIdHex}, 'hex')), '[]'::jsonb),
        'enrollment_challenges', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM enrollment_challenges c WHERE c."cardId" = decode(${cardIdHex}, 'hex')), '[]'::jsonb),
        'transactions', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM transactions t WHERE t."cardId" = decode(${cardIdHex}, 'hex')), '[]'::jsonb)
      ) AS state`;
    expect(snapshot).toBeDefined();
    console.log(`ENROLLMENT_SNAPSHOT ${label} cardId=${cardIdHex} ${JSON.stringify(snapshot, (key, value: unknown) => key === 'adminSessionId' ? '<redacted>' : value)}`);
    return snapshot;
  }

  it('issues an admin session with a thirty minute ttl', async () => {
    const response = await request(app.getHttpServer()).post('/admin/sessions').expect(201);
    sessionIds.push(response.body.sessionId);

    expect(response.body.sessionId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    const ttlMs = new Date(response.body.expiresAt).getTime() - Date.now();
    expect(ttlMs).toBeGreaterThan(29 * 60 * 1000);
    expect(ttlMs).toBeLessThanOrEqual(30 * 60 * 1000);

    const stored = await database.adminSession.findUnique({ where: { id: response.body.sessionId } });
    expect(stored?.expiresAt.toISOString()).toBe(response.body.expiresAt);
    expect(stored?.revokedAt).toBeNull();
  });

  it('enrolls a signed challenge and writes the ledger baseline', async () => {
    const cardIdHex = 'a1b2c3d41122ee01';
    const sessionId = await issueSession();
    const card = newCardKey();

    const challengeResponse = await createChallenge(sessionId, cardIdHex, card).expect(201);
    const challenge = challengeResponse.body;
    expect(Object.keys(challenge).sort()).toEqual(['challengeId', 'expiresAt', 'nonce']);
    expect(challenge.nonce).toMatch(/^[0-9a-f]{64}$/);
    const challengeTtlMs = new Date(challenge.expiresAt).getTime() - Date.now();
    expect(challengeTtlMs).toBeGreaterThan(4 * 60 * 1000);
    expect(challengeTtlMs).toBeLessThanOrEqual(5 * 60 * 1000);

    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, challenge.nonce);
    const completeResponse = await completeChallenge(sessionId, challenge.challengeId, signature).expect(201);

    expect(completeResponse.body.cardId).toBe(cardIdHex);
    expect(completeResponse.body.studentId).toBe(STUDENT_ID);
    expect(Object.keys(completeResponse.body).sort()).toEqual(['cardId', 'enrolledAt', 'studentId']);
    expect(Number.isNaN(Date.parse(completeResponse.body.enrolledAt))).toBe(false);

    const student = await database.student.findUnique({ where: { cardId: bytesOf(cardIdHex) } });
    expect(student).not.toBeNull();
    expect(student?.studentId).toBe(STUDENT_ID);
    expect(student?.major).toBeNull();
    expect(student?.classCode).toBeNull();
    expect(Buffer.from(student?.publicKey ?? []).toString('hex')).toBe(card.pointHex);

    const ledger = await database.cardLedger.findUnique({ where: { cardId: bytesOf(cardIdHex) } });
    expect(ledger).not.toBeNull();
    expect(ledger?.lastCounter).toBe(0n);
    expect(ledger?.lastBalance).toBe(0n);
    expect(ledger?.activeVisitId).toBeNull();

    const challengeRow = await database.enrollmentChallenge.findUnique({ where: { id: challenge.challengeId } });
    expect(challengeRow?.usedAt).toBeInstanceOf(Date);
    expect(challengeRow?.classCode).toBeNull();
    expect(Buffer.from(challengeRow?.publicKeyHash ?? []).toString('hex')).toBe(
      createHash('sha256').update(Buffer.from(card.pointHex, 'hex')).digest('hex'),
    );
  });

  it('derives majors and persists only the class code saved on each challenge', async () => {
    const sessionId = await issueSession();
    const cases = [
      {
        studentId: 'CT2026001',
        classCode: '  CT2026-A  ',
        storedClassCode: 'CT2026-A',
        major: 'Công nghệ thông tin',
        completedClassCode: 'AT-TAMPER',
      },
      { studentId: 'AT2026001', major: 'An toàn thông tin' },
      {
        studentId: 'DT2026001',
        classCode: 'DT2026-01',
        storedClassCode: 'DT2026-01',
        major: 'Điện tử viễn thông',
      },
      { studentId: 'ST2026001', major: null },
    ];

    for (const [index, testCase] of cases.entries()) {
      const cardIdHex = randomBytes(8).toString('hex');
      const card = newCardKey();
      const challengeResponse = await createChallenge(
        sessionId,
        cardIdHex,
        card,
        {
          studentId: testCase.studentId.toLowerCase(),
          ...(testCase.classCode === undefined
            ? {}
            : { classCode: testCase.classCode }),
        },
      ).expect(201);
      const challenge = await database.enrollmentChallenge.findUnique({
        where: { id: challengeResponse.body.challengeId },
      });
      expect(challenge?.studentId).toBe(testCase.studentId);
      expect(challenge?.classCode).toBe(testCase.storedClassCode ?? null);

      const signature = signChallenge(
        card.privateKey,
        cardIdHex,
        card.pointHex,
        challengeResponse.body.nonce,
      );
      const completeResponse = await completeChallenge(
        sessionId,
        challengeResponse.body.challengeId,
        signature,
        testCase.completedClassCode === undefined
          ? {}
          : { classCode: testCase.completedClassCode, studentId: 'AT2026999' },
      ).expect(201);
      expect(completeResponse.body.studentId).toBe(testCase.studentId);
      const publicKeyResponse = await request(app.getHttpServer())
        .get(`/enrollments/cards/${cardIdHex}`)
        .expect(200);
      expect(publicKeyResponse.body).toEqual({ cardId: cardIdHex, publicKey: card.pointHex });

      const student = await database.student.findUnique({
        where: { cardId: bytesOf(cardIdHex) },
      });
      expect(student?.studentId).toBe(testCase.studentId);
      expect(student?.major).toBe(testCase.major);
      expect(student?.classCode).toBe(testCase.storedClassCode ?? null);
      expect(Buffer.from(student?.publicKey ?? []).toString('hex')).toBe(
        card.pointHex,
      );
      const ledger = await database.cardLedger.findUnique({
        where: { cardId: bytesOf(cardIdHex) },
      });
      expect(ledger?.lastCounter).toBe(0n);
      expect(ledger?.lastBalance).toBe(0n);
      expect(ledger?.activeVisitId).toBeNull();
      await snapshotEnrollment(`profile-${index}-enrolled`, cardIdHex);
    }
  });

  it('rejects malformed optional class codes without creating challenges', async () => {
    const sessionId = await issueSession();
    const invalidValues: unknown[] = [
      null,
      123,
      true,
      {},
      [],
      '   ',
      'A\0B',
      'A'.repeat(65),
    ];

    for (const value of invalidValues) {
      const cardIdHex = randomBytes(8).toString('hex');
      const response = await createChallenge(
        sessionId,
        cardIdHex,
        newCardKey(),
        { classCode: value },
      ).expect(400);
      expect(response.body.message).toContain('classCode');
      expect(
        await database.enrollmentChallenge.count({
          where: { cardId: bytesOf(cardIdHex) },
        }),
      ).toBe(0);
      expect(
        await database.student.count({ where: { cardId: bytesOf(cardIdHex) } }),
      ).toBe(0);
    }
  });

  it('rejects a signature over a different nonce without enrolling the card', async () => {
    const cardIdHex = 'a1b2c3d41122ee02';
    const sessionId = await issueSession();
    const card = newCardKey();

    const challengeResponse = await createChallenge(sessionId, cardIdHex, card).expect(201);
    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, randomBytes(32).toString('hex'));

    await completeChallenge(sessionId, challengeResponse.body.challengeId, signature).expect(422);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
    const challengeRow = await database.enrollmentChallenge.findUnique({
      where: { id: challengeResponse.body.challengeId },
    });
    expect(challengeRow?.usedAt).toBeNull();
  });

  it('rejects a complete call from a different admin session', async () => {
    const cardIdHex = 'a1b2c3d41122ee03';
    const ownerSessionId = await issueSession();
    const otherSessionId = await issueSession();
    const card = newCardKey();

    const challengeResponse = await createChallenge(ownerSessionId, cardIdHex, card).expect(201);
    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, challengeResponse.body.nonce);

    await completeChallenge(otherSessionId, challengeResponse.body.challengeId, signature).expect(401);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
    const challengeRow = await database.enrollmentChallenge.findUnique({
      where: { id: challengeResponse.body.challengeId },
    });
    expect(challengeRow?.usedAt).toBeNull();
  });

  it('rejects an expired challenge with a valid signature', async () => {
    const cardIdHex = 'a1b2c3d41122ee04';
    const sessionId = await issueSession();
    const card = newCardKey();
    cardIds.push(cardIdHex);

    const nonce = randomBytes(32);
    const point = Buffer.from(card.pointHex, 'hex');
    const challengeRow = await database.enrollmentChallenge.create({
      data: {
        nonce: bytesOf(nonce.toString('hex')),
        cardId: bytesOf(cardIdHex),
        studentId: STUDENT_ID,
        publicKey: bytesOf(card.pointHex),
        publicKeyHash: bytesOf(createHash('sha256').update(point).digest('hex')),
        adminSessionId: sessionId,
        expiresAt: new Date(Date.now() - 60 * 1000),
      },
    });
    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, nonce.toString('hex'));

    await completeChallenge(sessionId, challengeRow.id, signature).expect(409);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
    const stored = await database.enrollmentChallenge.findUnique({ where: { id: challengeRow.id } });
    expect(stored?.usedAt).toBeNull();
  });

  it('rejects a replay of an already used challenge', async () => {
    const cardIdHex = randomBytes(8).toString('hex');
    const sessionId = await issueSession();
    const card = newCardKey();

    const challengeResponse = await createChallenge(sessionId, cardIdHex, card).expect(201);
    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, challengeResponse.body.nonce);
    await completeChallenge(sessionId, challengeResponse.body.challengeId, signature).expect(201);

    const before = await snapshotEnrollment('replay-before', cardIdHex);

    await completeChallenge(sessionId, challengeResponse.body.challengeId, signature).expect(409);

    expect(await snapshotEnrollment('replay-after', cardIdHex)).toEqual(before);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(1);
    expect(await database.cardLedger.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(1);
    expect(await database.transaction.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
    const ledger = await database.cardLedger.findUnique({ where: { cardId: bytesOf(cardIdHex) } });
    expect(ledger?.lastCounter).toBe(0n);
    expect(ledger?.lastBalance).toBe(0n);
    expect(ledger?.activeVisitId).toBeNull();
    const challenge = await database.enrollmentChallenge.findUnique({ where: { id: challengeResponse.body.challengeId } });
    expect(challenge?.usedAt).toBeInstanceOf(Date);
  });

  it('lets exactly one of two concurrent completes enroll the card', async () => {
    const cardIdHex = 'a1b2c3d41122ee06';
    const sessionId = await issueSession();
    const card = newCardKey();

    const challengeResponse = await createChallenge(sessionId, cardIdHex, card).expect(201);
    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, challengeResponse.body.nonce);

    const [first, second] = await Promise.all([
      completeChallenge(sessionId, challengeResponse.body.challengeId, signature),
      completeChallenge(sessionId, challengeResponse.body.challengeId, signature),
    ]);

    expect([first.status, second.status].sort((left, right) => left - right)).toEqual([201, 409]);
    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(1);
    expect(await database.cardLedger.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(1);
  });

  it('rejects a challenge request without an admin session header', async () => {
    const cardIdHex = 'a1b2c3d41122ee07';
    const card = newCardKey();

    await request(app.getHttpServer())
      .post('/enrollments/challenges')
      .send({ cardId: cardIdHex, studentId: STUDENT_ID, publicKey: card.pointHex })
      .expect(401);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
  });

  it('rejects a challenge for a card that is already enrolled', async () => {
    const cardIdHex = randomBytes(8).toString('hex');
    const sessionId = await issueSession();
    const card = newCardKey();

    const challengeResponse = await createChallenge(sessionId, cardIdHex, card).expect(201);
    const signature = signChallenge(card.privateKey, cardIdHex, card.pointHex, challengeResponse.body.nonce);
    const secondCard = newCardKey();
    const replacementChallenge = await createChallenge(sessionId, cardIdHex, secondCard).expect(201);
    const beforeWrongKey = await snapshotEnrollment('wrong-key-before', cardIdHex);
    const wrongSignature = signChallenge(secondCard.privateKey, cardIdHex, card.pointHex, challengeResponse.body.nonce);
    await completeChallenge(sessionId, challengeResponse.body.challengeId, wrongSignature).expect(422);
    expect(await snapshotEnrollment('wrong-key-after', cardIdHex)).toEqual(beforeWrongKey);
    await completeChallenge(sessionId, challengeResponse.body.challengeId, signature).expect(201);

    const before = await snapshotEnrollment('replace-key-before', cardIdHex);
    await createChallenge(sessionId, cardIdHex, secondCard).expect(409);
    expect(await snapshotEnrollment('replace-key-create-after', cardIdHex)).toEqual(before);
    const replacementSignature = signChallenge(secondCard.privateKey, cardIdHex, secondCard.pointHex, replacementChallenge.body.nonce);
    await completeChallenge(sessionId, replacementChallenge.body.challengeId, replacementSignature).expect(409);
    expect(await snapshotEnrollment('replace-key-complete-after', cardIdHex)).toEqual(before);

    const student = await database.student.findUnique({ where: { cardId: bytesOf(cardIdHex) } });
    expect(Buffer.from(student?.publicKey ?? []).toString('hex')).toBe(card.pointHex);
    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(1);
    expect(await database.cardLedger.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(1);
    expect(await database.transaction.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
    const replacement = await database.enrollmentChallenge.findUnique({ where: { id: replacementChallenge.body.challengeId } });
    expect(replacement?.usedAt).toBeNull();
  });

  it('rejects a malformed public key and a malformed challenge id', async () => {
    const cardIdHex = 'a1b2c3d41122ee09';
    const sessionId = await issueSession();
    cardIds.push(cardIdHex);

    await request(app.getHttpServer())
      .post('/enrollments/challenges')
      .set('x-admin-session', sessionId)
      .send({ cardId: cardIdHex, studentId: STUDENT_ID, publicKey: '04'.repeat(32) })
      .expect(400);

    await completeChallenge(sessionId, 'not-a-uuid', 'deadbeef').expect(400);
    await completeChallenge(sessionId, '00000000-0000-4000-8000-000000000000', 'zz').expect(400);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
  });

  it('reports an unknown enrollment challenge as not found', async () => {
    const sessionId = await issueSession();
    const card = newCardKey();

    await completeChallenge(
      sessionId,
      '00000000-0000-4000-8000-000000000000',
      signChallenge(card.privateKey, 'a1b2c3d41122ee0a', card.pointHex, randomBytes(32).toString('hex')),
    ).expect(404);
  });

  it('rejects an admin session that is expired, revoked or not a uuid', async () => {
    const cardIdHex = 'a1b2c3d41122ee0c';
    const card = newCardKey();
    const body = { cardId: cardIdHex, studentId: STUDENT_ID, publicKey: card.pointHex };

    const expiredSessionId = await issueSession();
    await database.adminSession.update({
      where: { id: expiredSessionId },
      data: { expiresAt: new Date(Date.now() - 60 * 1000) },
    });
    await request(app.getHttpServer())
      .post('/enrollments/challenges')
      .set('x-admin-session', expiredSessionId)
      .send(body)
      .expect(401);

    const revokedSessionId = await issueSession();
    await database.adminSession.update({
      where: { id: revokedSessionId },
      data: { revokedAt: new Date() },
    });
    await request(app.getHttpServer())
      .post('/enrollments/challenges')
      .set('x-admin-session', revokedSessionId)
      .send(body)
      .expect(401);

    await request(app.getHttpServer())
      .post('/enrollments/challenges')
      .set('x-admin-session', 'not-a-uuid')
      .send(body)
      .expect(401);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
  });

  it('rejects an admin session that is not known', async () => {
    const cardIdHex = 'a1b2c3d41122ee0b';
    const card = newCardKey();

    await request(app.getHttpServer())
      .post('/enrollments/challenges')
      .set('x-admin-session', '00000000-0000-4000-8000-000000000000')
      .send({ cardId: cardIdHex, studentId: STUDENT_ID, publicKey: card.pointHex })
      .expect(401);

    expect(await database.student.count({ where: { cardId: bytesOf(cardIdHex) } })).toBe(0);
  });
});
