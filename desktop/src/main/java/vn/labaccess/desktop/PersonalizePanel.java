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
    private final Button btnPersonalize = new Button("PERSONALIZE");
    private final Button btnPublicKey = new Button("GET_CARD_PUBLIC_KEY");
    private final Label valuePublicKey = UiLabels.value();
    private final VBox node;

    PersonalizePanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Consumer<Boolean> afterPersonalize) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.afterPersonalize = afterPersonalize;

        fieldCardId.setPrefColumnCount(17);
        fieldCardId.setPromptText("cardId 16 hex");
        fieldStudentId.setPrefColumnCount(12);
        fieldStudentId.setPromptText("studentId A-Z 0-9 -");
        fieldName.setPrefColumnCount(20);
        fieldName.setPromptText("tên hiển thị UTF-8 ≤96 byte");
        fieldAdminPin.setPrefColumnCount(9);
        fieldStudentPin.setPrefColumnCount(7);

        btnPersonalize.setOnAction(e -> personalize());
        btnPublicKey.setOnAction(e -> readPublicKey());

        HBox controls = new HBox(8,
                UiLabels.caption("cardId"), fieldCardId,
                UiLabels.caption("studentId"), fieldStudentId,
                UiLabels.caption("tên"), fieldName,
                UiLabels.caption("admin PIN"), fieldAdminPin,
                UiLabels.caption("student PIN"), fieldStudentPin,
                btnPersonalize, btnPublicKey);
        controls.setAlignment(Pos.CENTER_LEFT);

        HBox keyRow = new HBox(8, UiLabels.caption("Public key"), valuePublicKey);
        keyRow.setAlignment(Pos.CENTER_LEFT);

        node = new VBox(6, controls, keyRow);
        node.setPadding(new Insets(0, 10, 10, 10));
    }

    VBox node() {
        return node;
    }

    void updateAvailability(boolean personalizeEnabled, boolean publicKeyEnabled) {
        btnPersonalize.setDisable(!personalizeEnabled);
        btnPublicKey.setDisable(!publicKeyEnabled);
    }

    void resetView() {
        valuePublicKey.setText("-");
    }

    void showPublicKey() {
        java.security.PublicKey key = gateway.cardPublicKey();
        if (key == null) {
            valuePublicKey.setText("-");
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
        fieldAdminPin.clear();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        String cardId = fieldCardId.getText();
        String studentId = fieldStudentId.getText().strip();
        String displayName = fieldName.getText();

        String problem = PersonalizeRequest.problem(
                adminPin, cardId, studentId, studentPin, displayName);
        if (problem != null) {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            log.accept("[FAIL] PERSONALIZE: " + problem + " — không gửi APDU");
            return;
        }

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
