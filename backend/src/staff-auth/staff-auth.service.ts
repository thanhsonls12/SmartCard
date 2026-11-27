import { BadRequestException, HttpException, Injectable, UnauthorizedException } from '@nestjs/common';
import { createHash, randomBytes } from 'node:crypto';
import { DatabaseService } from '../database/database.service.js';
import { verifyPassword } from './password.js';

export interface StaffActor {
  sessionId: string;
  staffId: string;
  staffCode: string;
  username: string;
  displayName: string;
  expiresAt: Date;
}

const SESSION_MS = 8 * 60 * 60 * 1000;
const LOGIN_WINDOW_MS = 60_000;
const MAX_ATTEMPTS = 10;

function tokenHash(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

function loginInput(body: unknown) {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new BadRequestException('invalid login input');
  }
  const { username, password } = body as Record<string, unknown>;
  if (typeof username !== 'string' || !/^[a-zA-Z0-9_.-]{1,64}$/.test(username.trim())
      || typeof password !== 'string' || password.length < 1 || Buffer.byteLength(password) > 256) {
    throw new BadRequestException('invalid login input');
  }
  return { username: username.trim().toLowerCase(), password };
}

@Injectable()
export class StaffAuthService {
  private attempts: number[] = [];
  private running = 0;

  constructor(private readonly database: DatabaseService) {}

  async login(body: unknown) {
    const { username, password } = loginInput(body);
    const now = Date.now();
    this.attempts = this.attempts.filter((attempt) => now - attempt < LOGIN_WINDOW_MS);
    if (this.attempts.length >= MAX_ATTEMPTS || this.running >= 2) {
      throw new HttpException('too many login attempts; try again later', 429);
    }
    this.attempts.push(now);
    this.running++;
    try {
      const staff = await this.database.staff.findUnique({ where: { username } });
      const valid = await verifyPassword(password, staff?.passwordHash);
      if (!staff?.isActive || !valid) {
        throw new UnauthorizedException('invalid staff credentials');
      }
      const token = randomBytes(32).toString('base64url');
      const session = await this.database.staffSession.create({
        data: { staffId: staff.id, tokenHash: tokenHash(token), expiresAt: new Date(Date.now() + SESSION_MS) },
      });
      return {
        token,
        staff: { staffCode: staff.staffCode, username: staff.username, displayName: staff.displayName },
        expiresAt: session.expiresAt,
      };
    } finally {
      this.running--;
    }
  }

  async authenticate(authorization: unknown): Promise<StaffActor> {
    const match = typeof authorization === 'string' ? /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization) : null;
    if (!match?.[1]) { throw new UnauthorizedException('staff session required'); }
    const session = await this.database.staffSession.findUnique({
      where: { tokenHash: tokenHash(match[1]) }, include: { staff: true },
    });
    if (!session || session.revokedAt !== null || session.expiresAt.getTime() <= Date.now() || !session.staff.isActive) {
      throw new UnauthorizedException('staff session is not active');
    }
    return { sessionId: session.id, staffId: session.staffId, staffCode: session.staff.staffCode,
      username: session.staff.username, displayName: session.staff.displayName, expiresAt: session.expiresAt };
  }

  async logout(actor: StaffActor): Promise<void> {
    await this.database.staffSession.updateMany({
      where: { id: actor.sessionId, revokedAt: null }, data: { revokedAt: new Date() },
    });
  }
}
