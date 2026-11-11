import { Controller, Get } from '@nestjs/common';
import { DatabaseService, DatabaseStatus } from './database.service.js';

@Controller('health')
export class DatabaseController {
  constructor(private readonly database: DatabaseService) {}

  @Get('database')
  checkDatabase(): Promise<DatabaseStatus> {
    return this.database.checkConnection();
  }
}
