import { CARD_ID_BYTES, NONCE_BYTES, PUBLIC_KEY_HASH_BYTES } from '../enrollment/enrollment-message.js';

export const PROFILE_DOMAIN = 'LABACCESS-PROFILE-v1';
export const PROFILE_HASH_BYTES = 32;
export const PROFILE_MESSAGE_BYTES = 124;
export const EXPECTED_VERSION_BYTES = 12;
export const PROFILE_SCHEMA_VERSION = 0x01;

const DOMAIN_BYTES = Buffer.from(PROFILE_DOMAIN, 'ascii');
const LENGTH_FIELD_MAX = 0xff;

export interface ProfileMessageFields {
  cardId: Buffer;
  publicKeyHash: Buffer;
  nonce: Buffer;
  profileHash: Buffer;
}

export interface ProfilePlaintextFields {
  studentId: string;
  displayName: string;
}

function requireLength(value: Buffer, expectedBytes: number, field: string): void {
  if (value.length !== expectedBytes) {
    throw new RangeError(`${field} must be ${expectedBytes} bytes, received ${value.length}`);
  }
}

function requireLengthField(value: Buffer, field: string): number {
  if (value.length < 1 || value.length > LENGTH_FIELD_MAX) {
    throw new RangeError(`${field} must be 1..${LENGTH_FIELD_MAX} bytes, received ${value.length}`);
  }
  return value.length;
}

export function buildProfileMessage({ cardId, publicKeyHash, nonce, profileHash }: ProfileMessageFields): Buffer {
  requireLength(cardId, CARD_ID_BYTES, 'cardId');
  requireLength(publicKeyHash, PUBLIC_KEY_HASH_BYTES, 'publicKeyHash');
  requireLength(nonce, NONCE_BYTES, 'nonce');
  requireLength(profileHash, PROFILE_HASH_BYTES, 'profileHash');

  return Buffer.concat([DOMAIN_BYTES, cardId, publicKeyHash, nonce, profileHash]);
}

export function buildProfilePlaintext({ studentId, displayName }: ProfilePlaintextFields): Buffer {
  const studentIdBytes = Buffer.from(studentId.toUpperCase(), 'ascii');
  const studentIdLength = requireLengthField(studentIdBytes, 'studentId');
  const nameBytes = Buffer.from(displayName, 'utf8');
  const nameLength = requireLengthField(nameBytes, 'displayName');

  return Buffer.concat([
    Buffer.from([PROFILE_SCHEMA_VERSION, studentIdLength]),
    studentIdBytes,
    Buffer.from([nameLength]),
    nameBytes,
  ]);
}
