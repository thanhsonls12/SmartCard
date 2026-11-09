import { readFileSync } from 'node:fs';
import { createPublicKey, generateKeyPairSync, verify } from 'node:crypto';

const DOMAIN = Buffer.from('LABACCESS-RECEIPT-v1', 'ascii');
const PAYLOAD_LENGTH = 34;
const OFFSET_VERSION = 0;
const OFFSET_CARD_ID = 1;
const OFFSET_COUNTER = 9;
const OFFSET_OPERATION = 13;
const OFFSET_DELTA = 14;
const OFFSET_BALANCE = 16;
const OFFSET_VISIT_ID = 18;

const results = [];
let failures = 0;

function check(name, ok, detail) {
  results.push(`${ok ? 'PASS' : 'FAIL'} | ${name.padEnd(52)} | ${detail}`);
  if (!ok) failures++;
}

function jwkFromPoint(pointHex) {
  const raw = Buffer.from(pointHex, 'hex');
  if (raw.length !== 65 || raw[0] !== 0x04) {
    throw new Error(`public key khong hop le: ${raw.length} byte`);
  }
  return {
    kty: 'EC',
    crv: 'P-256',
    x: raw.subarray(1, 33).toString('base64url'),
    y: raw.subarray(33, 65).toString('base64url'),
  };
}

function verifyRecord(record, key) {
  const payload = Buffer.from(record.payloadHex, 'hex');
  const signature = Buffer.from(record.signatureDerHex, 'hex');
  return verify('sha256', Buffer.concat([DOMAIN, payload]), { key, dsaEncoding: 'der' }, signature);
}

const path = process.argv[2];
if (!path) {
  console.error('thieu tham so: duong dan artifact NDJSON');
  process.exit(2);
}

const records = readFileSync(path, 'utf8')
  .split(/\r?\n/)
  .filter((line) => line.trim().length > 0)
  .map((line) => JSON.parse(line));

if (records.length === 0) {
  console.error('artifact rong');
  process.exit(2);
}

console.log(`Artifact : ${path}`);
console.log(`Receipt  : ${records.length} ban ghi`);
console.log('Domain   : LABACCESS-RECEIPT-v1');
console.log('Thuat toan: ECDSA P-256 / SHA-256, chu ky DER');
console.log();

const foreignKey = generateKeyPairSync('ec', { namedCurve: 'P-256' }).publicKey;

for (const record of records) {
  const key = createPublicKey({ key: jwkFromPoint(record.publicKeyHex), format: 'jwk' });
  const payload = Buffer.from(record.payloadHex, 'hex');
  const signature = Buffer.from(record.signatureDerHex, 'hex');
  const expect = record.expect;

  const valid = verifyRecord(record, key);
  check(`${record.tag}: chu ky hop le`, valid === true,
    `verify=${valid}, payload=${payload.length}B, sig=${signature.length}B`);

  const structureOk = payload.length === PAYLOAD_LENGTH
    && payload.readUInt8(OFFSET_VERSION) === expect.version
    && payload.subarray(OFFSET_CARD_ID, OFFSET_CARD_ID + 8).toString('hex')
      === expect.cardIdHex.toLowerCase()
    && payload.readUInt32BE(OFFSET_COUNTER) === expect.counter
    && payload.readUInt8(OFFSET_OPERATION) === expect.operation
    && payload.readInt16BE(OFFSET_DELTA) === expect.delta
    && payload.readUInt16BE(OFFSET_BALANCE) === expect.balance
    && payload.subarray(OFFSET_VISIT_ID, OFFSET_VISIT_ID + 16).toString('hex')
      === expect.visitIdHex.toLowerCase();
  check(`${record.tag}: payload dung truong`, structureOk,
    `op=${payload.readUInt8(OFFSET_OPERATION)} delta=${payload.readInt16BE(OFFSET_DELTA)}`
    + ` balance=${payload.readUInt16BE(OFFSET_BALANCE)} counter=${payload.readUInt32BE(OFFSET_COUNTER)}`);

  check(`${record.tag}: dung public key khac -> FALSE`, verifyRecord(record, foreignKey) === false,
    'verify bang khoa la khac phai that bai');
}

const reference = records[0];
const referenceKey = createPublicKey({ key: jwkFromPoint(reference.publicKeyHex), format: 'jwk' });

const fieldOffsets = [
  ['version', OFFSET_VERSION],
  ['cardId', OFFSET_CARD_ID],
  ['counterAfter', OFFSET_COUNTER],
  ['operation', OFFSET_OPERATION],
  ['delta', OFFSET_DELTA],
  ['balanceAfter', OFFSET_BALANCE],
  ['visitId', OFFSET_VISIT_ID],
];

for (const [name, offset] of fieldOffsets) {
  const mutated = Buffer.from(reference.payloadHex, 'hex');
  mutated[offset] ^= 0x01;
  const tampered = { ...reference, payloadHex: mutated.toString('hex') };
  check(`sua 1 byte payload o ${name} -> FALSE`, verifyRecord(tampered, referenceKey) === false,
    `offset=${offset}`);
}

const tamperedSignature = Buffer.from(reference.signatureDerHex, 'hex');
tamperedSignature[tamperedSignature.length - 1] ^= 0x01;
check('sua 1 byte chu ky -> FALSE',
  verifyRecord({ ...reference, signatureDerHex: tamperedSignature.toString('hex') },
    referenceKey) === false, 'byte cuoi bi lat');

const byTag = new Map(records.map((record) => [record.tag, record]));
const pairs = [
  ['grant', 'grant-again', 'receipt GRANT khong doi sau khi Le thieu'],
  ['end', 'end-reconnect', 'receipt END khong doi sau reconnect'],
  ['before-commit-A', 'before-commit-A-kept', 'receipt cu giu nguyen sau fault truoc commit'],
];

for (const [left, right, label] of pairs) {
  const a = byTag.get(left);
  const b = byTag.get(right);
  check(label, a !== undefined && b !== undefined && a.payloadHex === b.payloadHex,
    a && b ? `payload ${a.payloadHex === b.payloadHex ? 'giong' : 'KHAC'} nhau` : 'thieu ban ghi');
}

const distinct = [
  ['grant', 'start', 'receipt START thay receipt GRANT'],
  ['start', 'end', 'receipt END thay receipt START'],
  ['after-commit-A', 'after-commit-new', 'fault sau commit van ghi receipt moi'],
];

for (const [left, right, label] of distinct) {
  const a = byTag.get(left);
  const b = byTag.get(right);
  check(label, a !== undefined && b !== undefined && a.payloadHex !== b.payloadHex,
    a && b ? `payload ${a.payloadHex !== b.payloadHex ? 'khac' : 'giong'} nhau` : 'thieu ban ghi');
}

console.log();
console.log('================ KET QUA NODE ================');
for (const line of results) console.log(line);
console.log('=============================================');
console.log(failures === 0
  ? `KET LUAN SIGN-05 (Node): PASS (${results.length} kiem tra)`
  : `KET LUAN SIGN-05 (Node): FAIL (${failures}/${results.length})`);
console.log('=============================================');

process.exit(failures === 0 ? 0 : 1);
