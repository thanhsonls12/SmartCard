import { BadRequestException, ConflictException, ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { DatabaseService } from '../../database/database.service.js';
import { isUuid, parseBody, parseHexBytes } from '../../enrollment/enrollment-input.js';
import { toStoredBytes } from '../../database/stored-bytes.js';
import { StaffAuditAction } from '../../generated/prisma/enums.js';
import { parseReceipt, RECEIPT_PAYLOAD_BYTES } from '../../receipts/receipt-canonical.js';
import type { StaffActor } from '../staff-auth.service.js';

function optionalCardId(value: unknown): string | null {
  return value === null || value === undefined ? null : parseHexBytes(value, 8, 'cardId').toString('hex');
}

@Injectable()
export class StaffAuditService {
  constructor(private readonly database: DatabaseService) {}

  async begin(actor: StaffActor, body: unknown) {
    const input = parseBody(body);
    const action = input['action'];
    if (typeof action !== 'string' || !Object.values(StaffAuditAction).some(value => value === action)) {
      throw new BadRequestException('invalid audit action');
    }
    const typedAction = action as StaffAuditAction;
    const cardId = optionalCardId(input['cardId']);
    const originId = input['originId'] ?? null;
    let expectedPayload: string | null = null;
    if (typedAction === 'GRANT') {
      const bytes = parseHexBytes(input['expectedPayload'], RECEIPT_PAYLOAD_BYTES, 'expectedPayload');
      const receipt = parseReceipt(bytes);
      if (receipt.operation !== 'GRANT' || cardId !== receipt.cardId.toString('hex')) {
        throw new BadRequestException('grant intent does not match card');
      }
      const ledger = await this.database.cardLedger.findUnique({ where: { cardId: toStoredBytes(receipt.cardId) } });
      if (!ledger || Number(ledger.lastCounter) + 1 !== receipt.counterAfter
          || Number(ledger.lastBalance) + receipt.delta !== receipt.balanceAfter) {
        throw new ConflictException('grant intent does not match ledger');
      }
      expectedPayload = bytes.toString('hex');
    } else if (input['expectedPayload'] !== undefined && input['expectedPayload'] !== null) {
      throw new BadRequestException('unexpected intent payload');
    }
    if (originId !== null) {
      if (typedAction !== 'RECONCILE' || !isUuid(originId)) {
        throw new BadRequestException('invalid audit origin');
      }
      const origin = await this.database.staffAuditOperation.findUnique({ where: { id: originId } });
      if (!origin || origin.action !== 'GRANT' || origin.cardId !== cardId) {
        throw new ConflictException('audit origin does not match card');
      }
    }
    const operation = await this.database.staffAuditOperation.create({
      data: { staffSessionId: actor.sessionId, action: typedAction, cardId, expectedPayload,
        originId: originId as string | null },
      select: { id: true, createdAt: true },
    });
    return { operationId: operation.id, createdAt: operation.createdAt };
  }

  async outcome(actor: StaffActor, id: string, body: unknown) {
    if (!isUuid(id)) { throw new BadRequestException('invalid operationId'); }
    const input = parseBody(body);
    const outcome = input['outcome'];
    if (outcome !== 'CLIENT_CONFIRMED' && outcome !== 'UNKNOWN'
        && outcome !== 'CLIENT_NOT_EXECUTED' && outcome !== 'CLIENT_SUPERSEDED') {
      throw new BadRequestException('invalid client outcome');
    }
    const operation = await this.database.staffAuditOperation.findUnique({ where: { id } });
    if (!operation) { throw new NotFoundException('audit operation not found'); }
    if (operation.staffSessionId !== actor.sessionId) { throw new ForbiddenException('audit operation belongs to another session'); }
    if (operation.action === 'GRANT' && outcome !== 'UNKNOWN') {
      throw new BadRequestException('grant requires verified receipt');
    }
    if ((outcome === 'CLIENT_NOT_EXECUTED' || outcome === 'CLIENT_SUPERSEDED') && operation.action !== 'RECONCILE') {
      throw new BadRequestException('reconciliation outcome requires reconciliation operation');
    }
    const cardId = optionalCardId(input['cardId']) ?? operation.cardId;
    if (operation.cardId !== null && cardId !== operation.cardId) {
      throw new ConflictException('audit outcome card differs from request');
    }
    await this.database.staffAuditEvent.upsert({
      where: { operationId_outcome: { operationId: id, outcome } },
      create: { operationId: id, outcome, cardId },
      update: {},
    });
  }

  async list(rawCardId: unknown, rawStaffCode: unknown, rawFrom: unknown, rawTo: unknown) {
    const cardId = optionalCardId(rawCardId);
    if (rawStaffCode !== undefined && (typeof rawStaffCode !== 'string' || !/^[A-Z0-9_-]{1,20}$/i.test(rawStaffCode))) {
      throw new BadRequestException('invalid staffCode');
    }
    const from = this.dateFilter(rawFrom);
    const to = this.dateFilter(rawTo);
    if (from && to && from >= to) { throw new BadRequestException('invalid date range'); }
    const operations = await this.database.staffAuditOperation.findMany({
      where: {
        ...(cardId === null ? {} : { OR: [{ cardId }, { events: { some: { cardId } } }] }),
        ...(typeof rawStaffCode === 'string' ? { staffSession: { staff: { staffCode: rawStaffCode.toUpperCase() } } } : {}),
        ...(from || to ? { createdAt: { ...(from ? { gte: from } : {}), ...(to ? { lt: to } : {}) } } : {}),
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: 100,
      select: { id: true, action: true, cardId: true, createdAt: true, originId: true,
        staffSession: { select: { staff: { select: { staffCode: true, displayName: true } } } },
        origin: { select: { staffSession: { select: { staff: { select: { staffCode: true, displayName: true } } } } } },
        events: { orderBy: { createdAt: 'asc' }, select: { outcome: true, cardId: true, createdAt: true,
          transaction: { select: { cardId: true, counterAfter: true, operation: true } } } },
      },
    });
    return operations.map(row => ({
      operationId: row.id, action: row.action, cardId: row.cardId ?? row.events.find(event => event.cardId)?.cardId ?? null,
      createdAt: row.createdAt, originId: row.originId, staff: row.staffSession.staff,
      originStaff: row.origin?.staffSession.staff ?? null,
      outcome: row.events.some(event => event.outcome === 'RECEIPT_VERIFIED') ? 'RECEIPT_VERIFIED'
        : row.events.at(-1)?.outcome ?? 'UNKNOWN',
      events: row.events.map(event => ({ outcome: event.outcome, createdAt: event.createdAt,
        receipt: event.transaction ? { cardId: Buffer.from(event.transaction.cardId).toString('hex'),
          counterAfter: Number(event.transaction.counterAfter), operation: event.transaction.operation } : null })),
    }));
  }

  private dateFilter(value: unknown): Date | null {
    if (value === undefined) { return null; }
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$/.test(value)) {
      throw new BadRequestException('invalid UTC date filter');
    }
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) { throw new BadRequestException('invalid date filter'); }
    return date;
  }
}
