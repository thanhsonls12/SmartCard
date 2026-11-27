import {
  UnauthorizedException,
  Injectable,
  UnprocessableEntityException,
} from '@nestjs/common';
import { DatabaseService } from '../database/database.service.js';
import { toStoredBytes } from '../database/stored-bytes.js';
import { parseBody, parseHexBytes, parseSignatureHex } from '../enrollment/enrollment-input.js';
import { requireP256PublicKey, verifyEnrollmentSignature } from '../enrollment/enrollment-crypto.js';
import {
  parseReceipt,
  receiptMessage,
  RECEIPT_PAYLOAD_BYTES,
} from './receipt-canonical.js';
import {
  listTransactions,
  readLedger,
  readStoredPayload,
  type LedgerSnapshot,
  type StoredReceiptPayload,
  type TransactionHistory,
} from './receipt-queries.js';
import { recordReceipt, type ReceiptRecord } from './record-receipt.js';
import type { ReceiptAuditContext } from './receipt-audit.js';

export type { LedgerSnapshot, StoredReceiptPayload, TransactionHistory, TransactionHistoryEntry } from './receipt-queries.js';
export type { ReceiptRecord } from './record-receipt.js';

@Injectable()
export class ReceiptsService {
  constructor(private readonly database: DatabaseService) {}

  async verifyReceipt(body: unknown, audit?: ReceiptAuditContext, publicRoute = false): Promise<ReceiptRecord> {
    const request = parseBody(body);
    const payloadBytes = parseHexBytes(request['payload'], RECEIPT_PAYLOAD_BYTES, 'payload');
    const signature = parseSignatureHex(request['signature']);
    const receipt = parseReceipt(payloadBytes);
    if (publicRoute && receipt.operation === 'GRANT') {
      throw new UnauthorizedException('GRANT receipt requires authenticated Staff audit operation');
    }

    const student = await this.database.student.findUnique({
      where: { cardId: toStoredBytes(receipt.cardId) },
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

    if (!verifyEnrollmentSignature({ publicKey, message: receiptMessage(payloadBytes), signature })) {
      throw new UnprocessableEntityException('receipt signature does not verify');
    }

    return recordReceipt(this.database, receipt, payloadBytes, signature, audit);
  }

  readLedger(rawCardId: string): Promise<LedgerSnapshot> {
    return readLedger(this.database, rawCardId);
  }

  readStoredPayload(rawCardId: string, rawCounter: string): Promise<StoredReceiptPayload> {
    return readStoredPayload(this.database, rawCardId, rawCounter);
  }

  listTransactions(rawCardId: string): Promise<TransactionHistory> {
    return listTransactions(this.database, rawCardId);
  }
}
