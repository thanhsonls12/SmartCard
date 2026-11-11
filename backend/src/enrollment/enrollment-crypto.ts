import { createHash, createPublicKey, verify } from 'node:crypto';
import { PUBLIC_KEY_BYTES } from './enrollment-message.js';

const P256_FIELD_PRIME = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffffn;
const P256_CURVE_A = P256_FIELD_PRIME - 3n;
const P256_CURVE_B = 0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604bn;
const UNCOMPRESSED_POINT_PREFIX = 0x04;
const COORDINATE_BYTES = 32;
const P256_JWK_CURVE = 'P-256';

export interface EnrollmentSignatureFields {
  publicKey: Buffer;
  message: Buffer;
  signature: Buffer;
}

function modField(value: bigint): bigint {
  const reduced = value % P256_FIELD_PRIME;
  return reduced >= 0n ? reduced : reduced + P256_FIELD_PRIME;
}

function coordinateToBigInt(coordinate: Buffer): bigint {
  return BigInt(`0x${coordinate.toString('hex')}`);
}

export function sha256PublicKey(publicKey: Buffer): Buffer {
  return createHash('sha256').update(publicKey).digest();
}

export function requireP256PublicKey(publicKey: Buffer): void {
  if (publicKey.length !== PUBLIC_KEY_BYTES) {
    throw new RangeError(`publicKey must be ${PUBLIC_KEY_BYTES} bytes, received ${publicKey.length}`);
  }
  if (publicKey.readUInt8(0) !== UNCOMPRESSED_POINT_PREFIX) {
    throw new RangeError(`publicKey must start with 0x04, received 0x${publicKey.subarray(0, 1).toString('hex')}`);
  }

  const x = coordinateToBigInt(publicKey.subarray(1, 1 + COORDINATE_BYTES));
  const y = coordinateToBigInt(publicKey.subarray(1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES));
  if (x >= P256_FIELD_PRIME || y >= P256_FIELD_PRIME) {
    throw new RangeError('publicKey coordinate is outside the P-256 field');
  }

  const left = modField(y * y);
  const right = modField(x * x * x + P256_CURVE_A * x + P256_CURVE_B);
  if (left !== right) {
    throw new RangeError('publicKey point is not on the P-256 curve');
  }
}

export function verifyEnrollmentSignature({ publicKey, message, signature }: EnrollmentSignatureFields): boolean {
  try {
    const key = createPublicKey({
      key: {
        kty: 'EC',
        crv: P256_JWK_CURVE,
        x: publicKey.subarray(1, 1 + COORDINATE_BYTES).toString('base64url'),
        y: publicKey.subarray(1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES).toString('base64url'),
      },
      format: 'jwk',
    });

    return verify(null, message, { key, dsaEncoding: 'der' }, signature);
  } catch {
    return false;
  }
}
