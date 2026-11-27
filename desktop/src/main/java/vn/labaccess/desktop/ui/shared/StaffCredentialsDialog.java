package vn.labaccess.desktop.ui.shared;

import java.util.Arrays;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiScale;

public final class StaffCredentialsDialog {
    public record Credentials(String username, char[] password) implements AutoCloseable {
        @Override
        public void close() {
            Arrays.fill(password, '\0');
        }
    }

    private StaffCredentialsDialog() { }

    public static Credentials ask(Node owner) {
        Dialog<Credentials> dialog = new Dialog<>();
        dialog.initOwner(owner.getScene().getWindow());
        dialog.setTitle("Xác thực nhân viên");
        TextField username = new TextField();
        PasswordField password = new PasswordField();
        username.setPromptText("Tên đăng nhập");
        password.setPromptText("Mật khẩu");
        username.setId("kiosk-staff-username");
        password.setId("kiosk-staff-password");
        Label explanation = new Label("Tài khoản nhân viên chỉ dùng cho lần xác nhận đối soát này.");
        explanation.setWrapText(true);
        VBox content = new VBox(UiScale.SPACE_3, explanation, new Label("Tên đăng nhập"), username,
                new Label("Mật khẩu"), password);
        dialog.getDialogPane().setContent(content);
        ButtonType confirm = new ButtonType("Xác thực", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        Button submit = (Button) dialog.getDialogPane().lookupButton(confirm);
        submit.disableProperty().bind(username.textProperty().isEmpty().or(password.textProperty().isEmpty()));
        dialog.setResultConverter(button -> button == confirm
                ? new Credentials(username.getText().strip(), password.getText().toCharArray()) : null);
        try {
            return dialog.showAndWait().orElse(null);
        } finally {
            password.clear();
        }
    }
}
