-- CreateEnum
CREATE TYPE "Operation" AS ENUM ('GRANT', 'START', 'END');

-- CreateTable
CREATE TABLE "students" (
    "id" UUID NOT NULL,
    "cardId" BYTEA NOT NULL,
    "studentId" VARCHAR(64) NOT NULL,
    "publicKey" BYTEA NOT NULL,
    "enrolledAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "students_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "card_ledgers" (
    "cardId" BYTEA NOT NULL,
    "lastCounter" BIGINT NOT NULL DEFAULT 0,
    "lastBalance" BIGINT NOT NULL DEFAULT 0,
    "activeVisitId" BYTEA,
    "updatedAt" TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT "card_ledgers_pkey" PRIMARY KEY ("cardId")
);

-- CreateTable
CREATE TABLE "transactions" (
    "id" UUID NOT NULL,
    "cardId" BYTEA NOT NULL,
    "counterAfter" BIGINT NOT NULL,
    "operation" "Operation" NOT NULL,
    "delta" BIGINT NOT NULL,
    "balanceAfter" BIGINT NOT NULL,
    "visitId" BYTEA,
    "payload" BYTEA NOT NULL,
    "signature" BYTEA NOT NULL,
    "recordedAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "transactions_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "students_cardId_key" ON "students"("cardId");

-- CreateIndex
CREATE INDEX "students_studentId_idx" ON "students"("studentId");

-- CreateIndex
CREATE UNIQUE INDEX "transactions_cardId_counterAfter_key" ON "transactions"("cardId", "counterAfter");

-- AddForeignKey
ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_cardId_fkey" FOREIGN KEY ("cardId") REFERENCES "students"("cardId") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_cardId_fkey" FOREIGN KEY ("cardId") REFERENCES "students"("cardId") ON DELETE RESTRICT ON UPDATE CASCADE;

-- Domain constraints: uint32 counters/balances stored as BIGINT, cardId is 8 bytes, visitId is 16 bytes.
ALTER TABLE "students" ADD CONSTRAINT "students_cardId_length" CHECK (octet_length("cardId") = 8);

ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_cardId_length" CHECK (octet_length("cardId") = 8);
ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_lastCounter_domain" CHECK ("lastCounter" >= 0 AND "lastCounter" <= 4294967295);
ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_lastBalance_domain" CHECK ("lastBalance" >= 0 AND "lastBalance" <= 4294967295);
ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_activeVisitId_length" CHECK ("activeVisitId" IS NULL OR octet_length("activeVisitId") = 16);

ALTER TABLE "transactions" ADD CONSTRAINT "transactions_cardId_length" CHECK (octet_length("cardId") = 8);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_counterAfter_domain" CHECK ("counterAfter" >= 0 AND "counterAfter" <= 4294967295);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_balanceAfter_domain" CHECK ("balanceAfter" >= 0 AND "balanceAfter" <= 4294967295);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_delta_domain" CHECK ("delta" >= -4294967295 AND "delta" <= 4294967295);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_operation_delta" CHECK (
    ("operation" = 'GRANT' AND "delta" > 0)
    OR ("operation" = 'START' AND "delta" = -1)
    OR ("operation" = 'END' AND "delta" = 0)
);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_visitId_operation" CHECK (
    ("operation" = 'GRANT' AND "visitId" IS NULL)
    OR ("operation" <> 'GRANT' AND "visitId" IS NOT NULL AND octet_length("visitId") = 16)
);

