import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { StaffAuthController } from './staff-auth.controller.js';
import { StaffAuthGuard } from './staff-auth.guard.js';
import { StaffAuthService } from './staff-auth.service.js';

@Module({
  imports: [DatabaseModule],
  controllers: [StaffAuthController],
  providers: [StaffAuthService, StaffAuthGuard],
  exports: [StaffAuthGuard, StaffAuthService],
})
export class StaffAuthModule {}
