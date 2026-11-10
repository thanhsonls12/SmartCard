package vn.labaccess.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

final class PhotoPanel {

    static final int PREVIEW_SIZE = 64;
    private static final int ADMIN_PIN_LENGTH = 8;
    private static final int STUDENT_PIN_LENGTH = 6;

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final Button btnChoose = new Button("Chọn ảnh…");
    private final Button btnUpload = new Button("Upload ảnh");
    private final Button btnRead = new Button("Đọc ảnh");
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label valueStatus = UiLabels.value();
    private final ImageView draftView = new ImageView();
    private final ImageView cardView = new ImageView();
    private final VBox node;

    private byte[] draft;
    private byte[] cardImage;
    private String draftLabel = "nháp: (trống)";
    private String cardLabel = "thẻ: (trống)";

    PhotoPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldAdminPin.setPrefColumnCount(10);
        fieldStudentPin.setPrefColumnCount(8);
        btnChoose.setOnAction(e -> choose());
        btnUpload.setOnAction(e -> upload());
        btnRead.setOnAction(e -> read());

        HBox actions = new HBox(8, btnChoose, btnUpload, btnRead,
                UiLabels.caption("Admin PIN"), fieldAdminPin,
                UiLabels.caption("Student PIN"), fieldStudentPin);
        actions.setAlignment(Pos.CENTER_LEFT);

        HBox previews = new HBox(16,
                previewColumn("Ảnh nháp", draftView),
                previewColumn("Đọc từ thẻ", cardView));
        previews.setAlignment(Pos.TOP_LEFT);

