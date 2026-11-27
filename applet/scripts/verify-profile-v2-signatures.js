'use strict';

const fs = require('node:fs');
const crypto = require('node:crypto');

const evidencePath = process.argv[2];
if (!evidencePath) throw new Error('usage: node verify-profile-v2-signatures.js <evidence.jsonl>');
const raw = fs.readFileSync(evidencePath, 'utf8').trim();
if (!raw) throw new Error('signature evidence is empty');
const pointPrefix = Buffer.from('3059301306072a8648ce3d020106082a8648ce3d030107034200', 'hex');
const canonicalPlaintext = Buffer.from(
  '0209435432303236303031104e677579e1bb856e2056c4836e20416e05434e545431'
  + '1743c3b46e67206e6768e1bb87207468c3b46e672074696e', 'hex');
const expectedCardId = Buffer.from('a1b2c3d411223344', 'hex');
let sawCanonical = false;
let sawMaximumV2 = false;
let count = 0;
for (const [lineIndex, line] of raw.split(/\r?\n/).entries()) {
  if (!line) continue;
  let row;
  try { row = JSON.parse(line); } catch (error) {
    throw new Error(`invalid JSON at line ${lineIndex + 1}: ${error.message}`);
  }
  const bytes = (field) => {
    if (typeof row[field] !== 'string' || !/^(?:[0-9a-f]{2})*$/i.test(row[field])) {
      throw new Error(`invalid ${field} hex at line ${lineIndex + 1}`);
    }
    return Buffer.from(row[field], 'hex');
  };
  const point = bytes('publicKeyHex');
  const cardId = bytes('cardIdHex');
  const nonce = bytes('nonceHex');
  const plaintext = bytes('plaintextHex');
  const message = bytes('messageHex');
  const signature = bytes('signatureDerHex');
  if (point.length !== 65 || point[0] !== 4 || cardId.length !== 8
      || !cardId.equals(expectedCardId) || nonce.length !== 32) {
    throw new Error(`invalid public key/challenge dimensions at line ${lineIndex + 1}`);
  }
  if (signature.length < 8 || signature.length > 72 || signature[0] !== 0x30
      || signature[1] !== signature.length - 2) {
    throw new Error(`invalid DER signature shape at line ${lineIndex + 1}`);
  }
  const computedMessage = Buffer.concat([
    Buffer.from('LABACCESS-PROFILE-v1', 'ascii'),
    cardId,
    crypto.createHash('sha256').update(point).digest(),
    nonce,
    crypto.createHash('sha256').update(plaintext).digest(),
  ]);
  if (!computedMessage.equals(message)) {
    throw new Error(`signed message does not bind supplied profile at line ${lineIndex + 1}`);
  }
  const key = crypto.createPublicKey({
    key: Buffer.concat([pointPrefix, point]),
    format: 'der',
    type: 'spki',
  });
  if (!crypto.verify('sha256', message, key, signature)) {
    throw new Error(`ECDSA verification failed at line ${lineIndex + 1}`);
  }
  if (plaintext.equals(canonicalPlaintext)) sawCanonical = true;
  if (plaintext.length === 182 && plaintext[0] === 2) sawMaximumV2 = true;
  count++;
}
if (!sawCanonical) throw new Error('canonical 58-byte v2 profile was not signed');
if (!sawMaximumV2) throw new Error('maximum-length v2 profile was not signed');
console.log(`PROFILE-V2 Node signatures verified=${count} canonical58=true maximumV2=true`);
