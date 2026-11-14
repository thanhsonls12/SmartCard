package vn.labaccess.desktop;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class ReceiptPanel {

    @FunctionalInterface
    interface PendingReconcile {

        CardOperationResult reconcile(char[] studentPin) throws javax.smartcardio.CardException;
    }

    record Session(BooleanSupplier open, Supplier<PendingJournal.Loaded> pendingJournal,
            java.nio.file.Path journalFile,
            Supplier<char[]> studentPin, Runnable studentPinCleared,
            PendingReconcile reconcilePending, Runnable historyInvalidated,
            Runnable historyRemembered, Runnable historyPublished, Runnable buttonsRefreshed,
            Runnable headerReconcile, Consumer<Boolean> receiptFinished,
            Consumer<Boolean> reconcileFinished, Runnable pinRequired) {
    }

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Session session;

    private final TextField fieldVisitId = new TextField();
    private final ReceiptView receiptView = new ReceiptView();
    private boolean canRead;
    private final Button btnReconcile = new Button("Đối soát giao dịch", Icons.reconcile());
    private final Label why = UiLabels.whyNote();
    private String pinNote;
    private final VBox node;

    ReceiptPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Session session) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;

        fieldVisitId.setPrefColumnCount(34);
        fieldVisitId.setPromptText("Chưa đọc trạng thái ca");
        fieldVisitId.setEditable(false);

        btnReconcile.getStyleClass().add("la-action");
        btnReconcile.setOnAction(e -> {
            PendingJournal.Loaded loaded = session.pendingJournal().get();
            if (JournalRecoveryView.needed(loaded)) {
                JournalRecoveryView.show(btnReconcile, loaded, session.journalFile());
            } else {
                reconcile();
            }
        });

        VBox visitIdField = UiRows.field("Mã ca đang mở", fieldVisitId);
        FlowPane controls = UiRows.wrapping(visitIdField);
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

    String displayedVisitId() {
        return fieldVisitId.getText();
    }

    String reconcileState() {
        return btnReconcile.isDisable() ? "khoa" : "mo";
    }

    void applyLock(boolean busy, String whyReadReceipt, String whyReconcile) {
        canRead = whyReadReceipt == null;
        boolean recovery = JournalRecoveryView.needed(session.pendingJournal().get());
        btnReconcile.setText(recovery ? "Hướng dẫn khôi phục" : "Đối soát giao dịch");
        btnReconcile.setTooltip(new javafx.scene.control.Tooltip(recovery
                ? "Xem nguyên nhân và các bước xử lý nhật ký; không thay đổi dữ liệu"
                : whyReconcile == null ? "Đọc biên nhận để đối soát giao dịch" : whyReconcile));
        btnReconcile.setDisable(recovery ? busy : whyReconcile != null);
        UiLabels.setWhy(why, pinNote != null ? pinNote
                : busy ? App.BUSY_NOTE : null);
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
        fieldVisitId.clear();
        fieldVisitId.setPromptText("Chưa đọc trạng thái ca");
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
        fieldVisitId.setText(authenticated && status.hasActiveVisit()
                ? status.activeVisitId() : "");
        fieldVisitId.setPromptText(authenticated ? "Không có ca đang mở" : "Chưa đọc trạng thái ca");
        session.headerReconcile().run();
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
                gateway.clearAuthenticatedStatus();
                CardOperationResult keyReady = gateway.prepareReceiptVerification();
                if (!keyReady.ok()) {
                    return CardOperationResult.fail("public key: " + keyReady.text());
                }
                CardOperationResult verify = gateway.verifyStudentPin(studentPin);
                if (!verify.ok()) {
                    return CardOperationResult.fail("VERIFY: " + verify.text());
                }
                CardOperationResult read = gateway.readLastReceipt(null);
                String receiptEvidence;
                if (!read.ok() && gateway.lastResponseSw() == Sw.CONDITIONS_NOT_SATISFIED) {
                    receiptEvidence = "thẻ ISSUED chưa có receipt";
                } else if (!read.ok()) {
                    return read;
                } else {
                    receiptEvidence = read.text();
                }
                CardOperationResult state = gateway.readAuthenticatedStatus(studentPin);
                if (!state.ok()) {
                    return CardOperationResult.fail(receiptEvidence
                            + " | không nạp được state ca: " + state.text());
                }
                return CardOperationResult.ok(receiptEvidence + " | " + state.text());
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
        if (!loaded.present() || !loaded.valid()
                || loaded.status() != PendingJournal.Status.PENDING) {
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
        runner.run("RECONCILE_PENDING", () -> {
            session.historyInvalidated().run();
            try {
                CardOperationResult reconciled = session.reconcilePending().reconcile(studentPin);
                if (!reconciled.ok()) {
                    return reconciled;
                }
                CardOperationResult refreshed = gateway.readAuthenticatedStatus(studentPin);
                if (!refreshed.ok()) {
                    return CardOperationResult.fail(reconciled.text()
                            + " | state ca chưa được làm mới: " + refreshed.text());
                }
                session.historyRemembered().run();
                return CardOperationResult.ok(reconciled.text() + " | " + refreshed.text());
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            session.reconcileFinished().accept(ok);
            showReceipt();
            session.historyPublished().run();
        });
    }

    private boolean confirmReconcile(PendingJournal.Entry entry) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận đối soát");
        confirmation.setHeaderText("Đọc biên nhận để xử lý nhật ký chờ gửi?");
        confirmation.setContentText(String.format(
                "Mã thẻ: %s%nSố giao dịch: %d%nThao tác: %s%n"
                        + "Hệ thống xác thực PIN học viên và đọc biên nhận để đối chiếu."
                        + " Không thực hiện lại giao dịch.",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation())));
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
