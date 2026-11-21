package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import vn.labaccess.desktop.CardOperationResult.SupersededProof;

final class ReceiptPanel {

    @FunctionalInterface
    interface PendingReconcile {

        CardOperationResult reconcile(char[] studentPin) throws javax.smartcardio.CardException;
    }

    record AdminRecovery(BooleanSupplier requirePin, AdminGrantService.PinSource pin,
            Runnable clearPin, Function<AdminGrantService.PinSource, CardOperationResult> reconcile,
            BiFunction<SupersededProof, char[], SupersededRecovery.Confirmation> confirmSuperseded,
            Function<AdminGrantService.PinSource, StaffManualReconcile.Decision> inspectManual,
            BiFunction<StaffManualReconcile.Decision, AdminGrantService.PinSource, CardOperationResult> confirmManual) {
    }

    record Session(BooleanSupplier open, Supplier<PendingJournal.Loaded> pendingJournal,
            java.nio.file.Path journalFile,
            Supplier<char[]> studentPin, Runnable studentPinCleared,
            PendingReconcile reconcilePending, AdminRecovery adminRecovery, Runnable historyInvalidated,
            Runnable historyRemembered, Runnable historyPublished, Runnable buttonsRefreshed,
            Consumer<Boolean> receiptFinished,
            Consumer<Boolean> reconcileFinished, Runnable pinRequired) {
    }

    record CardMismatch(String expectedCardId, String actualCardId) {
        String guidance() {
            return "Giao dịch đang chờ thuộc thẻ " + expectedCardId + "; thẻ hiện tại là " + actualCardId
                    + ". Đưa lại thẻ " + expectedCardId
                    + " rồi kết nối và nhận diện lại. Không thực hiện lại giao dịch.";
        }
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

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Session session;

    private final Label valueVisitId = UiLabels.value();
    private final Label valueReconcile = UiLabels.chip();
    private final ReceiptView receiptView = new ReceiptView();
    private boolean canRead;
    private final Button btnReconcile = new Button("Đối soát giao dịch", Icons.reconcile());
    private static final String GRANT_PIN_GUIDANCE =
            "Để đối soát cấp lượt, nhập lại PIN quản trị trong khối Vận hành thẻ.";
    private final Label why = UiLabels.whyNote();
    private String pinNote;
    private int adminPinRetries = -1;
    private int cardSessionRevision;
    private final VBox node;

    ReceiptPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Session session) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;

        valueVisitId.setText("Chưa đọc trạng thái ca");

        btnReconcile.getStyleClass().add("la-action");
        btnReconcile.setOnAction(e -> {
            PendingJournal.Loaded loaded = session.pendingJournal().get();
            if (JournalRecoveryView.needed(loaded)) {
                JournalRecoveryView.show(btnReconcile, loaded, session.journalFile());
            } else {
                reconcile();
            }
        });

