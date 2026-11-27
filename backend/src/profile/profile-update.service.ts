import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { Prisma } from '../generated/prisma/client.js';
import { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import {
  parseBody,
  parseHexBytes,
  parseProfileClassCode,
  parseProfileDisplayName,
  parseStudentId,
  parseUuid,
} from '../enrollment/enrollment-input.js';
import { CARD_ID_BYTES } from '../enrollment/enrollment-message.js';
import { completeProfileUpdate } from './complete-profile-update.js';
import { newProfileChallenge, type ProfileChallenge } from './profile-challenge.js';
import { findLatestProfileUpdate, profileUpdateCardId } from './profile-update-lookup.js';
import { EXPECTED_VERSION_BYTES } from './profile-message.js';

import type {
  FoundProfileUpdate,
  ProfileUpdateRequest,
  ProfileUpdateResult,
} from './profile-update-response.js';

export type {
  FoundCompletedUpdate,
  FoundPendingUpdate,
  FoundProfileUpdate,
  ProfileUpdateRequest,
  ProfileUpdateResult,
} from './profile-update-response.js';

interface ProfileContent {
  studentId: string;
  displayName: string;
  classCode: string;
  expectedVersion: Buffer;
}

const UNIQUE_VIOLATION_CODE = 'P2002';
const PENDING_REQUEST_CONFLICT = 'cardId already has a pending profile update';

@Injectable()
export class ProfileUpdateService {
  constructor(private readonly database: DatabaseService) {}

  requestedCardId(body: unknown): string {
    const payload = parseBody(body);
    return parseHexBytes(payload['cardId'], CARD_ID_BYTES, 'cardId').toString('hex');
  }

  async createRequest(adminSessionId: string, body: unknown): Promise<ProfileUpdateRequest> {
    const payload = parseBody(body);
    const cardId = parseHexBytes(payload['cardId'], CARD_ID_BYTES, 'cardId');
    const content = this.profileContent(payload);
    const storedCardId = toStoredBytes(cardId);

    const student = await this.database.student.findUnique({
      where: { cardId: storedCardId },
      select: { id: true },
    });
    if (student === null) {
      throw new NotFoundException('cardId is not enrolled');
    }

    const challenge = newProfileChallenge();
    return this.writePendingRequest(async (transaction) => {
      const pending = await transaction.profileUpdate.findFirst({
        where: { cardId: storedCardId, state: 'PENDING' },
        select: { id: true },
      });
      if (pending !== null) {
        throw new ConflictException(PENDING_REQUEST_CONFLICT);
      }

      return this.createPendingRequest(transaction, storedCardId, content, adminSessionId, challenge);
    });
  }

  async replaceRequest(
    adminSessionId: string,
    rawOperationId: string,
    body: unknown,
  ): Promise<ProfileUpdateRequest> {
    const operationId = parseUuid(rawOperationId, 'operationId');
    const content = this.profileContent(parseBody(body));
    const challenge = newProfileChallenge();

    return this.writePendingRequest(async (transaction) => {
      const stale = await transaction.profileUpdate.findUnique({
        where: { id: operationId },
        select: { cardId: true, state: true, expectedVersion: true, nonce: true },
      });
      if (stale === null) {
        throw new NotFoundException('profile update not found');
      }
      if (stale.state !== 'PENDING') {
        throw new ConflictException('profile update is no longer pending');
      }
      if (Buffer.from(stale.expectedVersion).equals(content.expectedVersion)) {
        throw new ConflictException('profile update is not stale; complete it instead of replacing it');
      }

      const superseded = await transaction.profileUpdate.updateMany({
        where: { id: operationId, state: 'PENDING', nonce: toStoredBytes(Buffer.from(stale.nonce)) },
        data: { state: 'SUPERSEDED' },
      });
      if (superseded.count !== 1) {
        throw new ConflictException('profile update changed while it was being replaced');
      }

      return this.createPendingRequest(
        transaction, toStoredBytes(Buffer.from(stale.cardId)), content, adminSessionId, challenge,
      );
    });
  }

  private async writePendingRequest(
    write: (transaction: Prisma.TransactionClient) => Promise<ProfileUpdateRequest>,
  ): Promise<ProfileUpdateRequest> {
    try {
      return await this.database.$transaction(write);
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === UNIQUE_VIOLATION_CODE) {
        throw new ConflictException(PENDING_REQUEST_CONFLICT);
      }
      throw error;
    }
  }

  private async createPendingRequest(
    transaction: Prisma.TransactionClient,
    storedCardId: Uint8Array<ArrayBuffer>,
    content: ProfileContent,
    adminSessionId: string,
    { nonce, expiresAt }: ProfileChallenge,
  ): Promise<ProfileUpdateRequest> {
    const operation = await transaction.profileUpdate.create({
      data: {
        cardId: storedCardId,
        studentId: content.studentId,
        displayName: content.displayName,
        classCode: content.classCode,
        expectedVersion: toStoredBytes(content.expectedVersion),
        nonce: toStoredBytes(nonce),
        adminSessionId,
        expiresAt,
      },
      select: { id: true },
    });
    return {
      operationId: operation.id,
      nonce: nonce.toString('hex'),
      expectedVersion: content.expectedVersion.toString('hex'),
      expiresAt,
    };
  }

  private profileContent(payload: Record<string, unknown>): ProfileContent {
    return {
      studentId: parseStudentId(payload['studentId']),
      displayName: parseProfileDisplayName(payload['displayName']),
      classCode: parseProfileClassCode(payload['classCode']),
      expectedVersion: parseHexBytes(payload['expectedVersion'], EXPECTED_VERSION_BYTES, 'expectedVersion'),
    };
  }

  async cardIdOf(rawOperationId: string): Promise<string> {
    const operationId = parseUuid(rawOperationId, 'operationId');
    const cardId = await profileUpdateCardId(this.database, operationId);
    return cardId.toString('hex');
  }

  async findOpenRequest(rawCardId: string): Promise<FoundProfileUpdate> {
    const cardId = parseHexBytes(rawCardId, CARD_ID_BYTES, 'cardId');
    return findLatestProfileUpdate(this.database, { cardId: toStoredBytes(cardId) }, cardId);
  }

  async findByOperationId(rawOperationId: string): Promise<FoundProfileUpdate> {
    const operationId = parseUuid(rawOperationId, 'operationId');
    const cardId = await profileUpdateCardId(this.database, operationId);
    return findLatestProfileUpdate(this.database, { id: operationId }, cardId);
  }

  completeRequest(rawOperationId: string, body: unknown): Promise<ProfileUpdateResult> {
    return completeProfileUpdate(this.database, rawOperationId, body);
  }
}
