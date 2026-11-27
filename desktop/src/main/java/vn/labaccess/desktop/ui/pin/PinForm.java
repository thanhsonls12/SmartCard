package vn.labaccess.desktop.ui.pin;

import java.util.Arrays;
import java.util.function.Consumer;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.card.PersonalizeRequest;

import vn.labaccess.desktop.ui.shared.UiLabels;

public final class PinForm {

    private PinForm() {
    }

    public static void configure(PasswordField field, Label error, int length) {
        field.setPrefColumnCount(length + 2);
        field.setPromptText(length + " chữ số");
        field.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(error));
    }

    public static boolean validField(PasswordField field, Label error, int length, Consumer<String> log) {
        char[] pin = field.getText().toCharArray();
        boolean valid = PinFormat.isDigits(pin, length);
        Arrays.fill(pin, '\0');
        String problem = length == PersonalizeRequest.ADMIN_PIN_LENGTH
                ? PersonalizeRequest.adminPinProblem() : PersonalizeRequest.studentPinProblem();
        UiLabels.setFieldError(error, valid ? null : problem + ".");
        if (!valid) {
            UiLabels.inputProblem(log, field, problem + ".");
        }
        return valid;
    }

    public static boolean confirmed(PasswordField pinField, PasswordField confirmation, Label error,
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

}
