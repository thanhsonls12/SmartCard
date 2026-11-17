import {
  ConflictException,
  Injectable,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { DatabaseService } from '../database/database.service.js';
import { parseBody, parseHexBytes, parseSignatureHex } from '../enrollment/enrollment-input.js';
import { requireP256PublicKey, verifyEnrollmentSignature } from '../enrollment/enrollment-crypto.js';
import {
  checkTransition,
  parseReceipt,
  receiptMessage,
  RECEIPT_PAYLOAD_BYTES,
  type Receipt,
  type ReceiptOperation,
} from './receipt-canonical.js';

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

function toStoredBytes(value: Buffer): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(value.byteLength);
  bytes.set(value);
  return bytes;
}

function storedToBuffer(value: Uint8Array): Buffer {
  return Buffer.from(value);
}

@Injectable()
export class ReceiptsService {
  constructor(private readonly database: DatabaseService) {}

  /**
   * BE-07: xác thực receipt do thẻ ký rồi ghi ledger. Sai canonical/domain là 400, chữ ký sai hoặc
   * thẻ chưa enroll là 422, xung đột ledger là 409 — không có nhánh nào tự sửa số liệu.
   */
  async verifyReceipt(body: unknown): Promise<ReceiptRecord> {
    const request = parseBody(body);
    const payloadBytes = parseHexBytes(request['payload'], RECEIPT_PAYLOAD_BYTES, 'payload');
    const signature = parseSignatureHex(request['signature']);
    const receipt = parseReceipt(payloadBytes);

    const student = await this.database.student.findUnique({
      where: { cardId: toStoredBytes(receipt.cardId) },
      select: { publicKey: true },
    });
    if (student === null) {
      throw new UnprocessableEntityException('cardId is not enrolled');
    }

    const publicKey = storedToBuffer(student.publicKey);
    try {
      requireP256PublicKey(publicKey);
    } catch {
      throw new UnprocessableEntityException('enrolled publicKey is not a P-256 point');
    }

    if (!verifyEnrollmentSignature({ publicKey, message: receiptMessage(payloadBytes), signature })) {
      throw new UnprocessableEntityException('receipt signature does not verify');
    }

    return this.record(receipt, payloadBytes, signature);
  }

  /**
   * Đọc mốc ledger của thẻ để đối soát nhánh chưa thực thi. Không có hàng ledger là 404: không dựng
   * baseline 0/0/null vì như vậy sẽ biến "chưa từng đăng ký" thành "đã khớp mốc".
   */
  async readLedger(rawCardId: string): Promise<LedgerSnapshot> {
    const cardId = parseHexBytes(rawCardId, 8, 'cardId');
    const ledger = await this.database.cardLedger.findUnique({
      where: { cardId: toStoredBytes(cardId) },
      select: { lastCounter: true, lastBalance: true, activeVisitId: true },
    });
    if (ledger === null) {
      throw new NotFoundException('cardId has no ledger row');
    }

    const lastCounter = Number(ledger.lastCounter);
    const lastBalance = Number(ledger.lastBalance);
    if (!Number.isSafeInteger(lastCounter) || lastCounter < 0 || lastCounter > 0xffffffff) {
      throw new ConflictException('ledger lastCounter is outside 0..4294967295');
    }
    if (!Number.isSafeInteger(lastBalance) || lastBalance < 0 || lastBalance > 9999) {
      throw new ConflictException('ledger lastBalance is outside 0..9999');
    }

    return {
      cardId: cardId.toString('hex'),
      lastCounter,
      lastBalance,
      activeVisitId:
        ledger.activeVisitId === null ? null : storedToBuffer(ledger.activeVisitId).toString('hex'),
    };
  }

  async listTransactions(rawCardId: string): Promise<TransactionHistory> {
    const cardId = parseHexBytes(rawCardId, 8, 'cardId');
    const storedCardId = toStoredBytes(cardId);
    const student = await this.database.student.findUnique({
      where: { cardId: storedCardId },
      select: { cardId: true },
    });
    if (student === null) {
      throw new NotFoundException('cardId is not enrolled');
    }

    const rows = await this.database.transaction.findMany({
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
      if (!Number.isSafeInteger(counterAfter) || counterAfter < 1 || counterAfter > 0xffffffff) {
        throw new ConflictException('transaction counterAfter is outside 1..4294967295');
      }
      if (!Number.isSafeInteger(delta) || delta < -1 || delta > 9999) {
        throw new ConflictException('transaction delta is outside -1..9999');
      }
      if (!Number.isSafeInteger(balanceAfter) || balanceAfter < 0 || balanceAfter > 9999) {
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

  /** BE-08: khoá hàng ledger của thẻ để tuần tự hoá, rồi mới quyết định duplicate/conflict/bản mới. */
  private async record(receipt: Receipt, payloadBytes: Buffer, signature: Buffer): Promise<ReceiptRecord> {
    const storedCardId = toStoredBytes(receipt.cardId);
    const counterAfter = BigInt(receipt.counterAfter);

    return this.database.$transaction(async (tx) => {
      const locked = await tx.$queryRaw<{ ok: number }[]>`
        SELECT 1 AS ok FROM card_ledgers WHERE "cardId" = decode(${receipt.cardId.toString('hex')}, 'hex') FOR UPDATE
      `;
      if (locked.length === 0) {
        throw new ConflictException('cardId has no ledger row');
      }

      const ledger = await tx.cardLedger.findUniqueOrThrow({
        where: { cardId: storedCardId },
        select: { lastCounter: true, lastBalance: true, activeVisitId: true },
      });

      const existing = await tx.transaction.findUnique({
        where: { cardId_counterAfter: { cardId: storedCardId, counterAfter } },
        select: {
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
        // Cùng ID và cùng canonical payload là duplicate hợp lệ sau verify, dù bytes chữ ký khác nhau.
        if (!storedToBuffer(existing.payload).equals(payloadBytes)) {
          throw new ConflictException('counterAfter already recorded with a different canonical payload');
        }
        return {
          status: 'duplicate',
          cardId: receipt.cardId.toString('hex'),
          counterAfter: Number(existing.counterAfter),
          operation: existing.operation,
          delta: Number(existing.delta),
          balanceAfter: Number(existing.balanceAfter),
          visitId: existing.visitId === null ? null : storedToBuffer(existing.visitId).toString('hex'),
          recordedAt: existing.recordedAt,
        };
      }

      const verdict = checkTransition(
        {
          lastCounter: Number(ledger.lastCounter),
          lastBalance: Number(ledger.lastBalance),
          activeVisitId: ledger.activeVisitId === null ? null : storedToBuffer(ledger.activeVisitId),
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
        },
        select: { recordedAt: true },
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
}
