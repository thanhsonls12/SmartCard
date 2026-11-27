import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { EnrollmentModule } from '../enrollment/enrollment.module.js';
import { ProfileUpdateController } from './profile-update.controller.js';
import { ProfileUpdateService } from './profile-update.service.js';
import { StaffAuthModule } from '../staff-auth/staff-auth.module.js';

@Module({
  imports: [DatabaseModule, EnrollmentModule, StaffAuthModule],
  controllers: [ProfileUpdateController],
  providers: [ProfileUpdateService],
})
export class ProfileUpdateModule {}
