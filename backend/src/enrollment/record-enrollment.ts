import { ConflictException } from '@nestjs/common';
import type { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import { Prisma } from '../generated/prisma/client.js';
import { majorFromStudentId } from './enrollment-input.js';

export interface EnrollmentResult {
  cardId: string;
  studentId: string;
  enrolledAt: Date;
}

const UNIQUE_VIOLATION_CODE = 'P2002';

export async function recordEnrollment(
  database: DatabaseService,
  challenge: { id: string; studentId: string; classCode: string | null },
  cardId: Buffer,
  publicKey: Buffer,
): Promise<EnrollmentResult> {
  try {
    return await database.$transaction(async (transaction) => {
      const consumed = await transaction.enrollmentChallenge.updateMany({
        where: { id: challenge.id, usedAt: null, expiresAt: { gt: new Date() } },
        data: { usedAt: new Date() },
      });
      if (consumed.count !== 1) {
        throw new ConflictException('enrollment challenge is no longer usable');
      }

      const student = await transaction.student.create({
        data: {
          cardId: toStoredBytes(cardId),
          studentId: challenge.studentId,
          major: majorFromStudentId(challenge.studentId),
          classCode: challenge.classCode,
          publicKey: toStoredBytes(publicKey),
        },
        select: { enrolledAt: true },
      });
      await transaction.cardLedger.create({
        data: { cardId: toStoredBytes(cardId), lastCounter: 0n, lastBalance: 0n, activeVisitId: null },
      });

      return { cardId: cardId.toString('hex'), studentId: challenge.studentId, enrolledAt: student.enrolledAt };
    });
  } catch (error) {
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === UNIQUE_VIOLATION_CODE) {
      throw new ConflictException('cardId is already enrolled');
    }
    throw error;
  }
}
