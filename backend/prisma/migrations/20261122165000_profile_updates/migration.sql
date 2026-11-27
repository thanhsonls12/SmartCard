BEGIN;

CREATE TYPE "ProfileUpdateState" AS ENUM ('PENDING', 'COMPLETED');

CREATE TABLE "profile_updates" (
    "id" UUID NOT NULL,
    "cardId" BYTEA NOT NULL,
    "studentId" VARCHAR(64) NOT NULL,
    "displayName" VARCHAR(96) NOT NULL,
    "classCode" VARCHAR(64),
    "expectedVersion" BYTEA NOT NULL,
    "nonce" BYTEA NOT NULL,
    "adminSessionId" UUID NOT NULL,
    "state" "ProfileUpdateState" NOT NULL DEFAULT 'PENDING',
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "completedAt" TIMESTAMPTZ(6),
    "resultMajor" VARCHAR(64),
    "resultStudentId" VARCHAR(64),
    "resultClassCode" VARCHAR(64),

    CONSTRAINT "profile_updates_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "profile_updates_cardId_idx" ON "profile_updates"("cardId");

CREATE INDEX "profile_updates_adminSessionId_idx" ON "profile_updates"("adminSessionId");

CREATE INDEX "profile_updates_expiresAt_idx" ON "profile_updates"("expiresAt");

CREATE UNIQUE INDEX "profile_updates_cardId_pending_key" ON "profile_updates"("cardId") WHERE "state" = 'PENDING';

ALTER TABLE "profile_updates" ADD CONSTRAINT "profile_updates_cardId_fkey" FOREIGN KEY ("cardId") REFERENCES "students"("cardId") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "profile_updates" ADD CONSTRAINT "profile_updates_adminSessionId_fkey" FOREIGN KEY ("adminSessionId") REFERENCES "admin_sessions"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "profile_updates" ADD CONSTRAINT "profile_updates_cardId_length" CHECK (octet_length("cardId") = 8);

ALTER TABLE "profile_updates" ADD CONSTRAINT "profile_updates_expectedVersion_length" CHECK (octet_length("expectedVersion") = 12);

ALTER TABLE "profile_updates" ADD CONSTRAINT "profile_updates_nonce_length" CHECK (octet_length("nonce") = 32);

ALTER TABLE "profile_updates" ADD CONSTRAINT "profile_updates_displayName_length" CHECK (char_length("displayName") >= 1 AND char_length("displayName") <= 96);

COMMIT;
