package vn.labaccess.desktop.ui.receipt;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import vn.labaccess.desktop.card.CardOperationResult.SupersededProof;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.session.AdminGrantService;
import vn.labaccess.desktop.session.ConfirmedOperationRefresh;
import vn.labaccess.desktop.session.StaffManualReconcile;
import vn.labaccess.desktop.session.SupersededRecovery;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.JournalRecoveryView;
import vn.labaccess.desktop.ui.shared.PinOperation;

public final class ReceiptReconciliationFlow {

    interface View {

        boolean confirmReconcile(PendingJournal.Entry entry);

        char[] requestSupersededPin(SupersededProof proof, int adminPinRetries);

        PinPrompt requestManualPin(StaffManualReconcile.Decision decision);

        boolean studentPinEntered();

        void setPinNote(String note);

        void showPinNote();

        void clearPinNote();

        void blockReconcile(String reason);

        void showReceipt();

        void defer(Runnable action);
    }

    record PinPrompt(AdminGrantService.PinSource take, Runnable clear) {
    }

    public record CardMismatch(String expectedCardId, String actualCardId) {

        public String guidance() {
            return "Giao dịch đang chờ thuộc thẻ " + expectedCardId + "; thẻ hiện tại là " + actualCardId
                    + ". Đưa lại thẻ " + expectedCardId
                    + " rồi kết nối và nhận diện lại. Không thực hiện lại giao dịch.";
        }
    }

    private static final String GRANT_PIN_GUIDANCE =
            "Để đối soát cấp lượt, nhập lại PIN quản trị trong khối Vận hành thẻ.";

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final ReceiptPanel.SessionContext session;
    private final View view;

    private int adminPinRetries = -1;
    private int cardSessionRevision;

