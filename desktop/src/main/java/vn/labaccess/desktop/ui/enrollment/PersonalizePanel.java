package vn.labaccess.desktop.ui.enrollment;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.validation.PinFormat;
import vn.labaccess.desktop.validation.ClassCode;
import vn.labaccess.desktop.validation.StudentMajor;

import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.ReceiptVerifier;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.PinOperation;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class PersonalizePanel {

    public record IssuedCard(String cardId, String studentId, String classCode) {
    }

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final BackendClient backend;
    private final Consumer<String> log;
    private final BiConsumer<Boolean, IssuedCard> afterPersonalize;

    private final TextField fieldCardId = new TextField();
    private final TextField fieldStudentId = new TextField();
    private final TextField fieldName = new TextField();
    private final TextField fieldClass = new TextField();
    private final Label valueMajor = UiLabels.value();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label cardIdError = UiLabels.fieldError();
    private final Label studentIdError = UiLabels.fieldError();
    private final Label nameError = UiLabels.fieldError();
    private final Label classError = UiLabels.fieldError();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label studentPinError = UiLabels.fieldError();
    private final Button btnNewCardId = new Button(UiLabels.NEW_CARD_ID);
    private final Button btnPersonalize = new Button("Phát hành thẻ");
    private final Button btnPublicKey = new Button("Đọc khóa công khai");
    private final Label valuePublicKey = UiLabels.value();
    private final Label whyNote = UiLabels.whyNote();
    private final VBox node;
    private final VBox technical;

    private volatile String cardIdRejection;

    public PersonalizePanel(CardGateway gateway, CardOpRunner runner, BackendClient backend, Consumer<String> log,
            BiConsumer<Boolean, IssuedCard> afterPersonalize) {
        this.gateway = gateway;
        this.runner = runner;
        this.backend = backend;
        this.log = log;
        this.afterPersonalize = afterPersonalize;

        fieldCardId.setPrefColumnCount(17);
        fieldCardId.setPromptText((PersonalizeRequest.CARD_ID_LENGTH * 2) + " ký tự hex");
        fieldStudentId.setPrefColumnCount(12);
        fieldStudentId.setPromptText("8–10 chữ/số");
        fieldName.setPrefColumnCount(20);
        fieldName.setPromptText("Họ tên trên thẻ");
        fieldClass.setPromptText(ClassCode.PROMPT);
        valueMajor.setText("Chưa xác định");
        fieldAdminPin.setPrefColumnCount(9);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldStudentPin.setPrefColumnCount(7);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldCardId.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(cardIdError));
        fieldStudentId.textProperty().addListener(
                (observable, was, now) -> {
                    UiLabels.clearFieldError(studentIdError);
                    String major = StudentMajor.fromStudentId(now);
                    valueMajor.setText(major.isEmpty() ? "Chưa xác định" : major);
                });
        fieldClass.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(classError));
        fieldName.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(nameError));
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        fieldStudentPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(studentPinError));

        btnPersonalize.setOnAction(e -> personalize());
        btnNewCardId.setOnAction(e -> fieldCardId.setText(PersonalizeRequest.randomCardId()));
        btnPublicKey.setOnAction(e -> readPublicKey());
        UiLabels.apduHint(btnPersonalize, "PERSONALIZE");
        UiLabels.apduHint(btnPublicKey, "GET_CARD_PUBLIC_KEY");
        btnPersonalize.getStyleClass().add("la-action");
        btnNewCardId.getStyleClass().add("la-action");
        btnPublicKey.getStyleClass().add("la-action");

        for (TextField field : new TextField[] {
                fieldCardId, fieldStudentId, fieldName, fieldClass, fieldAdminPin, fieldStudentPin}) {
            field.setMinWidth(0);
            field.setMaxWidth(Double.MAX_VALUE);
        }
        HBox cardIdRow = new HBox(UiScale.SPACE_2, fieldCardId, btnNewCardId);
        cardIdRow.setAlignment(Pos.CENTER_LEFT);
        cardIdRow.setMinWidth(0);
        HBox.setHgrow(fieldCardId, Priority.ALWAYS);
        GridPane controls = new GridPane();
        controls.setHgap(UiScale.SPACE_4);
        controls.setVgap(UiScale.SPACE_2);
        for (int index = 0; index < 2; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            column.setMinWidth(0);
            column.setHgrow(Priority.ALWAYS);
            controls.getColumnConstraints().add(column);
        }
        controls.add(UiRows.field("Mã thẻ", cardIdRow, cardIdError), 0, 0);
        controls.add(UiRows.field("Mã sinh viên", fieldStudentId, studentIdError), 1, 0);
        controls.add(UiRows.field("Tên hiển thị", fieldName, nameError), 0, 1, 2, 1);
        controls.add(UiRows.field("Lớp", fieldClass, classError), 0, 2);
        controls.add(UiRows.valueBlock("Chuyên ngành", valueMajor), 1, 2);
        controls.add(UiRows.field("PIN quản trị", fieldAdminPin, adminPinError), 0, 3);
        controls.add(UiRows.field("PIN sinh viên", fieldStudentPin, studentPinError), 1, 3);
        controls.getChildren().forEach(child -> ((VBox) child).setMinWidth(0));

        VBox keyRow = UiRows.valueBlock("Khóa công khai", valuePublicKey);

        technical = new VBox(UiScale.SPACE_2,
                UiRows.wrapping(btnPublicKey, UiLabels.copyButton(valuePublicKey, "khóa công khai")), keyRow);
        UiLabels.show(technical, false);
        node = UiSection.of("Phát hành thẻ", controls, UiRows.wrapping(btnPersonalize), whyNote, technical);
        fillCardIdIfEmpty();
    }

    public VBox node() {
        return node;
    }

    public void showTechnical(boolean visible) {
        UiLabels.show(technical, visible);
    }

    public void updateAvailability(boolean personalizeEnabled, boolean publicKeyEnabled,
            String whyDisabled) {
        btnPersonalize.setDisable(!personalizeEnabled);
        btnPublicKey.setDisable(!publicKeyEnabled);
        UiLabels.setWhy(whyNote, whyDisabled);
    }

    public void setIssuePrimary(boolean preferred) {
        UiLabels.markPrimary(btnPersonalize, preferred && !btnPersonalize.isDisable());
    }

    public void clearInputs() {
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        for (Label error : new Label[] {
                cardIdError, studentIdError, nameError, classError, adminPinError, studentPinError}) {
            UiLabels.clearFieldError(error);
        }
    }

    public void resetView() {
        clearInputs();
        fillCardIdIfEmpty();
        valuePublicKey.setText("Chưa đọc khóa công khai");
    }

    void fillCardIdIfEmpty() {
        String current = fieldCardId.getText();
        if (current == null || current.isBlank()) {
            fieldCardId.setText(PersonalizeRequest.randomCardId());
        }
    }

    public void showPublicKey() {
        java.security.PublicKey key = gateway.cardPublicKey();
        if (key == null) {
            valuePublicKey.setText("Chưa đọc khóa công khai");
            return;
        }
        CardStatus current = gateway.status();
        String cardId = current == null ? "chưa nhận diện" : current.cardId();
        valuePublicKey.setText(String.format("%s | cardId=%s | %s",
                key.getAlgorithm() + "/" + key.getFormat(),
                cardId,
                HexFormat.of().formatHex(ReceiptVerifier.encodePoint(key)).toUpperCase()));
    }

    public boolean pressPersonalize() {
        return press(btnPersonalize);
    }

    public boolean pressNewCardId() {
        return press(btnNewCardId);
    }

    public boolean pressPublicKey() {
        return press(btnPublicKey);
    }

    public void fillInputs(String cardId, String studentId, String displayName,
            char[] adminPin, char[] studentPin) {
        fillInputs(cardId, studentId, displayName, "", adminPin, studentPin);
    }

    public void fillInputs(String cardId, String studentId, String displayName, String classCode,
            char[] adminPin, char[] studentPin) {
        fieldClass.setText(classCode);
        fieldCardId.setText(cardId);
        fieldStudentId.setText(studentId);
        fieldName.setText(displayName);
        fieldAdminPin.setText(new String(adminPin));
        fieldStudentPin.setText(new String(studentPin));
    }

    private boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }

    private void personalize() {
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        String cardId = fieldCardId.getText();
        String enteredStudentId = fieldStudentId.getText();
        String displayName = fieldName.getText();
        String classCode = ClassCode.normalize(fieldClass.getText());

        String problem = PersonalizeRequest.problem(
                adminPin, cardId, enteredStudentId, studentPin, displayName, classCode);
        showFieldProblems(adminPin, cardId, studentPin);
        if (problem != null) {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            javafx.scene.control.TextInputControl first = cardIdError.isVisible() ? fieldCardId
                    : studentIdError.isVisible() ? fieldStudentId
                    : nameError.isVisible() ? fieldName
                    : classError.isVisible() ? fieldClass
                    : adminPinError.isVisible() ? fieldAdminPin : fieldStudentPin;
            UiLabels.inputProblem(log, first, "Phát hành thẻ: " + problem);
            return;
        }
        String studentId = PersonalizeRequest.normalizeStudentId(enteredStudentId);
        fieldStudentId.setText(studentId);
        fieldClass.setText(classCode);
        String targetCardId = cardId.strip().toUpperCase(Locale.ROOT);
        if (!confirmIssue(targetCardId, studentId, displayName, classCode)) {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            log.accept("[INFO] PERSONALIZE: operator chưa xác nhận, không gửi PERSONALIZE");
            return;
        }
        fieldAdminPin.clear();
        fieldStudentPin.clear();

        runner.run("PERSONALIZE", PinOperation.of(() -> {
                String enrolled = enrolledCardProblem(targetCardId);
                if (enrolled != null) {
                    cardIdRejection = enrolled;
                    return CardOperationResult.fail(enrolled + " | không gửi APDU");
                }
                return gateway.personalize(adminPin, targetCardId, studentId, studentPin, displayName, classCode);
        }, adminPin, studentPin), ok -> {
            fieldCardId.setText(targetCardId);
            if (cardIdRejection != null) {
                String rejection = cardIdRejection;
                cardIdRejection = null;
                UiLabels.setFieldError(cardIdError, rejection);
                UiLabels.inputProblem(log, fieldCardId, "Phát hành thẻ: " + rejection);
            }
            afterPersonalize.accept(ok, ok ? new IssuedCard(targetCardId, studentId, classCode) : null);
        });
    }

    private String enrolledCardProblem(String cardIdHex) {
        try {
            return backend.cardEnrolled(cardIdHex) ? UiLabels.CARD_ID_ENROLLED : null;
        } catch (BackendClient.Failure failure) {
            return UiLabels.CARD_ID_NOT_CHECKED + " " + failure.getMessage();
        }
    }

    private void showFieldProblems(char[] adminPin, String cardId, char[] studentPin) {
        UiLabels.setFieldError(adminPinError, PinFormat.isDigits(adminPin,
                PersonalizeRequest.ADMIN_PIN_LENGTH)
                ? null
                : PersonalizeRequest.adminPinProblem() + ".");
        UiLabels.setFieldError(cardIdError, PersonalizeRequest.cardIdProblem(cardId));
        UiLabels.setFieldError(studentIdError,
                PersonalizeRequest.studentIdProblem(fieldStudentId.getText()));
        UiLabels.setFieldError(nameError,
                PersonalizeRequest.displayNameProblem(fieldName.getText()));
        UiLabels.setFieldError(classError, ClassCode.problem(fieldClass.getText()));
        UiLabels.setFieldError(studentPinError, PinFormat.isDigits(studentPin,
                PersonalizeRequest.STUDENT_PIN_LENGTH)
                ? null
                : PersonalizeRequest.studentPinProblem() + ".");
    }

    private boolean confirmIssue(String cardId, String studentId, String displayName, String classCode) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận phát hành thẻ");
        confirmation.setHeaderText("Phát hành thẻ với thông tin này?");
        confirmation.setContentText(String.format(
                "Mã thẻ: %s%nMã sinh viên: %s%nHọ tên: %s%nLớp: %s%nChuyên ngành: %s%n"
                        + "Hồ sơ được mã hóa và lưu trên thẻ.",
                cardId, studentId, displayName, classCode,
                StudentMajor.fromStudentId(studentId).isEmpty()
                        ? "Chưa xác định" : StudentMajor.fromStudentId(studentId)));
        return UiLabels.confirm(confirmation, node, "Phát hành thẻ");
    }

    private void readPublicKey() {
        runner.run("GET_CARD_PUBLIC_KEY", gateway::readCardPublicKey, ok -> {
            showPublicKey();
            log.accept("GUI hiển thị Public key = " + valuePublicKey.getText());
        });
    }
}
