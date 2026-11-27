import { ConflictException, ForbiddenException, NotFoundException } from '@nestjs/common';
import type { Prisma } from '../generated/prisma/client.js';
import type { StaffActor } from '../staff-auth/staff-auth.service.js';
import type { Receipt } from './receipt-canonical.js';

export interface ReceiptAuditContext {
  actor: StaffActor;
  operationId: string;
}

export async function resolveReceiptAudit(tx: Prisma.TransactionClient, context: ReceiptAuditContext,
    receipt: Receipt, payload: Buffer) {
  const op = await tx.staffAuditOperation.findUnique({
    where: { id: context.operationId }, include: { origin: true },
  });
  if (!op) { throw new NotFoundException('audit operation not found'); }
  if (op.staffSessionId !== context.actor.sessionId) {
    throw new ForbiddenException('audit operation belongs to another session');
  }
  if (op.cardId !== receipt.cardId.toString('hex') || (op.action !== 'GRANT' && op.action !== 'RECONCILE')) {
    throw new ConflictException('audit operation does not match receipt');
  }
  const origin = op.action === 'GRANT' ? op : op.origin;
  if (origin && (receipt.operation !== 'GRANT' || origin.expectedPayload !== payload.toString('hex'))) {
    throw new ConflictException('receipt does not match grant intent');
  }
  const previous = await tx.staffAuditEvent.findUnique({
    where: { operationId_outcome: { operationId: op.id, outcome: 'RECEIPT_VERIFIED' } },
  });
  return { op, origin, previous };
}

export async function appendReceiptAudit(tx: Prisma.TransactionClient,
    resolved: Awaited<ReturnType<typeof resolveReceiptAudit>>, transaction: { id: string; grantAuditId: string | null }) {
  const { op, origin, previous } = resolved;
  if (previous && previous.transactionId !== transaction.id) {
    throw new ConflictException('audit operation already linked to another receipt');
  }
  if (op.action === 'GRANT' && transaction.grantAuditId !== op.id) {
    throw new ConflictException('cannot attribute a previously recorded receipt to a new origin');
  }
  const operations = new Set([op.id]);
  if (origin && transaction.grantAuditId === origin.id) { operations.add(origin.id); }
  for (const operationId of operations) {
    await tx.staffAuditEvent.upsert({
      where: { operationId_outcome: { operationId, outcome: 'RECEIPT_VERIFIED' } },
      create: { operationId, outcome: 'RECEIPT_VERIFIED', cardId: op.cardId, transactionId: transaction.id },
      update: {},
    });
  }
}
