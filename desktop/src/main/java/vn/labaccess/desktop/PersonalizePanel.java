package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class PersonalizePanel {

    /** Giá trị panel này truyền sang panel khác sau khi phát hành; không panel nào đọc control của panel kia. */
    record IssuedCard(String cardId, String studentId) {
    }

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final BiConsumer<Boolean, IssuedCard> afterPersonalize;

    private final TextField fieldCardId = new TextField();
    private final TextField fieldStudentId = new TextField();
    private final TextField fieldName = new TextField();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label cardIdError = UiLabels.fieldError();
    private final Label studentIdError = UiLabels.fieldError();
    private final Label nameError = UiLabels.fieldError();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label studentPinError = UiLabels.fieldError();
    private final Button btnPersonalize = new Button("Phát hành thẻ");
    private final Button btnPublicKey = new Button("Đọc khóa công khai");
    private final Label valuePublicKey = UiLabels.value();
    private final Label whyNote = UiLabels.whyNote();
    private final VBox node;
    private final VBox technical;

    PersonalizePanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            BiConsumer<Boolean, IssuedCard> afterPersonalize) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.afterPersonalize = afterPersonalize;

        fieldCardId.setPrefColumnCount(17);
        fieldCardId.setPromptText((PersonalizeRequest.CARD_ID_LENGTH * 2) + " ký tự hex");
        fieldStudentId.setPrefColumnCount(12);
        fieldStudentId.setPromptText("A-Z, 0-9, dấu -");
        fieldName.setPrefColumnCount(20);
        fieldName.setPromptText("Họ tên trên thẻ");
        fieldAdminPin.setPrefColumnCount(9);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldStudentPin.setPrefColumnCount(7);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldCardId.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(cardIdError));
        fieldStudentId.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(studentIdError));
        fieldName.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(nameError));
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        fieldStudentPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(studentPinError));

        btnPersonalize.setOnAction(e -> personalize());
        btnPublicKey.setOnAction(e -> readPublicKey());
        UiLabels.apduHint(btnPersonalize, "PERSONALIZE");
        UiLabels.apduHint(btnPublicKey, "GET_CARD_PUBLIC_KEY");
        btnPersonalize.getStyleClass().add("la-action");
        btnPublicKey.getStyleClass().add("la-action");

        for (TextField field : new TextField[] {
                fieldCardId, fieldStudentId, fieldName, fieldAdminPin, fieldStudentPin}) {
            field.setMinWidth(0);
            field.setMaxWidth(Double.MAX_VALUE);
        }
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
        controls.add(UiRows.field("Mã thẻ", fieldCardId, cardIdError), 0, 0);
        controls.add(UiRows.field("Mã sinh viên", fieldStudentId, studentIdError), 1, 0);
        controls.add(UiRows.field("Tên hiển thị", fieldName, nameError), 0, 1, 2, 1);
        controls.add(UiRows.field("PIN quản trị", fieldAdminPin, adminPinError), 0, 2);
        controls.add(UiRows.field("PIN học viên", fieldStudentPin, studentPinError), 1, 2);
        controls.getChildren().forEach(child -> ((VBox) child).setMinWidth(0));

        VBox keyRow = UiRows.valueBlock("Khóa công khai", valuePublicKey);

        technical = new VBox(UiScale.SPACE_2,
                UiRows.wrapping(btnPublicKey, UiLabels.copyButton(valuePublicKey, "khóa công khai")), keyRow);
        UiLabels.show(technical, false);
        node = UiSection.of("Phát hành thẻ", controls, UiRows.wrapping(btnPersonalize), whyNote, technical);
    }

    VBox node() {
        return node;
    }

    void showTechnical(boolean visible) {
        UiLabels.show(technical, visible);
    }

    void updateAvailability(boolean personalizeEnabled, boolean publicKeyEnabled,
            String whyDisabled) {
        btnPersonalize.setDisable(!personalizeEnabled);
        btnPublicKey.setDisable(!publicKeyEnabled);
        UiLabels.setWhy(whyNote, whyDisabled);
    }

    void setIssuePrimary(boolean preferred) {
        UiLabels.markPrimary(btnPersonalize, preferred && !btnPersonalize.isDisable());
    }

    void clearInputs() {
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        for (Label error : new Label[] {cardIdError, studentIdError, nameError, adminPinError, studentPinError}) {
            UiLabels.clearFieldError(error);
        }
    }

    void resetView() {
        clearInputs();
        valuePublicKey.setText("Chưa đọc khóa công khai");
    }

    void showPublicKey() {
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

    boolean pressPersonalize() {
        return press(btnPersonalize);
    }

    boolean pressPublicKey() {
        return press(btnPublicKey);
    }

    void fillInputs(String cardId, String studentId, String displayName,
            char[] adminPin, char[] studentPin) {
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
        String studentId = fieldStudentId.getText().strip();
        String displayName = fieldName.getText();

        String problem = PersonalizeRequest.problem(
                adminPin, cardId, studentId, studentPin, displayName);
        showFieldProblems(adminPin, cardId, studentPin);
        if (problem != null) {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            javafx.scene.control.TextInputControl first = cardIdError.isVisible() ? fieldCardId
                    : studentIdError.isVisible() ? fieldStudentId
                    : nameError.isVisible() ? fieldName
                    : adminPinError.isVisible() ? fieldAdminPin : fieldStudentPin;
            UiLabels.inputProblem(log, first, "Phát hành thẻ: " + problem);
            return;
        }
        if (!confirmIssue(cardId.strip().toUpperCase(), studentId, displayName)) {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            log.accept("[INFO] PERSONALIZE: operator chưa xác nhận, không gửi PERSONALIZE");
            return;
        }
        fieldAdminPin.clear();
        fieldStudentPin.clear();

        runner.run("PERSONALIZE", () -> {
            try {
                return gateway.personalize(adminPin, cardId, studentId, studentPin, displayName);
            } finally {
                Arrays.fill(studentPin, '\0');
                Arrays.fill(adminPin, '\0');
            }
        }, ok -> {
            fieldCardId.setText(cardId.strip().toUpperCase());
            afterPersonalize.accept(ok, ok
                    ? new IssuedCard(cardId.strip().toUpperCase(), studentId)
                    : null);
        });
    }

    private void showFieldProblems(char[] adminPin, String cardId, char[] studentPin) {
        UiLabels.setFieldError(adminPinError, App.validPin(adminPin,
                PersonalizeRequest.ADMIN_PIN_LENGTH)
                ? null
                : PersonalizeRequest.adminPinProblem() + ".");
        UiLabels.setFieldError(cardIdError, PersonalizeRequest.cardIdProblem(cardId));
        UiLabels.setFieldError(studentIdError,
                PersonalizeRequest.studentIdProblem(fieldStudentId.getText().strip()));
        UiLabels.setFieldError(nameError,
                PersonalizeRequest.displayNameProblem(fieldName.getText()));
        UiLabels.setFieldError(studentPinError, App.validPin(studentPin,
                PersonalizeRequest.STUDENT_PIN_LENGTH)
                ? null
                : PersonalizeRequest.studentPinProblem() + ".");
    }

    private boolean confirmIssue(String cardId, String studentId, String displayName) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận phát hành thẻ");
        confirmation.setHeaderText("Phát hành thẻ với thông tin này?");
        confirmation.setContentText(String.format(
                "Mã thẻ: %s%nMã sinh viên: %s%nHọ tên: %s%n"
                        + "Sau khi phát hành, không thể sửa lại thông tin đã ghi.",
                cardId, studentId, displayName));
        return UiLabels.confirm(confirmation, node, "Phát hành thẻ");
    }

    private void readPublicKey() {
        runner.run("GET_CARD_PUBLIC_KEY", gateway::readCardPublicKey, ok -> {
            showPublicKey();
            log.accept("GUI hiển thị Public key = " + valuePublicKey.getText());
        });
    }
}
