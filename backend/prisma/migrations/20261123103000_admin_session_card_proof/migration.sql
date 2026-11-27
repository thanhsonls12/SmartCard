ALTER TABLE "admin_sessions" ADD COLUMN "nonce" BYTEA NOT NULL DEFAULT decode(md5(random()::text) || md5(random()::text), 'hex');
ALTER TABLE "admin_sessions" ALTER COLUMN "nonce" DROP DEFAULT;
ALTER TABLE "admin_sessions" ADD COLUMN "cardId" BYTEA;
ALTER TABLE "admin_sessions" ADD COLUMN "activatedAt" TIMESTAMPTZ(6);
