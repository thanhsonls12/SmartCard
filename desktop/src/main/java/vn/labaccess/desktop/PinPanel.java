package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class PinPanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final PasswordField fieldVerifyPin = new PasswordField();
    private final PasswordField fieldPin = new PasswordField();
    private final PasswordField fieldNewPin = new PasswordField();
    private final PasswordField fieldConfirmPin = new PasswordField();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldUnblockPin = new PasswordField();
    private final PasswordField fieldConfirmUnblockPin = new PasswordField();
    private final Label verifyError = UiLabels.fieldError();
    private final Label pinError = UiLabels.fieldError();
    private final Label newPinError = UiLabels.fieldError();
    private final Label confirmPinError = UiLabels.fieldError();
    private final Label adminError = UiLabels.fieldError();
    private final Label unblockError = UiLabels.fieldError();
    private final Label confirmUnblockError = UiLabels.fieldError();
    private final Button btnVerify = new Button("Xác thực PIN");
    private final Button btnChange = new Button("Đổi PIN");
    private final Button btnUnblock = new Button("Mở khóa PIN");
    private final Label valueState = new Label();
    private final Label why = UiLabels.whyNote();
    private final VBox node;

    PinPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        configure(fieldVerifyPin, verifyError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        configure(fieldPin, pinError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        configure(fieldNewPin, newPinError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        configure(fieldConfirmPin, confirmPinError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        configure(fieldAdminPin, adminError, PersonalizeRequest.ADMIN_PIN_LENGTH);
        configure(fieldUnblockPin, unblockError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        configure(fieldConfirmUnblockPin, confirmUnblockError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        fieldNewPin.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(confirmPinError));
        fieldUnblockPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(confirmUnblockError));
        btnVerify.setOnAction(e -> verify());
        btnChange.setOnAction(e -> change());
        btnUnblock.setOnAction(e -> unblock());
        UiLabels.apduHint(btnVerify, "VERIFY_STUDENT_PIN");
        UiLabels.apduHint(btnChange, "CHANGE_STUDENT_PIN");
        UiLabels.apduHint(btnUnblock, "UNBLOCK_STUDENT_PIN");
        for (Button button : new Button[] {btnVerify, btnChange, btnUnblock}) {
            button.getStyleClass().add("la-action");
        }
        valueState.getStyleClass().add("la-note");
        valueState.setWrapText(true);
        UiLabels.applyNoteTone(valueState, UiLabels.Tone.NEUTRAL);

        FlowPane verify = UiRows.wrapping(
                UiRows.field("PIN học viên", fieldVerifyPin, verifyError), btnVerify);
        FlowPane change = UiRows.wrapping(
                UiRows.field("PIN hiện tại", fieldPin, pinError),
                UiRows.field("PIN mới", fieldNewPin, newPinError),
                UiRows.field("Nhập lại PIN mới", fieldConfirmPin, confirmPinError), btnChange);
        FlowPane unblock = UiRows.wrapping(
                UiRows.field("PIN quản trị", fieldAdminPin, adminError),
                UiRows.field("PIN mới sau mở khóa", fieldUnblockPin, unblockError),
                UiRows.field("Nhập lại PIN mới", fieldConfirmUnblockPin, confirmUnblockError), btnUnblock);
        VBox groups = new VBox(UiScale.SPACE_4,
                group("Kiểm tra PIN", verify), group("Đổi PIN", change),
                group("Mở khóa PIN", unblock));
        node = UiSection.of("Bảo mật PIN học viên", groups, why,
                UiRows.valueBlock("Trạng thái PIN", valueState));
    }

    private static void configure(PasswordField field, Label error, int length) {
        field.setPrefColumnCount(length + 2);
        field.setPromptText(length + " chữ số");
        field.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(error));
    }

    private static VBox group(String title, FlowPane controls) {
        Label label = new Label(title);
        label.getStyleClass().add("la-title");
        return new VBox(UiScale.SPACE_2, label, controls);
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled, String whyDisabled) {
        for (PasswordField field : new PasswordField[] {
                fieldVerifyPin, fieldPin, fieldNewPin, fieldConfirmPin,
                fieldAdminPin, fieldUnblockPin, fieldConfirmUnblockPin}) {
            field.setDisable(!enabled);
        }
        btnVerify.setDisable(!enabled);
        btnChange.setDisable(!enabled);
        btnUnblock.setDisable(!enabled);
        UiLabels.setWhy(why, whyDisabled);
    }

    void clearInputs() {
        for (PasswordField field : new PasswordField[] {
                fieldVerifyPin, fieldPin, fieldNewPin, fieldConfirmPin,
                fieldAdminPin, fieldUnblockPin, fieldConfirmUnblockPin}) {
            field.clear();
        }
        for (Label error : new Label[] {
                verifyError, pinError, newPinError, confirmPinError, adminError, unblockError, confirmUnblockError}) {
            UiLabels.clearFieldError(error);
        }
    }

    void clearState() {
        clearInputs();
        showValue(PinState.unknown().text(), UiLabels.Tone.NEUTRAL);
    }

    void resetState(String reason) {
        clearInputs();
        PinState state = PinState.unknown();
        showValue(state.text(), UiLabels.Tone.NEUTRAL);
        log.accept("GUI hiển thị PIN = " + state.text() + " | " + reason);
    }

    private void showValue(String text, UiLabels.Tone tone) {
        valueState.setText(text);
        UiLabels.applyNoteTone(valueState, tone);
    }

    boolean pressVerify() {
        return press(btnVerify);
    }

    boolean pressChange() {
        return press(btnChange);
    }

    boolean pressUnblock() {
        return press(btnUnblock);
    }

    void fillInputs(char[] pin, char[] newPin, char[] adminPin) {
        if (pin != null) {
            fieldPin.setText(new String(pin));
            fieldVerifyPin.setText(new String(pin));
        }
        if (newPin != null) {
            fieldNewPin.setText(new String(newPin));
            fieldUnblockPin.setText(new String(newPin));
            fieldConfirmPin.setText(new String(newPin));
            fieldConfirmUnblockPin.setText(new String(newPin));
        }
        if (adminPin != null) {
            fieldAdminPin.setText(new String(adminPin));
        }
    }

    private static boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }

    private boolean validField(PasswordField field, Label error, int length) {
        char[] pin = field.getText().toCharArray();
        boolean valid = App.validPin(pin, length);
        Arrays.fill(pin, '\0');
        String problem = length == PersonalizeRequest.ADMIN_PIN_LENGTH
                ? PersonalizeRequest.adminPinProblem() : PersonalizeRequest.studentPinProblem();
        UiLabels.setFieldError(error, valid ? null : problem + ".");
        if (!valid) {
            UiLabels.inputProblem(log, field, problem + ".");
        }
        return valid;
    }

    private void verify() {
        if (!validField(fieldVerifyPin, verifyError, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            return;
        }
        char[] pin = fieldVerifyPin.getText().toCharArray();
        fieldVerifyPin.clear();
        runner.run("VERIFY_STUDENT_PIN", () -> {
            try {
                return gateway.verifyStudentPin(pin);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }, ok -> showState(PinState.Operation.VERIFY, ok));
    }

    private void change() {
        if (!validField(fieldPin, pinError, PersonalizeRequest.STUDENT_PIN_LENGTH)
                || !validField(fieldNewPin, newPinError, PersonalizeRequest.STUDENT_PIN_LENGTH)
                || !confirmed(fieldNewPin, fieldConfirmPin, confirmPinError)) {
            return;
        }
        char[] oldPin = fieldPin.getText().toCharArray();
        char[] newPin = fieldNewPin.getText().toCharArray();
        fieldPin.clear();
        fieldNewPin.clear();
        fieldConfirmPin.clear();
        runner.run("CHANGE_STUDENT_PIN", () -> {
            try {
                return gateway.changeStudentPin(oldPin, newPin);
            } finally {
                Arrays.fill(oldPin, '\0');
                Arrays.fill(newPin, '\0');
            }
        }, ok -> showState(PinState.Operation.CHANGE, ok));
    }

    private void unblock() {
        if (!validField(fieldAdminPin, adminError, PersonalizeRequest.ADMIN_PIN_LENGTH)
                || !validField(fieldUnblockPin, unblockError, PersonalizeRequest.STUDENT_PIN_LENGTH)
                || !confirmed(fieldUnblockPin, fieldConfirmUnblockPin, confirmUnblockError)) {
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] newPin = fieldUnblockPin.getText().toCharArray();
        fieldAdminPin.clear();
        fieldUnblockPin.clear();
        fieldConfirmUnblockPin.clear();
        runner.run("UNBLOCK_STUDENT_PIN", () -> {
            try {
                return gateway.unblockStudentPin(adminPin, newPin);
            } finally {
                Arrays.fill(adminPin, '\0');
                Arrays.fill(newPin, '\0');
            }
        }, ok -> showState(PinState.Operation.UNBLOCK, ok));
    }

    private boolean confirmed(PasswordField pinField, PasswordField confirmation, Label error) {
        char[] pin = pinField.getText().toCharArray();
        char[] repeated = confirmation.getText().toCharArray();
        boolean matches;
        try {
            matches = Arrays.equals(pin, repeated);
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(repeated, '\0');
        }
        String problem = matches ? null : "PIN nhập lại chưa khớp. Nhập lại PIN mới.";
        UiLabels.setFieldError(error, matches ? null : "PIN chưa khớp.");
        if (!matches) {
            confirmation.requestFocus();
            log.accept("[FAIL] " + problem);
        }
        return matches;
    }

    private void showState(PinState.Operation operation, boolean ok) {
        int sw = gateway.lastPinSw();
        PinState state = PinState.of(operation, ok, sw);
        showValue(state.text(), PinState.tone(ok, sw));
        log.accept("GUI hiển thị PIN = " + state.text());
    }
}
