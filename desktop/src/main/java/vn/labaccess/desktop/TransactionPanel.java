package vn.labaccess.desktop;

import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class TransactionPanel {

    private static final int DELTA_MIN = 1;
    private static final int DELTA_MAX = Receipt.MAX_BALANCE;
    private static final int DELTA_DEFAULT = 5;
    private static final String DELTA_PROBLEM = "Số lượt thay đổi phải từ "
            + DELTA_MIN + " đến " + DELTA_MAX + ".";

    record Session(BooleanSupplier snapshotKnown, BooleanSupplier open,
            StateTransaction stateTransaction, Runnable historyInvalidated,
            Runnable historyRemembered, Consumer<Boolean> finished,
            Runnable studentPinChanged, Runnable stateReader) {
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

    private final Spinner<Integer> fieldDelta =
            new Spinner<>(DELTA_MIN, DELTA_MAX, DELTA_DEFAULT);
    private final PasswordField fieldStudentPin = new PasswordField();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final Label studentPinError = UiLabels.fieldError();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label deltaError = UiLabels.fieldError();
    private final Button btnReadState = new Button("Đọc trạng thái thẻ");
    private final Label balance = UiLabels.value();
    private final Label visit = UiLabels.chip();
    private final Label stateNote = UiLabels.caption("Mỗi lần đọc hoặc giao dịch cần nhập lại PIN học viên.");
    private final Button btnGrant = new Button("Cấp hạn mức");
    private final Button btnStart = new Button("Mở ca");
    private final Button btnEnd = new Button("Đóng ca");
    private final Label why = UiLabels.whyNote();
    private final VBox node;

    TransactionPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Session session) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;

        fieldDelta.setEditable(true);
        fieldDelta.getEditor().setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d{0,4}") ? change : null));
        fieldDelta.getEditor().setOnAction(event -> commitDelta());
        fieldDelta.focusedProperty().addListener((observable, was, now) -> {
            if (!now) {
                commitDelta();
            }
        });
        fieldStudentPin.setPrefColumnCount(8);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldAdminPin.setPrefColumnCount(10);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldStudentPin.textProperty().addListener((observable, was, now) -> {
            UiLabels.clearFieldError(studentPinError);
            session.studentPinChanged().run();
        });
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        fieldDelta.getEditor().textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(deltaError));

        for (Button action : new Button[] {btnReadState, btnGrant, btnStart, btnEnd}) {
            action.getStyleClass().add("la-action");
        }
        UiLabels.apduHint(btnGrant,
                "GRANT_QUOTA\nCần nhận diện thẻ, không có biên nhận chờ đối soát");
        UiLabels.apduHint(btnStart,
                "START_VISIT\nCần nhận diện thẻ, chưa mở ca, số dư lớn hơn 0");
        UiLabels.apduHint(btnEnd, "END_VISIT\nCần nhận diện thẻ và đang có ca mở");

        btnReadState.setOnAction(e -> {
            if (requireStudentPin()) {
                session.stateReader().run();
            }
        });
        btnGrant.setOnAction(e -> grant());
        btnStart.setOnAction(e -> start());
        btnEnd.setOnAction(e -> end());

        VBox studentField = UiRows.field("PIN học viên", fieldStudentPin, studentPinError);
        VBox adminField = UiRows.field("PIN quản trị", fieldAdminPin, adminPinError);

        balance.getStyleClass().add("la-balance-value");
        balance.setId("transaction-balance");
        visit.setId("transaction-visit");
        stateNote.setWrapText(true);
        FlowPane state = new FlowPane(UiScale.SPACE_5, UiScale.SPACE_2,
                UiRows.valueBlock("Số lượt còn lại", balance),
                UiRows.valueBlock("Ca hiện tại", visit));
        FlowPane studentActions = UiRows.wrapping(studentField, btnReadState, btnStart, btnEnd);
        FlowPane grantActions = UiRows.wrapping(adminField,
                UiRows.field("Số lượt cần cấp", fieldDelta, deltaError), btnGrant);
        VBox studentGroup = new VBox(UiScale.SPACE_2, UiLabels.caption("Sử dụng thẻ"),
                studentActions, stateNote);
        VBox grantGroup = new VBox(UiScale.SPACE_2, UiLabels.caption("Cấp lượt · quản trị"), grantActions);
        studentGroup.setPrefWidth(450);
        grantGroup.setPrefWidth(480);
        FlowPane actions = new FlowPane(UiScale.SPACE_5, UiScale.SPACE_4, studentGroup, grantGroup);
        node = UiSection.of("Vận hành thẻ", state, actions, why);
    }

    VBox node() {
        return node;
    }

    void fillInputs(char[] studentPin, char[] adminPin, int delta) {
        fieldStudentPin.setText(new String(studentPin));
        fieldAdminPin.setText(adminPin == null ? "" : new String(adminPin));
        fieldDelta.getValueFactory().setValue(delta);
    }

    char[] studentPinInput() {
        return fieldStudentPin.getText().toCharArray();
    }

    void clearInputs() {
        fieldStudentPin.clear();
        fieldAdminPin.clear();
        UiLabels.clearFieldError(studentPinError);
        UiLabels.clearFieldError(adminPinError);
        UiLabels.clearFieldError(deltaError);
    }

    void clearStudentPin() {
        fieldStudentPin.clear();
    }

    boolean requireStudentPin() {
        if (digits(fieldStudentPin.getText(), PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            return true;
        }
        UiLabels.setFieldError(studentPinError, PersonalizeRequest.studentPinProblem() + ".");
        UiLabels.inputProblem(log, fieldStudentPin, PersonalizeRequest.studentPinProblem() + ".");
        return false;
    }

    void showState(CardStatus status, boolean known) {
        boolean available = known && status != null && status.studentStateAvailable();
        String balanceText = available ? status.balance() + " lượt" : "Chưa đọc";
        if (!balanceText.equals(balance.getText())) {
            UiLabels.clearFieldError(deltaError);
        }
        balance.setText(balanceText);
        balance.getStyleClass().remove("la-state-unknown");
        if (!available) {
            balance.getStyleClass().add("la-state-unknown");
        }
        visit.setText(available ? status.hasActiveVisit() ? "Đang mở" : "Chưa mở" : "Chưa đọc");
        UiLabels.applyChip(visit, available && status.hasActiveVisit()
                ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
    }

    void applyLock(boolean busy, String whyGrant, String whyStart, String whyEnd, String whyRead) {
        btnReadState.setDisable(whyRead != null);
        btnGrant.setDisable(whyGrant != null);
        btnStart.setDisable(whyStart != null);
        btnEnd.setDisable(whyEnd != null);
        UiLabels.setWhy(why, busy ? App.BUSY_NOTE
                : UiLabels.whyLine(btnReadState, whyRead, btnGrant, whyGrant, btnStart, whyStart, btnEnd, whyEnd));
    }

    private boolean commitDelta() {
        String text = fieldDelta.getEditor().getText();
        boolean numeric = text.matches("\\d{1,4}");
        int typed = numeric ? Integer.parseInt(text) : 0;
        if (!numeric || typed < DELTA_MIN || typed > DELTA_MAX) {
            UiLabels.setFieldError(deltaError, DELTA_PROBLEM);
            return false;
        }
        UiLabels.clearFieldError(deltaError);
        fieldDelta.getValueFactory().setValue(typed);
        fieldDelta.getEditor().setText(String.valueOf(typed));
        return true;
    }

    void lockAll() {
        btnReadState.setDisable(true);
        btnGrant.setDisable(true);
        btnStart.setDisable(true);
        btnEnd.setDisable(true);
    }

    void markPrimary(boolean operations) {
        boolean needsState = operations && !session.snapshotKnown().getAsBoolean() && !btnReadState.isDisable();
        UiLabels.markPrimary(btnReadState, needsState);
        operations = operations && !needsState;
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
        boolean deltaOk = commitDelta();
        boolean pinsOk = pinsValid(true);
        if (!deltaOk || !pinsOk) {
            if (!deltaOk) {
                UiLabels.inputProblem(log, fieldDelta.getEditor(), DELTA_PROBLEM);
            }
            return;
        }
        int delta = fieldDelta.getValue();
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
            String problem = "Chỉ còn có thể cấp " + (Receipt.MAX_BALANCE - status.balance()) + " lượt.";
            UiLabels.setFieldError(deltaError, problem);
            UiLabels.inputProblem(log, fieldDelta.getEditor(), problem);
            return;
        }
        byte[] adminPin = pinToBytes(fieldAdminPin);
        if (adminPin == null) {
            log.accept("[FAIL] cấp quota: " + PersonalizeRequest.adminPinProblem());
            return;
        }
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!App.validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            App.clearBuffers(studentPin, adminPin);
            log.accept("[FAIL] cấp quota: " + PersonalizeRequest.studentPinProblem());
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
        if (!pinsValid(false)) {
            return;
        }
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
        if (!App.validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            App.clearBuffers(studentPin, null);
            log.accept("[FAIL] mở ca: " + PersonalizeRequest.studentPinProblem());
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
        if (!pinsValid(false)) {
            return;
        }
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
        if (!App.validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            App.clearBuffers(studentPin, null);
            log.accept("[FAIL] đóng ca: " + PersonalizeRequest.studentPinProblem());
            return;
        }
        fieldStudentPin.clear();
        runTransaction("END_VISIT", studentPin, null, expectation, 0,
                status.balance(), gateway.visitCommand((byte) 0x52, visitId));
    }

    private void runTransaction(String label, char[] studentPin, byte[] adminPin,
            ReceiptExpectation expectation, int delta, int expectedBalance,
            CardGateway.CommandSupplier supplier) {
        if (!App.validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            App.clearBuffers(studentPin, adminPin);
            log.accept("[FAIL] " + UiLabels.actionName(label) + ": " + PersonalizeRequest.studentPinProblem());
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
        if (text == null || text.length() != PersonalizeRequest.ADMIN_PIN_LENGTH) {
            return null;
        }
        byte[] bytes = new byte[PersonalizeRequest.ADMIN_PIN_LENGTH];
        for (int i = 0; i < PersonalizeRequest.ADMIN_PIN_LENGTH; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                java.util.Arrays.fill(bytes, (byte) 0);
                return null;
            }
            bytes[i] = (byte) c;
        }
        return bytes;
    }

    private boolean pinsValid(boolean needsAdminPin) {
        boolean studentOk = digits(fieldStudentPin.getText(),
                PersonalizeRequest.STUDENT_PIN_LENGTH);
        boolean adminOk = !needsAdminPin
                || digits(fieldAdminPin.getText(), PersonalizeRequest.ADMIN_PIN_LENGTH);
        UiLabels.setFieldError(studentPinError, studentOk ? null : PersonalizeRequest.studentPinProblem() + ".");
        UiLabels.setFieldError(adminPinError, adminOk ? null : PersonalizeRequest.adminPinProblem() + ".");
        if (!studentOk || !adminOk) {
            UiLabels.inputProblem(log, studentOk ? fieldAdminPin : fieldStudentPin,
                    studentOk ? PersonalizeRequest.adminPinProblem() : PersonalizeRequest.studentPinProblem());
        }
        return studentOk && adminOk;
    }

    private static boolean digits(String text, int length) {
        if (text == null || text.length() != length) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
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
