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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
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
    private String draftLabel = "Chưa chọn ảnh";
    private String cardLabel = "Chưa đọc ảnh từ thẻ";

    PhotoPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldAdminPin.setPrefColumnCount(10);
        fieldStudentPin.setPrefColumnCount(8);
        btnChoose.setOnAction(e -> choose());
        btnUpload.setOnAction(e -> upload());
        btnRead.setOnAction(e -> read());
        UiLabels.apduHint(btnChoose, "chọn tệp trên máy, không gửi APDU");
        UiLabels.apduHint(btnUpload, "PHOTO_BEGIN + PHOTO_WRITE_CHUNK + PHOTO_FINISH");
        UiLabels.apduHint(btnRead, "VERIFY_STUDENT_PIN + PHOTO_READ_CHUNK");
        btnChoose.getStyleClass().add("la-action");
        btnUpload.getStyleClass().add("la-action");
        btnRead.getStyleClass().add("la-action");

        FlowPane actions = UiRows.wrapping(btnChoose, btnUpload, btnRead,
                UiRows.field("PIN quản trị", fieldAdminPin),
                UiRows.field("PIN học viên", fieldStudentPin));

        HBox previews = new HBox(UiScale.SPACE_4,
                previewColumn("Ảnh nháp", draftView),
                previewColumn("Đọc từ thẻ", cardView));
        previews.setAlignment(Pos.TOP_LEFT);

        VBox statusRow = UiRows.valueBlock("Trạng thái ảnh", valueStatus);

        Label warning = new Label("Ảnh lưu trên thẻ chưa được mã hóa AES.");
        warning.getStyleClass().add("la-note");
        warning.setGraphic(Icons.warning());
        warning.setWrapText(true);

        VBox detail = new VBox(UiScale.SPACE_2, statusRow, warning);

        HBox body = new HBox(UiScale.SPACE_4, previews, detail);
        body.setAlignment(Pos.TOP_LEFT);

        node = UiSection.of("Ảnh trên thẻ — PIN quản trị để upload, PIN học viên để đọc",
                actions, body);
        clearDraft();
        clearCardImage("chưa đọc ảnh từ thẻ");
    }

    /** Khung ảnh cố định: viền và nền đặt trên StackPane để khung không co giãn theo ảnh. */
    private static VBox previewColumn(String caption, ImageView view) {
        view.setFitWidth(PREVIEW_SIZE);
        view.setFitHeight(PREVIEW_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(false);
        StackPane frame = new StackPane(view);
        frame.getStyleClass().add("la-preview");
        frame.setMinSize(PREVIEW_SIZE, PREVIEW_SIZE);
        frame.setPrefSize(PREVIEW_SIZE, PREVIEW_SIZE);
        frame.setMaxSize(PREVIEW_SIZE, PREVIEW_SIZE);
        VBox column = new VBox(UiScale.SPACE_1, UiLabels.caption(caption), frame);
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

    void setUploadPrimary(boolean preferred) {
        UiLabels.markPrimary(btnUpload, preferred && !btnUpload.isDisable());
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
            draftLabel = "Bản nháp: " + shortName(file) + " sha256 "
                    + PhotoImage.fingerprint(converted);
            refreshStatus();
            return true;
        } catch (IOException exception) {
            draft = null;
            draftView.setImage(null);
            draftLabel = "Chưa chọn ảnh";
            refreshStatus();
            log.accept("[FAIL] PHOTO chọn ảnh: " + exception.getMessage());
            return false;
        }
    }

    void clearDraft() {
        draft = null;
        draftView.setImage(null);
        draftLabel = "Chưa chọn ảnh";
        refreshStatus();
    }

    /** Về trạng thái ban đầu: dùng khi ngắt kết nối hoặc đổi thẻ. */
    void resetView() {
        clearDraft();
        clearCardImage("chưa đọc ảnh từ thẻ");
    }

    void clearCardImage(String reason) {
        cardImage = null;
        cardView.setImage(null);
        cardLabel = "Chưa đọc ảnh từ thẻ — " + reason;
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
        if (!isDigits(adminPin, ADMIN_PIN_LENGTH)) {
            Arrays.fill(adminPin, '\0');
            log.accept("[FAIL] upload ảnh: PIN quản trị phải đúng "
                    + ADMIN_PIN_LENGTH + " chữ số");
            return;
        }
        fieldAdminPin.clear();
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
        draftLabel = (ok ? "Bản nháp: đã nạp sha256 " : "Bản nháp: đã chọn, nạp THẤT BẠI sha256 ")
                + PhotoImage.fingerprint(image);
        refreshStatus();
    }

    private void read() {
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!isDigits(studentPin, STUDENT_PIN_LENGTH)) {
            Arrays.fill(studentPin, '\0');
            log.accept("[FAIL] đọc ảnh: PIN học viên phải đúng "
                    + STUDENT_PIN_LENGTH + " chữ số");
            clearCardImage("PIN học viên sai định dạng, chưa đọc");
            return;
        }
        fieldStudentPin.clear();
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
        cardLabel = "Trên thẻ: sha256 " + PhotoImage.fingerprint(photo);
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
