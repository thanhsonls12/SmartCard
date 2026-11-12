package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class PinPanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final PasswordField fieldPin = new PasswordField();
    private final PasswordField fieldNewPin = new PasswordField();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final Button btnVerify = new Button("Xác thực PIN");
    private final Button btnChange = new Button("Đổi PIN");
    private final Button btnUnblock = new Button("Mở khóa PIN");
    private final Label valueState = UiLabels.value();
    private final VBox node;

    PinPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldPin.setPrefColumnCount(8);
        fieldNewPin.setPrefColumnCount(8);
        fieldAdminPin.setPrefColumnCount(10);

        btnVerify.setOnAction(e -> verify());
        btnChange.setOnAction(e -> change());
        btnUnblock.setOnAction(e -> unblock());
        UiLabels.apduHint(btnVerify, "VERIFY_STUDENT_PIN");
        UiLabels.apduHint(btnChange, "CHANGE_STUDENT_PIN");
        UiLabels.apduHint(btnUnblock, "UNBLOCK_STUDENT_PIN");
        btnVerify.getStyleClass().add("la-action");
        btnChange.getStyleClass().add("la-action");
        btnUnblock.getStyleClass().add("la-action");

        FlowPane controls = UiRows.wrapping(
                UiRows.field("PIN hiện tại", fieldPin),
                UiRows.field("PIN mới", fieldNewPin),
                UiRows.field("PIN quản trị", fieldAdminPin),
                btnVerify, btnChange, btnUnblock);

        VBox state = UiRows.valueBlock("Trạng thái PIN", valueState);

        node = UiSection.of("PIN học viên", controls, state);
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled) {
        btnVerify.setDisable(!enabled);
        btnChange.setDisable(!enabled);
        btnUnblock.setDisable(!enabled);
    }

    void clearState() {
        valueState.setText(PinState.unknown().text());
    }

    void resetState(String reason) {
        PinState state = PinState.unknown();
        valueState.setText(state.text());
        log.accept("GUI hiển thị PIN = " + state.text() + " | " + reason);
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
        }
        if (newPin != null) {
            fieldNewPin.setText(new String(newPin));
        }
        if (adminPin != null) {
            fieldAdminPin.setText(new String(adminPin));
        }
    }

    private boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }

    private void verify() {
        char[] pin = fieldPin.getText().toCharArray();
        if (!digits(pin, 6)) {
            Arrays.fill(pin, '\0');
            log.accept("[FAIL] xác thực PIN: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldPin.clear();
        runner.run("VERIFY_STUDENT_PIN", () -> {
            try {
                return gateway.verifyStudentPin(pin);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }, ok -> showState(PinState.Operation.VERIFY, ok));
    }

    private void change() {
        char[] oldPin = fieldPin.getText().toCharArray();
        char[] newPin = fieldNewPin.getText().toCharArray();
        if (!digits(oldPin, 6) || !digits(newPin, 6)) {
            Arrays.fill(oldPin, '\0');
            Arrays.fill(newPin, '\0');
            log.accept("[FAIL] đổi PIN: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldPin.clear();
        fieldNewPin.clear();
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
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] newPin = fieldNewPin.getText().toCharArray();
        if (!digits(adminPin, 8) || !digits(newPin, 6)) {
            Arrays.fill(adminPin, '\0');
            Arrays.fill(newPin, '\0');
            log.accept("[FAIL] mở khóa PIN: PIN quản trị phải đúng 8 chữ số và PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldAdminPin.clear();
        fieldNewPin.clear();
        runner.run("UNBLOCK_STUDENT_PIN", () -> {
            try {
                return gateway.unblockStudentPin(adminPin, newPin);
            } finally {
                Arrays.fill(adminPin, '\0');
                Arrays.fill(newPin, '\0');
            }
        }, ok -> showState(PinState.Operation.UNBLOCK, ok));
    }

    private static boolean digits(char[] pin, int length) {
        if (pin == null || pin.length != length) {
            return false;
        }
        for (char c : pin) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private void showState(PinState.Operation operation, boolean ok) {
        PinState state = PinState.of(operation, ok, gateway.lastPinSw());
        valueState.setText(state.text());
        log.accept("GUI hiển thị PIN = " + state.text());
    }
}
