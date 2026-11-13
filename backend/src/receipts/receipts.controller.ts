import { Body, Controller, Get, HttpCode, Param, Post } from '@nestjs/common';
import { ReceiptsService, type LedgerSnapshot, type ReceiptRecord } from './receipts.service.js';

@Controller('receipts')
export class ReceiptsController {
  constructor(private readonly receipts: ReceiptsService) {}

  /** Trả 201 cho cả bản ghi mới và duplicate: cùng một kết luận "receipt đã nằm trong ledger". */
  @Post('verify')
  @HttpCode(201)
  verify(@Body() body: unknown): Promise<ReceiptRecord> {
    return this.receipts.verifyReceipt(body);
  }

  /** Đọc mốc ledger để đối soát nhánh chưa thực thi; không tìm thấy thì 404, không dựng baseline. */
  @Get('ledger/:cardId')
  @HttpCode(200)
  ledger(@Param('cardId') cardId: string): Promise<LedgerSnapshot> {
    return this.receipts.readLedger(cardId);
  }
}
