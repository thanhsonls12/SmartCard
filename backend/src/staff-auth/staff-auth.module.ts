import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { StaffAuthController } from './staff-auth.controller.js';
import { StaffAuthGuard } from './staff-auth.guard.js';
import { StaffAuthService } from './staff-auth.service.js';
import { StaffAuditController } from './audit/staff-audit.controller.js';
import { StaffAuditService } from './audit/staff-audit.service.js';

@Module({
  imports: [DatabaseModule],
  controllers: [StaffAuthController, StaffAuditController],
  providers: [StaffAuthService, StaffAuthGuard, StaffAuditService],
  exports: [StaffAuthGuard, StaffAuthService],
})
export class StaffAuthModule {}