        HBox statusRow = new HBox(8, UiLabels.caption("Trạng thái ảnh"), valueStatus);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Label warning = new Label("Ảnh lưu trên thẻ chưa được mã hóa AES.");
        warning.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;"
                + " -fx-text-fill: #B35C00;");

        VBox detail = new VBox(6, statusRow, warning);
        detail.setAlignment(Pos.CENTER_LEFT);

        HBox body = new HBox(16, previews, detail);
        body.setAlignment(Pos.TOP_LEFT);

        node = new VBox(6, actions, body);
        node.setPadding(new Insets(0, 10, 10, 10));
        clearDraft();
        clearCardImage("chưa đọc ảnh từ thẻ");
    }

    private static VBox previewColumn(String caption, ImageView view) {
        view.setFitWidth(PREVIEW_SIZE);
        view.setFitHeight(PREVIEW_SIZE);
        view.setSmooth(false);
        view.setStyle("-fx-border-color: #BBBBBB; -fx-border-width: 1;");
        VBox column = new VBox(2, UiLabels.caption(caption), view);
        column.setAlignment(Pos.TOP_LEFT);
        return column;
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled) {
        btnChoose.setDisable(!enabled);
        btnUpload.setDisable(!enabled);
        btnRead.setDisable(!enabled);
        fieldAdminPin.setDisable(!enabled);
        fieldStudentPin.setDisable(!enabled);
    }

    boolean pressChoose() {
        return press(btnChoose);
    }

    boolean pressUpload() {
        return press(btnUpload);
    }

    boolean pressRead() {
        return press(btnRead);
    }

    boolean loadDraft(Path file) {
        try {
            byte[] converted = PhotoDraft.fromFile(file);
            draft = converted;
            draftView.setImage(toImage(converted));
            draftLabel = "nháp: " + shortName(file) + " sha256 "
                    + PhotoImage.fingerprint(converted);
            refreshStatus();
            return true;
        } catch (IOException exception) {
            draft = null;
            draftView.setImage(null);
            draftLabel = "nháp: (trống)";
            refreshStatus();
            log.accept("[FAIL] PHOTO chọn ảnh: " + exception.getMessage());
            return false;
        }
    }

    void clearDraft() {
        draft = null;
        draftView.setImage(null);
        draftLabel = "nháp: (trống)";
        refreshStatus();
    }

    void clearCardImage(String reason) {
        cardImage = null;
        cardView.setImage(null);
        cardLabel = "thẻ: (trống) — " + reason;
        refreshStatus();
        log.accept("GUI hiển thị ảnh thẻ = (trống) | trạng thái ảnh = " + valueStatus.getText());
    }

    boolean hasDraft() {
        return draft != null;
    }

    boolean hasCardImage() {
        return cardImage != null;
    }

    byte[] draftBytes() {
        return draft == null ? null : draft.clone();
    }

    byte[] cardBytes() {
        return cardImage == null ? null : cardImage.clone();
    }

    String statusText() {
        return valueStatus.getText();
    }

    void fillAdminPin(char[] pin) {
        if (pin != null) {
            fieldAdminPin.setText(new String(pin));
        }
    }

    void fillStudentPin(char[] pin) {
        if (pin != null) {
            fieldStudentPin.setText(new String(pin));
        }
    }

    private boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }

    private void choose() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh (chuyển thành grayscale 64×64)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"),
                new FileChooser.ExtensionFilter("Tất cả tệp", "*.*"));
        File file = chooser.showOpenDialog(node.getScene() == null
                ? null : node.getScene().getWindow());
        if (file != null) {
            loadDraft(file.toPath());
        }
    }

    private void upload() {
        if (draft == null) {
            log.accept("[FAIL] PHOTO upload: chưa chọn ảnh");
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        fieldAdminPin.clear();
        if (!isDigits(adminPin, ADMIN_PIN_LENGTH)) {
            Arrays.fill(adminPin, '\0');
            log.accept("[FAIL] PHOTO upload: admin PIN phải đúng "
                    + ADMIN_PIN_LENGTH + " chữ số (chưa gửi APDU)");
            return;
        }
        byte[] image = draft.clone();
        runner.run("PHOTO upload", () -> {
            try {
                return gateway.uploadPhoto(image, adminPin);
            } finally {
                Arrays.fill(adminPin, '\0');
            }
        }, ok -> showUpload(ok, image));
    }

    private void showUpload(boolean ok, byte[] image) {
        draftLabel = (ok ? "nháp: đã nạp sha256 " : "nháp: đã chọn, nạp THẤT BẠI sha256 ")
                + PhotoImage.fingerprint(image);
        refreshStatus();
    }

    private void read() {
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        if (!isDigits(studentPin, STUDENT_PIN_LENGTH)) {
            Arrays.fill(studentPin, '\0');
            log.accept("[FAIL] PHOTO read: student PIN phải đúng "
                    + STUDENT_PIN_LENGTH + " chữ số (chưa gửi APDU)");
            clearCardImage("student PIN sai định dạng nên chưa đọc");
            return;
        }
        runner.run("PHOTO read", () -> {
            try {
                return gateway.readPhoto(studentPin);
            } finally {
                Arrays.fill(studentPin, '\0');
            }
        }, this::showRead);
    }

    private void showRead(boolean ok) {
        byte[] photo = gateway.lastPhoto();
        if (!ok || photo == null || photo.length != PhotoImage.LENGTH) {
            clearCardImage("đọc ảnh thất bại");
            return;
        }
        cardImage = photo;
        cardView.setImage(toImage(photo));
        cardLabel = "thẻ: sha256 " + PhotoImage.fingerprint(photo);
        refreshStatus();
        log.accept("GUI hiển thị ảnh thẻ = " + cardLabel + " | trạng thái ảnh = "
                + valueStatus.getText());
    }

    private void refreshStatus() {
        valueStatus.setText(draftLabel + " | " + cardLabel);
    }

    private static String shortName(Path file) {
        String name = String.valueOf(file.getFileName());
        return name.length() <= 24 ? name : name.substring(0, 21) + "...";
    }

    private static boolean isDigits(char[] value, int length) {
        if (value == null || value.length != length) {
            return false;
        }
        for (char character : value) {
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static WritableImage toImage(byte[] gray) {
        WritableImage image = new WritableImage(PhotoImage.WIDTH, PhotoImage.HEIGHT);
        PixelWriter writer = image.getPixelWriter();
        for (int row = 0; row < PhotoImage.HEIGHT; row++) {
            for (int column = 0; column < PhotoImage.WIDTH; column++) {
                int value = gray[row * PhotoImage.WIDTH + column] & 0xFF;
                writer.setArgb(column, row, 0xFF000000 | (value << 16) | (value << 8) | value);
            }
        }
        return image;
    }
}
