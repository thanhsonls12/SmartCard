import { Body, Controller, Get, Headers, Param, Post, UseGuards } from '@nestjs/common';
import { CurrentStaff, StaffAuthGuard } from '../staff-auth/staff-auth.guard.js';
import type { StaffActor } from '../staff-auth/staff-auth.service.js';
import { AdminSessionService } from './admin-session.service.js';
import { type EnrolledCard, EnrollmentChallengeResponse, EnrollmentResult, EnrollmentService } from './enrollment.service.js';

@Controller('enrollments')
export class EnrollmentController {
  constructor(
    private readonly enrollment: EnrollmentService,
    private readonly adminSessions: AdminSessionService,
  ) {}

  @Get('cards/:cardId')
  enrolledCard(@Param('cardId') cardId: string): Promise<EnrolledCard> {
    return this.enrollment.readEnrolledCard(cardId);
  }

  @Post('challenges')
  @UseGuards(StaffAuthGuard)
  async createChallenge(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Body() body: unknown,
    @CurrentStaff() actor: StaffActor,
  ): Promise<EnrollmentChallengeResponse> {
    const adminSessionId = await this.adminSessions.requireActiveSession(sessionHeader, actor.sessionId);
    return this.enrollment.createChallenge(adminSessionId, body);
  }

  @Post('complete')
  @UseGuards(StaffAuthGuard)
  async completeEnrollment(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Body() body: unknown,
    @CurrentStaff() actor: StaffActor,
  ): Promise<EnrollmentResult> {
    const adminSessionId = await this.adminSessions.requireActiveSession(sessionHeader, actor.sessionId);
    return this.enrollment.completeEnrollment(adminSessionId, body);
  }
}
