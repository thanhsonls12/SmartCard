import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { AdminSessionController } from './admin-session.controller.js';
import { AdminSessionService } from './admin-session.service.js';
import { EnrollmentController } from './enrollment.controller.js';
import { EnrollmentService } from './enrollment.service.js';
import { StaffAuthModule } from '../staff-auth/staff-auth.module.js';

@Module({
  imports: [DatabaseModule, StaffAuthModule],
  controllers: [AdminSessionController, EnrollmentController],
  providers: [AdminSessionService, EnrollmentService],
  exports: [AdminSessionService],
})
export class EnrollmentModule {}
