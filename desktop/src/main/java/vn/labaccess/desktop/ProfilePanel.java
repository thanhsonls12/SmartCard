package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class ProfilePanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final PasswordField fieldPin = new PasswordField();
    private final Button btnProfile = new Button("Đọc hồ sơ");
    private final Button btnMeta = new Button("Đọc thông tin mã hóa");
    private final Label valueStudentId = UiLabels.value();
    private final Label valueName = UiLabels.value();
    private final Label valueAlgorithm = UiLabels.value();
    private final Label valueRecordVersion = UiLabels.value();
    private final Label valueCiphertextLength = UiLabels.value();
    private final Label noteProfile = UiLabels.caption("Chưa đọc hồ sơ");
    private final Label noteMeta = UiLabels.caption("Chưa đọc thông tin mã hóa");
    private final VBox node;

    ProfilePanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldPin.setPrefColumnCount(8);
        btnProfile.setOnAction(e -> readProfile());
        btnMeta.setOnAction(e -> readMeta());
        UiLabels.apduHint(btnProfile, "GET_PROFILE");
        UiLabels.apduHint(btnMeta, "GET_PROFILE_CRYPTO_META");
        btnProfile.getStyleClass().add("la-action");
        btnMeta.getStyleClass().add("la-action");

        FlowPane controls = UiRows.wrapping(
                UiRows.field("PIN học viên", fieldPin), btnProfile, btnMeta);

        VBox profileBlock = new VBox(UiScale.SPACE_2,
                noteProfile,
                UiRows.pair(UiLabels.caption("Mã sinh viên"), valueStudentId),
                UiRows.pair(UiLabels.caption("Họ tên"), valueName));

        VBox metaBlock = new VBox(UiScale.SPACE_2,
                noteMeta,
                UiRows.pair(UiLabels.caption("Thuật toán"), valueAlgorithm),
                UiRows.pair(UiLabels.caption("Phiên bản bản ghi"), valueRecordVersion),
                UiRows.pair(UiLabels.caption("Độ dài bản mã"), valueCiphertextLength));

        node = UiSection.of("Đọc hồ sơ — PIN học viên", controls, profileBlock, metaBlock);
        clearView();
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled) {
        fieldPin.setDisable(!enabled);
        btnProfile.setDisable(!enabled);
        btnMeta.setDisable(!enabled);
    }

    void clearView() {
        valueStudentId.setText("-");
        valueName.setText("-");
        valueAlgorithm.setText("-");
        valueRecordVersion.setText("-");
        valueCiphertextLength.setText("-");
        showNote(noteProfile, true);
        showNote(noteMeta, true);
    }

    void resetView(String reason) {
        clearView();
        log.accept("GUI hiển thị hồ sơ = (trống) | " + reason);
        log.accept("GUI hiển thị thông tin mã hóa = (trống) | " + reason);
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
        if (!digits(pin, 6)) {
            Arrays.fill(pin, '\0');
            log.accept("[FAIL] đọc hồ sơ: PIN học viên phải đúng 6 chữ số");
            return;
        }
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
        if (!digits(pin, 6)) {
            Arrays.fill(pin, '\0');
            log.accept("[FAIL] đọc thông tin mã hóa: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldPin.clear();
        runner.run("GET_PROFILE_CRYPTO_META", () -> {
            try {
                return gateway.readProfileCryptoMetaWithPin(pin);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }, this::showMeta);
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

    private void showProfile(boolean ok) {
        Profile profile = gateway.lastProfile();
        if (ok && profile != null) {
            valueStudentId.setText(profile.studentId());
            valueName.setText(profile.displayName());
            showNote(noteProfile, false);
            log.accept("GUI hiển thị hồ sơ = " + profile.evidence()
                    + " | panel render mã sinh viên + họ tên");
            return;
        }
        showFailure();
    }

    private void showMeta(boolean ok) {
        ProfileCryptoMeta meta = gateway.lastProfileMeta();
        if (ok && meta != null) {
            valueAlgorithm.setText(meta.algorithmText());
            valueRecordVersion.setText(String.format("%02X / schema %02X",
                    meta.recordVersion(), meta.schemaVersion()));
            valueCiphertextLength.setText(meta.ciphertextLength() + " byte");
            showNote(noteMeta, false);
            log.accept("GUI hiển thị thông tin mã hóa = " + meta.evidence());
            return;
        }
        showFailure();
    }

    private void showFailure() {
        clearView();
        log.accept("GUI hiển thị hồ sơ = (xoá) | thao tác không thành công, không giữ kết quả cũ");
        log.accept("GUI hiển thị thông tin mã hóa = (xoá) | thao tác không thành công, không giữ kết quả cũ");
    }

    private static void showNote(Label note, boolean visible) {
        note.setVisible(visible);
        note.setManaged(visible);
    }
}
