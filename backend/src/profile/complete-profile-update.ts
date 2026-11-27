import { ConflictException, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { createHash } from 'node:crypto';
import type { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import {
  requireP256PublicKey,
  sha256PublicKey,
  verifyEnrollmentSignature,
} from '../enrollment/enrollment-crypto.js';
import { majorFromStudentId, parseBody, parseSignatureHex, parseUuid } from '../enrollment/enrollment-input.js';
import { buildProfileMessage, buildProfilePlaintext } from './profile-message.js';
import { completedResult, type ProfileUpdateResult } from './profile-update-response.js';

export async function completeProfileUpdate(
  database: DatabaseService,
  rawOperationId: string,
  body: unknown,
): Promise<ProfileUpdateResult> {
  const operationId = parseUuid(rawOperationId, 'operationId');
  const payload = parseBody(body);
  const signature = parseSignatureHex(payload['signature']);

  const operation = await database.profileUpdate.findUnique({
    where: { id: operationId },
    select: {
      id: true,
      cardId: true,
      studentId: true,
      displayName: true,
      classCode: true,
      nonce: true,
      state: true,
      expiresAt: true,
      completedAt: true,
      resultMajor: true,
      resultStudentId: true,
      resultClassCode: true,
    },
  });
  if (operation === null) {
    throw new NotFoundException('profile update not found');
  }
  if (operation.state === 'COMPLETED') {
    return completedResult(operation);
  }
  if (operation.expiresAt.getTime() <= Date.now()) {
    throw new ConflictException('profile update challenge expired');
  }

  const cardId = Buffer.from(operation.cardId);
  const storedCardId = toStoredBytes(cardId);
  const student = await database.student.findUnique({
    where: { cardId: storedCardId },
    select: { publicKey: true },
  });
  if (student === null) {
    throw new UnprocessableEntityException('cardId is not enrolled');
  }

  const publicKey = Buffer.from(student.publicKey);
  try {
    requireP256PublicKey(publicKey);
  } catch {
    throw new UnprocessableEntityException('enrolled publicKey is not a P-256 point');
  }

  const message = buildProfileMessage({
    cardId,
    publicKeyHash: sha256PublicKey(publicKey),
    nonce: Buffer.from(operation.nonce),
    profileHash: profileHash(operation.studentId, operation.displayName),
  });
  if (!verifyEnrollmentSignature({ publicKey, message, signature })) {
    throw new UnprocessableEntityException('profile update signature does not verify');
  }

  const major = majorFromStudentId(operation.studentId);
  const completedAt = new Date();

  return database.$transaction(async (transaction) => {
    const claimed = await transaction.profileUpdate.updateMany({
      where: {
        id: operation.id,
        state: 'PENDING',
        nonce: toStoredBytes(Buffer.from(operation.nonce)),
        expiresAt: { gt: completedAt },
      },
      data: {
        state: 'COMPLETED',
        completedAt,
        resultMajor: major,
        resultStudentId: operation.studentId,
        resultClassCode: operation.classCode,
      },
    });
    if (claimed.count !== 1) {
      throw new ConflictException('profile update challenge is no longer the current one');
    }

    await transaction.student.update({
      where: { cardId: storedCardId },
      data: { studentId: operation.studentId, major, classCode: operation.classCode },
    });

    return {
      operationId: operation.id,
      cardId: cardId.toString('hex'),
      studentId: operation.studentId,
      major,
      classCode: operation.classCode,
      completedAt,
      alreadyCompleted: false,
    };
  });
}

function profileHash(studentId: string, displayName: string): Buffer {
  return createHash('sha256').update(buildProfilePlaintext({ studentId, displayName })).digest();
}
