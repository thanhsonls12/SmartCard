import { Injectable, UnauthorizedException } from '@nestjs/common';
import { DatabaseService } from '../database/database.service.js';
import { isUuid } from './enrollment-input.js';

export interface IssuedAdminSession {
  sessionId: string;
  expiresAt: Date;
}

const SESSION_TTL_MS = 30 * 60 * 1000;

@Injectable()
export class AdminSessionService {
  constructor(private readonly database: DatabaseService) {}

  async issueSession(): Promise<IssuedAdminSession> {
    const session = await this.database.adminSession.create({
      data: { expiresAt: new Date(Date.now() + SESSION_TTL_MS) },
      select: { id: true, expiresAt: true },
    });

    return { sessionId: session.id, expiresAt: session.expiresAt };
  }

  async requireActiveSession(headerValue: unknown): Promise<string> {
    if (!isUuid(headerValue)) {
      throw new UnauthorizedException('admin session must be a uuid');
    }

    const session = await this.database.adminSession.findUnique({
      where: { id: headerValue },
      select: { id: true, expiresAt: true, revokedAt: true },
    });

    if (session === null || session.revokedAt !== null || session.expiresAt.getTime() <= Date.now()) {
      throw new UnauthorizedException('admin session is not active');
    }
    return session.id;
  }
}
