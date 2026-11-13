package vn.labaccess.desktop;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class ReceiptPanel {

    @FunctionalInterface
    interface PendingReconcile {

        CardOperationResult reconcile(char[] studentPin) throws javax.smartcardio.CardException;
    }

    record Session(BooleanSupplier open, Supplier<PendingJournal.Loaded> pendingJournal,
            Supplier<char[]> studentPin, Runnable studentPinCleared,
            PendingReconcile reconcilePending, Runnable historyInvalidated,
            Runnable historyRemembered, Runnable historyPublished, Runnable buttonsRefreshed,
            Runnable headerReconcile, Consumer<Boolean> receiptFinished,
            Consumer<Boolean> reconcileFinished) {
    }

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Session session;

    private final TextField fieldVisitId = new TextField();
    private final ReceiptView receiptView = new ReceiptView();
    private final Button btnReadReceipt = new Button("Đọc biên nhận cuối", Icons.receipt());
    private final Button btnReconcile = new Button("Đối soát giao dịch", Icons.reconcile());
    private final Label why = App.whyNote();
    private final VBox node;

    ReceiptPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Session session) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;

        fieldVisitId.setPrefColumnCount(34);
        fieldVisitId.setPromptText("32 ký tự hex của ca đang mở");
        fieldVisitId.setEditable(false);

        btnReadReceipt.getStyleClass().add("la-action");
        btnReconcile.getStyleClass().add("la-action");
        UiLabels.apduHint(btnReadReceipt,
                "VERIFY_STUDENT_PIN + GET_LAST_RECEIPT\nCần nhận diện thẻ và không có biên nhận chờ đối soát");
        UiLabels.apduHint(btnReconcile, "RECONCILE_PENDING\nCần có biên nhận chờ đối soát");
        btnReadReceipt.setOnAction(e -> readReceipt());
        btnReconcile.setOnAction(e -> reconcile());

        FlowPane controls = UiRows.wrapping(
                UiRows.field("Visit ID đang mở", fieldVisitId), btnReadReceipt);
        node = UiSection.of("Biên nhận", controls, why, receiptView.node());
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

    String reconcileLockReason(String whyReconcile) {
        return whyReconcile != null && btnReconcile.isDisable()
                ? btnReconcile.getText() + ": " + whyReconcile
                : null;
    }

    void applyLock(boolean busy, String whyReadReceipt, String whyReconcile) {
        btnReadReceipt.setDisable(whyReadReceipt != null);
        btnReconcile.setDisable(whyReconcile != null);
        App.setWhy(why, busy ? App.BUSY_NOTE
                : App.whyLine(btnReadReceipt, whyReadReceipt));
    }

    void lockAll() {
        btnReadReceipt.setDisable(true);
        btnReconcile.setDisable(true);
    }

    void clear() {
        fieldVisitId.clear();
        receiptView.clear("Chưa đọc biên nhận từ thẻ");
    }

    void showReceipt() {
        Receipt receipt = gateway.lastReceipt();
        CardStatus status = gateway.status();
        boolean authenticated = status != null && status.studentStateAvailable();
        if (receipt == null) {
            receiptView.clear(authenticated ? "Chưa có biên nhận" : "Chưa đọc biên nhận từ thẻ");
        } else {
            receiptView.show(receipt, gateway.lastReceiptSignatureChecked(),
                    gateway.lastReceiptSignatureOk());
        }
        fieldVisitId.setText(authenticated && status.hasActiveVisit()
                ? status.activeVisitId() : "");
        session.headerReconcile().run();
        if (!gateway.reconciliation().detail().isEmpty()) {
            log.accept("Đối soát: " + gateway.reconciliation().detail());
        }
    }

    boolean pressReadReceipt() {
        return press(btnReadReceipt);
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
            gateway.markTransactionUnknown("pending journal cần xử lý thủ công: " + loaded.problem());
            log.accept("[FAIL] Pending journal: " + loaded.problem() + " — khóa giao dịch");
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
        char[] studentPin = session.studentPin().get();
        if (!App.validPin(studentPin, 6)) {
            App.clearBuffers(studentPin, null);
            log.accept("[FAIL] đọc biên nhận: PIN học viên phải đúng 6 chữ số");
            return;
        }
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
        PendingJournal.Entry cardEntry = loaded.entry();
        String identifiedCard = identifiedCardId();
        if (!cardEntry.cardId().equalsIgnoreCase(identifiedCard)) {
            blockReconcileOnDifferentCard(cardEntry, identifiedCard);
            return;
        }
        PendingJournal.Entry entry = loaded.entry();
        javafx.scene.control.Alert confirmation = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận đối soát");
        confirmation.setHeaderText("Đọc receipt để xử lý pending journal?");
        confirmation.setContentText(String.format(
                "cardId=%s, counter=%d, operation=%s%n"
                        + "Hệ thống sẽ VERIFY student PIN và đọc GET_LAST_RECEIPT. Không gửi lại APDU đổi state.",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation())));
        java.util.Optional<javafx.scene.control.ButtonType> choice = confirmation.showAndWait();
        if (choice.isEmpty() || choice.get() != javafx.scene.control.ButtonType.OK) {
            log.accept("[INFO] RECONCILE_PENDING: operator chưa xác nhận, journal vẫn được giữ");
            return;
        }

        char[] studentPin = session.studentPin().get();
        if (!App.validPin(studentPin, 6)) {
            App.clearBuffers(studentPin, null);
            log.accept("[FAIL] đối soát giao dịch: PIN học viên phải đúng 6 chữ số");
            return;
        }
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

    private static boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }
}
