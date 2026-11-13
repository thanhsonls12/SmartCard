import { BadRequestException } from '@nestjs/common';
import { describe, expect, it } from 'vitest';
import {
  checkTransition,
  parseReceipt,
  receiptMessage,
  RECEIPT_DOMAIN,
  RECEIPT_PAYLOAD_BYTES,
  type Receipt,
} from './receipt-canonical.js';

const CARD_ID = Buffer.from('a1b2c3d41122ef07', 'hex');
const VISIT_ID = Buffer.from('0102030405060708090a0b0c0d0e0f10', 'hex');
const ZERO_VISIT = Buffer.alloc(16);

function buildPayload(overrides: Partial<{ version: number; operation: number; delta: number; balance: number; counter: number; visitId: Buffer }> = {}): Buffer {
  const bytes = Buffer.alloc(RECEIPT_PAYLOAD_BYTES);
  bytes.writeUInt8(overrides.version ?? 0x01, 0);
  CARD_ID.copy(bytes, 1);
  bytes.writeUInt32BE(overrides.counter ?? 1, 9);
  bytes.writeUInt8(overrides.operation ?? 0x01, 13);
  bytes.writeInt16BE(overrides.delta ?? 5, 14);
  bytes.writeUInt16BE(overrides.balance ?? 5, 16);
  (overrides.visitId ?? ZERO_VISIT).copy(bytes, 18);
  return bytes;
}

const GRANT: Receipt = {
  version: 1,
  cardId: CARD_ID,
  counterAfter: 1,
  operation: 'GRANT',
  delta: 5,
  balanceAfter: 5,
  visitId: null,
};

describe('parseReceipt', () => {
  it('reads a canonical GRANT payload and maps the zero visitId sentinel to null', () => {
    const receipt = parseReceipt(buildPayload());
    expect(receipt).toMatchObject({ counterAfter: 1, operation: 'GRANT', delta: 5, balanceAfter: 5 });
    expect(receipt.visitId).toBeNull();
    expect(receipt.cardId.equals(CARD_ID)).toBe(true);
  });

  it('reads a START payload with a non-zero visitId', () => {
    const receipt = parseReceipt(buildPayload({ operation: 0x02, delta: -1, balance: 4, visitId: VISIT_ID, counter: 2 }));
    expect(receipt.operation).toBe('START');
    expect(receipt.visitId?.equals(VISIT_ID)).toBe(true);
  });

  it('rejects a wrong payload length', () => {
    expect(() => parseReceipt(Buffer.alloc(RECEIPT_PAYLOAD_BYTES - 1))).toThrow(BadRequestException);
  });

  it('rejects an unknown version', () => {
    expect(() => parseReceipt(buildPayload({ version: 2 }))).toThrow(/version/);
  });

  it('rejects an unknown operation code', () => {
    expect(() => parseReceipt(buildPayload({ operation: 0x04 }))).toThrow(/operation/);
  });

  it('rejects GRANT with a visitId', () => {
    expect(() => parseReceipt(buildPayload({ visitId: VISIT_ID }))).toThrow(/sentinel/);
  });

  it('rejects START delta other than -1', () => {
    expect(() => parseReceipt(buildPayload({ operation: 0x02, delta: -2, visitId: VISIT_ID }))).toThrow(/START delta/);
  });

  it('rejects START without a visitId', () => {
    expect(() => parseReceipt(buildPayload({ operation: 0x02, delta: -1 }))).toThrow(/visitId/);
  });

  it('rejects END with a non-zero delta', () => {
    expect(() => parseReceipt(buildPayload({ operation: 0x03, delta: -1, visitId: VISIT_ID }))).toThrow(/END delta/);
  });

  it('rejects counterAfter 0 because 0 is only the enrollment baseline', () => {
    expect(() => parseReceipt(buildPayload({ counter: 0 }))).toThrow(/counterAfter/);
  });

  it('rejects balance above the quota ceiling', () => {
    expect(() => parseReceipt(buildPayload({ balance: 10000, delta: 5 }))).toThrow(/balanceAfter/);
  });
});

describe('receiptMessage', () => {
  it('prefixes the receipt domain without changing the payload bytes', () => {
    const payload = buildPayload();
    const message = receiptMessage(payload);
    expect(message.length).toBe(RECEIPT_DOMAIN.length + payload.length);
    expect(message.subarray(0, RECEIPT_DOMAIN.length).equals(RECEIPT_DOMAIN)).toBe(true);
    expect(message.subarray(RECEIPT_DOMAIN.length).equals(payload)).toBe(true);
  });
});

describe('checkTransition', () => {
  it('accepts the next counter with the matching balance', () => {
    const verdict = checkTransition({ lastCounter: 0, lastBalance: 0, activeVisitId: null }, GRANT);
    expect(verdict.ok).toBe(true);
    if (verdict.ok) {
      expect(verdict.next).toMatchObject({ lastCounter: 1, lastBalance: 5, activeVisitId: null });
    }
  });

  it('rejects a gap so the ledger needs manual reconciliation', () => {
    const verdict = checkTransition({ lastCounter: 0, lastBalance: 0, activeVisitId: null }, { ...GRANT, counterAfter: 2 });
    expect(verdict).toMatchObject({ ok: false });
    if (!verdict.ok) {
      expect(verdict.reason).toMatch(/gap/);
    }
  });

  it('rejects a GRANT whose balance does not add up', () => {
    const verdict = checkTransition({ lastCounter: 0, lastBalance: 3, activeVisitId: null }, GRANT);
    expect(verdict.ok).toBe(false);
  });

  it('opens a visit on START', () => {
    const start: Receipt = { ...GRANT, counterAfter: 2, operation: 'START', delta: -1, balanceAfter: 4, visitId: VISIT_ID };
    const verdict = checkTransition({ lastCounter: 1, lastBalance: 5, activeVisitId: null }, start);
    expect(verdict.ok).toBe(true);
    if (verdict.ok) {
      expect(verdict.next.activeVisitId?.equals(VISIT_ID)).toBe(true);
      expect(verdict.next.lastBalance).toBe(4);
    }
  });

  it('rejects START while another visit is open', () => {
    const start: Receipt = { ...GRANT, counterAfter: 2, operation: 'START', delta: -1, balanceAfter: 4, visitId: VISIT_ID };
    const verdict = checkTransition({ lastCounter: 1, lastBalance: 5, activeVisitId: VISIT_ID }, start);
    expect(verdict.ok).toBe(false);
  });

  it('closes the open visit on END', () => {
    const end: Receipt = { ...GRANT, counterAfter: 3, operation: 'END', delta: 0, balanceAfter: 4, visitId: VISIT_ID };
    const verdict = checkTransition({ lastCounter: 2, lastBalance: 4, activeVisitId: VISIT_ID }, end);
    expect(verdict.ok).toBe(true);
    if (verdict.ok) {
      expect(verdict.next.activeVisitId).toBeNull();
      expect(verdict.next.lastBalance).toBe(4);
    }
  });

  it('rejects END for a visit that is not open', () => {
    const other = Buffer.from('ff02030405060708090a0b0c0d0e0f10', 'hex');
    const end: Receipt = { ...GRANT, counterAfter: 3, operation: 'END', delta: 0, balanceAfter: 4, visitId: other };
    const verdict = checkTransition({ lastCounter: 2, lastBalance: 4, activeVisitId: VISIT_ID }, end);
    expect(verdict).toMatchObject({ ok: false });
  });
});
