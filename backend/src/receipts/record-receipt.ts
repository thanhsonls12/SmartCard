import { ConflictException } from '@nestjs/common';
import type { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import { checkTransition, type Receipt, type ReceiptOperation } from './receipt-canonical.js';
import { appendReceiptAudit, resolveReceiptAudit, type ReceiptAuditContext } from './receipt-audit.js';

export interface ReceiptRecord {
  status: 'recorded' | 'duplicate';
  cardId: string;
  counterAfter: number;
  operation: ReceiptOperation;
  delta: number;
  balanceAfter: number;
  visitId: string | null;
  recordedAt: Date;
}

export async function recordReceipt(
  database: DatabaseService,
  receipt: Receipt,
  payloadBytes: Buffer,
  signature: Buffer,
  audit?: ReceiptAuditContext,
): Promise<ReceiptRecord> {
  const storedCardId = toStoredBytes(receipt.cardId);
  const counterAfter = BigInt(receipt.counterAfter);

  return database.$transaction(async (tx) => {
    const locked = await tx.$queryRaw<{ ok: number }[]>`
      SELECT 1 AS ok FROM card_ledgers WHERE "cardId" = decode(${receipt.cardId.toString('hex')}, 'hex') FOR UPDATE
    `;
    if (locked.length === 0) {
      throw new ConflictException('cardId has no ledger row');
    }
    const resolvedAudit = audit ? await resolveReceiptAudit(tx, audit, receipt, payloadBytes) : null;

    const ledger = await tx.cardLedger.findUniqueOrThrow({
      where: { cardId: storedCardId },
      select: { lastCounter: true, lastBalance: true, activeVisitId: true },
    });

    const existing = await tx.transaction.findUnique({
      where: { cardId_counterAfter: { cardId: storedCardId, counterAfter } },
      select: {
        id: true,
        grantAuditId: true,
        counterAfter: true,
        operation: true,
        delta: true,
        balanceAfter: true,
        visitId: true,
        payload: true,
        recordedAt: true,
      },
    });

    if (existing !== null) {

      if (!Buffer.from(existing.payload).equals(payloadBytes)) {
        throw new ConflictException('counterAfter already recorded with a different canonical payload');
      }
      if (resolvedAudit) { await appendReceiptAudit(tx, resolvedAudit, existing); }
      return {
        status: 'duplicate',
        cardId: receipt.cardId.toString('hex'),
        counterAfter: Number(existing.counterAfter),
        operation: existing.operation,
        delta: Number(existing.delta),
        balanceAfter: Number(existing.balanceAfter),
        visitId: existing.visitId === null ? null : Buffer.from(existing.visitId).toString('hex'),
        recordedAt: existing.recordedAt,
      };
    }

    const verdict = checkTransition(
      {
        lastCounter: Number(ledger.lastCounter),
        lastBalance: Number(ledger.lastBalance),
        activeVisitId: ledger.activeVisitId === null ? null : Buffer.from(ledger.activeVisitId),
      },
      receipt,
    );
    if (!verdict.ok) {
      throw new ConflictException(verdict.reason);
    }

    const recordedAt = new Date();
    const created = await tx.transaction.create({
      data: {
        cardId: storedCardId,
        counterAfter,
        operation: receipt.operation,
        delta: BigInt(receipt.delta),
        balanceAfter: BigInt(receipt.balanceAfter),
        visitId: receipt.visitId === null ? null : toStoredBytes(receipt.visitId),
        payload: toStoredBytes(payloadBytes),
        signature: toStoredBytes(signature),
        recordedAt,
        ...(resolvedAudit?.origin ? { grantAuditId: resolvedAudit.origin.id } : {}),
      },
      select: { recordedAt: true, id: true, grantAuditId: true },
    });

    await tx.cardLedger.update({
      where: { cardId: storedCardId },
      data: {
        lastCounter: BigInt(verdict.next.lastCounter),
        lastBalance: BigInt(verdict.next.lastBalance),
        activeVisitId: verdict.next.activeVisitId === null ? null : toStoredBytes(verdict.next.activeVisitId),
        updatedAt: recordedAt,
      },
    });
    if (resolvedAudit) { await appendReceiptAudit(tx, resolvedAudit, created); }

    return {
      status: 'recorded',
      cardId: receipt.cardId.toString('hex'),
      counterAfter: receipt.counterAfter,
      operation: receipt.operation,
      delta: receipt.delta,
      balanceAfter: receipt.balanceAfter,
      visitId: receipt.visitId === null ? null : receipt.visitId.toString('hex'),
      recordedAt: created.recordedAt,
    };
  });
}
