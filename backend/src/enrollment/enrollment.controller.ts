import { Body, Controller, Get, Headers, Param, Post } from '@nestjs/common';
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
  async createChallenge(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Body() body: unknown,
  ): Promise<EnrollmentChallengeResponse> {
    const adminSessionId = await this.adminSessions.requireActiveSession(sessionHeader);
    return this.enrollment.createChallenge(adminSessionId, body);
  }

  @Post('complete')
  async completeEnrollment(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Body() body: unknown,
  ): Promise<EnrollmentResult> {
    const adminSessionId = await this.adminSessions.requireActiveSession(sessionHeader);
    return this.enrollment.completeEnrollment(adminSessionId, body);
  }
}
