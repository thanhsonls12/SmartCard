import { BadRequestException } from '@nestjs/common';
import {
  majorFromStudentId,
  parseOptionalClassCode,
  parseProfileClassCode,
  parseProfileDisplayName,
  parseStudentId,
} from './enrollment-input.js';

describe('student id input', () => {
  it.each(['AB123456', 'AB12345678', '12345678', 'ABCDEFGH'])('accepts %s', (value) => {
    expect(parseStudentId(value)).toBe(value);
  });

  it.each(['sv2024001', 'Sv2024001'])('stores %s in uppercase', (value) => {
    expect(parseStudentId(value)).toBe('SV2024001');
  });

  it.each(['', 'AB12345', 'AB123456789', 'SV2024-01', 'SV2024_01', ' SV2024001', 'SV2024001 ', 'SV 24001', 'ß1234567', 'ＳＶ2024001', 'SV2024001\n', null, 12345678])(
    'rejects invalid student id %s',
    (value) => {
      expect(() => parseStudentId(value)).toThrow(BadRequestException);
    },
  );
});

describe('optional class code input', () => {
  it('omits an absent class code for existing clients', () => {
    expect(parseOptionalClassCode(undefined)).toBeUndefined();
  });

  it('trims surrounding whitespace before storing', () => {
    expect(parseOptionalClassCode('  CT2026-01  ')).toBe('CT2026-01');
  });

  it('accepts a class code at the database column limit', () => {
    const classCode = '𐐀'.repeat(64);
    expect(parseOptionalClassCode(classCode)).toBe(classCode);
  });

  it.each([null, 0, true, {}, [], '   ', 'A\0B', 'A'.repeat(65)])(
    'rejects invalid class code %s',
    (value) => {
      expect(() => parseOptionalClassCode(value)).toThrow(BadRequestException);
    },
  );
});

describe('student major mapping', () => {
  it.each([
    ['CT2026001', 'Công nghệ thông tin'],
    ['AT2026001', 'An toàn thông tin'],
    ['DT2026001', 'Điện tử viễn thông'],
  ])('maps %s', (studentId, expected) => {
    expect(majorFromStudentId(studentId)).toBe(expected);
  });

  it('leaves legacy and unknown prefixes unclassified', () => {
    expect(majorFromStudentId('ST2026001')).toBeNull();
    expect(majorFromStudentId('XY2026001')).toBeNull();
  });
});

describe('profile class code input', () => {
  it.each(['CNTT1', 'CNTT12', 'CNTT123'])('accepts %s', (value) => {
    expect(parseProfileClassCode(value)).toBe(value);
  });

  it('trims and uppercases before storing', () => {
    expect(parseProfileClassCode('  cntt1  ')).toBe('CNTT1');
  });

  it.each(['CNTT', 'CNTT1234', 'CN-TT', 'CN TT', 'CNTT_1', '', '   ', 'AT1', null, 12345])(
    'rejects invalid class code %s',
    (value) => {
      expect(() => parseProfileClassCode(value)).toThrow(BadRequestException);
    },
  );
});

describe('profile display name input', () => {
  it('keeps an accented name and trims surrounding whitespace', () => {
    expect(parseProfileDisplayName('  Nguyễn Văn Bình  ')).toBe('Nguyễn Văn Bình');
  });

  it('accepts a name at the 96 byte ceiling', () => {
    const displayName = 'a'.repeat(96);
    expect(parseProfileDisplayName(displayName)).toBe(displayName);
  });

  it('measures the ceiling in UTF-8 bytes rather than characters', () => {
    const displayName = 'ễ'.repeat(32);
    expect(displayName.length).toBe(32);
    expect(parseProfileDisplayName(displayName)).toBe(displayName);
    expect(() => parseProfileDisplayName('ễ'.repeat(33))).toThrow(BadRequestException);
  });

  it.each(['', '   ', 'a'.repeat(97), 'Lê\nVăn A', 'Lê\tVăn A', 'Lê\0Văn A', null, 42])(
    'rejects invalid display name %j',
    (value) => {
      expect(() => parseProfileDisplayName(value)).toThrow(BadRequestException);
    },
  );
});
