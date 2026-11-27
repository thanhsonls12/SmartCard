import { createParamDecorator, ExecutionContext, Injectable, type CanActivate } from '@nestjs/common';
import type { Request } from 'express';
import { StaffAuthService, type StaffActor } from './staff-auth.service.js';

type StaffRequest = Request & { staffActor: StaffActor };

@Injectable()
export class StaffAuthGuard implements CanActivate {
  constructor(private readonly auth: StaffAuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<StaffRequest>();
    request.staffActor = await this.auth.authenticate(request.headers.authorization);
    return true;
  }
}

export const CurrentStaff = createParamDecorator((_data: unknown, context: ExecutionContext): StaffActor =>
  context.switchToHttp().getRequest<StaffRequest>().staffActor);
