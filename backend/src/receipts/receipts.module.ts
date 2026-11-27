import { Module } from '@nestjs/common';
import { DatabaseModule } from '../database/database.module.js';
import { CardsController } from './cards.controller.js';
import { ReceiptsController } from './receipts.controller.js';
import { ReceiptsService } from './receipts.service.js';
import { StaffAuthModule } from '../staff-auth/staff-auth.module.js';

@Module({
  imports: [DatabaseModule, StaffAuthModule],
  controllers: [ReceiptsController, CardsController],
  providers: [ReceiptsService],
})
export class ReceiptsModule {}
