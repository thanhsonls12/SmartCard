import { randomBytes } from 'node:crypto';
import { NONCE_BYTES } from '../enrollment/enrollment-message.js';

const REQUEST_TTL_MS = 5 * 60 * 1000;

export interface ProfileChallenge {
  nonce: Buffer;
  expiresAt: Date;
}

export function newProfileChallenge(): ProfileChallenge {
  const nonce = randomBytes(NONCE_BYTES);
  return { nonce, expiresAt: new Date(Date.now() + REQUEST_TTL_MS) };
}
