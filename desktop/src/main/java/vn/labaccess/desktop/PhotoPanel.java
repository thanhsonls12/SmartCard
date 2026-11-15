package vn.labaccess.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

final class PhotoPanel {

    static final int PREVIEW_SIZE = 160;
    private static final double ACTION_WIDTH = 192;

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;

    private final Button btnChoose = new Button("Chọn ảnh…");
    private final Button btnUpload = new Button("Tải ảnh lên");
    private final Button btnRead = new Button("Đọc ảnh");
    private final PasswordField fieldAdminPin = new PasswordField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label studentPinError = UiLabels.fieldError();
    private final Label draftError = UiLabels.fieldError();
    private final Label draftName = UiLabels.caption("Chưa chọn tệp");
    private final Label valueStatus = UiLabels.value();
    private final Label summaryStatus = new Label();
    private final VBox technical;
    private final Label why = UiLabels.whyNote();
    private final ImageView draftView = new ImageView();
    private final ImageView cardView = new ImageView();
    private final VBox node;

    private byte[] draft;
    private byte[] cardImage;
    private String currentCardId = "";
    private boolean cardHasPhoto;
    private String uploadFeedback = "";
    private boolean uploadFailed;
    private boolean panelEnabled;
    private String lockReason;
    private String draftLabel = "Chưa chọn ảnh";
    private String cardLabel = "Chưa đọc ảnh từ thẻ";

    PhotoPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;

