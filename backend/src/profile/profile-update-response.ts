import { ConflictException } from '@nestjs/common';

export interface ProfileUpdateRequest {
  operationId: string;
  nonce: string;
  expectedVersion: string;
  expiresAt: Date;
}

export interface FoundPendingUpdate {
  state: 'PENDING';
  operationId: string;
  cardId: string;
  studentId: string;
  displayName: string;
  classCode: string | null;
  expectedVersion: string;
  nonce: string;
  expiresAt: Date;
}

export interface FoundCompletedUpdate {
  state: 'COMPLETED';
  operationId: string;
  cardId: string;
  studentId: string;
  major: string | null;
  classCode: string | null;
  completedAt: Date;
}

export type FoundProfileUpdate = FoundPendingUpdate | FoundCompletedUpdate;

export interface ProfileUpdateResult {
  operationId: string;
  cardId: string;
  studentId: string;
  major: string | null;
  classCode: string | null;
  completedAt: Date;
  alreadyCompleted: boolean;
}

export interface StoredPendingUpdate {
  id: string;
  studentId: string;
  displayName: string;
  classCode: string | null;
  expectedVersion: Uint8Array;
  nonce: Uint8Array;
  expiresAt: Date;
}

interface StoredCompletedUpdate {
  id: string;
  cardId: Uint8Array;
  resultMajor: string | null;
  resultStudentId: string | null;
  resultClassCode: string | null;
  completedAt: Date | null;
}

export function pendingView(
  row: StoredPendingUpdate,
  cardId: Buffer,
  challenge: { nonce: Buffer; expiresAt: Date },
): FoundPendingUpdate {
  return {
    state: 'PENDING',
    operationId: row.id,
    cardId: cardId.toString('hex'),
    studentId: row.studentId,
    displayName: row.displayName,
    classCode: row.classCode,
    expectedVersion: Buffer.from(row.expectedVersion).toString('hex'),
    nonce: challenge.nonce.toString('hex'),
    expiresAt: challenge.expiresAt,
  };
}

export function completedView(row: StoredCompletedUpdate): FoundCompletedUpdate {
  if (row.completedAt === null || row.resultStudentId === null) {
    throw new ConflictException('completed profile update is missing its stored result');
  }

  return {
    state: 'COMPLETED',
    operationId: row.id,
    cardId: Buffer.from(row.cardId).toString('hex'),
    studentId: row.resultStudentId,
    major: row.resultMajor,
    classCode: row.resultClassCode,
    completedAt: row.completedAt,
  };
}

export function completedResult(operation: StoredCompletedUpdate): ProfileUpdateResult {
  const view = completedView(operation);

  return {
    operationId: view.operationId,
    cardId: view.cardId,
    studentId: view.studentId,
    major: view.major,
    classCode: view.classCode,
    completedAt: view.completedAt,
    alreadyCompleted: true,
  };
}
