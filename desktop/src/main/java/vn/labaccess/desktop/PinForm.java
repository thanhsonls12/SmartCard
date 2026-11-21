package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

final class PinForm {

    private PinForm() {
    }

    static void configure(PasswordField field, Label error, int length) {
        field.setPrefColumnCount(length + 2);
        field.setPromptText(length + " chữ số");
        field.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(error));
    }

    static boolean validField(PasswordField field, Label error, int length, Consumer<String> log) {
        char[] pin = field.getText().toCharArray();
        boolean valid = validPin(pin, length);
        Arrays.fill(pin, '\0');
        String problem = length == PersonalizeRequest.ADMIN_PIN_LENGTH
                ? PersonalizeRequest.adminPinProblem() : PersonalizeRequest.studentPinProblem();
        UiLabels.setFieldError(error, valid ? null : problem + ".");
        if (!valid) {
            UiLabels.inputProblem(log, field, problem + ".");
        }
        return valid;
    }

    static boolean confirmed(PasswordField pinField, PasswordField confirmation, Label error,
            Consumer<String> log) {
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
}
