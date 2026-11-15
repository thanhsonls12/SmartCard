import { BadRequestException } from '@nestjs/common';
import { parseStudentId } from './enrollment-input.js';

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
