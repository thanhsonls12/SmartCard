import { Body, Controller, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import {
  ActivatedAdminSession,
  AdminSessionService,
  IssuedAdminSession,
} from './admin-session.service.js';

@Controller('admin')
export class AdminSessionController {
  constructor(private readonly adminSessions: AdminSessionService) {}

  @Post('sessions')
  issueSession(): Promise<IssuedAdminSession> {
    return this.adminSessions.issueSession();
  }

  @Post('sessions/:sessionId/activate')
  @HttpCode(HttpStatus.OK)
  activateSession(
    @Param('sessionId') sessionId: string,
    @Body() body: unknown,
  ): Promise<ActivatedAdminSession> {
    return this.adminSessions.activateSession(sessionId, body);
  }
}