    ReceiptReconciliationFlow(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            ReceiptPanel.SessionContext session, View view) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;
        this.view = view;
    }

    void cardSessionCleared() {
        cardSessionRevision++;
        adminPinRetries = -1;
    }

    CardMismatch pendingCardMismatch() {
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        CardStatus current = gateway.isReady() ? gateway.status() : null;
        if (current == null || !loaded.valid() || loaded.status() != PendingJournal.Status.PENDING
                || loaded.entry() == null || loaded.entry().cardId().equalsIgnoreCase(current.cardId())) {
            return null;
        }
        return new CardMismatch(loaded.entry().cardId(), current.cardId());
    }

    String pendingOperationReason() {
        return pendingOperationReason(session.pendingJournal().get());
    }

    boolean rejectStaffPending(PendingJournal.Loaded loaded) {
        String reason = pendingOperationReason(loaded);
        if (reason == null) {
            return false;
        }
        gateway.markTransactionUnknown(reason);
        log.accept("[FAIL] RECONCILE_PENDING: " + reason);
        view.blockReconcile(reason);
        if (session.open().getAsBoolean()) {
            session.buttonsRefreshed().run();
        }
        return true;
    }

    void offerPendingReconciliation() {
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        if (rejectStaffPending(loaded) || !loaded.present()) {
            return;
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            gateway.markTransactionUnknown(JournalRecoveryView.summary(loaded));
            log.accept("[FAIL] " + JournalRecoveryView.summary(loaded) + " Mở Hướng dẫn khôi phục.");
            session.buttonsRefreshed().run();
            return;
        }
        String identifiedCard = identifiedCardId();
        if (!loaded.entry().cardId().equalsIgnoreCase(identifiedCard)) {
            blockReconcileOnDifferentCard(loaded.entry(), identifiedCard);
            return;
        }
        gateway.markTransactionUnknown("pending journal chờ operator đối soát");
        if (loaded.entry().operation() == Receipt.OPERATION_GRANT) {
            view.setPinNote(grantPinGuidance());
            session.buttonsRefreshed().run();
            return;
        }
        session.buttonsRefreshed().run();
        reconcile();
    }

    void reconcile() {
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        if (rejectStaffPending(loaded) || !session.open().getAsBoolean()) {
            return;
        }
        if (!loaded.present()) {
            reconcileWithoutJournal();
            return;
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            log.accept("[FAIL] RECONCILE_PENDING: journal không ở trạng thái PENDING hợp lệ");
            session.buttonsRefreshed().run();
            return;
        }
        PendingJournal.Entry entry = loaded.entry();
        String identifiedCard = identifiedCardId();
        if (!entry.cardId().equalsIgnoreCase(identifiedCard)) {
            blockReconcileOnDifferentCard(entry, identifiedCard);
            return;
        }
        if (entry.operation() == Receipt.OPERATION_GRANT) {
            reconcileGrant(entry);
            return;
        }
        if (session instanceof ReceiptPanel.Session student) {
            reconcileStudent(student, entry);
        }
    }

    private void reconcileStudent(ReceiptPanel.Session student, PendingJournal.Entry entry) {
        if (!view.studentPinEntered()) {
            return;
        }
        if (!view.confirmReconcile(entry)) {
            log.accept("[INFO] RECONCILE_PENDING: operator chưa xác nhận, journal vẫn được giữ");
            return;
        }
        view.clearPinNote();
        char[] studentPin = student.studentPin().get();
        student.studentPinCleared().run();
        AtomicReference<SupersededProof> proof = new AtomicReference<>();
        runner.run("RECONCILE_PENDING", () -> {
            session.historyInvalidated().run();
            try {
                CardOperationResult reconciled = student.reconcilePending().reconcile(studentPin);
                proof.set(reconciled.superseded());
                if (!reconciled.ok()) {
                    return reconciled;
                }
                CardOperationResult done = ConfirmedOperationRefresh.read(gateway, reconciled, studentPin);
                session.historyRemembered().run();
                return done;
            } finally {
                Arrays.fill(studentPin, '\0');
            }
        }, ok -> finishReconcile(ok, proof.get()));
    }

    private void reconcileWithoutJournal() {
        int revision = cardSessionRevision;
        ReceiptPanel.AdminRecovery admin = session.adminRecovery();
        if (!admin.requirePin().getAsBoolean()) {
            view.setPinNote(grantPinGuidance());
            view.showPinNote();
            return;
        }
        if (!session.open().getAsBoolean() || cardSessionRevision != revision
                || rejectStaffPending(session.pendingJournal().get())) {
            admin.clearPin().run();
            return;
        }
        AtomicReference<StaffManualReconcile.Decision> decision = new AtomicReference<>();
        boolean accepted = runner.run("ĐỐI SOÁT TAY", () -> {
            StaffManualReconcile.Decision found = admin.inspectManual().apply(admin.pin());
            decision.set(found);
            if (found.kind() == StaffManualReconcile.Kind.REJECTED) {
                return CardOperationResult.fail(found.detail());
            }
            return CardOperationResult.ok(found.detail());
        }, ok -> {
            if (cardSessionRevision != revision || rejectStaffPending(session.pendingJournal().get())) {
                admin.clearPin().run();
                return;
            }
            StaffManualReconcile.Decision found = decision.get();
            if (found == null || found.kind() != StaffManualReconcile.Kind.NEEDS_CONFIRMATION) {
                admin.clearPin().run();
                finishReconcile(ok);
                return;
            }
            PinPrompt again = view.requestManualPin(found);
            admin.clearPin().run();
            if (again == null) {
                return;
            }
            view.defer(() -> {
                if (!session.open().getAsBoolean() || cardSessionRevision != revision
                        || rejectStaffPending(session.pendingJournal().get())) {
                    again.clear().run();
                    return;
                }
                boolean posting = runner.run("GHI SỔ ĐỐI SOÁT TAY",
                        () -> admin.confirmManual().apply(found, again.take()),
                        posted -> {
                            again.clear().run();
                            if (cardSessionRevision == revision) {
                                finishReconcile(posted);
                            }
                        });
                if (!posting) {
                    again.clear().run();
                    log.accept("[INFO] Đối soát tay: không nhận tác vụ, đã xóa PIN");
                }
            });
        });
        if (!accepted) {
            admin.clearPin().run();
        }
    }

    private void reconcileGrant(PendingJournal.Entry entry) {
        int revision = cardSessionRevision;
        ReceiptPanel.AdminRecovery admin = session.adminRecovery();
        if (!admin.requirePin().getAsBoolean()) {
            view.setPinNote(grantPinGuidance());
            view.showPinNote();
            return;
        }
        if (!session.open().getAsBoolean() || cardSessionRevision != revision
                || rejectStaffPending(session.pendingJournal().get())) {
            admin.clearPin().run();
            return;
        }
        if (!view.confirmReconcile(entry)) {
            admin.clearPin().run();
            log.accept("[INFO] RECONCILE_PENDING: operator chưa xác nhận, journal vẫn được giữ");
            return;
        }
        if (!session.open().getAsBoolean() || cardSessionRevision != revision
                || rejectStaffPending(session.pendingJournal().get())) {
            admin.clearPin().run();
            return;
        }
        view.clearPinNote();
        AtomicReference<SupersededProof> proof = new AtomicReference<>();
        boolean accepted = runner.run("RECONCILE_PENDING", () -> {
            session.historyInvalidated().run();
            CardOperationResult result = admin.reconcile().apply(admin.pin());
            proof.set(result.superseded());
            if (result.ok()) {
                session.historyRemembered().run();
            }
            return result;
        }, ok -> {
            admin.clearPin().run();
            if (cardSessionRevision == revision) {
                finishReconcile(ok, proof.get());
            }
        });
        if (!accepted) {
            admin.clearPin().run();
        }
    }

    private void finishReconcile(boolean ok, SupersededProof proof) {
        finishReconcile(ok);
        if (proof != null) {
            int revision = cardSessionRevision;
            view.defer(() -> {
                if (cardSessionRevision == revision) {
                    confirmSuperseded(proof);
                }
            });
        }
    }

    private void confirmSuperseded(SupersededProof proof) {
        if (rejectStaffPending(session.pendingJournal().get()) || !session.open().getAsBoolean()) {
            return;
        }
        int revision = cardSessionRevision;
        char[] pin = view.requestSupersededPin(proof, adminPinRetries);
        if (pin == null) {
            log.accept("[INFO] Nhân viên chưa xác nhận SUPERSEDED, journal được giữ");
            return;
        }
        if (!session.open().getAsBoolean() || cardSessionRevision != revision
                || rejectStaffPending(session.pendingJournal().get())) {
            Arrays.fill(pin, '\0');
            return;
        }
        AtomicReference<SupersededRecovery.Confirmation> confirmed = new AtomicReference<>();
        boolean accepted = runner.run("RECONCILE_PENDING", PinOperation.of(() -> {
            SupersededRecovery.Confirmation attempt = session.adminRecovery().confirmSuperseded().apply(proof, pin);
            confirmed.set(attempt);
            return attempt.result();
        }, pin), ok -> {
            SupersededRecovery.Confirmation attempt = confirmed.get();
            CardStatus current = gateway.status();
            if (attempt != null && cardSessionRevision == revision && gateway.isReady() && current != null
                    && current.publicView().equals(proof.state().publicView())) {
                attempt.adminStatusWord().ifPresent(this::rememberAdminPinRetries);
            }
            if (cardSessionRevision == revision) {
                finishReconcile(ok);
            }
        });
        if (!accepted) {
            Arrays.fill(pin, '\0');
        }
    }

    private void rememberAdminPinRetries(int sw) {
        if ((sw & 0xFFF0) == 0x63C0) {
            adminPinRetries = sw & 0x0F;
        } else if (sw == Sw.AUTH_BLOCKED || sw == Sw.OK) {
            adminPinRetries = sw == Sw.AUTH_BLOCKED ? 0 : -1;
        }
    }

    private void finishReconcile(boolean ok) {
        session.reconcileFinished().accept(ok);
        view.showReceipt();
        session.historyPublished().run();
    }

    private String pendingOperationReason(PendingJournal.Loaded loaded) {
        if (!(session instanceof ReceiptPanel.StaffSession) || !loaded.present() || !loaded.valid()
                || loaded.status() != PendingJournal.Status.PENDING || loaded.entry() == null
                || loaded.entry().operation() == Receipt.OPERATION_GRANT) {
            return null;
        }
        return "Nhật ký " + Receipt.operationText(loaded.entry().operation())
                + " thuộc luồng sinh viên. Mở Kiosk với cùng tệp nhật ký và đúng thẻ để đối soát."
                + " Giữ nguyên tệp PENDING; không thực hiện lại giao dịch trong StaffApp.";
    }

    private String grantPinGuidance() {
        return session instanceof ReceiptPanel.StaffSession
                ? "Để đối soát cấp lượt, nhập lại PIN quản trị trong khối Cấp lượt."
                : GRANT_PIN_GUIDANCE;
    }

    private String identifiedCardId() {
        CardStatus current = gateway.status();
        return current == null || current.cardId() == null ? "" : current.cardId();
    }

    private void blockReconcileOnDifferentCard(PendingJournal.Entry entry, String identifiedCard) {
        gateway.markTransactionUnknown("pending journal thuộc cardId=" + entry.cardId()
                + " khác thẻ đang nhận diện=" + identifiedCard);
        log.accept(String.format("[FAIL] DOI-SOAT-THE-KHAC: journal cardId=%s nhưng thẻ đang nhận diện là %s"
                        + " — không mở hộp thoại đối soát, không dùng PIN, giữ journal PENDING",
                entry.cardId(), identifiedCard.isEmpty() ? "<chưa nhận diện>" : identifiedCard));
        session.buttonsRefreshed().run();
    }
}
