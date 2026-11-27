import { BadRequestException, ConflictException, NotFoundException } from '@nestjs/common';
import type { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import { parseHexBytes } from '../enrollment/enrollment-input.js';
import {
  BALANCE_MAX,
  CARD_ID_BYTES,
  COUNTER_MAX,
  GRANT_DELTA_MAX,
  parseReceipt,
  type Receipt,
  type ReceiptOperation,
} from './receipt-canonical.js';

export interface LedgerSnapshot {
  cardId: string;
  lastCounter: number;
  lastBalance: number;
  activeVisitId: string | null;
}

export interface TransactionHistoryEntry {
  id: string;
  counterAfter: number;
  operation: ReceiptOperation;
  delta: number;
  balanceAfter: number;
  verifyStatus: 'verified';
  recordedAt: Date;
}

export interface TransactionHistory {
  cardId: string;
  transactions: TransactionHistoryEntry[];
}

export interface StoredReceiptPayload {
  cardId: string;
  counterAfter: number;
  payload: string;
}

export async function readLedger(database: DatabaseService, rawCardId: string): Promise<LedgerSnapshot> {
  const cardId = parseHexBytes(rawCardId, CARD_ID_BYTES, 'cardId');
  const ledger = await database.cardLedger.findUnique({
    where: { cardId: toStoredBytes(cardId) },
    select: { lastCounter: true, lastBalance: true, activeVisitId: true },
  });
  if (ledger === null) {
    throw new NotFoundException('cardId has no ledger row');
  }

  const lastCounter = Number(ledger.lastCounter);
  const lastBalance = Number(ledger.lastBalance);
  if (!Number.isSafeInteger(lastCounter) || lastCounter < 0 || lastCounter > COUNTER_MAX) {
    throw new ConflictException('ledger lastCounter is outside 0..4294967295');
  }
  if (!Number.isSafeInteger(lastBalance) || lastBalance < 0 || lastBalance > BALANCE_MAX) {
    throw new ConflictException('ledger lastBalance is outside 0..9999');
  }

  return {
    cardId: cardId.toString('hex'),
    lastCounter,
    lastBalance,
    activeVisitId:
      ledger.activeVisitId === null ? null : Buffer.from(ledger.activeVisitId).toString('hex'),
  };
}

export async function readStoredPayload(database: DatabaseService, rawCardId: string, rawCounter: string): Promise<StoredReceiptPayload> {
  const cardId = parseHexBytes(rawCardId, CARD_ID_BYTES, 'cardId');
  if (!/^[1-9][0-9]{0,9}$/.test(rawCounter) || Number(rawCounter) > COUNTER_MAX) {
    throw new BadRequestException('counterAfter must be 1..4294967295');
  }
  const counterAfter = Number(rawCounter);
  const row = await database.transaction.findUnique({
    where: { cardId_counterAfter: { cardId: toStoredBytes(cardId), counterAfter: BigInt(counterAfter) } },
    select: { payload: true },
  });
  if (row === null) {
    throw new NotFoundException('no receipt stored for cardId and counterAfter');
  }
  const payload = Buffer.from(row.payload);
  const receipt = parseStoredPayload(payload);
  if (!receipt.cardId.equals(cardId) || receipt.counterAfter !== counterAfter) {
    throw new ConflictException('stored receipt payload differs from its identity');
  }
  return { cardId: cardId.toString('hex'), counterAfter, payload: payload.toString('hex') };
}

function parseStoredPayload(payload: Buffer): Receipt {
  try {
    return parseReceipt(payload);
  } catch (failure) {
    if (failure instanceof BadRequestException) {
      throw new ConflictException('stored receipt payload is invalid');
    }
    throw failure;
  }
}

export async function listTransactions(database: DatabaseService, rawCardId: string): Promise<TransactionHistory> {
  const cardId = parseHexBytes(rawCardId, CARD_ID_BYTES, 'cardId');
  const storedCardId = toStoredBytes(cardId);
  const student = await database.student.findUnique({
    where: { cardId: storedCardId },
    select: { cardId: true },
  });
  if (student === null) {
    throw new NotFoundException('cardId is not enrolled');
  }

  const rows = await database.transaction.findMany({
    where: { cardId: storedCardId },
    orderBy: { counterAfter: 'asc' },
    select: {
      id: true,
      counterAfter: true,
      operation: true,
      delta: true,
      balanceAfter: true,
      recordedAt: true,
    },
  });

  const transactions: TransactionHistoryEntry[] = rows.map((row) => {
    const counterAfter = Number(row.counterAfter);
    const delta = Number(row.delta);
    const balanceAfter = Number(row.balanceAfter);
    if (!Number.isSafeInteger(counterAfter) || counterAfter < 1 || counterAfter > COUNTER_MAX) {
      throw new ConflictException('transaction counterAfter is outside 1..4294967295');
    }
    if (!Number.isSafeInteger(delta) || delta < -1 || delta > GRANT_DELTA_MAX) {
      throw new ConflictException('transaction delta is outside -1..9999');
    }
    if (!Number.isSafeInteger(balanceAfter) || balanceAfter < 0 || balanceAfter > BALANCE_MAX) {
      throw new ConflictException('transaction balanceAfter is outside 0..9999');
    }
    return {
      id: row.id,
      counterAfter,
      operation: row.operation,
      delta,
      balanceAfter,
      verifyStatus: 'verified',
      recordedAt: row.recordedAt,
    };
  });

  return { cardId: cardId.toString('hex'), transactions };
}
