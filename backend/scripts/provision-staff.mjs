import 'dotenv/config';
import { PrismaPg } from '@prisma/adapter-pg';
import { PrismaClient } from '../dist/generated/prisma/client.js';
import { hashPassword } from '../dist/staff-auth/password.js';

try {
  const chunks = [];
  for await (const chunk of process.stdin) { chunks.push(chunk); }
  const input = JSON.parse(Buffer.concat(chunks).toString('utf8').replace(/^\uFEFF/, ''));
  const staffCode = typeof input.staffCode === 'string' ? input.staffCode.trim().toUpperCase() : '';
  const username = typeof input.username === 'string' ? input.username.trim().toLowerCase() : '';
  const displayName = typeof input.displayName === 'string' ? input.displayName.trim() : '';
  const password = input.password;
  if (!/^[A-Z0-9_-]{1,20}$/.test(staffCode) || !/^[a-z0-9_.-]{1,64}$/.test(username)
      || !displayName || displayName.length > 100 || typeof password !== 'string'
      || password.length < 12 || Buffer.byteLength(password) > 256) {
    throw new Error('Invalid input. Password must have at least 12 characters.');
  }
  const url = process.env.DATABASE_URL;
  if (!url || !['localhost', '127.0.0.1', '[::1]'].includes(new URL(url).hostname)) {
    throw new Error('Provisioning requires a local database.');
  }
  const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
  try {
    const staff = await db.staff.create({ data: { staffCode, username, displayName,
      passwordHash: await hashPassword(password) }, select: { staffCode: true, username: true } });
    console.log('Created staff:', staff.staffCode, staff.username);
  } finally {
    await db.$disconnect();
  }
} catch {
  console.error('Staff provisioning failed. Check database migration, unique staff code/username, and input format.');
  process.exitCode = 1;
}
