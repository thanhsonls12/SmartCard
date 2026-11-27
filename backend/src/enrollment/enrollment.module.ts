import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { AdminSessionController } from './admin-session.controller.js';
import { AdminSessionService } from './admin-session.service.js';
import { EnrollmentController } from './enrollment.controller.js';
import { EnrollmentService } from './enrollment.service.js';

@Module({
  imports: [DatabaseModule],
  controllers: [AdminSessionController, EnrollmentController],
  providers: [AdminSessionService, EnrollmentService],
  exports: [AdminSessionService],
})
export class EnrollmentModule {}
