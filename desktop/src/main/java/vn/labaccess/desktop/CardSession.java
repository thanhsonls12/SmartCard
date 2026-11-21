package vn.labaccess.desktop;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.function.Consumer;
import javax.smartcardio.CardException;

final class CardSession {

    record StartupJournal(boolean present, String message) {
    }

    record Connection(boolean connected, boolean ready) {
    }

    record History(BackendClient.HistoryLoad loaded, Exception failure) {
    }

    private final CardGateway gateway = new CardGateway(
            System.getProperty("labaccess.host", "127.0.0.1"),
            Integer.getInteger("labaccess.port", 9025));
    private final BackendClient backend = new BackendClient(
            System.getProperty("labaccess.backend", "http://127.0.0.1:3100"));
    private final PendingJournal journal;
    private final CardConnectionService connection;
    private final SupersededRecovery superseded;
    private final StaffManualReconcile manual = new StaffManualReconcile(gateway, backend);
    private boolean snapshotKnown;

    CardSession(Consumer<String> diagnostics) {
        this(Path.of(System.getProperty("labaccess.journal",
                System.getProperty("user.home") + "/.labaccess/pending-journal.txt")), diagnostics);
    }

    CardSession(Path journalFile, Consumer<String> diagnostics) {
        journal = new PendingJournal(journalFile);
        superseded = new SupersededRecovery(gateway, journal, backend);
        connection = new CardConnectionService(gateway, backend, diagnostics);
        gateway.setDiagnostics(diagnostics);
        gateway.setReceiptSink(new CardGateway.ReceiptSink() {
            @Override
            public CardGateway.ReceiptSave save(Receipt receipt) {
                return saveReceiptToLedger(receipt);
            }

            @Override
            public CardGateway.LedgerCheck ledger(String cardIdHex) {
                return ledgerOf(cardIdHex);
            }
        });
    }

    CardGateway gateway() {
        return gateway;
    }

    BackendClient backend() {
        return backend;
    }

    Path journalFile() {
        return journal.file();
    }

    PendingJournal.Loaded pendingJournal() {
        return journal.load();
    }

