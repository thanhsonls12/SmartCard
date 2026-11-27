package vn.labaccess.desktop.ui.profile;

import vn.labaccess.desktop.validation.ClassCode;
import vn.labaccess.desktop.validation.StudentMajor;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.ui.foundation.UiColumns;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.PhotoImage;
import vn.labaccess.desktop.card.ProfileEditRequest;
import vn.labaccess.desktop.card.Profile;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.ui.shared.EnrollmentRunner;
import vn.labaccess.desktop.ui.shared.PinOperation;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

import vn.labaccess.desktop.ui.photo.PhotoDraftPicker;

public final class ProfileEditPanel {

    private final EnrollmentRunner runner;
    private final ProfileUpdateFlow flow;
    private final Consumer<String> log;
    private final PhotoDraftPicker picker;

    private final TextField fieldName = new TextField();
    private final TextField fieldStudentId = new TextField();
    private final TextField fieldClass = new TextField();
    private final Label valueMajor = UiLabels.value();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label nameError = UiLabels.fieldError();
    private final Label studentIdError = UiLabels.fieldError();
    private final Label classError = UiLabels.fieldError();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label studentPinError = UiLabels.fieldError();
    private final Button btnSave = new Button(UiLabels.SAVE_PROFILE);
    private final Button btnReplace = new Button(UiLabels.REPLACE_PHOTO);
    private final Button btnReopen = new Button(UiLabels.REOPEN_PROFILE_REQUEST);
    private final Button btnReplaceStale = new Button(UiLabels.REPLACE_STALE_REQUEST);
    private final Button btnReplaceLegacy = new Button(UiLabels.REPLACE_LEGACY_PENDING);
    private final Label valueCardId = UiLabels.value();
    private final Label valueCardProfile = UiLabels.value();
    private final Label status = new Label();
    private final Label why = UiLabels.whyNote();
    private final VBox profileForm;
    private final VBox profilePhoto;
    private final VBox node;

    private String cardId = "";
    private ProfileEditRequest lastSubmitted;
    private ProfileUpdateFlow.State lastState;
    private boolean replaceArmed;
    private boolean enabled = true;

