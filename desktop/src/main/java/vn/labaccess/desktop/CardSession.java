package vn.labaccess.desktop;

import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    enum Role {
        STAFF("staff-journal.txt"), KIOSK("kiosk-journal.txt");

        private final String journalName;

        Role(String journalName) {
            this.journalName = journalName;
        }
    }

    private final PendingJournal journal;
    private final Path legacyFile;
    private final Role role;
    private final CardConnectionService connection;
    private final SupersededRecovery superseded;
    private final StaffManualReconcile manual = new StaffManualReconcile(gateway, backend);
    private boolean snapshotKnown;

    CardSession(Consumer<String> diagnostics) {
        this(Path.of(System.getProperty("labaccess.journal",
                System.getProperty("user.home") + "/.labaccess/pending-journal.txt")), diagnostics);
    }

    CardSession(Path journalFile, Consumer<String> diagnostics) {
        this(journalFile, null, null, diagnostics);
    }

    static CardSession forRole(Role role, Consumer<String> diagnostics) {
        Path directory = Path.of(System.getProperty("user.home"), ".labaccess").toAbsolutePath().normalize();
        Path file = Path.of(System.getProperty("labaccess.journal", directory.resolve(role.journalName).toString()));
        return new CardSession(file.toAbsolutePath().normalize(), directory.resolve("pending-journal.txt"),
                role, diagnostics);
    }

    private CardSession(Path journalFile, Path legacyFile, Role role, Consumer<String> diagnostics) {
        this.legacyFile = legacyFile;
        this.role = role;
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

    private boolean legacySelected() {
        return legacyFile != null && journal.file().equals(legacyFile);
    }

    private boolean legacyPresent() {
        return legacyFile != null && !Files.notExists(legacyFile, LinkOption.NOFOLLOW_LINKS);
    }

    String newTransactionProblem() {
        if (legacySelected() && !legacyPresent()) {
            return "Đóng ứng dụng và mở lại với journal mặc định trước khi thực hiện giao dịch mới.";
        }
        if (!legacyPresent()) {
            return null;
        }
        return legacySelected() ? "Đang mở journal cũ chỉ để đối soát; không thực hiện giao dịch mới."
                : "Còn journal cũ " + legacyFile + ". Đóng ứng dụng; mở Staff cho GRANT hoặc Kiosk cho START/END"
                        + " với -Dlabaccess.journal trỏ đúng tệp cũ để đối soát tại chỗ.";
    }

    String recoveryProblem() {
        if (!legacySelected() || !legacyPresent()) {
            return newTransactionProblem();
        }
        if (!Files.isRegularFile(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
            return "Journal cũ không phải tệp thường. Giữ nguyên và kiểm tra tệp trước khi đối soát.";
        }
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present() || !loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            return loaded.present() ? loaded.recoverySummary() : "Không đọc được journal cũ. Giữ nguyên tệp.";
        }
        boolean grant = loaded.entry().operation() == Receipt.OPERATION_GRANT;
        return grant == (role == Role.STAFF) ? null
                : "Sai ứng dụng cho journal cũ. Mở " + (grant ? "Staff cho GRANT" : "Kiosk cho START/END")
                        + " với cùng tệp để đối soát; giữ nguyên journal.";
    }

    StartupJournal blockOnStartupJournal() {
        String restricted = recoveryProblem();
        if (restricted != null) {
            return new StartupJournal(legacyPresent(), "[INFO] Khởi động: " + restricted);
        }
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
        String problem = newTransactionProblem();
        return problem == null ? gateway.runStateTransaction(journal, plan, supplier, studentPin)
                : CardOperationResult.fail(problem);
    }

    CardOperationResult grant(int delta, AdminGrantService.PinSource pinSource) {
        String problem = newTransactionProblem();
        return problem == null ? new AdminGrantService(gateway, journal, backend).grant(delta, pinSource)
                : CardOperationResult.fail(problem);
    }

    CardOperationResult reconcilePending(char[] studentPin) throws CardException {
        String problem = recoveryProblem();
        return problem == null ? gateway.reconcilePending(journal, studentPin, superseded::studentEvidence)
                : CardOperationResult.fail(problem);
    }

    CardOperationResult reconcileAdminGrant(AdminGrantService.PinSource pinSource) {
        String problem = recoveryProblem();
        return problem == null ? new AdminGrantRecovery(gateway, journal, backend).reconcile(pinSource)
                : CardOperationResult.fail(problem);
    }

    SupersededRecovery.Confirmation confirmSuperseded(CardOperationResult.SupersededProof proof, char[] adminPin) {
        String problem = recoveryProblem();
        if (problem == null) {
            return superseded.confirm(proof, adminPin);
        }
        if (adminPin != null) {
            java.util.Arrays.fill(adminPin, (char) 0);
        }
        return SupersededRecovery.Confirmation.withoutAdminResponse(CardOperationResult.fail(problem));
    }

    StaffManualReconcile.Decision inspectManualReconcile(AdminGrantService.PinSource pinSource) {
        String problem = newTransactionProblem();
        return problem == null ? manual.inspect(pinSource)
                : new StaffManualReconcile.Decision(StaffManualReconcile.Kind.REJECTED, null, null, problem);
    }

    CardOperationResult confirmManualReconcile(StaffManualReconcile.Decision preview,
            AdminGrantService.PinSource pinSource) {
        String problem = newTransactionProblem();
        return problem == null ? manual.confirm(preview, pinSource) : CardOperationResult.fail(problem);
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
