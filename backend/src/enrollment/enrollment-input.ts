import { BadRequestException } from '@nestjs/common';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HEX_PATTERN = /^[0-9a-f]+$/i;

const STUDENT_ID_MIN_LENGTH = 8;
const STUDENT_ID_MAX_LENGTH = 10;
const STUDENT_ID_INVALID_CHARACTERS = /[^A-Za-z0-9]/;
export const SIGNATURE_MIN_BYTES = 8;
export const SIGNATURE_MAX_BYTES = 72;

export function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

export function parseBody(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new BadRequestException('request body must be a JSON object');
  }
  return value as Record<string, unknown>;
}

export function parseHexBytes(value: unknown, expectedBytes: number, field: string): Buffer {
  const expectedHexLength = expectedBytes * 2;
  if (typeof value !== 'string' || value.length !== expectedHexLength || !HEX_PATTERN.test(value)) {
    throw new BadRequestException(`${field} must be exactly ${expectedHexLength} hex characters`);
  }
  return Buffer.from(value, 'hex');
}

export function parseSignatureHex(value: unknown): Buffer {
  if (typeof value !== 'string' || value.length % 2 !== 0 || !HEX_PATTERN.test(value)) {
    throw new BadRequestException('signature must be an even-length hex string');
  }

  const signature = Buffer.from(value, 'hex');
  if (signature.length < SIGNATURE_MIN_BYTES || signature.length > SIGNATURE_MAX_BYTES) {
    throw new BadRequestException(`signature must be ${SIGNATURE_MIN_BYTES}..${SIGNATURE_MAX_BYTES} bytes`);
  }
  return signature;
}

export function parseStudentId(value: unknown): string {
  if (typeof value !== 'string') {
    throw new BadRequestException('studentId is required');
  }

  if (value.length < STUDENT_ID_MIN_LENGTH || value.length > STUDENT_ID_MAX_LENGTH
    || STUDENT_ID_INVALID_CHARACTERS.test(value)) {
    throw new BadRequestException('studentId must be 8..10 ASCII letters or digits');
  }
  return value.toUpperCase();
}

export function parseUuid(value: unknown, field: string): string {
  if (!isUuid(value)) {
    throw new BadRequestException(`${field} must be a uuid`);
  }
  return value;
}
