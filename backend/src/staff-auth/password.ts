import { argon2, randomBytes, timingSafeEqual } from 'node:crypto';

const PREFIX = 'argon2id$v=1$m=19456,t=2,p=1';

function derive(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const message = Buffer.from(password, 'utf8');
    argon2('argon2id', {
      message,
      nonce: salt,
      parallelism: 1,
      tagLength: 32,
      memory: 19456,
      passes: 2,
    }, (error, key) => {
      message.fill(0);
      if (error) { reject(error); }
      else { resolve(key); }
    });
  });
}

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const key = await derive(password, salt);
  try {
    return [PREFIX, salt.toString('hex'), key.toString('hex')].join('$');
  } finally {
    key.fill(0);
  }
}

export async function verifyPassword(password: string, stored: string | undefined): Promise<boolean> {
  const match = stored?.match(/^argon2id\$v=1\$m=19456,t=2,p=1\$([0-9a-f]{32})\$([0-9a-f]{64})$/);
  const salt = Buffer.from(match?.[1] ?? '00'.repeat(16), 'hex');
  const expected = Buffer.from(match?.[2] ?? '00'.repeat(32), 'hex');
  const actual = await derive(password, salt);
  try {
    return timingSafeEqual(actual, expected) && match !== undefined && match !== null;
  } finally {
    actual.fill(0);
    expected.fill(0);
  }
}
