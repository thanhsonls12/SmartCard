import { buildEnrollmentMessage, ENROLLMENT_MESSAGE_BYTES } from './enrollment-message.js';

const CARD_ID_HEX = 'a1b2c3d41122ee01';
const PUBLIC_KEY_HASH_HEX = 'aa'.repeat(32);
const NONCE_HEX = 'bb'.repeat(32);

function fields() {
  return {
    cardId: Buffer.from(CARD_ID_HEX, 'hex'),
    publicKeyHash: Buffer.from(PUBLIC_KEY_HASH_HEX, 'hex'),
    nonce: Buffer.from(NONCE_HEX, 'hex'),
  };
}

describe('buildEnrollmentMessage', () => {
  it('lays out domain, cardId, publicKeyHash and nonce in 95 bytes', () => {
    const message = buildEnrollmentMessage(fields());

    expect(message).toHaveLength(95);
    expect(ENROLLMENT_MESSAGE_BYTES).toBe(95);
    expect(message.toString('hex')).toBe(
      '4c41424143434553532d454e524f4c4c4d454e542d7631' +
        CARD_ID_HEX +
        PUBLIC_KEY_HASH_HEX +
        NONCE_HEX,
    );
  });

  it('places each field at its fixed offset', () => {
    const message = buildEnrollmentMessage(fields());

    expect(message.subarray(0, 23).toString('ascii')).toBe('LABACCESS-ENROLLMENT-v1');
    expect(message.subarray(23, 31)).toEqual(Buffer.from(CARD_ID_HEX, 'hex'));
    expect(message.subarray(31, 63)).toEqual(Buffer.from(PUBLIC_KEY_HASH_HEX, 'hex'));
    expect(message.subarray(63, 95)).toEqual(Buffer.from(NONCE_HEX, 'hex'));
  });

  it('rejects a cardId that is not 8 bytes', () => {
    expect(() => buildEnrollmentMessage({ ...fields(), cardId: Buffer.alloc(7) })).toThrow(RangeError);
    expect(() => buildEnrollmentMessage({ ...fields(), cardId: Buffer.alloc(9) })).toThrow(RangeError);
  });

  it('rejects a publicKeyHash that is not 32 bytes', () => {
    expect(() => buildEnrollmentMessage({ ...fields(), publicKeyHash: Buffer.alloc(31) })).toThrow(RangeError);
    expect(() => buildEnrollmentMessage({ ...fields(), publicKeyHash: Buffer.alloc(33) })).toThrow(RangeError);
  });

  it('rejects a nonce that is not 32 bytes', () => {
    expect(() => buildEnrollmentMessage({ ...fields(), nonce: Buffer.alloc(31) })).toThrow(RangeError);
    expect(() => buildEnrollmentMessage({ ...fields(), nonce: Buffer.alloc(33) })).toThrow(RangeError);
  });
});
