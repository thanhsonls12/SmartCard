import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Param, Post, UseGuards } from '@nestjs/common';
import { CurrentStaff, StaffAuthGuard } from '../staff-auth/staff-auth.guard.js';
import type { StaffActor } from '../staff-auth/staff-auth.service.js';
import { AdminSessionService } from '../enrollment/admin-session.service.js';
import {
  FoundProfileUpdate,
  ProfileUpdateRequest,
  ProfileUpdateResult,
  ProfileUpdateService,
} from './profile-update.service.js';

@Controller('profile-updates')
@UseGuards(StaffAuthGuard)
export class ProfileUpdateController {
  constructor(
    private readonly profileUpdates: ProfileUpdateService,
    private readonly adminSessions: AdminSessionService,
  ) {}

  @Post()
  async createRequest(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Body() body: unknown,
    @CurrentStaff() actor: StaffActor,
  ): Promise<ProfileUpdateRequest> {
    const cardId = this.profileUpdates.requestedCardId(body);
    const adminSessionId = await this.adminSessions.requireCardSession(sessionHeader, cardId, actor.sessionId);
    return this.profileUpdates.createRequest(adminSessionId, body);
  }

  @Get('pending/:cardId')
  async pending(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('cardId') cardId: string,
    @CurrentStaff() actor: StaffActor,
  ): Promise<FoundProfileUpdate> {
    await this.adminSessions.requireCardSession(sessionHeader, cardId, actor.sessionId);
    return this.profileUpdates.findOpenRequest(cardId);
  }

  @Get(':operationId')
  async request(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('operationId') operationId: string,
    @CurrentStaff() actor: StaffActor,
  ): Promise<FoundProfileUpdate> {
    await this.adminSessions.requireCardSession(sessionHeader, await this.profileUpdates.cardIdOf(operationId), actor.sessionId);
    return this.profileUpdates.findByOperationId(operationId);
  }

  @Post(':operationId/replace')
  @HttpCode(HttpStatus.OK)
  async replaceRequest(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('operationId') operationId: string,
    @Body() body: unknown,
    @CurrentStaff() actor: StaffActor,
  ): Promise<ProfileUpdateRequest> {
    const adminSessionId = await this.adminSessions.requireCardSession(
      sessionHeader,
      await this.profileUpdates.cardIdOf(operationId),
      actor.sessionId,
    );
    return this.profileUpdates.replaceRequest(adminSessionId, operationId, body);
  }

  @Post(':operationId/complete')
  @HttpCode(HttpStatus.OK)
  async completeRequest(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('operationId') operationId: string,
    @Body() body: unknown,
    @CurrentStaff() actor: StaffActor,
  ): Promise<ProfileUpdateResult> {
    await this.adminSessions.requireCardSession(sessionHeader, await this.profileUpdates.cardIdOf(operationId), actor.sessionId);
    return this.profileUpdates.completeRequest(operationId, body);
  }
}
