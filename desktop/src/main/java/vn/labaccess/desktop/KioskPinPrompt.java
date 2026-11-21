package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

final class KioskPinPrompt {

    private static final int PIN_LENGTH = 6;

    private final Consumer<Request> submitted;
    private final Runnable cancelled;
    private final Consumer<String> log;
    private final Label actionTitle = new Label();
    private final PasswordField pin = new PasswordField();
    private final PasswordField newPin = new PasswordField();
    private final PasswordField confirmation = new PasswordField();
    private final Label pinError = UiLabels.fieldError();
    private final Label newPinError = UiLabels.fieldError();
    private final Label confirmationError = UiLabels.fieldError();
    private final Button submit = new Button("Xác nhận");
    private final Button cancel = new Button("Hủy");
    private final VBox newPinRow;
    private final VBox confirmationRow;
    private final VBox root;
    private KioskOperations.Action action;

    KioskPinPrompt(Consumer<Request> submitted, Runnable cancelled, Consumer<String> log) {
        this.submitted = submitted;
        this.cancelled = cancelled;
        this.log = log;
        PinForm.configure(pin, pinError, PIN_LENGTH);
        PinForm.configure(newPin, newPinError, PIN_LENGTH);
        PinForm.configure(confirmation, confirmationError, PIN_LENGTH);
        newPinRow = UiRows.field("PIN mới", newPin, newPinError);
        confirmationRow = UiRows.field("Nhập lại PIN mới", confirmation, confirmationError);
        submit.getStyleClass().add("la-action");
        cancel.getStyleClass().add("la-action");
        submit.setOnAction(event -> fireSubmit());
        cancel.setOnAction(event -> {
            clear();
            this.cancelled.run();
        });
        pin.setOnAction(event -> fireSubmit());
        newPin.setOnAction(event -> fireSubmit());
        confirmation.setOnAction(event -> fireSubmit());
        root = UiSection.of("PIN học viên", actionTitle,
                UiRows.field("PIN sinh viên", pin, pinError), newPinRow, confirmationRow,
                UiRows.wrapping(submit, cancel));
        clear();
    }

    VBox node() {
        return root;
    }

    void open(KioskOperations.Action requestedAction) {
        clear();
        action = requestedAction;
        actionTitle.setText(action.label());
        boolean changingPin = action == KioskOperations.Action.CHANGE;
        newPinRow.setVisible(changingPin);
        newPinRow.setManaged(changingPin);
        confirmationRow.setVisible(changingPin);
        confirmationRow.setManaged(changingPin);
        root.setVisible(true);
        root.setManaged(true);
        pin.requestFocus();
    }

    void clear() {
        pin.clear();
        newPin.clear();
        confirmation.clear();
        UiLabels.clearFieldError(pinError);
        UiLabels.clearFieldError(newPinError);
        UiLabels.clearFieldError(confirmationError);
        actionTitle.setText("");
        action = null;
        root.setVisible(false);
        root.setManaged(false);
    }

    void setBusy(boolean busy) {
        pin.setDisable(busy);
        newPin.setDisable(busy);
        confirmation.setDisable(busy);
        submit.setDisable(busy);
        cancel.setDisable(busy);
    }

    boolean isOpen() {
        return root.isVisible();
    }

    private void fireSubmit() {
        if (submit.isDisabled() || !isOpen() || action == null) {
            return;
        }
        if (!PinForm.validField(pin, pinError, PIN_LENGTH, log)) {
            return;
        }
        boolean changingPin = action == KioskOperations.Action.CHANGE;
        if (changingPin && (!PinForm.validField(newPin, newPinError, PIN_LENGTH, log)
                || !PinForm.confirmed(newPin, confirmation, confirmationError, log))) {
            return;
        }
        Request request = new Request(action, pin.getText().toCharArray(),
                changingPin ? newPin.getText().toCharArray() : null);
        clear();
        try {
            submitted.accept(request);
        } catch (RuntimeException | Error exception) {
            request.close();
            throw exception;
        }
    }

    record Request(KioskOperations.Action action, char[] pin, char[] newPin) implements AutoCloseable {

        @Override
        public void close() {
            if (pin != null) {
                Arrays.fill(pin, '\0');
            }
            if (newPin != null) {
                Arrays.fill(newPin, '\0');
            }
        }
    }
}
