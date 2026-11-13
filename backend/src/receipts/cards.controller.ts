import { Controller, Get, HttpCode, Param } from '@nestjs/common';
import { ReceiptsService, type TransactionHistory } from './receipts.service.js';

@Controller('cards')
export class CardsController {
  constructor(private readonly receipts: ReceiptsService) {}

  @Get(':cardId/transactions')
  @HttpCode(200)
  transactions(@Param('cardId') cardId: string): Promise<TransactionHistory> {
    return this.receipts.listTransactions(cardId);
  }
}
