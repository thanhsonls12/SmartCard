import { generateKeyPairSync, sign, type KeyObject } from 'node:crypto';
import { requireP256PublicKey, verifyEnrollmentSignature } from './enrollment-crypto.js';
import { buildEnrollmentMessage } from './enrollment-message.js';

const CARD_ID = Buffer.from('a1b2c3d41122ee01', 'hex');
const PUBLIC_KEY_HASH = Buffer.from('aa'.repeat(32), 'hex');
const NONCE = Buffer.from('bb'.repeat(32), 'hex');

function rawPoint(publicKey: KeyObject): Buffer {
  const jwk = publicKey.export({ format: 'jwk' }) as { x: string; y: string };
  return Buffer.concat([Buffer.from([0x04]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
}

function newKeyPair() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  return { publicKey, privateKey, point: rawPoint(publicKey) };
}

describe('requireP256PublicKey', () => {
  it('accepts a generated P-256 public point', () => {
    const { point } = newKeyPair();

    expect(point).toHaveLength(65);
    expect(() => requireP256PublicKey(point)).not.toThrow();
  });

  it('rejects a 64-byte public key', () => {
    const { point } = newKeyPair();

    expect(() => requireP256PublicKey(point.subarray(1))).toThrow(/must be 65 bytes/);
  });

  it('rejects a key without the 0x04 uncompressed prefix', () => {
    const { point } = newKeyPair();
    const compressed = Buffer.from(point);
    compressed.writeUInt8(0x05, 0);

    expect(() => requireP256PublicKey(compressed)).toThrow(/must start with 0x04/);
  });

  it('rejects a 65-byte point that is not on the curve', () => {
    const offCurve = Buffer.concat([Buffer.from([0x04]), Buffer.alloc(32), Buffer.alloc(31), Buffer.from([0x01])]);
    offCurve.writeUInt8(0x01, 32);

    expect(() => requireP256PublicKey(offCurve)).toThrow(/not on the P-256 curve/);
  });

  it('rejects a coordinate outside the P-256 field', () => {
    const outsideField = Buffer.concat([Buffer.from([0x04]), Buffer.alloc(64, 0xff)]);

    expect(() => requireP256PublicKey(outsideField)).toThrow(/outside the P-256 field/);
  });
});

describe('verifyEnrollmentSignature', () => {
  function signedMessage(overrides: { nonce?: Buffer; cardId?: Buffer } = {}) {
    const { privateKey, point } = newKeyPair();
    const message = buildEnrollmentMessage({
      cardId: overrides.cardId ?? CARD_ID,
      publicKeyHash: PUBLIC_KEY_HASH,
      nonce: overrides.nonce ?? NONCE,
    });
    return { point, message, signature: sign('sha256', message, privateKey) };
  }

  it('accepts a signature over the enrollment message', () => {
    const { point, message, signature } = signedMessage();

    expect(verifyEnrollmentSignature({ publicKey: point, message, signature })).toBe(true);
  });

  it('rejects a signature over a different nonce', () => {
    const { point, message, signature } = signedMessage({ nonce: Buffer.from('cc'.repeat(32), 'hex') });
    const rebuilt = buildEnrollmentMessage({ cardId: CARD_ID, publicKeyHash: PUBLIC_KEY_HASH, nonce: NONCE });

    expect(message.equals(rebuilt)).toBe(false);
    expect(verifyEnrollmentSignature({ publicKey: point, message: rebuilt, signature })).toBe(false);
  });

  it('rejects a signature over a different cardId', () => {
    const { point, signature } = signedMessage({ cardId: Buffer.from('ff'.repeat(8), 'hex') });
    const rebuilt = buildEnrollmentMessage({ cardId: CARD_ID, publicKeyHash: PUBLIC_KEY_HASH, nonce: NONCE });

    expect(verifyEnrollmentSignature({ publicKey: point, message: rebuilt, signature })).toBe(false);
  });

  it('rejects a signature produced by a different key', () => {
    const signed = signedMessage();
    const other = newKeyPair();

    expect(verifyEnrollmentSignature({ publicKey: other.point, message: signed.message, signature: signed.signature })).toBe(
      false,
    );
  });

  it('rejects a tampered signature byte', () => {
    const { point, message, signature } = signedMessage();
    const tampered = Buffer.from(signature);
    tampered.writeUInt8(tampered.readUInt8(5) ^ 0x01, 5);

    expect(verifyEnrollmentSignature({ publicKey: point, message, signature: tampered })).toBe(false);
  });

  it('rejects a signature that is not DER', () => {
    const { point, message } = signedMessage();

    expect(verifyEnrollmentSignature({ publicKey: point, message, signature: Buffer.alloc(8) })).toBe(false);
  });
});
