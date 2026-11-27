CREATE TYPE "StaffAuditAction" AS ENUM ('PERSONALIZE','ENROLLMENT','GRANT','PHOTO_UPLOAD','PHOTO_REPLACE','UNBLOCK','PROFILE_SAVE','PROFILE_REOPEN','PROFILE_REPLACE','RECONCILE');
CREATE TYPE "StaffAuditOutcome" AS ENUM ('CLIENT_CONFIRMED','CLIENT_NOT_EXECUTED','CLIENT_SUPERSEDED','UNKNOWN','RECEIPT_VERIFIED');
CREATE TABLE "staff_audit_operations" (
    "id" UUID NOT NULL,
    "staffSessionId" UUID NOT NULL,
    "action" "StaffAuditAction" NOT NULL,
    "cardId" VARCHAR(16),
    "expectedPayload" VARCHAR(68),
    "originId" UUID,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "staff_audit_operations_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "staff_audit_operations_staffSessionId_fkey" FOREIGN KEY ("staffSessionId") REFERENCES "staff_sessions"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "staff_audit_operations_originId_fkey" FOREIGN KEY ("originId") REFERENCES "staff_audit_operations"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX "staff_audit_operations_staffSessionId_createdAt_idx" ON "staff_audit_operations"("staffSessionId","createdAt");
CREATE INDEX "staff_audit_operations_cardId_createdAt_idx" ON "staff_audit_operations"("cardId","createdAt");
CREATE INDEX "staff_audit_operations_originId_idx" ON "staff_audit_operations"("originId");
ALTER TABLE "transactions" ADD COLUMN "grantAuditId" UUID;
CREATE UNIQUE INDEX "transactions_grantAuditId_key" ON "transactions"("grantAuditId");
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_grantAuditId_fkey" FOREIGN KEY ("grantAuditId") REFERENCES "staff_audit_operations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE TABLE "staff_audit_events" (
    "id" UUID NOT NULL,
    "operationId" UUID NOT NULL,
    "outcome" "StaffAuditOutcome" NOT NULL,
    "cardId" VARCHAR(16),
    "transactionId" UUID,
    "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "staff_audit_events_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "staff_audit_events_operationId_fkey" FOREIGN KEY ("operationId") REFERENCES "staff_audit_operations"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "staff_audit_events_transactionId_fkey" FOREIGN KEY ("transactionId") REFERENCES "transactions"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "staff_audit_events_operationId_outcome_key" ON "staff_audit_events"("operationId","outcome");
CREATE INDEX "staff_audit_events_transactionId_idx" ON "staff_audit_events"("transactionId");