    StartupJournal blockOnStartupJournal() {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            return new StartupJournal(false, "Khởi động: không có pending journal");
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            gateway.markTransactionUnknown(loaded.recoverySummary());
            return new StartupJournal(true, "[FAIL] Khởi động: journal không dùng được ("
                    + (loaded.valid() ? loaded.status() : loaded.problem())
                    + ") — KHÓA giao dịch, giữ nguyên file, không gửi APDU");
        }
        PendingJournal.Entry entry = loaded.entry();
        gateway.markTransactionUnknown("khôi phục pending journal khi mở lại ứng dụng");
        return new StartupJournal(true, String.format(
                "[FAIL] Khởi động: còn pending journal cardId=%s counter=%d operation=%s"
                        + " tạo lúc %s — KHÓA giao dịch ngay; chưa gửi APDU, chưa đọc thẻ, chưa dùng PIN;"
                        + " kết nối và nhận diện đúng thẻ rồi đối soát",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation()),
                java.time.Instant.ofEpochMilli(entry.createdAtEpochMillis())));
    }

    void blockUnusableJournal(PendingJournal.Loaded loaded) {
        if (loaded.present() && !loaded.valid()) {
            gateway.markTransactionUnknown("pending journal không hợp lệ — cần xử lý thủ công");
        } else if (loaded.present() && loaded.status() == PendingJournal.Status.RESOLVED) {
            gateway.markTransactionUnknown("journal đã RESOLVED nhưng chưa xóa — cần xử lý thủ công");
        }
    }

    CardOperationResult connect() throws CardException, NoSuchAlgorithmException, NoSuchProviderException {
        return connection.connect();
    }

    CardOperationResult identify() throws CardException {
        return connection.identify();
    }

    CardOperationResult disconnect() throws CardException {
        return gateway.disconnect();
    }

    Connection synchronizeConnection() {
        boolean connected = gateway.isConnected();
        boolean ready = gateway.isReady();
        if (!ready) {
            invalidateSnapshot();
            gateway.clearAuthenticatedStatus();
        }
        return new Connection(connected, ready);
    }

    boolean snapshotKnown() {
        return snapshotKnown;
    }

    void invalidateSnapshot() {
        snapshotKnown = false;
    }

    CardOperationResult runStateTransaction(CardGateway.TransactionPlan plan,
            CardGateway.CommandSupplier supplier, char[] studentPin) throws CardException {
        return gateway.runStateTransaction(journal, plan, supplier, studentPin);
    }

    CardOperationResult grant(int delta, AdminGrantService.PinSource pinSource) {
        return new AdminGrantService(gateway, journal, backend).grant(delta, pinSource);
    }

    CardOperationResult reconcilePending(char[] studentPin) throws CardException {
        return gateway.reconcilePending(journal, studentPin, superseded::studentEvidence);
    }

    CardOperationResult reconcileAdminGrant(AdminGrantService.PinSource pinSource) {
        return new AdminGrantRecovery(gateway, journal, backend).reconcile(pinSource);
    }

    SupersededRecovery.Confirmation confirmSuperseded(CardOperationResult.SupersededProof proof, char[] adminPin) {
        return superseded.confirm(proof, adminPin);
    }

    StaffManualReconcile.Decision inspectManualReconcile(AdminGrantService.PinSource pinSource) {
        return manual.inspect(pinSource);
    }

    CardOperationResult confirmManualReconcile(StaffManualReconcile.Decision preview,
            AdminGrantService.PinSource pinSource) {
        return manual.confirm(preview, pinSource);
    }

    History loadHistory() {
        CardStatus current = gateway.status();
        if (current == null || current.cardId() == null || current.cardId().isBlank()) {
            return new History(null, new IllegalStateException("chưa có mã thẻ để đọc lịch sử"));
        }
        try {
            return new History(backend.historyWithLedger(current.cardId()), null);
        } catch (BackendClient.Failure failure) {
            return new History(null, failure);
        }
    }

    void transactionFinished(boolean ok) {
        CardStatus refreshed = gateway.status();
        snapshotKnown = ok && refreshed != null && refreshed.stateAvailable();
        if (!snapshotKnown) {
            gateway.clearAuthenticatedStatus();
        }
    }

    void receiptFinished(boolean ok) {
        CardStatus current = gateway.status();
        snapshotKnown = ok && current != null && current.studentStateAvailable();
    }

    void reconcileFinished(boolean ok) {
        CardStatus refreshed = gateway.status();
        snapshotKnown = ok && !journal.load().present()
                && gateway.reconciliation().transactionAllowed()
                && refreshed != null && refreshed.stateAvailable();
    }

    private CardGateway.ReceiptSave saveReceiptToLedger(Receipt receipt) {
        try {
            BackendClient.SavedReceipt saved = backend.verifyReceipt(receipt);
            return new CardGateway.ReceiptSave(true, String.format(
                    "lưu ledger=OK (%s, counter=%d, %s, HTTP %d)", saved.status(),
                    saved.counterAfter(), saved.operation(), saved.httpStatus()));
        } catch (BackendClient.Failure failure) {
            return new CardGateway.ReceiptSave(false, failure.getMessage());
        }
    }

    private CardGateway.LedgerCheck ledgerOf(String cardIdHex) {
        try {
            BackendClient.LedgerSnapshot snapshot = backend.ledgerOf(cardIdHex);
            return new CardGateway.LedgerCheck(true, "ledger đọc được", snapshot.cardId(),
                    snapshot.lastCounter(), snapshot.lastBalance(), snapshot.activeVisitId());
        } catch (BackendClient.Failure failure) {
            return new CardGateway.LedgerCheck(false, failure.getMessage(), cardIdHex, -1, -1, null);
        }
    }
}
