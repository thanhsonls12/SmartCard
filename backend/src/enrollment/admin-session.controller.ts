import { Controller, Post } from '@nestjs/common';
import { AdminSessionService, IssuedAdminSession } from './admin-session.service.js';

@Controller('admin')
export class AdminSessionController {
  constructor(private readonly adminSessions: AdminSessionService) {}

  @Post('sessions')
  issueSession(): Promise<IssuedAdminSession> {
    return this.adminSessions.issueSession();
  }
}
