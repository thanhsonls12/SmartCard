import { majorFromStudentId } from '../enrollment/enrollment-input.js';
import { CARD_ID_BYTES, NONCE_BYTES, PUBLIC_KEY_HASH_BYTES } from '../enrollment/enrollment-message.js';

export const PROFILE_DOMAIN = 'LABACCESS-PROFILE-v1';
export const PROFILE_HASH_BYTES = 32;
export const PROFILE_MESSAGE_BYTES = 124;
export const EXPECTED_VERSION_BYTES = 12;
export const PROFILE_SCHEMA_VERSION = 0x02;
export const LEGACY_PROFILE_SCHEMA_VERSION = 0x01;

const DOMAIN_BYTES = Buffer.from(PROFILE_DOMAIN, 'ascii');
const LENGTH_FIELD_MAX = 0xff;
const INVALID_ASCII_IDENTIFIER_CHARACTER = /[^A-Z0-9]/;

export interface ProfileMessageFields {
  cardId: Buffer;
  publicKeyHash: Buffer;
  nonce: Buffer;
  profileHash: Buffer;
}

export interface LegacyProfilePlaintextFields {
  studentId: string;
  displayName: string;
}

export interface ProfilePlaintextFields extends LegacyProfilePlaintextFields {
  classCode: string;
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

export function buildLegacyProfilePlaintext({ studentId, displayName }: LegacyProfilePlaintextFields): Buffer {
  const normalizedStudentId = studentId.toUpperCase();
  if (INVALID_ASCII_IDENTIFIER_CHARACTER.test(normalizedStudentId)) {
    throw new RangeError('studentId must contain ASCII letters and digits');
  }
  const studentIdBytes = Buffer.from(normalizedStudentId, 'ascii');
  const studentIdLength = requireLengthField(studentIdBytes, 'studentId');
  const nameBytes = Buffer.from(displayName, 'utf8');
  const nameLength = requireLengthField(nameBytes, 'displayName');

  return Buffer.concat([
    Buffer.from([LEGACY_PROFILE_SCHEMA_VERSION, studentIdLength]),
    studentIdBytes,
    Buffer.from([nameLength]),
    nameBytes,
  ]);
}

export function buildProfilePlaintext({ studentId, displayName, classCode }: ProfilePlaintextFields): Buffer {
  if (classCode.length < 5 || classCode.length > 7 || INVALID_ASCII_IDENTIFIER_CHARACTER.test(classCode)) {
    throw new RangeError('classCode must be 5..7 uppercase ASCII letters and digits');
  }
  const identity = buildLegacyProfilePlaintext({ studentId, displayName });
  identity[0] = PROFILE_SCHEMA_VERSION;
  const classBytes = Buffer.from(classCode, 'ascii');
  const majorBytes = Buffer.from(majorFromStudentId(studentId) ?? '', 'utf8');

  return Buffer.concat([
    identity,
    Buffer.from([classBytes.length]),
    classBytes,
    Buffer.from([majorBytes.length]),
    majorBytes,
  ]);
}
