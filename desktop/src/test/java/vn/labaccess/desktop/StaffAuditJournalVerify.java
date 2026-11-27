package vn.labaccess.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptCodec;

public final class StaffAuditJournalVerify {
    private static int checks;

    private StaffAuditJournalVerify() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("labaccess-audit-journal-");
        PendingJournal journal = new PendingJournal(directory.resolve("pending.txt"));
        try {
            PendingJournal.Entry legacy = new PendingJournal.Entry("A1B2C3D411223344", 1,
                    Receipt.OPERATION_GRANT, 5, 5, null, System.currentTimeMillis());
            require(journal.writePending(legacy), "legacy v1 journal writes");
            require(journal.load().valid() && journal.load().entry().auditOperationId() == null,
                    "legacy v1 remains readable without invented actor");
            journal.remove();
            String id = UUID.randomUUID().toString();
            PendingJournal.Entry audited = legacy.withAudit(id);
            require(journal.writePending(audited), "v2 audited journal writes");
            PendingJournal.Loaded reloaded = new PendingJournal(journal.file()).load();
            require(reloaded.valid() && id.equals(reloaded.entry().auditOperationId()),
                    "audit origin survives reopen");
            require(Arrays.equals(ReceiptCodec.intentPayload(legacy), ReceiptCodec.intentPayload(reloaded.entry())),
                    "signed receipt format stays unchanged");
            require(!journal.writePending(legacy), "existing audited journal cannot be overwritten");
            require(journal.writeResolved(audited) && id.equals(journal.load().entry().auditOperationId()),
                    "resolved journal retains audit origin");
            require(!PendingJournal.parse(PendingJournal.formatLine(audited, PendingJournal.Status.PENDING)
                    .replace(id, "invalid")).valid(), "invalid v2 audit ID rejected");
            System.out.println("STAFF AUDIT JOURNAL PASS: " + checks + " checks");
        } finally {
            journal.remove();
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean value, String name) {
        if (!value) { throw new AssertionError(name); }
        checks++;
        System.out.println("PASS " + name);
    }
}
