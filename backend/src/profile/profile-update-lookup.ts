import { ConflictException, NotFoundException } from '@nestjs/common';
import type { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import type { Prisma } from '../generated/prisma/client.js';
import { newProfileChallenge, type ProfileChallenge } from './profile-challenge.js';
import {
  completedView,
  pendingView,
  type FoundPendingUpdate,
  type FoundProfileUpdate,
  type StoredPendingUpdate,
} from './profile-update-response.js';

export async function profileUpdateCardId(database: DatabaseService, operationId: string): Promise<Buffer> {
  const operation = await database.profileUpdate.findUnique({
    where: { id: operationId },
    select: { cardId: true },
  });
  if (operation === null) {
    throw new NotFoundException('profile update not found');
  }
  return Buffer.from(operation.cardId);
}

export async function findLatestProfileUpdate(
  database: DatabaseService,
  key: Prisma.ProfileUpdateWhereInput,
  cardId: Buffer,
): Promise<FoundProfileUpdate> {
  const pending = await database.profileUpdate.findFirst({
    where: { ...key, state: 'PENDING' },
    orderBy: { createdAt: 'desc' },
    select: {
      id: true,
      studentId: true,
      displayName: true,
      classCode: true,
      expectedVersion: true,
      nonce: true,
      expiresAt: true,
    },
  });
  if (pending !== null) {
    return openPendingView(database, pending, cardId);
  }

  const completed = await database.profileUpdate.findFirst({
    where: { ...key, state: 'COMPLETED' },
    orderBy: { completedAt: 'desc' },
    select: {
      id: true,
      cardId: true,
      resultMajor: true,
      resultStudentId: true,
      resultClassCode: true,
      completedAt: true,
    },
  });
  if (completed !== null) {
    return completedView(completed);
  }

  throw new NotFoundException('cardId has no profile update to reopen');
}

async function openPendingView(
  database: DatabaseService,
  row: StoredPendingUpdate,
  cardId: Buffer,
): Promise<FoundPendingUpdate> {
  const challenge = row.expiresAt.getTime() <= Date.now()
    ? await renewChallenge(database, row)
    : { nonce: Buffer.from(row.nonce), expiresAt: row.expiresAt };

  return pendingView(row, cardId, challenge);
}

async function renewChallenge(database: DatabaseService, row: StoredPendingUpdate): Promise<ProfileChallenge> {
  const challenge = newProfileChallenge();
  const renewed = await database.profileUpdate.updateMany({
    where: { id: row.id, state: 'PENDING', nonce: toStoredBytes(Buffer.from(row.nonce)) },
    data: { nonce: toStoredBytes(challenge.nonce), expiresAt: challenge.expiresAt },
  });
  if (renewed.count === 1) {
    return challenge;
  }

  const current = await database.profileUpdate.findUnique({
    where: { id: row.id },
    select: { state: true, nonce: true, expiresAt: true },
  });
  if (current === null || current.state !== 'PENDING') {
    throw new ConflictException('profile update is no longer pending');
  }
  return { nonce: Buffer.from(current.nonce), expiresAt: current.expiresAt };
}
