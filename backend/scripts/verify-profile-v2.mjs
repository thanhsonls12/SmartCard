// Run after `npm run build` and migrations against a disposable local database:
// TEST_DATABASE_URL=postgresql://.../labaccess_test node scripts/verify-profile-v2.mjs
// Inserts isolated fixtures; never deletes application data or prints credentials.
import 'reflect-metadata';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { Test } from '@nestjs/testing';
import request from 'supertest';

assert.ok(process.env.TEST_DATABASE_URL, 'Set TEST_DATABASE_URL explicitly; .env is not a test database source');
const target = new URL(process.env.TEST_DATABASE_URL);
assert.ok(['postgres:', 'postgresql:'].includes(target.protocol), 'A PostgreSQL URL is required');
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname), 'Only a local disposable database is allowed');
assert.equal(target.pathname, '/labaccess_test', 'The database must be named labaccess_test');
process.env.DATABASE_URL = process.env.TEST_DATABASE_URL;

// Load configuration only after enforcing the explicit test database override.
const { AppModule } = await import('../dist/app.module.js');
const { DatabaseService } = await import('../dist/database/database.service.js');
const { hashPassword } = await import('../dist/staff-auth/password.js');
const sha = value => createHash('sha256').update(value).digest();
const field = value => {
  const bytes = Buffer.from(value, 'utf8');
  return Buffer.concat([Buffer.from([bytes.length]), bytes]);
};
const plaintext = (studentId, classCode, major, schema = 2) => Buffer.concat([
  Buffer.from([schema]), field(studentId), field('Nguyễn Văn An'),
  ...(schema === 2 ? [field(classCode), field(major)] : []),
]);
let checks = 0;
function passed(label) {
  checks++;
  console.log(`PASS ${checks} ${label}`);
}
const fixture = await Test.createTestingModule({ imports: [AppModule] }).compile();
const app = fixture.createNestApplication();
try {
  await app.init();
  const db = app.get(DatabaseService);
  assert.equal((await db.checkConnection()).database, 'labaccess_test');
  passed('disposable PostgreSQL identity verified');
  const suffix = randomBytes(8).toString('hex');
  const password = randomBytes(32).toString('base64url');
  const username = `v2-${suffix}`;
  await db.staff.create({
    data: { staffCode: suffix, username, displayName: 'Profile v2 probe', passwordHash: await hashPassword(password) },
  });
  const login = await request(app.getHttpServer()).post('/staff/auth/login').send({ username, password }).expect(200);
  const auth = `Bearer ${login.body.token}`;
  const api = (method, path, session) => {
    const req = request(app.getHttpServer())[method](path).set('Authorization', auth);
    return session ? req.set('x-admin-session', session) : req;
  };
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' });
  const point = Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
  const cardId = randomBytes(8).toString('hex');
  const storedCardId = Buffer.from(cardId, 'hex');
  const profileMessage = (nonce, hash) => Buffer.concat([
    Buffer.from('LABACCESS-PROFILE-v1', 'ascii'), storedCardId, sha(point), Buffer.from(nonce, 'hex'), hash,
  ]);
  const signature = (nonce, plain) => sign('sha256', profileMessage(nonce, sha(plain)), privateKey).toString('hex');
  const issued = (await api('post', '/admin/sessions').expect(201)).body;
  const challenge = (await api('post', '/enrollments/challenges', issued.sessionId).send({
    cardId, studentId: 'CT2026001', publicKey: point.toString('hex'), classCode: 'CNTT1',
  }).expect(201)).body;
  const enrollmentMessage = Buffer.concat([
    Buffer.from('LABACCESS-ENROLLMENT-v1', 'ascii'), storedCardId, sha(point), Buffer.from(challenge.nonce, 'hex'),
  ]);
  await api('post', '/enrollments/complete', issued.sessionId).send({
    challengeId: challenge.challengeId, signature: sign('sha256', enrollmentMessage, privateKey).toString('hex'),
  }).expect(201);
  passed('real HTTP staff login and signed enrollment');
  const proofHash = sha(plaintext('CT2026001', 'CNTT1', 'Công nghệ thông tin'));
  await api('post', `/admin/sessions/${issued.sessionId}/activate`).send({
    cardId, profileHash: proofHash.toString('hex'),
    signature: sign('sha256', profileMessage(issued.nonce, proofHash), privateKey).toString('hex'),
  }).expect(200);
  passed('real signed card-bound admin proof');
  const content = {
    studentId: 'CT2026001', displayName: 'Nguyễn Văn An', classCode: 'CNTT1', expectedVersion: '11'.repeat(12),
  };
  const legacy = await db.profileUpdate.create({
    data: {
      cardId: storedCardId, ...content, classCode: null,
      expectedVersion: Buffer.from(content.expectedVersion, 'hex'), nonce: randomBytes(32),
      adminSessionId: issued.sessionId, expiresAt: new Date(Date.now() + 300000),
    },
  });
  const found = (await api('get', `/profile-updates/pending/${cardId}`, issued.sessionId).expect(200)).body;
  assert.equal(found.operationId, legacy.id);
  assert.equal(found.classCode, null);
  passed('legacy pending discovery preserves null classCode');
  await api('post', `/profile-updates/${legacy.id}/complete`, issued.sessionId).send({
    signature: signature(found.nonce, plaintext(content.studentId, '', '', 1)),
  }).expect(409);
  assert.equal((await db.profileUpdate.findUniqueOrThrow({ where: { id: legacy.id } })).state, 'PENDING');
  passed('legacy completion returns 409 without writing');
  const replacement = (await api('post', `/profile-updates/${legacy.id}/replace`, issued.sessionId).send(content).expect(200)).body;
  assert.notEqual(replacement.operationId, legacy.id);
  assert.equal(replacement.expectedVersion, content.expectedVersion);
  assert.equal((await db.profileUpdate.findUniqueOrThrow({ where: { id: legacy.id } })).state, 'SUPERSEDED');
  assert.equal(await db.profileUpdate.count({ where: { cardId: storedCardId, state: 'PENDING' } }), 1);
  passed('same-version legacy replacement supersedes old row and creates exactly one pending row');
  await api('post', `/profile-updates/${replacement.operationId}/replace`, issued.sessionId).send(content).expect(409);
  assert.equal((await db.profileUpdate.findUniqueOrThrow({ where: { id: replacement.operationId } })).state, 'PENDING');
  passed('non-null v2 same-version replacement still returns 409');
  for (const [label, plain] of [
    ['legacy v1', plaintext(content.studentId, '', '', 1)],
    ['wrong class', plaintext(content.studentId, 'CNTT2', 'Công nghệ thông tin')],
    ['wrong major', plaintext(content.studentId, content.classCode, 'An toàn thông tin')],
    ['missing major', plaintext(content.studentId, content.classCode, '')],
  ]) {
    await api('post', `/profile-updates/${replacement.operationId}/complete`, issued.sessionId).send({
      signature: signature(replacement.nonce, plain),
    }).expect(422);
    assert.equal((await db.profileUpdate.findUniqueOrThrow({ where: { id: replacement.operationId } })).state, 'PENDING');
    passed(`${label} signature rejected with 422 and no completion`);
  }
  for (const [index, [studentId, major]] of [
    ['CT2026001', 'Công nghệ thông tin'], ['AT2026001', 'An toàn thông tin'],
    ['DT2026001', 'Điện tử viễn thông'], ['XY2026001', null],
  ].entries()) {
    const current = index === 0 ? replacement : (await api('post', '/profile-updates', issued.sessionId).send({
      ...content, cardId, studentId,
    }).expect(201)).body;
    const signed = signature(current.nonce, plaintext(studentId, content.classCode, major ?? ''));
    const result = (await api('post', `/profile-updates/${current.operationId}/complete`, issued.sessionId).send({
      signature: signed,
    }).expect(200)).body;
    assert.equal(result.studentId, studentId);
    assert.equal(result.classCode, content.classCode);
    assert.equal(result.major, major);
    assert.equal(result.alreadyCompleted, false);
    const stored = await db.student.findUniqueOrThrow({ where: { cardId: storedCardId } });
    const operation = await db.profileUpdate.findUniqueOrThrow({ where: { id: current.operationId } });
    assert.equal(stored.studentId, studentId);
    assert.equal(stored.major, major);
    assert.equal(stored.classCode, content.classCode);
    assert.equal(operation.state, 'COMPLETED');
    assert.equal(operation.resultMajor, major);
    assert.equal(operation.resultClassCode, content.classCode);
    const repeat = (await api('post', `/profile-updates/${current.operationId}/complete`, issued.sessionId).send({
      signature: signed,
    }).expect(200)).body;
    assert.equal(repeat.alreadyCompleted, true);
    assert.equal(repeat.completedAt, result.completedAt);
    console.log(`PROFILE_RESULT ${JSON.stringify({ studentId, major, classCode: stored.classCode, state: operation.state, idempotent: true })}`);
    passed(`${studentId.slice(0, 2)} canonical v2 HTTP completion persists class/major and replays idempotently`);
  }
  console.log(`PROFILE_V2_PROBE ${checks}/${checks} PASS database=labaccess_test explicit_TEST_DATABASE_URL=true`);
} finally {
  await app.close();
}
