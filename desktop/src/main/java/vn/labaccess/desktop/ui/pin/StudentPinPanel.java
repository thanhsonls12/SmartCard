package vn.labaccess.desktop.ui.pin;

import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.PersonalizeRequest;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.PinOperation;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

final class StudentPinPanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Consumer<PinState.Outcome> finished;
    private final PasswordField fieldVerifyPin = new PasswordField();
    private final PasswordField fieldPin = new PasswordField();
    private final PasswordField fieldNewPin = new PasswordField();
    private final PasswordField fieldConfirmPin = new PasswordField();
    private final Label verifyError = UiLabels.fieldError();
    private final Label pinError = UiLabels.fieldError();
    private final Label newPinError = UiLabels.fieldError();
    private final Label confirmPinError = UiLabels.fieldError();
    private final Button btnVerify = new Button("Xác thực PIN");
    private final Button btnChange = new Button("Đổi PIN");
    private final FlowPane verifyNode;
    private final FlowPane changeNode;

    StudentPinPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Consumer<PinState.Outcome> finished) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.finished = finished;
        PinForm.configure(fieldVerifyPin, verifyError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        PinForm.configure(fieldPin, pinError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        PinForm.configure(fieldNewPin, newPinError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        PinForm.configure(fieldConfirmPin, confirmPinError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        fieldNewPin.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(confirmPinError));
        btnVerify.setOnAction(event -> verify());
        btnChange.setOnAction(event -> change());
        UiLabels.apduHint(btnVerify, "VERIFY_STUDENT_PIN");
        UiLabels.apduHint(btnChange, "CHANGE_STUDENT_PIN");
        btnVerify.getStyleClass().add("la-action");
        btnChange.getStyleClass().add("la-action");
        verifyNode = UiRows.wrapping(UiRows.field("PIN sinh viên", fieldVerifyPin, verifyError), btnVerify);
        changeNode = UiRows.wrapping(
                UiRows.field("PIN hiện tại", fieldPin, pinError),
                UiRows.field("PIN mới", fieldNewPin, newPinError),
                UiRows.field("Nhập lại PIN mới", fieldConfirmPin, confirmPinError), btnChange);
    }

    Node verifyNode() {
        return verifyNode;
    }

    Node changeNode() {
        return changeNode;
    }

    void setEnabled(boolean enabled) {
        for (PasswordField field : new PasswordField[] {fieldVerifyPin, fieldPin, fieldNewPin, fieldConfirmPin}) {
            field.setDisable(!enabled);
        }
        btnVerify.setDisable(!enabled);
        btnChange.setDisable(!enabled);
    }

    void clearInputs() {
        for (PasswordField field : new PasswordField[] {fieldVerifyPin, fieldPin, fieldNewPin, fieldConfirmPin}) {
            field.clear();
        }
        for (Label error : new Label[] {verifyError, pinError, newPinError, confirmPinError}) {
            UiLabels.clearFieldError(error);
        }
    }

    void fillCurrentPin(char[] pin) {
        fieldPin.setText(new String(pin));
        fieldVerifyPin.setText(new String(pin));
    }

    void fillNewPin(char[] pin) {
        fieldNewPin.setText(new String(pin));
    }

    void fillConfirmation(char[] pin) {
        fieldConfirmPin.setText(new String(pin));
    }

    boolean pressVerify() {
        if (btnVerify.isDisabled()) {
            return false;
        }
        btnVerify.fire();
        return true;
    }

    boolean pressChange() {
        if (btnChange.isDisabled()) {
            return false;
        }
        btnChange.fire();
        return true;
    }

    private void verify() {
        if (!PinForm.validField(fieldVerifyPin, verifyError, PersonalizeRequest.STUDENT_PIN_LENGTH, log)) {
            return;
        }
        char[] pin = fieldVerifyPin.getText().toCharArray();
        fieldVerifyPin.clear();
        PinOperation<CardOperationResult> operation = PinOperation.of(() -> gateway.verifyStudentPin(pin), pin);
        boolean accepted = false;
        try {
            accepted = runner.run("VERIFY_STUDENT_PIN", operation, ok -> report(PinState.Operation.VERIFY, ok));
        } finally {
            if (!accepted) {
                PinOperation.discard(operation);
            }
        }
    }

    private void change() {
        if (!PinForm.validField(fieldPin, pinError, PersonalizeRequest.STUDENT_PIN_LENGTH, log)
                || !PinForm.validField(fieldNewPin, newPinError, PersonalizeRequest.STUDENT_PIN_LENGTH, log)
                || !PinForm.confirmed(fieldNewPin, fieldConfirmPin, confirmPinError, log)) {
            return;
        }
        char[] oldPin = fieldPin.getText().toCharArray();
        char[] newPin = fieldNewPin.getText().toCharArray();
        fieldPin.clear();
        fieldNewPin.clear();
        fieldConfirmPin.clear();
        PinOperation<CardOperationResult> operation = PinOperation.of(
                () -> gateway.changeStudentPin(oldPin, newPin), oldPin, newPin);
        boolean accepted = false;
        try {
            accepted = runner.run("CHANGE_STUDENT_PIN", operation,
                    ok -> report(PinState.Operation.CHANGE, ok));
        } finally {
            if (!accepted) {
                PinOperation.discard(operation);
            }
        }
    }

    private void report(PinState.Operation operation, boolean ok) {
        finished.accept(new PinState.Outcome(operation, ok, gateway.lastPinSw()));
    }
}
