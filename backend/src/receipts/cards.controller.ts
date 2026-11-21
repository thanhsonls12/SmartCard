import { Controller, Get, HttpCode, Param } from '@nestjs/common';
import { ReceiptsService, type StoredReceiptPayload, type TransactionHistory } from './receipts.service.js';

@Controller('cards')
export class CardsController {
  constructor(private readonly receipts: ReceiptsService) {}

  @Get(':cardId/transactions')
  @HttpCode(200)
  transactions(@Param('cardId') cardId: string): Promise<TransactionHistory> {
    return this.receipts.listTransactions(cardId);
  }

  @Get(':cardId/transactions/:counterAfter')
  @HttpCode(200)
  storedPayload(
    @Param('cardId') cardId: string,
    @Param('counterAfter') counterAfter: string,
  ): Promise<StoredReceiptPayload> {
    return this.receipts.readStoredPayload(cardId, counterAfter);
  }
}
