package vn.labaccess.desktop.ui.kiosk;

import java.util.Arrays;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiScale;

import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

import vn.labaccess.desktop.ui.pin.PinForm;

public final class KioskPinPrompt {

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

    public KioskPinPrompt(Consumer<Request> submitted, Runnable cancelled, Consumer<String> log) {
        this.submitted = submitted;
        this.cancelled = cancelled;
        this.log = log;
        PinForm.configure(pin, pinError, PIN_LENGTH);
        PinForm.configure(newPin, newPinError, PIN_LENGTH);
        PinForm.configure(confirmation, confirmationError, PIN_LENGTH);
        newPinRow = UiRows.field("PIN mới", newPin, newPinError);
        confirmationRow = UiRows.field("Nhập lại PIN mới", confirmation, confirmationError);
        submit.getStyleClass().addAll("la-action", "la-primary");
        cancel.getStyleClass().add("la-action");
        submit.setOnAction(event -> fireSubmit());
        cancel.setOnAction(event -> {
            clear();
            this.cancelled.run();
        });
        pin.setOnAction(event -> fireSubmit());
        newPin.setOnAction(event -> fireSubmit());
        confirmation.setOnAction(event -> fireSubmit());
        actionTitle.setWrapText(true);
        actionTitle.getStyleClass().add("la-section-title");
        Label hint = UiLabels.caption("PIN gồm 6 chữ số. Chỉ dùng cho thao tác này.");
        hint.setWrapText(true);
        root = new VBox(UiScale.SPACE_3, actionTitle, hint,
                UiRows.field("PIN sinh viên", pin, pinError), newPinRow, confirmationRow,
                UiRows.wrapping(cancel, submit));
        root.getStyleClass().add("la-kiosk-pin");
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && !cancel.isDisabled()) {
                cancel.fire();
                event.consume();
            }
        });
        clear();
    }

    public VBox node() {
        return root;
    }

    public void open(KioskOperations.Action requestedAction) {
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
        javafx.application.Platform.runLater(() -> {
            if (isOpen() && !pin.isDisabled()) {
                pin.requestFocus();
            }
        });
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

    public void setBusy(boolean busy) {
        pin.setDisable(busy);
        newPin.setDisable(busy);
        confirmation.setDisable(busy);
        submit.setDisable(busy);
        cancel.setDisable(busy);
    }

    public boolean isOpen() {
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

    public record Request(KioskOperations.Action action, char[] pin, char[] newPin) implements AutoCloseable {

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
