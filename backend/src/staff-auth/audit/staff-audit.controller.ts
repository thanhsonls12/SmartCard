import { Body, Controller, Get, HttpCode, Param, Post, Query, UseGuards } from '@nestjs/common';
import { CurrentStaff, StaffAuthGuard } from '../staff-auth.guard.js';
import type { StaffActor } from '../staff-auth.service.js';
import { StaffAuditService } from './staff-audit.service.js';

@Controller('staff/audit')
@UseGuards(StaffAuthGuard)
export class StaffAuditController {
  constructor(private readonly audit: StaffAuditService) {}

  @Post()
  begin(@CurrentStaff() actor: StaffActor, @Body() body: unknown) {
    return this.audit.begin(actor, body);
  }

  @Post(':id/outcome')
  @HttpCode(204)
  async outcome(@CurrentStaff() actor: StaffActor, @Param('id') id: string, @Body() body: unknown) {
    await this.audit.outcome(actor, id, body);
  }

  @Get()
  list(@Query('cardId') cardId: unknown, @Query('staffCode') staffCode: unknown,
      @Query('from') from: unknown, @Query('to') to: unknown) {
    return this.audit.list(cardId, staffCode, from, to);
  }
}
