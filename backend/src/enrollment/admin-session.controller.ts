import { Body, Controller, HttpCode, HttpStatus, Param, Post, UseGuards } from '@nestjs/common';
import { CurrentStaff, StaffAuthGuard } from '../staff-auth/staff-auth.guard.js';
import type { StaffActor } from '../staff-auth/staff-auth.service.js';
import {
  ActivatedAdminSession,
  AdminSessionService,
  IssuedAdminSession,
} from './admin-session.service.js';

@Controller('admin')
@UseGuards(StaffAuthGuard)
export class AdminSessionController {
  constructor(private readonly adminSessions: AdminSessionService) {}

  @Post('sessions')
  issueSession(@CurrentStaff() actor: StaffActor): Promise<IssuedAdminSession> {
    return this.adminSessions.issueSession(actor.sessionId);
  }

  @Post('sessions/:sessionId/activate')
  @HttpCode(HttpStatus.OK)
  activateSession(
    @Param('sessionId') sessionId: string,
    @Body() body: unknown,
    @CurrentStaff() actor: StaffActor,
  ): Promise<ActivatedAdminSession> {
    return this.adminSessions.activateSession(sessionId, body, actor.sessionId);
  }
}
