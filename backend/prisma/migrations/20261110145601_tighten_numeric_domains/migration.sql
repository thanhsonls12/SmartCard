-- Tighten numeric domains to the LabAccess contract: balance 0..9999, receipt counter >= 1,
-- delta per operation (GRANT 1..9999, START -1, END 0), and visit IDs are NULL or non-zero 16 bytes.
ALTER TABLE "transactions" DROP CONSTRAINT "transactions_counterAfter_domain";
ALTER TABLE "transactions" DROP CONSTRAINT "transactions_balanceAfter_domain";
ALTER TABLE "transactions" DROP CONSTRAINT "transactions_delta_domain";
ALTER TABLE "transactions" DROP CONSTRAINT "transactions_operation_delta";
ALTER TABLE "transactions" DROP CONSTRAINT "transactions_visitId_operation";
ALTER TABLE "card_ledgers" DROP CONSTRAINT "card_ledgers_lastBalance_domain";
ALTER TABLE "card_ledgers" DROP CONSTRAINT "card_ledgers_activeVisitId_length";

ALTER TABLE "transactions" ADD CONSTRAINT "transactions_counterAfter_domain" CHECK ("counterAfter" >= 1 AND "counterAfter" <= 4294967295);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_balanceAfter_domain" CHECK ("balanceAfter" >= 0 AND "balanceAfter" <= 9999);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_delta_domain" CHECK ("delta" >= -1 AND "delta" <= 9999);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_operation_delta" CHECK (
    ("operation" = 'GRANT' AND "delta" >= 1 AND "delta" <= 9999)
    OR ("operation" = 'START' AND "delta" = -1)
    OR ("operation" = 'END' AND "delta" = 0)
);
ALTER TABLE "transactions" ADD CONSTRAINT "transactions_visitId_operation" CHECK (
    ("operation" = 'GRANT' AND "visitId" IS NULL)
    OR (
        "operation" <> 'GRANT'
        AND "visitId" IS NOT NULL
        AND octet_length("visitId") = 16
        AND "visitId" <> decode('00000000000000000000000000000000', 'hex')
    )
);

ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_lastBalance_domain" CHECK ("lastBalance" >= 0 AND "lastBalance" <= 9999);
ALTER TABLE "card_ledgers" ADD CONSTRAINT "card_ledgers_activeVisitId_domain" CHECK (
    "activeVisitId" IS NULL
    OR (
        octet_length("activeVisitId") = 16
        AND "activeVisitId" <> decode('00000000000000000000000000000000', 'hex')
    )
);
