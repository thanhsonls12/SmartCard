import { Body, Controller, Get, Header, HttpCode, Post, UseGuards } from '@nestjs/common';
import { CurrentStaff, StaffAuthGuard } from './staff-auth.guard.js';
import { StaffAuthService, type StaffActor } from './staff-auth.service.js';

@Controller('staff/auth')
export class StaffAuthController {
  constructor(private readonly auth: StaffAuthService) {}

  @Post('login')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  login(@Body() body: unknown) {
    return this.auth.login(body);
  }

  @Get('me')
  @UseGuards(StaffAuthGuard)
  @Header('Cache-Control', 'no-store')
  me(@CurrentStaff() actor: StaffActor) {
    return { staffCode: actor.staffCode, username: actor.username, displayName: actor.displayName, expiresAt: actor.expiresAt };
  }

  @Post('logout')
  @HttpCode(204)
  @UseGuards(StaffAuthGuard)
  async logout(@CurrentStaff() actor: StaffActor): Promise<void> {
    await this.auth.logout(actor);
  }
}
