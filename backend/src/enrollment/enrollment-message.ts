export const ENROLLMENT_DOMAIN = 'LABACCESS-ENROLLMENT-v1';

export const CARD_ID_BYTES = 8;
export const PUBLIC_KEY_BYTES = 65;
export const PUBLIC_KEY_HASH_BYTES = 32;
export const NONCE_BYTES = 32;
export const ENROLLMENT_MESSAGE_BYTES = 95;

const DOMAIN_BYTES = Buffer.from(ENROLLMENT_DOMAIN, 'ascii');

export interface EnrollmentMessageFields {
  cardId: Buffer;
  publicKeyHash: Buffer;
  nonce: Buffer;
}

function requireLength(value: Buffer, expectedBytes: number, field: string): void {
  if (value.length !== expectedBytes) {
    throw new RangeError(`${field} must be ${expectedBytes} bytes, received ${value.length}`);
  }
}

export function buildEnrollmentMessage({ cardId, publicKeyHash, nonce }: EnrollmentMessageFields): Buffer {
  requireLength(cardId, CARD_ID_BYTES, 'cardId');
  requireLength(publicKeyHash, PUBLIC_KEY_HASH_BYTES, 'publicKeyHash');
  requireLength(nonce, NONCE_BYTES, 'nonce');

  return Buffer.concat([DOMAIN_BYTES, cardId, publicKeyHash, nonce]);
}