    public ProfileEditPanel(CardGateway gateway, BackendClient backend, EnrollmentRunner runner,
            Consumer<String> log) {
        this.runner = runner;
        this.log = log;
        this.flow = new ProfileUpdateFlow(gateway, backend);
        this.picker = new PhotoDraftPicker(log, this::refreshActions);

        fieldName.setPromptText("Họ tên in trên thẻ, tối đa "
                + PersonalizeRequest.DISPLAY_NAME_MAX_LENGTH + " byte UTF-8");
        fieldStudentId.setPromptText(PersonalizeRequest.STUDENT_ID_MIN_LENGTH + "–"
                + PersonalizeRequest.STUDENT_ID_MAX_LENGTH + " ký tự chữ hoặc số");
        fieldClass.setPromptText(ClassCode.PROMPT);
        fieldAdminPin.setPrefColumnCount(10);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldStudentPin.setPrefColumnCount(8);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldName.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(nameError));
        fieldStudentId.textProperty().addListener(
                (observable, was, now) -> {
                    UiLabels.clearFieldError(studentIdError);
                    String major = StudentMajor.fromStudentId(now);
                    valueMajor.setText(major.isEmpty() ? "Chưa xác định" : major);
                });
        fieldClass.textProperty().addListener((observable, was, now) -> UiLabels.clearFieldError(classError));
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        fieldStudentPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(studentPinError));
        btnSave.setOnAction(event -> save());
        btnReplace.setOnAction(event -> replacePhoto());
        btnReopen.setOnAction(event -> reopen());
        btnReplaceStale.setOnAction(event -> replaceStale());
        btnReplaceLegacy.setOnAction(event -> replaceLegacyPending());
        UiLabels.apduHint(btnSave, "SIGN_PROFILE_CHALLENGE (phiên) + UPDATE_IDENTITY + SIGN_PROFILE_CHALLENGE");
        UiLabels.apduHint(btnReplace,
                "PHOTO_REPLACE_BEGIN + PHOTO_WRITE_CHUNK + PHOTO_FINISH + PHOTO_READ_CHUNK");
        UiLabels.apduHint(btnReopen, "SIGN_PROFILE_CHALLENGE (phiên) + GET_PROFILE + GET /profile-updates");
        UiLabels.apduHint(btnReplaceStale, "GET_PROFILE + SIGN_PROFILE_CHALLENGE (phiên)"
                + " + POST /profile-updates/{id}/replace + UPDATE_IDENTITY + SIGN_PROFILE_CHALLENGE");
        UiLabels.apduHint(btnReplaceLegacy, "GET_PROFILE + SIGN_PROFILE_CHALLENGE (phiên)"
                + " + POST /profile-updates/{id}/replace rồi mới UPDATE_IDENTITY");
        for (Button button : new Button[] {btnSave, btnReplace, btnReopen, btnReplaceStale, btnReplaceLegacy}) {
            button.getStyleClass().add("la-action");
        }

        GridPane identity = grid(3);
        identity.add(UiRows.field("Họ tên", fieldName, nameError), 0, 0);
        identity.add(UiRows.field("Mã sinh viên", fieldStudentId, studentIdError), 1, 0);
        identity.add(UiRows.field("Lớp", fieldClass, classError), 2, 0);
        GridPane secrets = grid(2);
        secrets.add(UiRows.field("PIN quản trị", fieldAdminPin, adminPinError), 0, 0);
        secrets.add(UiRows.field("PIN sinh viên", fieldStudentPin, studentPinError), 1, 0);
        FlowPane actions = UiRows.wrapping(btnSave, btnReplace, btnReopen, btnReplaceStale, btnReplaceLegacy);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(0);
        actions.setMaxWidth(Double.MAX_VALUE);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox form = new VBox(UiScale.SPACE_4, identity,
                UiRows.valueBlock("Chuyên ngành sẽ lưu theo mã sinh viên", valueMajor), secrets,
                UiRows.valueBlock("Hồ sơ đang lưu trên thẻ", valueCardProfile), spacer, actions);
        form.setMinWidth(0);
        form.setPrefWidth(UiScale.PROFILE_COLUMNS_WIDTH);
        form.setMaxWidth(UiScale.PROFILE_COLUMNS_WIDTH);
        actions.prefWidthProperty().bind(form.widthProperty());
        VBox photo = new VBox(UiScale.SPACE_3, picker.node());
        photo.setAlignment(Pos.TOP_CENTER);
        photo.setPrefWidth(UiScale.PROFILE_PHOTO_WIDTH);
        photo.setMaxWidth(UiScale.PROFILE_PHOTO_WIDTH);
        VBox.setVgrow(picker.node(), Priority.ALWAYS);
        profileForm = form;
        profilePhoto = photo;
        status.getStyleClass().add("la-note");
        status.setWrapText(true);
        UiLabels.applyNoteTone(status, UiLabels.Tone.NEUTRAL);
        HBox columns = new HBox(UiColumns.fixed(
                UiScale.PROFILE_COLUMNS_WIDTH + UiScale.SPACE_4 + UiScale.PROFILE_PHOTO_WIDTH,
                UiScale.PROFILE_COLUMNS_WIDTH, true, form, photo));
        columns.setAlignment(Pos.TOP_CENTER);
        node = UiSection.of("Sửa hồ sơ",
                UiRows.wrapping(UiLabels.caption("Mã thẻ đang chọn"), valueCardId,
                        UiLabels.copyButton(valueCardId, "mã thẻ")),
                why,
                columns,
                status);
        UiLabels.show(btnReopen, false);
        UiLabels.show(btnReplaceLegacy, false);
        resetView();
    }

    private static GridPane grid(int columns) {
        GridPane grid = new GridPane();
        grid.setHgap(UiScale.SPACE_3);
        grid.setVgap(UiScale.SPACE_3);
        for (int index = 0; index < columns; index++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            constraint.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraint);
        }
        return grid;
    }

    public VBox node() {
        return node;
    }

    public void setEnabled(boolean enabled, String whyDisabled) {
        this.enabled = enabled;
        for (TextField field : new TextField[] {fieldName, fieldStudentId, fieldClass,
                fieldAdminPin, fieldStudentPin}) {
            field.setDisable(!enabled);
        }
        btnSave.setDisable(!enabled);
        btnReplace.setDisable(!enabled);
        btnReopen.setDisable(!enabled);
        btnReplaceStale.setDisable(!enabled);
        btnReplaceLegacy.setDisable(!enabled);
        picker.setEnabled(enabled);
        UiLabels.setWhy(why, whyDisabled);
        refreshActions();
    }

    public void setSavePrimary(boolean primary) {
        UiLabels.markPrimary(btnSave, primary && !btnSave.isDisable());
    }

    public void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase(Locale.ROOT);
        if (!next.equals(this.cardId)) {
            resetView();
            this.cardId = next;
        }
        valueCardId.setText(next.isEmpty() ? "Chưa nhận diện thẻ" : next);
        refreshActions();
    }

    public void resetView() {
        flow.clearReadProfile();
        clearInputs();
        fieldName.clear();
        fieldStudentId.clear();
        fieldClass.clear();
        valueMajor.setText("Chưa xác định");
        picker.clearDraft();
        UiLabels.setEmpty(valueCardProfile, "Chưa có hồ sơ trên thẻ",
                "Kết nối thẻ và mở lại hồ sơ để xem.");
        cardId = "";
        lastSubmitted = null;
        lastState = null;
        showStatus(null, UiLabels.Tone.NEUTRAL);
        refreshActions();
    }

    public void clearInputs() {
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        UiLabels.clearFieldError(nameError);
        UiLabels.clearFieldError(studentIdError);
        UiLabels.clearFieldError(classError);
        UiLabels.clearFieldError(adminPinError);
        UiLabels.clearFieldError(studentPinError);
    }

    private void save() {
        if (btnSave.isDisabled()) {
            return;
        }
        String targetCard = cardId;
        if (targetCard.isEmpty()) {
            showStatus("Chưa có mã thẻ để sửa hồ sơ.", UiLabels.Tone.ERROR);
            return;
        }
        String studentIdProblem = PersonalizeRequest.studentIdProblem(fieldStudentId.getText());
        if (studentIdProblem != null) {
            UiLabels.setFieldError(studentIdError, studentIdProblem);
            UiLabels.inputProblem(log, fieldStudentId, "Sửa hồ sơ: " + studentIdProblem);
            return;
        }
        String nameProblem = PersonalizeRequest.displayNameProblem(fieldName.getText());
        if (nameProblem != null) {
            UiLabels.setFieldError(nameError, nameProblem);
            UiLabels.inputProblem(log, fieldName, "Sửa hồ sơ: " + nameProblem);
            return;
        }
        String classProblem = ClassCode.problem(fieldClass.getText());
        if (classProblem != null) {
            UiLabels.setFieldError(classError, classProblem);
            UiLabels.inputProblem(log, fieldClass, "Sửa hồ sơ: " + classProblem);
            return;
        }
        ProfileEditRequest request = ProfileEditRequest.normalized(fieldStudentId.getText(),
                fieldName.getText(), fieldClass.getText());
        lastSubmitted = request;
        fieldStudentId.setText(request.studentId());
        fieldClass.setText(request.classCode());
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!pinsUsable(adminPin, studentPin)) {
            return;
        }
        runner.run("PROFILE save", PinOperation.of(
                () -> flow.save(targetCard, request, adminPin, studentPin), adminPin, studentPin),
                this::showResult, failure -> showFailure("PROFILE save", failure));
    }

    private void replacePhoto() {
        if (btnReplace.isDisabled()) {
            return;
        }
        if (cardId.isEmpty()) {
            showStatus("Chưa có mã thẻ để thay ảnh.", UiLabels.Tone.ERROR);
            return;
        }
        if (!picker.hasDraft()) {
            showStatus("Chọn ảnh trước khi bấm Thay ảnh.", UiLabels.Tone.PENDING);
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!pinsUsable(adminPin, studentPin)) {
            return;
        }
        byte[] image = picker.draftBytes();
        runner.run("PHOTO replace", PinOperation.of(
                () -> flow.replacePhoto(image, adminPin, studentPin), adminPin, studentPin), result -> {
            showResult(result);
            if (result.state() == ProfileUpdateFlow.State.DONE) {
                picker.setLabel("Ảnh đã thay: sha256 " + PhotoImage.fingerprint(image));
            }
        }, failure -> showFailure("PHOTO replace", failure));
    }

    private void reopen() {
        if (btnReopen.isDisabled()) {
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!pinsUsable(adminPin, studentPin)) {
            return;
        }
        String targetCard = cardId;
        runner.run("PROFILE reopen", PinOperation.of(
                () -> flow.reopen(targetCard, adminPin, studentPin), adminPin, studentPin),
                this::showResult, failure -> showFailure("PROFILE reopen", failure));
    }

    private void replaceStale() {
        if (btnReplaceStale.isDisabled() || lastState != ProfileUpdateFlow.State.STALE) {
            return;
        }
        if (!replaceArmed) {
            replaceArmed = true;
            btnReplaceStale.setText(UiLabels.REPLACE_STALE_CONFIRM);
            showStatus("Bấm " + UiLabels.REPLACE_STALE_CONFIRM + " lần nữa để thay yêu cầu cũ"
                    + " bằng hồ sơ hiện tại trên thẻ.", UiLabels.Tone.PENDING);
            return;
        }
        ProfileEditRequest held = flow.pendingRequest();
        ProfileEditRequest request = held == null ? lastSubmitted : held;
        if (request == null) {
            showStatus("Chưa giữ được nội dung yêu cầu cũ nên chưa thay được.", UiLabels.Tone.ERROR);
            return;
        }
        if (request.classCode() == null) {
            replaceArmed = false;
            showResult(new ProfileUpdateFlow.Result(ProfileUpdateFlow.State.CLASS_REQUIRED,
                    "Yêu cầu cũ chưa có lớp. Nhập lớp hợp lệ rồi thay yêu cầu cũ để tiếp tục.", ""));
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!pinsUsable(adminPin, studentPin)) {
            return;
        }
        replaceArmed = false;
        String targetCard = cardId;
        runner.run("PROFILE replace", PinOperation.of(
                () -> flow.replaceStale(targetCard, request, adminPin, studentPin), adminPin, studentPin), result -> {
            picker.clearDraft();
            showResult(result);
        }, failure -> {
            picker.clearDraft();
            showFailure("PROFILE replace", failure);
        });
    }

    private void replaceLegacyPending() {
        if (btnReplaceLegacy.isDisabled() || lastState != ProfileUpdateFlow.State.CLASS_REQUIRED) {
            return;
        }
        if (cardId.isEmpty()) {
            showStatus("Chưa có mã thẻ để bổ sung lớp cho yêu cầu cũ.", UiLabels.Tone.ERROR);
            return;
        }
        String classCode = ClassCode.normalize(fieldClass.getText());
        String problem = ClassCode.problem(classCode);
        if (problem != null) {
            UiLabels.setFieldError(classError, problem);
            UiLabels.inputProblem(log, fieldClass, "Bổ sung lớp yêu cầu cũ: " + problem);
            return;
        }
        fieldClass.setText(classCode);
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!pinsUsable(adminPin, studentPin)) {
            return;
        }
        String targetCard = cardId;
        runner.run("PROFILE replace legacy", PinOperation.of(
                () -> flow.replaceLegacyPending(targetCard, classCode, adminPin, studentPin),
                adminPin, studentPin), result -> {
            picker.clearDraft();
            showResult(result);
        }, failure -> showFailure("PROFILE replace legacy", failure));
    }

    private boolean pinsUsable(char[] adminPin, char[] studentPin) {
        if (!PinFormat.isDigits(adminPin, PersonalizeRequest.ADMIN_PIN_LENGTH)) {
            wipe(adminPin, studentPin);
            UiLabels.setFieldError(adminPinError, PersonalizeRequest.adminPinProblem() + ".");
            UiLabels.inputProblem(log, fieldAdminPin, "Sửa hồ sơ: " + PersonalizeRequest.adminPinProblem());
            return false;
        }
        if (!PinFormat.isDigits(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            wipe(adminPin, studentPin);
            UiLabels.setFieldError(studentPinError, PersonalizeRequest.studentPinProblem() + ".");
            UiLabels.inputProblem(log, fieldStudentPin,
                    "Sửa hồ sơ: " + PersonalizeRequest.studentPinProblem());
            return false;
        }
        UiLabels.clearFieldError(adminPinError);
        UiLabels.clearFieldError(studentPinError);
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        return true;
    }

    private void showResult(ProfileUpdateFlow.Result result) {
        UiLabels.Tone tone = switch (result.state()) {
            case DONE -> UiLabels.Tone.OK;
            case REJECTED, STALE -> UiLabels.Tone.ERROR;
            case CLASS_REQUIRED, UNVERIFIED -> UiLabels.Tone.PENDING;
        };
        lastState = result.state();
        String detail = result.detail();
        showStatus(detail == null || detail.isBlank() ? result.message() : result.message() + " " + detail,
                tone);
        Profile read = flow.lastReadProfile();
        ProfileEditRequest completed = flow.lastCompletedRequest();
        if (read != null) {
            showReadProfile(read);
        } else if (result.state() == ProfileUpdateFlow.State.DONE && completed != null) {
            UiLabels.clearEmpty(valueCardProfile);
            String major = StudentMajor.fromStudentId(completed.studentId());
            valueCardProfile.setText(completed.studentId() + " • " + completed.displayName()
                    + " • Lớp: " + completed.classCode()
                    + " • Chuyên ngành: " + (major.isEmpty() ? "Chưa xác định" : major));
        } else if (result.state() == ProfileUpdateFlow.State.UNVERIFIED) {
            UiLabels.clearEmpty(valueCardProfile);
            valueCardProfile.setText("Chưa xác định — đọc lại thẻ");
        }
        log.accept("[" + (result.state() == ProfileUpdateFlow.State.DONE ? "OK"
                : result.state() == ProfileUpdateFlow.State.UNVERIFIED ? "PENDING" : "FAIL") + "] "
                + result.message() + (detail == null || detail.isBlank() ? "" : " " + detail));
        refreshActions();
    }

    private void showReadProfile(Profile profile) {
        UiLabels.clearEmpty(valueCardProfile);
        valueCardProfile.setText(profile.studentId() + " • " + profile.displayName()
                + " • Lớp: " + (profile.classCode() == null ? "Chưa lưu trên thẻ" : profile.classCode())
                + " • Chuyên ngành: " + (profile.major() == null ? "Chưa lưu trên thẻ"
                        : profile.major().isEmpty() ? "Chưa xác định" : profile.major()));
        if (fieldStudentId.getText().isBlank()) {
            fieldStudentId.setText(profile.studentId());
        }
        if (fieldName.getText().isBlank()) {
            fieldName.setText(profile.displayName());
        }
        if (fieldClass.getText().isBlank() && profile.classCode() != null) {
            fieldClass.setText(profile.classCode());
        }
    }

    private void showFailure(String label, Exception failure) {
        lastState = null;
        showStatus(UiLabels.failureMessage(label, "", failure), UiLabels.Tone.ERROR);
        refreshActions();
    }

    private void showStatus(String text, UiLabels.Tone tone) {
        boolean visible = text != null && !text.isBlank();
        status.setText(visible ? text : "");
        UiLabels.applyNoteTone(status, tone);
        UiLabels.show(status, visible);
    }

    private void refreshActions() {
        boolean stale = enabled && lastState == ProfileUpdateFlow.State.STALE && !cardId.isEmpty();
        boolean legacy = enabled && lastState == ProfileUpdateFlow.State.CLASS_REQUIRED && !cardId.isEmpty();
        if (!stale) {
            replaceArmed = false;
        }
        btnReplaceStale.setText(replaceArmed ? UiLabels.REPLACE_STALE_CONFIRM
                : UiLabels.REPLACE_STALE_REQUEST);
        UiLabels.show(btnReplaceStale, stale);
        UiLabels.show(btnReplaceLegacy, legacy);
        btnSave.setDisable(!enabled || legacy);
        btnReplace.setDisable(!enabled || legacy);
        btnReopen.setDisable(!enabled);
        btnReplaceStale.setDisable(!enabled);
        btnReplaceLegacy.setDisable(!enabled || !legacy);
        UiLabels.show(btnReopen, !cardId.isEmpty());
    }

    private static void wipe(char[] adminPin, char[] studentPin) {
        Arrays.fill(adminPin, '\0');
        Arrays.fill(studentPin, '\0');
    }
}
