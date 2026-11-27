import { createHash } from 'node:crypto';
import { buildProfileMessage, buildProfilePlaintext, PROFILE_MESSAGE_BYTES } from './profile-message.js';

const CARD_ID_HEX = 'a1b2c3d41122ef07';
const PUBLIC_KEY_HASH_HEX = 'aa'.repeat(32);
const NONCE_HEX = 'bb'.repeat(32);
const PROFILE_HASH_HEX = 'cc'.repeat(32);
const DOMAIN_HEX = '4c41424143434553532d50524f46494c452d7631';

function messageFields() {
  return {
    cardId: Buffer.from(CARD_ID_HEX, 'hex'),
    publicKeyHash: Buffer.from(PUBLIC_KEY_HASH_HEX, 'hex'),
    nonce: Buffer.from(NONCE_HEX, 'hex'),
    profileHash: Buffer.from(PROFILE_HASH_HEX, 'hex'),
  };
}

describe('buildProfileMessage', () => {
  it('lays out domain, cardId, publicKeyHash, nonce and profileHash in 124 bytes', () => {
    const message = buildProfileMessage(messageFields());

    expect(message).toHaveLength(124);
    expect(PROFILE_MESSAGE_BYTES).toBe(124);
    expect(message.toString('hex')).toBe(
      DOMAIN_HEX + CARD_ID_HEX + PUBLIC_KEY_HASH_HEX + NONCE_HEX + PROFILE_HASH_HEX,
    );
  });

  it('places each field at its fixed offset', () => {
    const message = buildProfileMessage(messageFields());

    expect(message.subarray(0, 20).toString('ascii')).toBe('LABACCESS-PROFILE-v1');
    expect(message.subarray(20, 28)).toEqual(Buffer.from(CARD_ID_HEX, 'hex'));
    expect(message.subarray(28, 60)).toEqual(Buffer.from(PUBLIC_KEY_HASH_HEX, 'hex'));
    expect(message.subarray(60, 92)).toEqual(Buffer.from(NONCE_HEX, 'hex'));
    expect(message.subarray(92, 124)).toEqual(Buffer.from(PROFILE_HASH_HEX, 'hex'));
  });

  it('ends with the sha256 of the profile plaintext', () => {
    const plaintext = buildProfilePlaintext({ studentId: 'SV2024001', displayName: 'Nguyễn Văn Bình' });
    const message = buildProfileMessage({
      ...messageFields(),
      profileHash: createHash('sha256').update(plaintext).digest(),
    });

    expect(message.subarray(92, 124)).toEqual(createHash('sha256').update(plaintext).digest());
  });

  it('rejects a cardId that is not 8 bytes', () => {
    expect(() => buildProfileMessage({ ...messageFields(), cardId: Buffer.alloc(7) })).toThrow(RangeError);
    expect(() => buildProfileMessage({ ...messageFields(), cardId: Buffer.alloc(9) })).toThrow(RangeError);
  });

  it('rejects a publicKeyHash that is not 32 bytes', () => {
    expect(() => buildProfileMessage({ ...messageFields(), publicKeyHash: Buffer.alloc(31) })).toThrow(RangeError);
    expect(() => buildProfileMessage({ ...messageFields(), publicKeyHash: Buffer.alloc(33) })).toThrow(RangeError);
  });

  it('rejects a nonce that is not 32 bytes', () => {
    expect(() => buildProfileMessage({ ...messageFields(), nonce: Buffer.alloc(31) })).toThrow(RangeError);
    expect(() => buildProfileMessage({ ...messageFields(), nonce: Buffer.alloc(33) })).toThrow(RangeError);
  });

  it('rejects a profileHash that is not 32 bytes', () => {
    expect(() => buildProfileMessage({ ...messageFields(), profileHash: Buffer.alloc(31) })).toThrow(RangeError);
    expect(() => buildProfileMessage({ ...messageFields(), profileHash: Buffer.alloc(33) })).toThrow(RangeError);
  });
});

describe('buildProfilePlaintext', () => {
  it('writes schema version, length-prefixed studentId, length-prefixed name', () => {
    const plaintext = buildProfilePlaintext({ studentId: 'SV2024001', displayName: 'Le Van A' });

    expect(plaintext.toString('hex')).toBe(
      '01' + '09' + Buffer.from('SV2024001', 'ascii').toString('hex') + '08' + Buffer.from('Le Van A', 'ascii').toString('hex'),
    );
  });

  it('measures the name length in UTF-8 bytes rather than characters', () => {
    const displayName = 'Nguyễn Văn Bình';
    const plaintext = buildProfilePlaintext({ studentId: 'SV2024001', displayName });
    const nameLengthOffset = 2 + 9;

    expect(Buffer.byteLength(displayName, 'utf8')).toBe(19);
    expect(plaintext.readUInt8(nameLengthOffset)).toBe(19);
    expect(plaintext.subarray(nameLengthOffset + 1)).toEqual(Buffer.from(displayName, 'utf8'));
  });

  it('writes the studentId in uppercase ascii', () => {
    const plaintext = buildProfilePlaintext({ studentId: 'sv2024001', displayName: 'Le Van A' });

    expect(plaintext.subarray(2, 11).toString('ascii')).toBe('SV2024001');
  });

  it('rejects an empty studentId', () => {
    expect(() => buildProfilePlaintext({ studentId: '', displayName: 'Le Van A' })).toThrow(RangeError);
  });

  it('rejects an empty name', () => {
    expect(() => buildProfilePlaintext({ studentId: 'SV2024001', displayName: '' })).toThrow(RangeError);
  });

  it('rejects a name longer than one length byte', () => {
    expect(() => buildProfilePlaintext({ studentId: 'SV2024001', displayName: 'a'.repeat(256) })).toThrow(RangeError);
  });
});
