import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import { AdminSessionService } from '../enrollment/admin-session.service.js';
import {
  FoundProfileUpdate,
  ProfileUpdateRequest,
  ProfileUpdateResult,
  ProfileUpdateService,
} from './profile-update.service.js';

@Controller('profile-updates')
export class ProfileUpdateController {
  constructor(
    private readonly profileUpdates: ProfileUpdateService,
    private readonly adminSessions: AdminSessionService,
  ) {}

  @Post()
  async createRequest(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Body() body: unknown,
  ): Promise<ProfileUpdateRequest> {
    const cardId = this.profileUpdates.requestedCardId(body);
    const adminSessionId = await this.adminSessions.requireCardSession(sessionHeader, cardId);
    return this.profileUpdates.createRequest(adminSessionId, body);
  }

  @Get('pending/:cardId')
  async pending(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('cardId') cardId: string,
  ): Promise<FoundProfileUpdate> {
    await this.adminSessions.requireCardSession(sessionHeader, cardId);
    return this.profileUpdates.findOpenRequest(cardId);
  }

  @Get(':operationId')
  async request(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('operationId') operationId: string,
  ): Promise<FoundProfileUpdate> {
    await this.adminSessions.requireCardSession(sessionHeader, await this.profileUpdates.cardIdOf(operationId));
    return this.profileUpdates.findByOperationId(operationId);
  }

  @Post(':operationId/replace')
  @HttpCode(HttpStatus.OK)
  async replaceRequest(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('operationId') operationId: string,
    @Body() body: unknown,
  ): Promise<ProfileUpdateRequest> {
    const adminSessionId = await this.adminSessions.requireCardSession(
      sessionHeader,
      await this.profileUpdates.cardIdOf(operationId),
    );
    return this.profileUpdates.replaceRequest(adminSessionId, operationId, body);
  }

  @Post(':operationId/complete')
  @HttpCode(HttpStatus.OK)
  async completeRequest(
    @Headers('x-admin-session') sessionHeader: string | undefined,
    @Param('operationId') operationId: string,
    @Body() body: unknown,
  ): Promise<ProfileUpdateResult> {
    await this.adminSessions.requireCardSession(sessionHeader, await this.profileUpdates.cardIdOf(operationId));
    return this.profileUpdates.completeRequest(operationId, body);
  }
}
