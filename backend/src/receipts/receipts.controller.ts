import { BadRequestException, Body, Controller, Get, HttpCode, Param, Post, UseGuards } from '@nestjs/common';
import { isUuid } from '../enrollment/enrollment-input.js';
import { CurrentStaff, StaffAuthGuard } from '../staff-auth/staff-auth.guard.js';
import type { StaffActor } from '../staff-auth/staff-auth.service.js';
import { ReceiptsService, type LedgerSnapshot, type ReceiptRecord } from './receipts.service.js';

@Controller('receipts')
export class ReceiptsController {
  constructor(private readonly receipts: ReceiptsService) {}

  @Post('verify')
  @HttpCode(201)
  verify(@Body() body: unknown): Promise<ReceiptRecord> {
    return this.receipts.verifyReceipt(body, undefined, true);
  }

  @Post('staff/:operationId/verify')
  @HttpCode(201)
  @UseGuards(StaffAuthGuard)
  verifyStaff(@CurrentStaff() actor: StaffActor, @Param('operationId') operationId: string,
      @Body() body: unknown): Promise<ReceiptRecord> {
    if (!isUuid(operationId)) { throw new BadRequestException('invalid operationId'); }
    return this.receipts.verifyReceipt(body, { actor, operationId });
  }

  @Get('ledger/:cardId')
  @HttpCode(200)
  ledger(@Param('cardId') cardId: string): Promise<LedgerSnapshot> {
    return this.receipts.readLedger(cardId);
  }
}
