package vn.labaccess.desktop;

import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class TransactionPanel {

    record Session(BooleanSupplier snapshotKnown, BooleanSupplier open,
            StateTransaction stateTransaction, Runnable historyInvalidated,
            Runnable historyRemembered, Consumer<Boolean> finished) {
    }

    @FunctionalInterface
    interface StateTransaction {

        CardOperationResult run(CardGateway.TransactionPlan plan,
                CardGateway.CommandSupplier supplier, char[] studentPin)
                throws javax.smartcardio.CardException;
    }

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Session session;

    private final TextField fieldDelta = new TextField("5");
    private final PasswordField fieldStudentPin = new PasswordField();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final Button btnGrant = new Button("Cấp quota");
    private final Button btnStart = new Button("Mở ca");
    private final Button btnEnd = new Button("Đóng ca");
    private final Label why = App.whyNote();
    private final VBox node;

    TransactionPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Session session) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;

        fieldDelta.setPrefColumnCount(6);
        fieldStudentPin.setPrefColumnCount(8);
        fieldAdminPin.setPrefColumnCount(10);

        for (Button action : new Button[] {btnGrant, btnStart, btnEnd}) {
            action.getStyleClass().add("la-action");
        }
        UiLabels.apduHint(btnGrant,
                "GRANT_QUOTA\nCần nhận diện thẻ, không có biên nhận chờ đối soát");
        UiLabels.apduHint(btnStart,
                "START_VISIT\nCần nhận diện thẻ, chưa mở ca, số dư lớn hơn 0");
        UiLabels.apduHint(btnEnd, "END_VISIT\nCần nhận diện thẻ và đang có ca mở");

        btnGrant.setOnAction(e -> grant());
        btnStart.setOnAction(e -> start());
        btnEnd.setOnAction(e -> end());

        FlowPane controls = UiRows.wrapping(
                UiRows.field("PIN học viên", fieldStudentPin),
                UiRows.field("PIN quản trị", fieldAdminPin),
                UiRows.field("Số lượt thay đổi", fieldDelta),
                btnGrant, btnStart, btnEnd);
        node = UiSection.of("Giao dịch thẻ", controls, why);
    }

    VBox node() {
        return node;
    }

    void fillInputs(char[] studentPin, char[] adminPin, int delta) {
        fieldStudentPin.setText(new String(studentPin));
        fieldAdminPin.setText(adminPin == null ? "" : new String(adminPin));
        fieldDelta.setText(Integer.toString(delta));
    }

    char[] studentPinInput() {
        return fieldStudentPin.getText().toCharArray();
    }

    void clearStudentPin() {
        fieldStudentPin.clear();
    }

    void applyLock(boolean busy, String whyGrant, String whyStart, String whyEnd) {
        btnGrant.setDisable(whyGrant != null);
        btnStart.setDisable(whyStart != null);
        btnEnd.setDisable(whyEnd != null);
        App.setWhy(why, busy ? App.BUSY_NOTE
                : App.whyLine(btnGrant, whyGrant, btnStart, whyStart, btnEnd, whyEnd));
    }

    void lockAll() {
        btnGrant.setDisable(true);
        btnStart.setDisable(true);
        btnEnd.setDisable(true);
    }

    void markPrimary(boolean operations) {
        boolean canEnd = operations && !btnEnd.isDisable();
        boolean canStart = operations && !btnStart.isDisable();
        UiLabels.markPrimary(btnEnd, canEnd);
        UiLabels.markPrimary(btnStart, !canEnd && canStart);
        UiLabels.markPrimary(btnGrant, operations && !canEnd && !canStart && !btnGrant.isDisable());
    }

    String grantState() {
        return enabled(btnGrant);
    }

    String startState() {
        return enabled(btnStart);
    }

    String endState() {
        return enabled(btnEnd);
    }

    boolean pressGrant() {
        return press(btnGrant);
    }

    boolean pressStart() {
        return press(btnStart);
    }

    boolean pressEnd() {
        return press(btnEnd);
    }

    void logTransactionState(String label, String displayedVisitId) {
        CardStatus current = gateway.status();
        if (current == null || !current.studentStateAvailable()) {
            log.accept("UI-06 " + label + " | state unavailable | displayedVisitId="
                    + displayedVisitId);
            return;
        }
        log.accept(String.format("UI-06 %s | balance=%d counter=%d active=%s activeVisitId=%s"
                        + " displayedVisitId=%s receipt=%s",
                label, current.balance(), current.counter(), current.hasActiveVisit(),
                current.activeVisitId(), displayedVisitId,
                gateway.lastReceipt() == null ? "none"
                        : Receipt.operationText(gateway.lastReceipt().operation())));
    }

    String probeTransactionGate(String label) {
        CardStatus current = gateway.status();
        if (current == null || !current.studentStateAvailable()) {
            return label + " | khong-co-state-xac-thuc-de-dung-probe";
        }
        CardGateway.TransactionPlan plan = new CardGateway.TransactionPlan(
                new ReceiptExpectation(HexFormat.of().parseHex(current.cardId()),
                        current.counter() + 1, Receipt.OPERATION_GRANT, null),
                5, current.balance() + 5);
        try {
            CardOperationResult result = session.stateTransaction().run(plan, () -> {
                throw new IllegalStateException("probe: khong duoc gui APDU");
            }, null);
            return label + " | " + (result.ok() ? "MO (khong dat ky vong)" : "CHAN: " + result.text());
        } catch (javax.smartcardio.CardException ex) {
            return label + " | loi truyen: " + ex.getMessage();
        }
    }

    private void grant() {
        int delta;
        try {
            delta = Integer.parseInt(fieldDelta.getText().trim());
        } catch (NumberFormatException ex) {
            log.accept("[FAIL] GRANT_QUOTA: delta phải là số nguyên");
            return;
        }
        if (delta <= 0 || delta > 9999) {
            log.accept("[FAIL] GRANT_QUOTA: delta phải trong 1..9999");
            return;
        }
        if (!session.snapshotKnown().getAsBoolean()) {
            log.accept("[FAIL] GRANT_QUOTA: đọc state hiện tại bằng VERIFY + GET_LAST_RECEIPT trước");
            return;
        }
        CardStatus status = gateway.status();
        if (status == null || !status.studentStateAvailable()) {
            log.accept("[FAIL] GRANT_QUOTA: chưa có snapshot state student đã xác thực");
            return;
        }
        long counter = status.counter();
        if (counter >= Receipt.COUNTER_MASK) {
            log.accept("[FAIL] GRANT_QUOTA: counter đã đạt giới hạn");
            return;
        }
        int expectedBalance = status.balance() + delta;
        if (expectedBalance > Receipt.MAX_BALANCE) {
            log.accept("[FAIL] GRANT_QUOTA: balance dự kiến vượt 9999");
            return;
        }
        byte[] adminPin = pinToBytes(fieldAdminPin);
        if (adminPin == null) {
            log.accept("[FAIL] cấp quota: PIN quản trị phải đúng 8 chữ số");
            return;
        }
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!App.validPin(studentPin, 6)) {
            App.clearBuffers(studentPin, adminPin);
            log.accept("[FAIL] cấp quota: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), counter + 1,
                Receipt.OPERATION_GRANT, null);
        runTransaction("GRANT_QUOTA", studentPin, adminPin, expectation,
                delta, expectedBalance, gateway.grantCommand(adminPin, delta));
    }

    private void start() {
        CardStatus status = gateway.status();
        if (!session.snapshotKnown().getAsBoolean()) {
            log.accept("[FAIL] START_VISIT: đọc state hiện tại bằng VERIFY + GET_STATUS trước");
            return;
        }
        if (status == null || !status.studentStateAvailable()) {
            log.accept("[FAIL] START_VISIT: chưa có snapshot state student đã xác thực");
            return;
        }
        if (status.hasActiveVisit()) {
            log.accept("[FAIL] START_VISIT: đã có ca đang mở");
            return;
        }
        if (status.balance() <= 0) {
            log.accept("[FAIL] START_VISIT: balance hiện tại không còn lượt");
            return;
        }
        if (status.counter() >= Receipt.COUNTER_MASK) {
            log.accept("[FAIL] START_VISIT: counter đã đạt giới hạn");
            return;
        }
        byte[] visitId = newVisitId();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!App.validPin(studentPin, 6)) {
            App.clearBuffers(studentPin, null);
            log.accept("[FAIL] mở ca: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), status.counter() + 1,
                Receipt.OPERATION_START, visitId);
        runTransaction("START_VISIT", studentPin, null, expectation, -1,
                status.balance() - 1, gateway.visitCommand((byte) 0x51, visitId));
    }

    private void end() {
        CardStatus status = gateway.status();
        if (!session.snapshotKnown().getAsBoolean()) {
            log.accept("[FAIL] END_VISIT: đọc state hiện tại bằng VERIFY + GET_STATUS trước");
            return;
        }
        if (status == null || !status.studentStateAvailable()) {
            log.accept("[FAIL] END_VISIT: chưa có snapshot state student đã xác thực");
            return;
        }
        if (!status.hasActiveVisit()) {
            log.accept("[FAIL] END_VISIT: thẻ không có ca đang mở");
            return;
        }
        if (status.counter() >= Receipt.COUNTER_MASK) {
            log.accept("[FAIL] END_VISIT: counter đã đạt giới hạn");
            return;
        }
        byte[] visitId = HexFormat.of().parseHex(status.activeVisitId());
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), status.counter() + 1,
                Receipt.OPERATION_END, visitId);
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!App.validPin(studentPin, 6)) {
            App.clearBuffers(studentPin, null);
            log.accept("[FAIL] đóng ca: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldStudentPin.clear();
        runTransaction("END_VISIT", studentPin, null, expectation, 0,
                status.balance(), gateway.visitCommand((byte) 0x52, visitId));
    }

    private void runTransaction(String label, char[] studentPin, byte[] adminPin,
            ReceiptExpectation expectation, int delta, int expectedBalance,
            CardGateway.CommandSupplier supplier) {
        if (!App.validPin(studentPin, 6)) {
            App.clearBuffers(studentPin, adminPin);
            log.accept("[FAIL] " + UiLabels.actionName(label) + ": PIN học viên phải đúng 6 chữ số");
            return;
        }
        if (!session.open().getAsBoolean()) {
            App.clearBuffers(studentPin, adminPin);
            log.accept("[FAIL] " + label + ": đang xử lý thao tác khác hoặc đang đóng");
            return;
        }
        runner.run(label, () -> {
            session.historyInvalidated().run();
            try {
                gateway.clearAuthenticatedStatus();
                CardOperationResult verify = gateway.verifyStudentPin(studentPin);
                if (!verify.ok()) {
                    return CardOperationResult.fail(label + " không chạy — VERIFY: " + verify.text());
                }
                CardOperationResult transaction = session.stateTransaction().run(
                        new CardGateway.TransactionPlan(expectation, delta, expectedBalance),
                        supplier, studentPin);
                if (!transaction.ok()) {
                    return transaction;
                }
                CardOperationResult refreshed = gateway.readAuthenticatedStatus(studentPin);
                if (!refreshed.ok()) {
                    gateway.clearAuthenticatedStatus();
                    return CardOperationResult.fail(transaction.text()
                            + " | state ca chưa được làm mới: " + refreshed.text());
                }
                CardOperationResult done = CardOperationResult.ok(transaction.text()
                        + " | " + refreshed.text());
                session.historyRemembered().run();
                return done;
            } finally {
                App.clearBuffers(studentPin, adminPin);
            }
        }, session.finished());
    }

    private byte[] newVisitId() {
        byte[] visitId = new byte[16];
        java.security.SecureRandom random = new java.security.SecureRandom();
        do {
            random.nextBytes(visitId);
        } while (allZero(visitId));
        return visitId;
    }

    private static byte[] pinToBytes(PasswordField field) {
        String text = field.getText();
        if (text == null || text.length() != 8) {
            return null;
        }
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                java.util.Arrays.fill(bytes, (byte) 0);
                return null;
            }
            bytes[i] = (byte) c;
        }
        return bytes;
    }

    private static boolean allZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String enabled(Button button) {
        return button.isDisable() ? "khoa" : "mo";
    }

    private static boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }
}
