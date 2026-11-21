package vn.labaccess.desktop;

import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

final class VisitPanel {

    record Actions(BooleanSupplier snapshotKnown, BooleanSupplier open, StateTransaction stateTransaction,
            Runnable historyInvalidated, Runnable historyRemembered, Consumer<Boolean> finished,
            Runnable studentPinChanged, Runnable stateReader, Runnable pinValidation) {
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
    private final Actions actions;
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label studentPinError = UiLabels.fieldError();
    private final Button btnReadState = new Button("Đọc thẻ");
    private final Label balance = UiLabels.value();
    private final Label visit = UiLabels.chip();
    private final Label stateNote = UiLabels.caption(
            "Đọc thẻ, mở/đóng ca dùng PIN học viên. Cấp lượt chỉ cần PIN quản trị; nhập lại PIN cho mỗi thao tác.");
    private final Button btnStart = new Button("Mở ca · −1 lượt");
    private final Button btnEnd = new Button("Đóng ca");
    private final Label visitPreview = UiLabels.caption("");
    private final FlowPane state;
    private final HBox buttons;

    VisitPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log, Actions actions) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.actions = actions;
        fieldStudentPin.setPrefColumnCount(8);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldStudentPin.textProperty().addListener((observable, was, now) -> {
            UiLabels.clearFieldError(studentPinError);
            actions.studentPinChanged().run();
        });
        fieldStudentPin.setOnAction(event -> {
            if (!actions.snapshotKnown().getAsBoolean() && !btnReadState.isDisabled()) {
                btnReadState.fire();
            } else if (!btnEnd.isDisabled()) {
                btnEnd.requestFocus();
            } else if (!btnStart.isDisabled()) {
                btnStart.requestFocus();
            }
        });
        for (Button action : new Button[] {btnReadState, btnStart, btnEnd}) {
            action.getStyleClass().add("la-action");
        }
        UiLabels.apduHint(btnStart,
                "START_VISIT\nCần nhận diện thẻ, chưa mở ca và còn lượt");
        UiLabels.apduHint(btnEnd, "END_VISIT\nCần nhận diện thẻ và đang có ca mở");
        btnReadState.setOnAction(event -> {
            if (requirePin()) {
                actions.stateReader().run();
            }
        });
        btnStart.setOnAction(event -> start());
        btnEnd.setOnAction(event -> end());
        balance.getStyleClass().add("la-balance-value");
        balance.setId("transaction-balance");
        visit.setId("transaction-visit");
        stateNote.setWrapText(true);
        state = UiRows.wrapping(
                UiRows.valueBlock("Số lượt còn lại", balance),
                UiRows.valueBlock("Ca hiện tại", visit),
                UiRows.field("PIN học viên · đọc thẻ, mở/đóng ca", fieldStudentPin, studentPinError), btnReadState);
        buttons = new HBox(UiScale.SPACE_2, btnStart, btnEnd);
        visitPreview.setId("visit-preview");
        visitPreview.setWrapText(true);
    }

    Node stateNode() { return state; }

    Node noteNode() { return stateNote; }

    Node actionsNode() { return buttons; }

    Node previewNode() { return visitPreview; }

    void fillInput(char[] studentPin) {
        fieldStudentPin.setText(new String(studentPin));
    }

    char[] pinInput() {
        return fieldStudentPin.getText().toCharArray();
    }

    void clearInput() {
        fieldStudentPin.clear();
        UiLabels.clearFieldError(studentPinError);
    }

    void clearPin() {
        fieldStudentPin.clear();
    }

    void prepareGrant() {
        UiLabels.clearFieldError(studentPinError);
        fieldStudentPin.clear();
    }

    boolean requirePin() {
        if (digits(fieldStudentPin.getText(), PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            return true;
        }
        UiLabels.setFieldError(studentPinError, PersonalizeRequest.studentPinProblem() + ".");
        UiLabels.inputProblem(log, fieldStudentPin, PersonalizeRequest.studentPinProblem() + ".");
        return false;
    }

    void focusPin() {
        if (!fieldStudentPin.isDisabled() && fieldStudentPin.getScene() != null) {
            fieldStudentPin.requestFocus();
        }
    }

    boolean showState(CardStatus status, boolean known, boolean grantAwaitingRead) {
        boolean available = known && status != null && status.stateAvailable();
        String balanceText = available ? status.balance() + " lượt"
                : grantAwaitingRead ? "Đã cấp — cần đọc lại" : "Chưa đọc";
        boolean changed = !balanceText.equals(balance.getText());
        balance.setText(balanceText);
        balance.getStyleClass().remove("la-state-unknown");
        if (!available) {
            balance.getStyleClass().add("la-state-unknown");
        }
        visit.setText(available ? status.hasActiveVisit() ? "Đang mở" : "Chưa mở" : "Chưa đọc");
        UiLabels.applyChip(visit, available && status.hasActiveVisit()
                ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
        return changed;
    }

    void showEffects(CardStatus status, boolean known) {
        boolean available = known && status != null && status.studentStateAvailable();
        visitPreview.setText(!available ? "Đọc thẻ để xem tác động của ca sử dụng."
                : status.hasActiveVisit() ? "Đóng ca không trừ thêm lượt."
                : status.balance() == 0 ? "Thẻ hết lượt. Cấp thêm lượt trước khi mở ca."
                : "Sau khi mở ca: còn " + (status.balance() - 1) + " lượt.");
    }

    void applyLock(String whyRead, String whyStart, String whyEnd) {
        btnReadState.setDisable(whyRead != null);
        btnStart.setDisable(whyStart != null);
        btnEnd.setDisable(whyEnd != null);
    }

    UiLabels.ActionReason readReason(String reason) {
        return UiLabels.actionReason(btnReadState, SessionGates.panelNote(reason));
    }

    UiLabels.ActionReason startReason(String reason) {
        return UiLabels.actionReason(btnStart, SessionGates.panelNote(reason));
    }

    UiLabels.ActionReason endReason(String reason) {
        return UiLabels.actionReason(btnEnd, SessionGates.panelNote(reason));
    }

    void lockAll() {
        btnReadState.setDisable(true);
        btnStart.setDisable(true);
        btnEnd.setDisable(true);
    }

    boolean markPrimary(boolean operations) {
        boolean needsState = operations && !actions.snapshotKnown().getAsBoolean() && !btnReadState.isDisable();
        UiLabels.markPrimary(btnReadState, needsState);
        operations = operations && !needsState;
        boolean canEnd = operations && !btnEnd.isDisable();
        boolean canStart = operations && !btnStart.isDisable();
        UiLabels.markPrimary(btnEnd, canEnd);
        UiLabels.markPrimary(btnStart, !canEnd && canStart);
        return operations && !canEnd && !canStart;
    }

    String startState() { return enabled(btnStart); }

    String endState() { return enabled(btnEnd); }

    boolean pressStart() { return press(btnStart); }

    boolean pressEnd() { return press(btnEnd); }

    private void start() {
        if (!studentPinValid()) {
            return;
        }
        CardStatus status = gateway.status();
        if (!actions.snapshotKnown().getAsBoolean()) {
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
        if (!validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            clearBuffers(studentPin);
            log.accept("[FAIL] mở ca: " + PersonalizeRequest.studentPinProblem());
            return;
        }
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), status.counter() + 1,
                Receipt.OPERATION_START, visitId);
        runTransaction("START_VISIT", studentPin, expectation, -1,
                status.balance() - 1, gateway.visitCommand((byte) 0x51, visitId));
    }

    private void end() {
        if (!studentPinValid()) {
            return;
        }
        CardStatus status = gateway.status();
        if (!actions.snapshotKnown().getAsBoolean()) {
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
        if (!validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            clearBuffers(studentPin);
            log.accept("[FAIL] đóng ca: " + PersonalizeRequest.studentPinProblem());
            return;
        }
        fieldStudentPin.clear();
        runTransaction("END_VISIT", studentPin, expectation, 0,
                status.balance(), gateway.visitCommand((byte) 0x52, visitId));
    }

    private void runTransaction(String label, char[] studentPin,
            ReceiptExpectation expectation, int delta, int expectedBalance,
            CardGateway.CommandSupplier supplier) {
        if (!validPin(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            clearBuffers(studentPin);
            log.accept("[FAIL] " + UiLabels.actionName(label) + ": " + PersonalizeRequest.studentPinProblem());
            return;
        }
        if (!actions.open().getAsBoolean()) {
            clearBuffers(studentPin);
            log.accept("[FAIL] " + label + ": đang xử lý thao tác khác hoặc đang đóng");
            return;
        }
        runner.run(label, () -> {
            actions.historyInvalidated().run();
            try {
                gateway.clearAuthenticatedStatus();
                CardOperationResult transaction = actions.stateTransaction().run(
                        new CardGateway.TransactionPlan(expectation, delta, expectedBalance),
                        supplier, studentPin);
                if (!transaction.ok()) {
                    return transaction;
                }
                CardOperationResult done = ConfirmedOperationRefresh.read(gateway, transaction, studentPin);
                actions.historyRemembered().run();
                return done;
            } finally {
                clearBuffers(studentPin);
            }
        }, actions.finished());
    }

    private byte[] newVisitId() {
        byte[] visitId = new byte[16];
        java.security.SecureRandom random = new java.security.SecureRandom();
        do {
            random.nextBytes(visitId);
        } while (allZero(visitId));
        return visitId;
    }

    private boolean studentPinValid() {
        boolean studentOk = digits(fieldStudentPin.getText(), PersonalizeRequest.STUDENT_PIN_LENGTH);
        UiLabels.setFieldError(studentPinError, studentOk ? null : PersonalizeRequest.studentPinProblem() + ".");
        actions.pinValidation().run();
        if (!studentOk) {
            UiLabels.inputProblem(log, fieldStudentPin, PersonalizeRequest.studentPinProblem());
        }
        return studentOk;
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

    private static boolean validPin(char[] pin, int length) {
        if (pin == null || pin.length != length) {
            return false;
        }
        for (char character : pin) {
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static void clearBuffers(char[] studentPin) {
        if (studentPin != null) {
            java.util.Arrays.fill(studentPin, '\0');
        }
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