        VBox visitIdField = UiRows.field("Mã ca đang mở", valueVisitId);
        FlowPane controls = UiRows.wrapping(visitIdField,
                UiLabels.copyButton(valueVisitId, "mã ca"));
        TitledPane details = new TitledPane("Chi tiết biên nhận",
                new VBox(UiScale.SPACE_2, controls, receiptView.detailsNode()));
        details.setExpanded(false);
        details.getStyleClass().add("la-receipt-details");
        node = UiSection.of("Biên nhận gần nhất", receiptView.node(), why, details);
    }

    VBox node() {
        return node;
    }

    Node reconcileAction() {
        return btnReconcile;
    }

    Node reconcileStatus() {
        return valueReconcile;
    }

    void showReconcile() {
        boolean known = gateway.reconciliation().transactionAllowed();
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        boolean recovery = JournalRecoveryView.needed(loaded);
        boolean pending = loaded.present() && loaded.valid()
                && loaded.status() == PendingJournal.Status.PENDING;
        if (recovery) {
            valueReconcile.setText("Cần khôi phục");
        } else if (pending || !known) {
            valueReconcile.setText(gateway.reconciliation().receiptUnverified()
                    ? "Cần xác minh biên nhận" : "Cần đối soát");
        } else {
            valueReconcile.setText("Không có giao dịch chờ");
        }
        UiLabels.Tone tone = recovery || !known || pending
                ? UiLabels.Tone.ERROR : UiLabels.Tone.NEUTRAL;
        UiLabels.applyChip(valueReconcile, tone);
    }

    String displayedVisitId() {
        return valueVisitId.getText();
    }

    String reconcileState() {
        return btnReconcile.isDisable() ? "khoa" : "mo";
    }

    void applyLock(boolean busy, String whyReadReceipt, String whyReconcile) {
        canRead = whyReadReceipt == null;
        CardMismatch mismatch = pendingCardMismatch();
        if (mismatch != null) {
            whyReconcile = mismatch.guidance();
        }
        boolean recovery = JournalRecoveryView.needed(session.pendingJournal().get());
        btnReconcile.setText(recovery ? "Hướng dẫn khôi phục" : "Đối soát giao dịch");
        btnReconcile.setTooltip(new javafx.scene.control.Tooltip(recovery
                ? "Xem nguyên nhân và các bước xử lý nhật ký; không thay đổi dữ liệu"
                : whyReconcile == null ? "Đọc biên nhận để đối soát giao dịch" : whyReconcile));
        btnReconcile.setDisable(recovery ? busy : whyReconcile != null);
        UiLabels.setWhy(why, mismatch == null ? pinNote : mismatch.guidance());
    }

    boolean reconcileEnabled() {
        return !btnReconcile.isDisable();
    }

    void setReconcilePrimary(boolean primary) {
        UiLabels.markPrimary(btnReconcile, primary && !btnReconcile.isDisable());
    }

    void lockAll() {
        canRead = false;
        btnReconcile.setDisable(true);
    }

    void clear() {
        cardSessionRevision++;
        adminPinRetries = -1;
        valueVisitId.setText("Chưa đọc trạng thái ca");
        receiptView.clear("Chưa đọc biên nhận từ thẻ");
    }

    private void showPinMissing() {
        pinNote = "Nhập PIN học viên " + PersonalizeRequest.STUDENT_PIN_LENGTH
                + " chữ số trong khối Vận hành thẻ.";
        session.pinRequired().run();
        UiLabels.setWhy(why, pinNote);
    }

    private void clearPinNote() {
        if (pinNote == null) {
            return;
        }
        pinNote = null;
        session.buttonsRefreshed().run();
    }

    void studentPinChanged() {
        clearPinNote();
    }

    private boolean pinEntered() {
        char[] studentPin = session.studentPin().get();
        boolean ok = App.validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH);
        App.clearBuffers(studentPin, null);
        if (!ok) {
            showPinMissing();
        }
        return ok;
    }

    void showReceipt() {
        Receipt receipt = gateway.lastReceipt();
        CardStatus status = gateway.status();
        boolean authenticated = status != null && status.studentStateAvailable();
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        boolean ledgerPending = loaded.present()
                && loaded.status() == PendingJournal.Status.PENDING;
        if (receipt == null) {
            receiptView.clear(authenticated ? "Chưa có biên nhận" : "Chưa đọc biên nhận từ thẻ");
        } else {
            receiptView.show(receipt, gateway.lastReceiptSignatureChecked(),
                    gateway.lastReceiptSignatureOk(), ledgerPending);
        }
        valueVisitId.setText(authenticated && status.hasActiveVisit()
                ? status.activeVisitId()
                : authenticated ? "Không có ca đang mở" : "Chưa đọc trạng thái ca");
        showReconcile();
        if (!gateway.reconciliation().detail().isEmpty()) {
            log.accept("Đối soát: " + gateway.reconciliation().detail());
        }
    }

    boolean pressReadReceipt() {
        if (!canRead) {
            return false;
        }
        readReceipt();
        return true;
    }

    boolean pressReconcile() {
        return press(btnReconcile);
    }

    void offerPendingReconciliation() {
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        if (!loaded.present()) {
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
            pinNote = GRANT_PIN_GUIDANCE;
            session.buttonsRefreshed().run();
            return;
        }
        session.buttonsRefreshed().run();
        reconcile();
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

    private void readReceipt() {
        if (!session.open().getAsBoolean()) {
            return;
        }
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        if (loaded.present()) {
            log.accept("[FAIL] GET_LAST_RECEIPT: còn journal "
                    + (loaded.valid() ? loaded.status() : "không hợp lệ")
                    + ", phải đối soát trước");
            session.buttonsRefreshed().run();
            return;
        }
        if (!pinEntered()) {
            return;
        }
        clearPinNote();
        char[] studentPin = session.studentPin().get();
        session.studentPinCleared().run();
        runner.run("VERIFY + GET_LAST_RECEIPT + GET_STATUS state", () -> {
            try {
                return gateway.readOperatorCard(studentPin);
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            session.receiptFinished().accept(ok);
            showReceipt();
        });
    }

    private void reconcile() {
        if (!session.open().getAsBoolean()) {
            return;
        }
        PendingJournal.Loaded loaded = session.pendingJournal().get();
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
        if (!pinEntered()) {
            return;
        }
        if (!confirmReconcile(entry)) {
            log.accept("[INFO] RECONCILE_PENDING: operator chưa xác nhận, journal vẫn được giữ");
            return;
        }
        clearPinNote();
        char[] studentPin = session.studentPin().get();
        session.studentPinCleared().run();
        AtomicReference<SupersededProof> proof = new AtomicReference<>();
        runner.run("RECONCILE_PENDING", () -> {
            session.historyInvalidated().run();
            try {
                CardOperationResult reconciled = session.reconcilePending().reconcile(studentPin);
                proof.set(reconciled.superseded());
                if (!reconciled.ok()) {
                    return reconciled;
                }
                CardOperationResult done = ConfirmedOperationRefresh.read(gateway, reconciled, studentPin);
                session.historyRemembered().run();
                return done;
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> finishReconcile(ok, proof.get()));
    }

    private void reconcileWithoutJournal() {
        AdminRecovery admin = session.adminRecovery();
        if (!admin.requirePin().getAsBoolean()) {
            pinNote = GRANT_PIN_GUIDANCE;
            UiLabels.setWhy(why, pinNote);
            return;
        }
        AtomicReference<StaffManualReconcile.Decision> decision = new AtomicReference<>();
        runner.run("ĐỐI SOÁT TAY", () -> {
            StaffManualReconcile.Decision found = admin.inspectManual().apply(admin.pin());
            decision.set(found);
            if (found.kind() == StaffManualReconcile.Kind.REJECTED) {
                return CardOperationResult.fail(found.detail());
            }
            return CardOperationResult.ok(found.detail());
        }, ok -> {
            StaffManualReconcile.Decision found = decision.get();
            if (found == null || found.kind() != StaffManualReconcile.Kind.NEEDS_CONFIRMATION) {
                admin.clearPin().run();
                finishReconcile(ok);
                return;
            }
            PasswordField again = confirmManual(found);
            admin.clearPin().run();
            if (again == null) {
                return;
            }
            Platform.runLater(() -> {
                boolean accepted = runner.run("GHI SỔ ĐỐI SOÁT TAY",
                        () -> admin.confirmManual().apply(found, () -> copyConfirmPin(again)),
                        posted -> {
                            again.clear();
                            finishReconcile(posted);
                        });
                if (!accepted) {
                    again.clear();
                    log.accept("[INFO] Đối soát tay: không nhận tác vụ, đã xóa PIN");
                }
            });
        });
    }

    private static char[] copyConfirmPin(PasswordField pin) {
        try {
            return FxPinSource.take(() -> takeConfirmPin(pin));
        } catch (RuntimeException failure) {
            Platform.runLater(pin::clear);
            throw failure;
        }
    }

    private static char[] takeConfirmPin(PasswordField pin) {
        char[] copy = pin.getText().toCharArray();
        pin.clear();
        return copy;
    }

    private PasswordField confirmManual(StaffManualReconcile.Decision decision) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Xác nhận ghi sổ");
        dialog.setHeaderText("Ghi receipt này vào sổ giao dịch?");
        PasswordField pin = new PasswordField();
        pin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        Label evidence = new Label(decision.detail() + "\nKhông gửi lệnh đổi trạng thái thẻ.");
        evidence.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(UiScale.SPACE_2, evidence,
                UiRows.field("PIN quản trị", pin)));
        ButtonType confirm = new ButtonType("Ghi sổ", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(cancel, confirm);
        UiLabels.attachDialog(dialog, node);
        try {
            if (dialog.showAndWait().filter(confirm::equals).isEmpty()) {
                log.accept("[INFO] Đối soát tay: nhân viên hủy, không ghi sổ");
                pin.clear();
                return null;
            }
            char[] probe = pin.getText().toCharArray();
            boolean missing = AdminGrantService.pinProblem(probe) != null;
            Arrays.fill(probe, '\0');
            if (missing) {
                pin.clear();
                log.accept("[INFO] Đối soát tay: thiếu PIN quản trị, không ghi sổ");
                return null;
            }
            return pin;
        } catch (RuntimeException failure) {
            pin.clear();
            throw failure;
        }
    }

    private void reconcileGrant(PendingJournal.Entry entry) {
        AdminRecovery admin = session.adminRecovery();
        if (!admin.requirePin().getAsBoolean()) {
            pinNote = GRANT_PIN_GUIDANCE;
            UiLabels.setWhy(why, pinNote);
            return;
        }
        if (!confirmReconcile(entry)) {
            admin.clearPin().run();
            log.accept("[INFO] RECONCILE_PENDING: operator chưa xác nhận, journal vẫn được giữ");
            return;
        }
        if (!session.open().getAsBoolean()) {
            admin.clearPin().run();
            return;
        }
        clearPinNote();
        AtomicReference<SupersededProof> proof = new AtomicReference<>();
        runner.run("RECONCILE_PENDING", () -> {
            session.historyInvalidated().run();
            CardOperationResult result = admin.reconcile().apply(admin.pin());
            proof.set(result.superseded());
            if (result.ok()) {
                session.historyRemembered().run();
            }
            return result;
        }, ok -> {
            admin.clearPin().run();
            finishReconcile(ok, proof.get());
        });
    }

    private void finishReconcile(boolean ok, SupersededProof proof) {
        finishReconcile(ok);
        if (proof != null) {
            Platform.runLater(() -> confirmSuperseded(proof));
        }
    }

    private void confirmSuperseded(SupersededProof proof) {
        if (!session.open().getAsBoolean()) {
            return;
        }
        int revision = cardSessionRevision;
        char[] pin = SupersededDialog.confirm(btnReconcile, proof, adminPinRetries);
        if (pin == null) {
            log.accept("[INFO] Nhân viên chưa xác nhận SUPERSEDED, journal được giữ");
            return;
        }
        if (!session.open().getAsBoolean() || cardSessionRevision != revision) {
            Arrays.fill(pin, '\0');
            return;
        }
        AtomicReference<SupersededRecovery.Confirmation> confirmed = new AtomicReference<>();
        runner.run("RECONCILE_PENDING", () -> {
            SupersededRecovery.Confirmation attempt = session.adminRecovery().confirmSuperseded().apply(proof, pin);
            confirmed.set(attempt);
            return attempt.result();
        }, ok -> {
            SupersededRecovery.Confirmation attempt = confirmed.get();
            CardStatus current = gateway.status();
            if (attempt != null && cardSessionRevision == revision && gateway.isReady() && current != null
                    && current.publicView().equals(proof.state().publicView())) {
                attempt.adminStatusWord().ifPresent(this::rememberAdminPinRetries);
            }
            finishReconcile(ok);
        });
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
        showReceipt();
        session.historyPublished().run();
    }

    private boolean confirmReconcile(PendingJournal.Entry entry) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận đối soát");
        confirmation.setHeaderText("Đọc biên nhận để xử lý nhật ký chờ gửi?");
        confirmation.setContentText(String.format(
                "Mã thẻ: %s%nSố giao dịch: %d%nThao tác: %s%n"
                        + "Hệ thống dùng PIN %s để đọc bằng chứng đối soát."
                        + " Không thực hiện lại giao dịch.",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation()),
                entry.operation() == Receipt.OPERATION_GRANT ? "quản trị" : "học viên"));
        return UiLabels.confirm(confirmation, node, "Đối soát giao dịch");
    }

    private static boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }
}