        fieldAdminPin.setPrefColumnCount(10);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldStudentPin.setPrefColumnCount(8);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        fieldStudentPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(studentPinError));
        btnChoose.setOnAction(e -> choose());
        btnUpload.setOnAction(e -> upload());
        btnRead.setOnAction(e -> read());
        UiLabels.apduHint(btnChoose, "chọn tệp trên máy, không gửi APDU");
        UiLabels.apduHint(btnUpload, "PHOTO_BEGIN + PHOTO_WRITE_CHUNK + PHOTO_FINISH");
        UiLabels.apduHint(btnRead, "VERIFY_STUDENT_PIN + PHOTO_READ_CHUNK");
        btnChoose.getStyleClass().add("la-action");
        btnUpload.getStyleClass().add("la-action");
        btnRead.getStyleClass().add("la-action");

        for (PasswordField field : new PasswordField[] {fieldAdminPin, fieldStudentPin}) {
            field.setMinWidth(0);
            field.setMaxWidth(Double.MAX_VALUE);
        }
        VBox uploadActions = new VBox(UiScale.SPACE_2, btnChoose, draftError,
                UiRows.field("PIN quản trị", fieldAdminPin, adminPinError), btnUpload);
        VBox readActions = new VBox(UiScale.SPACE_2,
                UiRows.field("PIN học viên", fieldStudentPin, studentPinError), btnRead);
        for (VBox actions : new VBox[] {uploadActions, readActions}) {
            actions.setMinWidth(ACTION_WIDTH);
            actions.setPrefWidth(ACTION_WIDTH);
            actions.setMaxWidth(ACTION_WIDTH);
        }
        HBox upload = new HBox(UiScale.SPACE_3,
                previewColumn("Chọn ảnh để xem trước", draftView, draftName), uploadActions);
        HBox read = new HBox(UiScale.SPACE_3,
                previewColumn("Nhập PIN và đọc ảnh từ thẻ", cardView,
                        UiLabels.caption(PhotoImage.WIDTH + " × " + PhotoImage.HEIGHT + " px · ảnh xám")),
                readActions);
        Label uploadTitle = new Label("Ghi ảnh lên thẻ");
        Label readTitle = new Label("Đọc ảnh từ thẻ");
        uploadTitle.getStyleClass().add("la-title");
        readTitle.getStyleClass().add("la-title");
        var body = new UiColumns(2 * (PREVIEW_SIZE + ACTION_WIDTH + UiScale.SPACE_3) + UiScale.SPACE_4, 50,
                new VBox(UiScale.SPACE_2, uploadTitle, upload),
                new VBox(UiScale.SPACE_2, readTitle, read));

        technical = UiRows.valueBlock("Chi tiết ảnh", valueStatus);
        UiLabels.show(technical, false);
        summaryStatus.setId("photo-summary");
        summaryStatus.getStyleClass().add("la-note");
        summaryStatus.setWrapText(true);
        UiLabels.applyNoteTone(summaryStatus, UiLabels.Tone.NEUTRAL);
        VBox statusRow = new VBox(UiScale.SPACE_2, summaryStatus, technical);

        Label warning = new Label("Ảnh lưu trên thẻ chưa được mã hóa AES.");
        warning.getStyleClass().add("la-note");
        UiLabels.applyNoteTone(warning, UiLabels.Tone.PENDING);
        warning.setGraphic(Icons.warning());
        warning.setWrapText(true);

        node = UiSection.of("Ảnh trên thẻ", why, body, statusRow, warning);
        clearDraft();
        clearCardImage("chưa đọc ảnh từ thẻ");
    }

    private static VBox previewColumn(String emptyText, ImageView view, Label caption) {
        view.setFitWidth(PREVIEW_SIZE);
        view.setFitHeight(PREVIEW_SIZE);
        view.setPreserveRatio(true);
        view.setSmooth(false);
        Label placeholder = new Label(emptyText);
        placeholder.getStyleClass().add("la-preview-empty");
        placeholder.setWrapText(true);
        placeholder.setMaxWidth(PREVIEW_SIZE - UiScale.SPACE_5 * 2);
        placeholder.visibleProperty().bind(view.imageProperty().isNull());
        placeholder.managedProperty().bind(placeholder.visibleProperty());
        StackPane frame = new StackPane(placeholder, view);
        frame.getStyleClass().add("la-preview");
        frame.setMinSize(PREVIEW_SIZE, PREVIEW_SIZE);
        frame.setPrefSize(PREVIEW_SIZE, PREVIEW_SIZE);
        frame.setMaxSize(PREVIEW_SIZE, PREVIEW_SIZE);
        caption.setMinWidth(0);
        caption.setMaxWidth(PREVIEW_SIZE);
        caption.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        VBox column = new VBox(UiScale.SPACE_2, frame, caption);
        column.setAlignment(Pos.TOP_LEFT);
        return column;
    }

    VBox node() {
        return node;
    }

    void showTechnical(boolean visible) {
        UiLabels.show(technical, visible);
    }

    void setEnabled(boolean enabled, String whyDisabled) {
        panelEnabled = enabled;
        lockReason = whyDisabled;
        btnChoose.setDisable(!enabled);
        btnRead.setDisable(!enabled);
        fieldAdminPin.setDisable(!enabled || cardHasPhoto);
        fieldStudentPin.setDisable(!enabled);
        refreshActions();
    }

    private void refreshActions() {
        boolean uploadReady = panelEnabled && draft != null && !cardHasPhoto;
        btnUpload.setDisable(!uploadReady);
        fieldAdminPin.setDisable(!panelEnabled || cardHasPhoto);
        btnChoose.setText(cardHasPhoto ? "Chọn ảnh đối chiếu…" : "Chọn ảnh…");
        String reason = lockReason != null ? lockReason
                : cardHasPhoto ? "Thẻ đã có ảnh, không thể ghi thêm."
                        + " Chọn ảnh gốc rồi bấm Đọc ảnh để đối chiếu."
                : draft == null ? "Tải ảnh lên: chọn ảnh trước." : null;
        UiLabels.setWhy(why, reason);
    }

    void setUploadPrimary(boolean preferred) {
        UiLabels.markPrimary(btnUpload, preferred && !btnUpload.isDisable());
        UiLabels.markPrimary(btnRead, preferred && cardHasPhoto && !btnRead.isDisable());
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
            draftName.setText(String.valueOf(file.getFileName()));
            draftName.setTooltip(new Tooltip(draftName.getText()));
            draftLabel = "Bản nháp: " + draftName.getText() + " sha256 "
                    + PhotoImage.fingerprint(converted);
            UiLabels.clearFieldError(draftError);
            refreshStatus();
            refreshActions();
            return true;
        } catch (IOException exception) {
            UiLabels.setFieldError(draftError, draft == null
                    ? "Không đọc được ảnh. Chọn tệp ảnh khác."
                    : "Không đọc được ảnh mới. Đang giữ ảnh nháp trước đó.");
            log.accept("[FAIL] PHOTO chọn ảnh: " + exception.getMessage());
            return false;
        }
    }

    void clearDraft() {
        UiLabels.clearFieldError(draftError);
        draft = null;
        draftView.setImage(null);
        draftName.setText("Chưa chọn tệp");
        draftName.setTooltip(null);
        draftLabel = "Chưa chọn ảnh";
        refreshStatus();
        refreshActions();
    }

    void clearInputs() {
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        UiLabels.clearFieldError(adminPinError);
        UiLabels.clearFieldError(studentPinError);
    }

    void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!next.equals(currentCardId)) {
            resetView();
            currentCardId = next;
        }
    }

    void resetView() {
        clearInputs();
        currentCardId = "";
        cardHasPhoto = false;
        uploadFeedback = "";
        uploadFailed = false;
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
        if (!isDigits(adminPin, PersonalizeRequest.ADMIN_PIN_LENGTH)) {
            Arrays.fill(adminPin, '\0');
            UiLabels.setFieldError(adminPinError, PersonalizeRequest.adminPinProblem() + ".");
            UiLabels.inputProblem(log, fieldAdminPin, "Ghi ảnh: " + PersonalizeRequest.adminPinProblem());
            return;
        }
        UiLabels.clearFieldError(adminPinError);
        if (!confirmWrite()) {
            Arrays.fill(adminPin, '\0');
            log.accept("[INFO] PHOTO upload: operator chưa xác nhận, không gửi lệnh ảnh");
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

    private boolean confirmWrite() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận ghi ảnh lên thẻ");
        confirmation.setHeaderText("Ghi ảnh nháp lên thẻ?");
        confirmation.setContentText("Ảnh chỉ được ghi một lần. Sau khi ghi thành công,"
                + " không thể thay ảnh trên thẻ này. Thẻ đã có ảnh sẽ từ chối lệnh ghi.");
        return UiLabels.confirm(confirmation, node, "Ghi ảnh");
    }

    private void showUpload(boolean ok, byte[] image) {
        cardHasPhoto = cardHasPhoto || ok;
        uploadFailed = !ok;
        uploadFeedback = ok ? "Đã ghi ảnh lên thẻ."
                : "Chưa xác nhận ghi ảnh thành công. Đọc ảnh để kiểm tra trước khi ghi lại.";
        draftLabel = (ok ? "Bản nháp: đã nạp sha256 " : "Bản nháp: đã chọn, nạp THẤT BẠI sha256 ")
                + PhotoImage.fingerprint(image);
        refreshStatus();
        refreshActions();
    }

    private void read() {
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!isDigits(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH)) {
            Arrays.fill(studentPin, '\0');
            UiLabels.setFieldError(studentPinError, PersonalizeRequest.studentPinProblem() + ".");
            UiLabels.inputProblem(log, fieldStudentPin, "Đọc ảnh: " + PersonalizeRequest.studentPinProblem());
            clearCardImage("PIN học viên sai định dạng, chưa đọc");
            return;
        }
        UiLabels.clearFieldError(studentPinError);
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
        cardHasPhoto = true;
        uploadFeedback = "";
        uploadFailed = false;
        cardImage = photo;
        cardView.setImage(toImage(photo));
        cardLabel = "Trên thẻ: sha256 " + PhotoImage.fingerprint(photo);
        refreshStatus();
        refreshActions();
        log.accept("GUI hiển thị ảnh thẻ = " + cardLabel + " | trạng thái ảnh = "
                + valueStatus.getText());
    }

    private void refreshStatus() {
        valueStatus.setText(draftLabel + " | " + cardLabel);
        summaryStatus.setText((draft == null ? "Chưa chọn ảnh nháp." : "Đã chọn ảnh nháp.")
                + (uploadFeedback.isEmpty() ? "" : " " + uploadFeedback)
                + (cardImage != null ? " Đã đọc ảnh từ thẻ."
                        : cardHasPhoto ? " Chưa đọc lại ảnh. Nhập PIN học viên và bấm Đọc ảnh để kiểm tra."
                        : " Chưa đọc ảnh từ thẻ.")
                + (draft != null && cardImage != null
                        ? Arrays.equals(draft, cardImage) ? " Hai ảnh khớp nhau." : " Hai ảnh khác nhau."
                        : ""));
        UiLabels.applyNoteTone(summaryStatus, summaryTone());
    }

    private UiLabels.Tone summaryTone() {
        if (uploadFailed) {
            return UiLabels.Tone.ERROR;
        }
        if (draft == null) {
            return UiLabels.Tone.NEUTRAL;
        }
        if (cardImage == null) {
            return cardHasPhoto ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL;
        }
        return Arrays.equals(draft, cardImage) ? UiLabels.Tone.OK : UiLabels.Tone.ERROR;
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
