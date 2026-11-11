-- CreateTable
CREATE TABLE "admin_sessions" (
    "id" UUID NOT NULL,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "revokedAt" TIMESTAMPTZ(6),

    CONSTRAINT "admin_sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "enrollment_challenges" (
    "id" UUID NOT NULL,
    "nonce" BYTEA NOT NULL,
    "cardId" BYTEA NOT NULL,
    "studentId" VARCHAR(64) NOT NULL,
    "publicKey" BYTEA NOT NULL,
    "publicKeyHash" BYTEA NOT NULL,
    "adminSessionId" UUID NOT NULL,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(6) NOT NULL,
    "usedAt" TIMESTAMPTZ(6),

    CONSTRAINT "enrollment_challenges_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "enrollment_challenges_cardId_idx" ON "enrollment_challenges"("cardId");

-- CreateIndex
CREATE INDEX "enrollment_challenges_adminSessionId_idx" ON "enrollment_challenges"("adminSessionId");

-- CreateIndex
CREATE INDEX "enrollment_challenges_expiresAt_idx" ON "enrollment_challenges"("expiresAt");

-- CreateIndex
CREATE UNIQUE INDEX "enrollment_challenges_nonce_key" ON "enrollment_challenges"("nonce");

-- AddForeignKey
ALTER TABLE "enrollment_challenges" ADD CONSTRAINT "enrollment_challenges_adminSessionId_fkey" FOREIGN KEY ("adminSessionId") REFERENCES "admin_sessions"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "enrollment_challenges" ADD CONSTRAINT "enrollment_challenges_nonce_length" CHECK (octet_length("nonce") = 32);

ALTER TABLE "enrollment_challenges" ADD CONSTRAINT "enrollment_challenges_cardId_length" CHECK (octet_length("cardId") = 8);
ALTER TABLE "enrollment_challenges" ADD CONSTRAINT "enrollment_challenges_publicKey_length" CHECK (octet_length("publicKey") = 65);
ALTER TABLE "enrollment_challenges" ADD CONSTRAINT "enrollment_challenges_publicKeyHash_length" CHECK (octet_length("publicKeyHash") = 32);
ALTER TABLE "enrollment_challenges" ADD CONSTRAINT "enrollment_challenges_studentId_length" CHECK (char_length("studentId") >= 1 AND char_length("studentId") <= 64);
