import { BadRequestException, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { generateKeyPairSync } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { DatabaseService } from '../database/database.service.js';
import { EnrollmentService } from './enrollment.service.js';

function point(): Buffer {
  const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' }) as { x: string; y: string };
  return Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url')]);
}

function fixture(publicKey: Uint8Array | null) {
  const findUnique = vi.fn().mockResolvedValue(publicKey === null ? null : { publicKey });
  const database = { student: { findUnique } } as unknown as DatabaseService;
  return { service: new EnrollmentService(database), findUnique };
}

describe('EnrollmentService.readEnrolledCard', () => {
  it('returns only the stored enrolled public key and requested canonical cardId', async () => {
    const publicKey = point();
    const f = fixture(publicKey);
    expect(await f.service.readEnrolledCard('A1B2C3D411223344')).toEqual({
      cardId: 'a1b2c3d411223344', publicKey: publicKey.toString('hex'),
    });
    expect(f.findUnique).toHaveBeenCalledExactlyOnceWith({
      where: { cardId: new Uint8Array(Buffer.from('a1b2c3d411223344', 'hex')) },
      select: { publicKey: true },
    });
  });

  it.each(['', '00', 'Z'.repeat(16), '00'.repeat(9)])('rejects malformed identity before database access: %s', async (cardId) => {
    const f = fixture(point());
    await expect(f.service.readEnrolledCard(cardId)).rejects.toBeInstanceOf(BadRequestException);
    expect(f.findUnique).not.toHaveBeenCalled();
  });

  it('returns not found rather than taking a key from the caller or card', async () => {
    const f = fixture(null);
    await expect(f.service.readEnrolledCard('A1B2C3D411223344')).rejects.toBeInstanceOf(NotFoundException);
  });

  it.each([Buffer.alloc(0), Buffer.alloc(64), Buffer.alloc(65), Buffer.concat([Buffer.from([4]), Buffer.alloc(64)])])(
    'rejects corrupt stored keys instead of publishing a trusted response: %j', async (publicKey) => {
      const f = fixture(publicKey);
      await expect(f.service.readEnrolledCard('A1B2C3D411223344')).rejects.toBeInstanceOf(UnprocessableEntityException);
    },
  );

  it('propagates storage failure and never reports an unenrolled fallback key', async () => {
    const f = fixture(point());
    f.findUnique.mockRejectedValueOnce(new Error('fixture database unavailable'));
    await expect(f.service.readEnrolledCard('A1B2C3D411223344')).rejects.toThrow('fixture database unavailable');
  });
});
