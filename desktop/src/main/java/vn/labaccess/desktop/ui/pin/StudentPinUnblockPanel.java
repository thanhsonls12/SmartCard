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

public final class StudentPinUnblockPanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Consumer<PinState.Outcome> finished;
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldUnblockPin = new PasswordField();
    private final PasswordField fieldConfirmUnblockPin = new PasswordField();
    private final Label adminError = UiLabels.fieldError();
    private final Label unblockError = UiLabels.fieldError();
    private final Label confirmUnblockError = UiLabels.fieldError();
    private final Button btnUnblock = new Button("Mở khóa PIN");
    private final FlowPane node;
    private static final String READY_NOTE =
            "Chưa mở khóa — nhập PIN quản trị và PIN sinh viên mới để thực hiện.";
    private final Label feedback = UiLabels.caption(READY_NOTE);

    public StudentPinUnblockPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Consumer<PinState.Outcome> finished) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.finished = finished;
        PinForm.configure(fieldAdminPin, adminError, PersonalizeRequest.ADMIN_PIN_LENGTH);
        PinForm.configure(fieldUnblockPin, unblockError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        PinForm.configure(fieldConfirmUnblockPin, confirmUnblockError, PersonalizeRequest.STUDENT_PIN_LENGTH);
        fieldUnblockPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(confirmUnblockError));
        btnUnblock.setOnAction(event -> unblock());
        UiLabels.apduHint(btnUnblock, "UNBLOCK_STUDENT_PIN");
        btnUnblock.getStyleClass().add("la-action");
        feedback.getStyleClass().add("la-note");
        feedback.setWrapText(true);
        UiLabels.applyNoteTone(feedback, UiLabels.Tone.NEUTRAL);
        node = UiRows.wrapping(
                UiRows.field("PIN quản trị", fieldAdminPin, adminError),
                UiRows.field("PIN mới sau mở khóa", fieldUnblockPin, unblockError),
                UiRows.field("Nhập lại PIN mới", fieldConfirmUnblockPin, confirmUnblockError), btnUnblock);
    }

    public Node node() {
        return node;
    }

    public Node feedbackNode() {
        return feedback;
    }

    public void resetFeedback() {
        feedback.setText(READY_NOTE);
        UiLabels.applyNoteTone(feedback, UiLabels.Tone.NEUTRAL);
    }

    public void setEnabled(boolean enabled) {
        for (PasswordField field : new PasswordField[] {fieldAdminPin, fieldUnblockPin, fieldConfirmUnblockPin}) {
            field.setDisable(!enabled);
        }
        btnUnblock.setDisable(!enabled);
    }

    public void clearInputs() {
        for (PasswordField field : new PasswordField[] {fieldAdminPin, fieldUnblockPin, fieldConfirmUnblockPin}) {
            field.clear();
        }
        for (Label error : new Label[] {adminError, unblockError, confirmUnblockError}) {
            UiLabels.clearFieldError(error);
        }
    }

    public void fillAdminPin(char[] pin) {
        fieldAdminPin.setText(new String(pin));
    }

    public void fillNewPin(char[] pin) {
        fieldUnblockPin.setText(new String(pin));
    }

    public void fillConfirmation(char[] pin) {
        fieldConfirmUnblockPin.setText(new String(pin));
    }

    public boolean pressUnblock() {
        if (btnUnblock.isDisabled()) {
            return false;
        }
        btnUnblock.fire();
        return true;
    }

    private void unblock() {
        if (!PinForm.validField(fieldAdminPin, adminError, PersonalizeRequest.ADMIN_PIN_LENGTH, log)
                || !PinForm.validField(fieldUnblockPin, unblockError, PersonalizeRequest.STUDENT_PIN_LENGTH, log)
                || !PinForm.confirmed(fieldUnblockPin, fieldConfirmUnblockPin, confirmUnblockError, log)) {
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] newPin = fieldUnblockPin.getText().toCharArray();
        fieldAdminPin.clear();
        fieldUnblockPin.clear();
        fieldConfirmUnblockPin.clear();
        PinOperation<CardOperationResult> operation = PinOperation.of(
                () -> gateway.unblockStudentPin(adminPin, newPin), adminPin, newPin);
        boolean accepted = false;
        try {
            accepted = runner.run("UNBLOCK_STUDENT_PIN", operation, ok -> {
                PinState.Outcome outcome = new PinState.Outcome(PinState.Operation.UNBLOCK, ok, gateway.lastPinSw());
                feedback.setText(PinState.of(outcome.operation(), outcome.ok(), outcome.sw()).text());
                UiLabels.applyNoteTone(feedback, PinState.tone(outcome.ok(), outcome.sw()));
                finished.accept(outcome);
            });
        } finally {
            if (!accepted) {
                PinOperation.discard(operation);
            }
        }
    }
}
