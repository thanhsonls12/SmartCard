/*
 * Gate 0 — G0-15: kiem chu ky receipt doc lap bang Node.js.
 *
 * Xac minh ECDSA P-256 / SHA-256 tren  domain "LABACCESS-RECEIPT-v1" || payload,
 * dung public key doc tu the. Khong tin vao loi applet tu bao.
 *
 * Kiem 4 dieu kien:
 *   1. Receipt B sau commit  -> verify TRUE
 *   2. Receipt A sau abort   -> verify TRUE (con nguyen, dung khoa)
 *   3. Sua 1 byte payload    -> verify FALSE
 *   4. Sua 1 byte chu ky     -> verify FALSE
 *
 * Chay: node verify-receipt.mjs
 */

import { readFileSync } from 'node:fs';
import { createPublicKey, verify } from 'node:crypto';

const DIR = 'D:\\SmartCard\\artifacts\\tx-probe';
const DOMAIN = Buffer.from('LABACCESS-RECEIPT-v1', 'ascii');

const results = [];
let failures = 0;

function check(name, ok, detail) {
  results.push(`${ok ? 'PASS' : 'FAIL'} | ${name.padEnd(46)} | ${detail}`);
  if (!ok) failures++;
}

/** JWK P-256 tu uncompressed point 04||X||Y (65 byte). */
function jwkFromPoint(pointHex) {
  const b = Buffer.from(pointHex, 'hex');
  if (b.length !== 65 || b[0] !== 0x04) {
    throw new Error('public key khong phai 65 byte 04||X||Y');
  }
  return {
    kty: 'EC',
    crv: 'P-256',
    x: b.subarray(1, 33).toString('base64url'),
    y: b.subarray(33, 65).toString('base64url'),
  };
}

function loadJson(name) {
  return JSON.parse(readFileSync(`${DIR}\\${name}`, 'utf8'));
}

function verifyReceipt(rec, key) {
  const payload = Buffer.from(rec.payloadHex, 'hex');
  const sig = Buffer.from(rec.signatureDerHex, 'hex');
  const msg = Buffer.concat([DOMAIN, payload]);
  return verify('sha256', msg, { key, dsaEncoding: 'der' }, sig);
}

const pubRec = loadJson('receipt-B.json');
const key = createPublicKey({ key: jwkFromPoint(pubRec.publicKeyHex), format: 'jwk' });

const recB = loadJson('receipt-B.json');
const recA = loadJson('receipt-A-after-abort.json');
const recBErr = loadJson('receipt-B-after-error.json');

console.log('Public key : ' + pubRec.publicKeyHex.slice(0, 24) + '...');
console.log('Domain     : LABACCESS-RECEIPT-v1');
console.log('Hash       : SHA-256, chu ky DER');
console.log();

/* 1. receipt B sau commit */
const okB = verifyReceipt(recB, key);
check('1. Receipt B sau commit', okB === true, `verify=${okB}, payload=${recB.payloadLength}B, sig=${recB.signatureLength}B`);

/* 2. receipt A sau abort — phai con nguyen va verify duoc */
const okA = verifyReceipt(recA, key);
check('2. Receipt A sau abort (con nguyen)', okA === true, `verify=${okA}, payload=${recA.payloadLength}B, sig=${recA.signatureLength}B`);

/* 3. sua 1 byte payload */
const badPayload = { ...recB, payloadHex: flipFirstByte(recB.payloadHex) };
const okBadPayload = verifyReceipt(badPayload, key);
check('3. Sua 1 byte payload -> FALSE', okBadPayload === false, `verify=${okBadPayload}`);

/* 4. sua 1 byte chu ky */
const badSig = { ...recB, signatureDerHex: flipLastByte(recB.signatureDerHex) };
const okBadSig = verifyReceipt(badSig, key);
check('4. Sua 1 byte chu ky -> FALSE', okBadSig === false, `verify=${okBadSig}`);

/* 5. receipt sau loi commit cung phai verify duoc (du lieu da la B) */
const okBErr = verifyReceipt(recBErr, key);
check('5. Receipt B sau loi commit', okBErr === true, `verify=${okBErr}`);

/* 6. hai receipt B phai la cung payload (cung giao dich) */
check('6. Payload B truoc/sau loi giong nhau',
  recB.payloadHex === recBErr.payloadHex,
  `${recB.payloadHex === recBErr.payloadHex ? 'giong' : 'KHAC'}`);

function flipFirstByte(hex) {
  const b = Buffer.from(hex, 'hex');
  b[0] ^= 0x01;
  return b.toString('hex');
}

function flipLastByte(hex) {
  const b = Buffer.from(hex, 'hex');
  b[b.length - 1] ^= 0x01;
  return b.toString('hex');
}

console.log();
console.log('================ KET QUA ================');
for (const r of results) console.log(r);
console.log('========================================');
console.log(failures === 0 ? 'KET LUAN G0-15 (chu ky): PASS' : `KET LUAN G0-15 (chu ky): FAIL (${failures})`);
console.log('========================================');

process.exit(failures === 0 ? 0 : 1);
