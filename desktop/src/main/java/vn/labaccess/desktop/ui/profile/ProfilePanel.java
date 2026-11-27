package vn.labaccess.desktop.ui.profile;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.ui.foundation.UiColumns;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.Profile;
import vn.labaccess.desktop.card.ProfileCryptoMeta;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class ProfilePanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final PasswordField fieldPin = new PasswordField();
    private final Label pinError = UiLabels.fieldError();
    private final Button btnProfile = new Button("Đọc hồ sơ");
    private final Button btnMeta = new Button("Đọc thông tin mã hóa");
    private final Label why = UiLabels.whyNote();
    private final Label valueStudentId = UiLabels.value();
    private final Label valueName = UiLabels.value();
    private final Label valueClass = UiLabels.value();
    private final Label valueMajor = UiLabels.value();
    private final Label valueAlgorithm = UiLabels.value();
    private final Label valueRecordVersion = UiLabels.value();
    private final Label valueCiphertextLength = UiLabels.value();
    private final Label noteProfile = UiLabels.caption("Chưa đọc hồ sơ");
    private final Label noteMeta = UiLabels.caption("Chưa đọc thông tin mã hóa");
    private final VBox node;
    private final VBox technical;

    public ProfilePanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldPin.setPrefColumnCount(8);
        fieldPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(pinError));
        btnProfile.setOnAction(e -> readProfile());
        btnMeta.setOnAction(e -> readMeta());
        UiLabels.apduHint(btnProfile, "GET_PROFILE");
        UiLabels.apduHint(btnMeta, "GET_PROFILE_CRYPTO_META");
        btnProfile.getStyleClass().add("la-action");
        btnMeta.getStyleClass().add("la-action");

        VBox pinField = UiRows.field("PIN sinh viên", fieldPin, pinError);
        FlowPane controls = UiRows.wrapping(pinField, btnProfile);

        valueName.getStyleClass().add("la-profile-name");
        valueStudentId.getStyleClass().add("la-card-id");
        VBox profileBlock = new VBox(UiScale.SPACE_2,
                noteProfile,
                UiRows.valueBlock("Họ tên", valueName),
                UiRows.valueBlock("Mã sinh viên", valueStudentId),
                UiRows.valueBlock("Lớp", valueClass),
                UiRows.valueBlock("Chuyên ngành", valueMajor));

        VBox metaBlock = new VBox(UiScale.SPACE_2,
                noteMeta,
                UiRows.pair(UiLabels.caption("Thuật toán"), valueAlgorithm),
                UiRows.pair(UiLabels.caption("Phiên bản bản ghi"), valueRecordVersion),
                UiRows.pair(UiLabels.caption("Độ dài bản mã"), valueCiphertextLength));

        technical = new VBox(UiScale.SPACE_2, btnMeta, metaBlock);
        UiLabels.show(technical, false);
        node = UiSection.of("Hồ sơ sinh viên",
                new UiColumns(UiScale.PROFILE_COLUMNS_WIDTH, 38, controls, profileBlock),
                why, technical);
        clearView();
    }

    public VBox node() {
        return node;
    }

    public void showTechnical(boolean visible) {
        UiLabels.show(technical, visible);
    }

    public void setEnabled(boolean enabled, String whyDisabled) {
        fieldPin.setDisable(!enabled);
        btnProfile.setDisable(!enabled);
        btnMeta.setDisable(!enabled);
        UiLabels.setWhy(why, whyDisabled);
    }

    public void clearInputs() {
        fieldPin.clear();
        UiLabels.clearFieldError(pinError);
    }

    public void clearView() {
        clearInputs();
        valueStudentId.setText(UiLabels.EMPTY_VALUE);
        valueName.setText(UiLabels.EMPTY_VALUE);
        valueClass.setText(UiLabels.EMPTY_VALUE);
        valueMajor.setText(UiLabels.EMPTY_VALUE);
        valueAlgorithm.setText(UiLabels.EMPTY_VALUE);
        valueRecordVersion.setText(UiLabels.EMPTY_VALUE);
        valueCiphertextLength.setText(UiLabels.EMPTY_VALUE);
        showNote(noteProfile, true);
        showNote(noteMeta, true);
    }

    public void resetView(String reason) {
        clearView();
        log.accept("GUI hiển thị hồ sơ = (trống) | " + reason);
        log.accept("GUI hiển thị thông tin mã hóa = (trống) | " + reason);
    }

    public boolean pressProfile() {
        return press(btnProfile);
    }

    public boolean pressMeta() {
        return press(btnMeta);
    }

    public void fillPin(char[] pin) {
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
        if (!PinFormat.isDigits(pin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            Arrays.fill(pin, '\0');
            showPinError();
            UiLabels.inputProblem(log, fieldPin, "Đọc hồ sơ: " + PersonalizeRequest.studentPinProblem());
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
        if (!PinFormat.isDigits(pin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            Arrays.fill(pin, '\0');
            showPinError();
            UiLabels.inputProblem(log, fieldPin, "Đọc thông tin mã hóa: " + PersonalizeRequest.studentPinProblem());
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

    private void showPinError() {
        UiLabels.setFieldError(pinError,
                "PIN sinh viên phải đúng " + PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số.");
    }

    private void showProfile(boolean ok) {
        Profile profile = gateway.lastProfile();
        if (ok && profile != null) {
            valueStudentId.setText(profile.studentId());
            valueName.setText(profile.displayName());
            valueClass.setText(profile.classCode() == null ? "Chưa lưu trên thẻ" : profile.classCode());
            valueMajor.setText(profile.major() == null ? "Chưa lưu trên thẻ"
                    : profile.major().isEmpty() ? "Chưa xác định" : profile.major());
            showNote(noteProfile, false);
            log.accept("GUI hiển thị hồ sơ = " + profile.evidence()
                    + " | panel render mã sinh viên + họ tên + lớp + chuyên ngành");
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
