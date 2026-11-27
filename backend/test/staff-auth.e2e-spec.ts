import { type INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { createHash, randomBytes } from 'node:crypto';
import request from 'supertest';
import type { App } from 'supertest/types.js';
import { AppModule } from '../src/app.module.js';
import { DatabaseService } from '../src/database/database.service.js';
import { hashPassword } from '../src/staff-auth/password.js';

describe('Staff authentication (e2e)', () => {
  let app: INestApplication<App>;
  let db: DatabaseService;
  const staffIds: string[] = [];
  const adminIds: string[] = [];

  beforeAll(() => {
    const url = process.env.DATABASE_URL ?? '';
    if (!URL.canParse(url) || new URL(url).pathname !== '/labaccess_test') {
      throw new Error('staff authentication e2e requires labaccess_test');
    }
  });

  beforeEach(async () => {
    const module = await Test.createTestingModule({ imports: [AppModule] }).compile();
    app = module.createNestApplication();
    await app.init();
    db = app.get(DatabaseService);
    staffIds.length = 0;
    adminIds.length = 0;
  });

  afterEach(async () => {
    await db.adminSession.deleteMany({ where: { id: { in: adminIds } } });
    await db.staffSession.deleteMany({ where: { staffId: { in: staffIds } } });
    await db.staff.deleteMany({ where: { id: { in: staffIds } } });
    await app.close();
  });

  async function account(isActive = true) {
    const suffix = randomBytes(8).toString('hex');
    const username = 'test-' + suffix;
    const password = randomBytes(24).toString('base64url');
    const staff = await db.staff.create({ data: {
      staffCode: 'T' + suffix, username, displayName: 'Test ' + suffix,
      passwordHash: await hashPassword(password), isActive,
    } });
    staffIds.push(staff.id);
    return { staff, username, password };
  }

  async function login(credentials: { username: string; password: string }) {
    const response = await request(app.getHttpServer()).post('/staff/auth/login')
      .send({ username: credentials.username, password: credentials.password }).expect(200);
    return response.body as { token: string; staff: { staffCode: string; username: string; displayName: string }; expiresAt: string };
  }

  function admin(token: string) {
    return request(app.getHttpServer()).post('/admin/sessions').set('Authorization', 'Bearer ' + token);
  }

  it('requires Staff authentication for all privileged entry points', async () => {
    await request(app.getHttpServer()).post('/admin/sessions').expect(401);
    await request(app.getHttpServer()).post('/admin/sessions/invalid/activate').expect(401);
    await request(app.getHttpServer()).post('/enrollments/challenges').expect(401);
    await request(app.getHttpServer()).post('/enrollments/complete').expect(401);
    await request(app.getHttpServer()).post('/profile-updates').expect(401);
    await request(app.getHttpServer()).get('/profile-updates/pending/invalid').expect(401);
  });

  it('hashes passwords and session tokens, returns identity without credentials and revokes at logout', async () => {
    const a = await account();
    const signed = await login(a);
    expect(signed.staff.staffCode).toBe(a.staff.staffCode);
    expect(signed.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(a.staff.passwordHash).not.toContain(a.password);
    const session = await db.staffSession.findFirstOrThrow({ where: { staffId: a.staff.id } });
    expect(session.tokenHash).toBe(createHash('sha256').update(signed.token).digest('hex'));
    expect(new Date(signed.expiresAt).getTime() - Date.now()).toBeGreaterThan(7 * 60 * 60 * 1000);
    const auth = 'Bearer ' + signed.token;
    const me = await request(app.getHttpServer()).get('/staff/auth/me').set('Authorization', auth).expect(200);
    expect(me.body).toEqual({ ...signed.staff, expiresAt: signed.expiresAt });
    const issued = await admin(signed.token).expect(201);
    adminIds.push(issued.body.sessionId as string);
    await request(app.getHttpServer()).post('/staff/auth/logout').set('Authorization', auth).expect(204);
    await request(app.getHttpServer()).get('/staff/auth/me').set('Authorization', auth).expect(401);
    await admin(signed.token).expect(401);
  });

  it('separates two staff and rejects reuse of another technical admin session', async () => {
    const a = await login(await account());
    const b = await login(await account());
    expect(a.staff.staffCode).not.toBe(b.staff.staffCode);
    const issued = await admin(a.token).expect(201);
    const id = issued.body.sessionId as string;
    adminIds.push(id);
    const auth = 'Bearer ' + b.token;
    await request(app.getHttpServer()).post('/admin/sessions/' + id + '/activate').set('Authorization', auth).expect(403);
    await request(app.getHttpServer()).post('/enrollments/challenges')
      .set('Authorization', auth).set('x-admin-session', id).expect(403);
    const row = await db.adminSession.findUniqueOrThrow({ where: { id }, include: { staffSession: { include: { staff: true } } } });
    expect(row.staffSession?.staff.staffCode).toBe(a.staff.staffCode);
  });

  it('rejects incorrect credentials, disabled accounts, invalid tokens and expired sessions', async () => {
    const a = await account();
    const disabled = await account(false);
    await request(app.getHttpServer()).post('/staff/auth/login')
      .send({ username: a.username, password: randomBytes(24).toString('hex') }).expect(401);
    await request(app.getHttpServer()).post('/staff/auth/login')
      .send({ username: disabled.username, password: disabled.password }).expect(401);
    await admin(randomBytes(32).toString('base64url')).expect(401);
    const signed = await login(a);
    await db.staffSession.updateMany({ where: { staffId: a.staff.id }, data: { expiresAt: new Date(Date.now() - 1000) } });
    await admin(signed.token).expect(401);
    const other = await login(a);
    await db.staff.update({ where: { id: a.staff.id }, data: { isActive: false } });
    await admin(other.token).expect(401);
  });

  it('limits login attempts within one minute', async () => {
    const username = 'absent-' + randomBytes(6).toString('hex');
    const password = randomBytes(12).toString('hex');
    for (let i = 0; i < 10; i++) {
      await request(app.getHttpServer()).post('/staff/auth/login').send({ username, password }).expect(401);
    }
    await request(app.getHttpServer()).post('/staff/auth/login').send({ username, password }).expect(429);
  });
});
