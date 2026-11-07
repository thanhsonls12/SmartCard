/*
 * LabAccess — Gate 0 (G0-13) kiem chung chu ky ECDSA bang Node.js.
 *
 * Doc artifact do client Java xuat ra, kiem 4 truong hop:
 *   1. message goc + pubkey 1 + chu ky  -> phai TRUE
 *   2. message doi 1 byte + pubkey 1    -> phai FALSE
 *   3. message goc + pubkey 1 + chu ky doi 1 byte -> phai FALSE
 *   4. message goc + pubkey 2 (khoa khac)         -> phai FALSE
 *
 * Chu ky o dang DER, duong cong P-256, hash SHA-256 (crypto.verify tu bam 1 lan).
 */

import { readFileSync } from 'node:fs';
import { createPublicKey, verify } from 'node:crypto';

const ARTIFACT = 'D:\\SmartCard\\artifacts\\crypto-probe\\ecdsa-proof.json';

function parsePoint(hex) {
  const raw = Buffer.from(hex, 'hex');
  if (raw.length !== 65 || raw[0] !== 0x04) {
    throw new Error(`EC point khong hop le: ${raw.length} byte, tien to 0x${raw[0].toString(16)}`);
  }
  const x = raw.subarray(1, 33).toString('base64url');
  const y = raw.subarray(33, 65).toString('base64url');
  return createPublicKey({ key: { kty: 'EC', crv: 'P-256', x, y }, format: 'jwk' });
}

const proof = JSON.parse(readFileSync(ARTIFACT, 'utf8'));
const message = Buffer.from(proof.messageHex, 'hex');
const signature = Buffer.from(proof.signatureHex, 'hex');
const pub1 = parsePoint(proof.publicKey1Hex);
const pub2 = parsePoint(proof.publicKey2Hex);

console.log('Artifact : ' + ARTIFACT);
console.log('Curve    : ' + proof.curve + ' (nguon: ' + proof.curveSource + ')');
console.log('Hash     : ' + proof.hash);
console.log('Sig format: ' + proof.signatureFormat + ', ' + signature.length + ' byte');
console.log('Message  : ' + JSON.stringify(proof.messageUtf8) + ' (' + message.length + ' byte)');
console.log('Pubkey1  : ' + proof.publicKey1Hex.slice(0, 20) + '...');
console.log('Pubkey2  : ' + proof.publicKey2Hex.slice(0, 20) + '...');
console.log('');

function check(label, pub, msg, sig, expected) {
  const got = verify('sha256', msg, { key: pub, dsaEncoding: 'der' }, sig);
  const pass = got === expected;
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${label}`);
  console.log(`       ket qua=${got}, ky vong=${expected}`);
  return pass;
}

const results = [];

results.push(check('Chu ky that + message goc', pub1, message, signature, true));

const tamperedMsg = Buffer.from(message);
tamperedMsg[0] ^= 0x01;
results.push(check('Message bi doi 1 byte', pub1, tamperedMsg, signature, false));

const tamperedSig = Buffer.from(signature);
tamperedSig[tamperedSig.length - 1] ^= 0x01;
results.push(check('Chu ky bi doi 1 byte', pub1, message, tamperedSig, false));

results.push(check('Dung public key cua cap khoa khac', pub2, message, signature, false));

const ok = results.every(Boolean);
console.log('');
console.log('========================================');
console.log('KET LUAN G0-13: ' + (ok ? 'PASS' : 'FAIL'));
console.log('========================================');
process.exit(ok ? 0 : 1);
