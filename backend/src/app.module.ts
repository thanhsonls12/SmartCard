import { ConfigModule } from '@nestjs/config';
import { Module } from '@nestjs/common';
import { AppController } from './app.controller.js';
import { AppService } from './app.service.js';
import { DatabaseModule } from './database/database.module.js';
import { EnrollmentModule } from './enrollment/enrollment.module.js';
import { ProfileUpdateModule } from './profile/profile-update.module.js';
import { ReceiptsModule } from './receipts/receipts.module.js';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, envFilePath: '.env' }),
    DatabaseModule,
    EnrollmentModule,
    ProfileUpdateModule,
    ReceiptsModule,
  ],
  controllers: [AppController],
  providers: [AppService],
})
export class AppModule {}
