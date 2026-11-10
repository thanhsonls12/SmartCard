package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class ProfilePanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final PasswordField fieldPin = new PasswordField();
    private final Button btnProfile = new Button("GET_PROFILE");
    private final Button btnMeta = new Button("GET_PROFILE_CRYPTO_META");
    private final Label valueProfile = UiLabels.value();
    private final Label valueMeta = UiLabels.value();
    private final VBox node;

    ProfilePanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldPin.setPrefColumnCount(8);
        btnProfile.setOnAction(e -> readProfile());
        btnMeta.setOnAction(e -> readMeta());

        HBox controls = new HBox(8,
                UiLabels.caption("Student PIN"), fieldPin, btnProfile, btnMeta);
        controls.setAlignment(Pos.CENTER_LEFT);

        HBox profileRow = new HBox(8, UiLabels.caption("Hồ sơ"), valueProfile);
        profileRow.setAlignment(Pos.CENTER_LEFT);

        HBox metaRow = new HBox(8, UiLabels.caption("Metadata"), valueMeta);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        node = new VBox(6, controls, profileRow, metaRow);
        node.setPadding(new Insets(0, 10, 10, 10));
        clearView();
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled) {
        btnProfile.setDisable(!enabled);
        btnMeta.setDisable(!enabled);
    }

    void clearView() {
        valueProfile.setText("-");
        valueMeta.setText("-");
    }

    void resetView(String reason) {
        clearView();
        log.accept("GUI hiển thị hồ sơ = (trống) | " + reason);
        log.accept("GUI hiển thị metadata = (trống) | " + reason);
    }

    boolean pressProfile() {
        return press(btnProfile);
    }

    boolean pressMeta() {
        return press(btnMeta);
    }

    void fillPin(char[] pin) {
        if (pin != null) {
            fieldPin.setText(new String(pin));
        }
    }

    private boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }

    private void readProfile() {
        char[] pin = fieldPin.getText().toCharArray();
        fieldPin.clear();
        runner.run("GET_PROFILE", () -> {
            try {
                return gateway.readProfileWithPin(pin);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }, this::showProfile);
    }

    private void readMeta() {
        char[] pin = fieldPin.getText().toCharArray();
        fieldPin.clear();
        runner.run("GET_PROFILE_CRYPTO_META", () -> {
            try {
                return gateway.readProfileCryptoMetaWithPin(pin);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }, this::showMeta);
    }

    private void showProfile(boolean ok) {
        Profile profile = gateway.lastProfile();
        if (ok && profile != null) {
            valueProfile.setText(profile.studentId() + " | " + profile.displayName());
            log.accept("GUI hiển thị hồ sơ = " + profile.evidence()
                    + " | panel render studentId + displayName");
            return;
        }
        showFailure();
    }

    private void showMeta(boolean ok) {
        ProfileCryptoMeta meta = gateway.lastProfileMeta();
        if (ok && meta != null) {
            valueMeta.setText(meta.evidence());
            log.accept("GUI hiển thị metadata = " + meta.evidence());
            return;
        }
        showFailure();
    }

    private void showFailure() {
        clearView();
        log.accept("GUI hiển thị hồ sơ = (xoá) | thao tác không thành công, không giữ kết quả cũ");
        log.accept("GUI hiển thị metadata = (xoá) | thao tác không thành công, không giữ kết quả cũ");
    }
}
