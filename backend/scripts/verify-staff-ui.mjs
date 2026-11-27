import 'dotenv/config';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../dist/generated/prisma/client.js';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { receiptMessage } from '../dist/receipts/receipt-canonical.js';
import { readFileSync, mkdtempSync } from 'node:fs';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const url = new URL(process.env.DATABASE_URL);
assert(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname));
url.pathname = '/labaccess_test';
const root = path.resolve('..');
const port = 13100;
const testEnv = { ...process.env, DATABASE_URL: url.href, PORT: String(port) };
const base = 'http://127.0.0.1:' + port;
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url.href }) });
const staffIds = [];
const cardId = randomBytes(8);
const cardHex = cardId.toString('hex');
const accounts = ['A', 'B'].map(label => {
  const suffix = randomBytes(8).toString('hex');
  return { staffCode: 'U' + suffix, username: 'ui-' + suffix,
    displayName: 'Staff Test ' + label, password: randomBytes(24).toString('base64url') };
});
let server;
async function api(route, body, token) {
  const response = await fetch(base + route, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(15000),
  });
  assert(response.ok, 'fixture API failed: ' + route + ' HTTP ' + response.status);
  return response.status === 204 ? null : response.json();
}

async function createAuditFixture() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' });
  const point = Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
  await db.student.create({ data: { cardId, studentId: 'UI' + cardHex.slice(0, 8), publicKey: point } });
  await db.cardLedger.create({ data: { cardId } });
  const payload = Buffer.alloc(34);
  payload[0] = 1;
  cardId.copy(payload, 1);
  payload.writeUInt32BE(1, 9);
  payload[13] = 1;
  payload.writeInt16BE(5, 14);
  payload.writeUInt16BE(5, 16);
  const a = await api('/staff/auth/login', accounts[0]);
  const b = await api('/staff/auth/login', accounts[1]);
  try {
    const grant = await api('/staff/audit', { action: 'GRANT', cardId: cardHex,
      expectedPayload: payload.toString('hex') }, a.token);
    const recovery = await api('/staff/audit', { action: 'RECONCILE', cardId: cardHex,
      originId: grant.operationId }, b.token);
    await api('/receipts/staff/' + recovery.operationId + '/verify', {
      payload: payload.toString('hex'), signature: sign('sha256', receiptMessage(payload), privateKey).toString('hex'),
    }, b.token);
    await api('/staff/audit', { action: 'PHOTO_UPLOAD', cardId: cardHex }, a.token);
  } finally {
    await api('/staff/auth/logout', {}, a.token);
    await api('/staff/auth/logout', {}, b.token);
  }
  console.log('PASS fixture created via authenticated API and verified P-256 receipt');
}
try {
  let occupied = false;
  try { await fetch(base, { signal: AbortSignal.timeout(1000) }); occupied = true; } catch { }
  assert(!occupied, 'test Backend port is occupied');
  for (const account of accounts) {
    const created = spawnSync(process.execPath, ['scripts/provision-staff.mjs'], {
      cwd: process.cwd(), env: testEnv, input: JSON.stringify(account), encoding: 'utf8', timeout: 15000,
    });
    assert.equal(created.status, 0, 'provisioning script failed');
    const staff = await db.staff.findUniqueOrThrow({ where: { username: account.username } });
    staffIds.push(staff.id);
    assert.equal(staff.displayName, account.displayName);
    console.log('PASS provisioning script created test account');
  }
  server = spawn(process.execPath, ['dist/main.js'], { env: testEnv, stdio: 'ignore', windowsHide: true });
  let ready = false;
  for (let i = 0; i < 40; i++) {
    if (server.exitCode !== null) { break; }
    try {
      const response = await fetch(base + '/health/database', { signal: AbortSignal.timeout(1000) });
      ready = response.ok && (await response.json()).database === 'labaccess_test';
      if (ready) { break; }
    } catch { }
    await delay(500);
  }
  assert(ready, 'test Backend not ready');
  await createAuditFixture();
  const desktop = path.join(root, 'desktop');
  const deps = readFileSync(path.join(desktop, 'target/launcher/runtime-classpath.txt'), 'utf8').trim();
  const cp = [path.join(desktop, 'target/test-classes'), path.join(desktop, 'target/classes'), deps].join(path.delimiter);
  const evidence = mkdtempSync(path.join(root, 'logs/staff-auth-ui-'));
  const java = path.join(process.env.JAVA_HOME, 'bin/java.exe');
  const result = spawnSync(java, ['--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8', '-Dlabaccess.backend=' + base, '-Duser.home=' + evidence,
    '-cp', cp, 'vn.labaccess.desktop.StaffAuthUiVerify'], {
    cwd: desktop, input: accounts.map(a => a.username + '\n' + a.password).join('\n')
      + '\n' + cardHex + '\n' + accounts.map(a => a.staffCode.toUpperCase()).join('\n') + '\n',
    encoding: 'utf8', timeout: 90000, windowsHide: true,
  });
  const output = (result.stdout ?? '').split(/\r?\n/).filter(line => /^PASS |^STAFF AUTH UI PASS/.test(line));
  output.forEach(line => console.log(line));
  if (result.status !== 0) {
    const diagnostic = (result.stderr ?? '').split(/\r?\n/).filter(line => /AssertionError|Exception|Caused by:/.test(line));
    diagnostic.forEach(line => console.error(line.replace(/Bearer [A-Za-z0-9_-]+/g, '<redacted>')));
    throw new Error('Staff UI verification failed');
  }
  const sessions = await db.staffSession.findMany({ where: { staffId: { in: staffIds } } });
  assert.equal(sessions.length, 4);
  assert(sessions.every(session => session.revokedAt !== null));
  console.log('PASS both Staff sessions revoked in PostgreSQL after GUI logout');
} finally {
  if (server && server.exitCode === null) { server.kill(); }
  const operations = { staffSession: { staffId: { in: staffIds } } };
  await db.staffAuditEvent.deleteMany({ where: { operation: operations } });
  await db.transaction.deleteMany({ where: { cardId } });
  await db.staffAuditOperation.deleteMany({ where: { ...operations, originId: { not: null } } });
  await db.staffAuditOperation.deleteMany({ where: operations });
  await db.cardLedger.deleteMany({ where: { cardId } });
  await db.student.deleteMany({ where: { cardId } });
  await db.staffSession.deleteMany({ where: { staffId: { in: staffIds } } });
  await db.staff.deleteMany({ where: { id: { in: staffIds } } });
  await db.$disconnect();
}
