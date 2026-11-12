package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class PersonalizePanel {

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Consumer<Boolean> afterPersonalize;

    private final TextField fieldCardId = new TextField();
    private final TextField fieldStudentId = new TextField();
    private final TextField fieldName = new TextField();
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Button btnPersonalize = new Button("Phát hành thẻ");
    private final Button btnPublicKey = new Button("Đọc khóa công khai");
    private final Label valuePublicKey = UiLabels.value();
    private final VBox node;

    PersonalizePanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Consumer<Boolean> afterPersonalize) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.afterPersonalize = afterPersonalize;

        fieldCardId.setPrefColumnCount(17);
        fieldCardId.setPromptText("16 ký tự hex");
        fieldStudentId.setPrefColumnCount(12);
        fieldStudentId.setPromptText("A-Z, 0-9, dấu -");
        fieldName.setPrefColumnCount(20);
        fieldName.setPromptText("tên, tối đa 96 byte");
        fieldAdminPin.setPrefColumnCount(9);
        fieldStudentPin.setPrefColumnCount(7);

        btnPersonalize.setOnAction(e -> personalize());
        btnPublicKey.setOnAction(e -> readPublicKey());
        UiLabels.apduHint(btnPersonalize, "PERSONALIZE");
        UiLabels.apduHint(btnPublicKey, "GET_CARD_PUBLIC_KEY");
        btnPersonalize.getStyleClass().add("la-action");
        btnPublicKey.getStyleClass().add("la-action");

        FlowPane controls = UiRows.wrapping(
                UiRows.field("Mã thẻ", fieldCardId),
                UiRows.field("Mã sinh viên", fieldStudentId),
                UiRows.field("Tên hiển thị", fieldName),
                UiRows.field("PIN quản trị", fieldAdminPin),
                UiRows.field("PIN học viên", fieldStudentPin),
                btnPersonalize, btnPublicKey);

        VBox keyRow = UiRows.valueBlock("Khóa công khai", valuePublicKey);

        node = UiSection.of("Phát hành thẻ", controls, keyRow);
    }

    VBox node() {
        return node;
    }

    void updateAvailability(boolean personalizeEnabled, boolean publicKeyEnabled) {
        btnPersonalize.setDisable(!personalizeEnabled);
        btnPublicKey.setDisable(!publicKeyEnabled);
    }

    void setIssuePrimary(boolean preferred) {
        UiLabels.markPrimary(btnPersonalize, preferred && !btnPersonalize.isDisable());
    }

    void resetView() {
        valuePublicKey.setText("Chưa đọc khóa công khai");
    }

    void showPublicKey() {
        java.security.PublicKey key = gateway.cardPublicKey();
        if (key == null) {
            valuePublicKey.setText("Chưa đọc khóa công khai");
            return;
        }
        CardGateway.Status current = gateway.status();
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
        if (problem != null) {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            log.accept("[FAIL] phát hành thẻ: " + problem);
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
            afterPersonalize.accept(ok);
        });
    }

    private void readPublicKey() {
        runner.run("GET_CARD_PUBLIC_KEY", gateway::readCardPublicKey, ok -> {
            showPublicKey();
            log.accept("GUI hiển thị Public key = " + valuePublicKey.getText());
        });
    }
}
