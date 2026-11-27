CREATE TABLE "staff" (
    "id" UUID NOT NULL,
    "staffCode" VARCHAR(20) NOT NULL,
    "username" VARCHAR(64) NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "displayName" VARCHAR(100) NOT NULL,
    "isActive" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT "staff_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "staff_staffCode_key" ON "staff"("staffCode");
CREATE UNIQUE INDEX "staff_username_key" ON "staff"("username");
CREATE TABLE "staff_sessions" (
    "id" UUID NOT NULL,
    "staffId" UUID NOT NULL,
    "tokenHash" VARCHAR(64) NOT NULL,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "revokedAt" TIMESTAMPTZ(6),
    CONSTRAINT "staff_sessions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "staff_sessions_staffId_fkey" FOREIGN KEY ("staffId") REFERENCES "staff"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "staff_sessions_tokenHash_key" ON "staff_sessions"("tokenHash");
CREATE INDEX "staff_sessions_staffId_idx" ON "staff_sessions"("staffId");
CREATE INDEX "staff_sessions_expiresAt_idx" ON "staff_sessions"("expiresAt");
ALTER TABLE "admin_sessions" ADD COLUMN "staffSessionId" UUID;
CREATE INDEX "admin_sessions_staffSessionId_idx" ON "admin_sessions"("staffSessionId");
ALTER TABLE "admin_sessions" ADD CONSTRAINT "admin_sessions_staffSessionId_fkey" FOREIGN KEY ("staffSessionId") REFERENCES "staff_sessions"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
