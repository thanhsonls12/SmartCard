import { BadRequestException } from '@nestjs/common';

export const RECEIPT_PAYLOAD_BYTES = 34;
export const RECEIPT_VERSION = 0x01;
export const CARD_ID_BYTES = 8;
export const VISIT_ID_BYTES = 16;
export const COUNTER_MAX = 0xffffffff;
export const BALANCE_MAX = 9999;
export const GRANT_DELTA_MAX = 9999;

/** Domain ký của receipt, tách khỏi domain enrollment (Q-22). */
export const RECEIPT_DOMAIN = Buffer.from('LABACCESS-RECEIPT-v1', 'ascii');

const OFFSET_VERSION = 0;
const OFFSET_CARD_ID = 1;
const OFFSET_COUNTER = 9;
const OFFSET_OPERATION = 13;
const OFFSET_DELTA = 14;
const OFFSET_BALANCE = 16;
const OFFSET_VISIT_ID = 18;

export type ReceiptOperation = 'GRANT' | 'START' | 'END';

const OPERATION_BY_CODE: Record<number, ReceiptOperation> = { 0x01: 'GRANT', 0x02: 'START', 0x03: 'END' };

export interface Receipt {
  version: number;
  cardId: Buffer;
  counterAfter: number;
  operation: ReceiptOperation;
  delta: number;
  balanceAfter: number;
  /** GRANT dùng sentinel toàn zero trên thẻ; lưu NULL trong DB. */
  visitId: Buffer | null;
}

export interface LedgerState {
  lastCounter: number;
  lastBalance: number;
  activeVisitId: Buffer | null;
}

export interface NextLedgerState extends LedgerState {}

export type TransitionCheck =
  | { ok: true; next: NextLedgerState }
  | { ok: false; reason: string };

function isZeroBytes(value: Buffer): boolean {
  return value.every((byte) => byte === 0);
}

function fail(message: string): never {
  throw new BadRequestException(message);
}

/**
 * Đọc payload canonical 34 byte do thẻ ký. Mọi sai lệch về độ dài, version, mã lệnh hoặc miền số
 * là lỗi schema (400), không phải lỗi conflict: chưa có gì để đối chiếu với ledger.
 */
export function parseReceipt(bytes: Buffer): Receipt {
  if (bytes.length !== RECEIPT_PAYLOAD_BYTES) {
    fail(`payload must be exactly ${RECEIPT_PAYLOAD_BYTES} bytes`);
  }

  const version = bytes.readUInt8(OFFSET_VERSION);
  if (version !== RECEIPT_VERSION) {
    fail(`payload version must be ${RECEIPT_VERSION}`);
  }

  const cardId = Buffer.from(bytes.subarray(OFFSET_CARD_ID, OFFSET_CARD_ID + CARD_ID_BYTES));
  const counterAfter = bytes.readUInt32BE(OFFSET_COUNTER);
  if (counterAfter < 1 || counterAfter > COUNTER_MAX) {
    fail('counterAfter must be 1..4294967295');
  }

  const operationCode = bytes.readUInt8(OFFSET_OPERATION);
  const operation = OPERATION_BY_CODE[operationCode];
  if (operation === undefined) {
    fail(`operation must be one of 0x01 GRANT, 0x02 START, 0x03 END`);
  }

  const delta = bytes.readInt16BE(OFFSET_DELTA);
  const balanceAfter = bytes.readUInt16BE(OFFSET_BALANCE);
  if (balanceAfter > BALANCE_MAX) {
    fail(`balanceAfter must be 0..${BALANCE_MAX}`);
  }

  const visitRaw = Buffer.from(bytes.subarray(OFFSET_VISIT_ID, OFFSET_VISIT_ID + VISIT_ID_BYTES));
  const visitId = isZeroBytes(visitRaw) ? null : visitRaw;

  if (operation === 'GRANT') {
    if (delta < 1 || delta > GRANT_DELTA_MAX) {
      fail(`GRANT delta must be 1..${GRANT_DELTA_MAX}`);
    }
    if (visitId !== null) {
      fail('GRANT must carry the empty visitId sentinel');
    }
  } else if (operation === 'START') {
    if (delta !== -1) {
      fail('START delta must be -1');
    }
    if (visitId === null) {
      fail('START must carry a non-zero visitId');
    }
  } else {
    if (delta !== 0) {
      fail('END delta must be 0');
    }
    if (visitId === null) {
      fail('END must carry a non-zero visitId');
    }
  }

  return { version, cardId, counterAfter, operation, delta, balanceAfter, visitId };
}

/** Thông điệp đã ký: domain receipt nối trước payload canonical, không đổi thứ tự. */
export function receiptMessage(bytes: Buffer): Buffer {
  return Buffer.concat([RECEIPT_DOMAIN, bytes]);
}

/**
 * Kiểm chuyển trạng thái thuần, không chạm DB: kết quả phải khớp ledger hiện tại thì mới được ghi.
 * Counter phải liên tục; nhảy cóc là gap, phải đối soát thủ công chứ không tự sửa.
 */
export function checkTransition(ledger: LedgerState, receipt: Receipt): TransitionCheck {
  if (receipt.counterAfter !== ledger.lastCounter + 1) {
    return { ok: false, reason: `gap: counterAfter ${receipt.counterAfter} is not ${ledger.lastCounter + 1}` };
  }

  if (receipt.operation === 'GRANT') {
    if (receipt.balanceAfter !== ledger.lastBalance + receipt.delta) {
      return {
        ok: false,
        reason: `GRANT balance ${receipt.balanceAfter} is not ${ledger.lastBalance} + ${receipt.delta}`,
      };
    }
    return {
      ok: true,
      next: { lastCounter: receipt.counterAfter, lastBalance: receipt.balanceAfter, activeVisitId: ledger.activeVisitId },
    };
  }

  if (receipt.operation === 'START') {
    if (ledger.activeVisitId !== null) {
      return { ok: false, reason: 'START while a visit is already open' };
    }
    if (receipt.balanceAfter !== ledger.lastBalance - 1) {
      return {
        ok: false,
        reason: `START balance ${receipt.balanceAfter} is not ${ledger.lastBalance} - 1`,
      };
    }
    return {
      ok: true,
      next: { lastCounter: receipt.counterAfter, lastBalance: receipt.balanceAfter, activeVisitId: receipt.visitId },
    };
  }

  const open = ledger.activeVisitId;
  if (open === null) {
    return { ok: false, reason: 'END without an open visit' };
  }
  if (receipt.visitId === null || !open.equals(receipt.visitId)) {
    return { ok: false, reason: 'END closes a visit that is not open' };
  }
  if (receipt.balanceAfter !== ledger.lastBalance) {
    return { ok: false, reason: `END balance ${receipt.balanceAfter} is not ${ledger.lastBalance}` };
  }
  return {
    ok: true,
    next: { lastCounter: receipt.counterAfter, lastBalance: receipt.balanceAfter, activeVisitId: null },
  };
}
