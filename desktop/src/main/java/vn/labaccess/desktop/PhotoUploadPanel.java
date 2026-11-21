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
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

final class PhotoUploadPanel {

    private static final int PREVIEW_SIZE = 160;
    private static final double ACTION_WIDTH = 192;

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Runnable onChanged;
    private final boolean combinedPanel;
    private final Button btnChoose = new Button("Chọn ảnh…");
    private final Button btnUpload = new Button("Tải ảnh lên");
    private final PasswordField fieldAdminPin = new PasswordField();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label draftError = UiLabels.fieldError();
    private final Label draftName = UiLabels.caption("Chưa chọn tệp");
    private final Label valueStatus = UiLabels.value();
    private final Label summaryStatus = new Label();
    private final Label why = UiLabels.whyNote();
    private final ImageView draftView = new ImageView();
    private final VBox technical;
    private final VBox node;

    private byte[] draft;
    private String currentCardId = "";
    private boolean cardHasPhoto;
    private String uploadFeedback = "";
    private boolean uploadFailed;
    private boolean panelEnabled;
    private String lockReason;
    private String draftLabel = "Chưa chọn ảnh";
    private long generation;

    PhotoUploadPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this(gateway, runner, log, () -> { }, false);
    }

    static PhotoUploadPanel forCombinedPanel(CardGateway gateway, CardOpRunner runner,
            Consumer<String> log, Runnable onChanged) {
        return new PhotoUploadPanel(gateway, runner, log, onChanged, true);
    }

    private PhotoUploadPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Runnable onChanged, boolean combinedPanel) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.onChanged = onChanged;
        this.combinedPanel = combinedPanel;

        fieldAdminPin.setPrefColumnCount(10);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldAdminPin.setMinWidth(0);
        fieldAdminPin.setMaxWidth(Double.MAX_VALUE);
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        btnChoose.setOnAction(e -> choose());
        btnUpload.setOnAction(e -> upload());
        UiLabels.apduHint(btnChoose, "chọn tệp trên máy, không gửi APDU");
        UiLabels.apduHint(btnUpload, "PHOTO_BEGIN + PHOTO_WRITE_CHUNK + PHOTO_FINISH");
        btnChoose.getStyleClass().add("la-action");
        btnUpload.getStyleClass().add("la-action");
        VBox actions = new VBox(UiScale.SPACE_2, btnChoose, draftError,
                UiRows.field("PIN quản trị", fieldAdminPin, adminPinError), btnUpload);
        actions.setMinWidth(ACTION_WIDTH);
        actions.setPrefWidth(ACTION_WIDTH);
        actions.setMaxWidth(ACTION_WIDTH);
        HBox upload = new HBox(UiScale.SPACE_3, previewColumn(), actions);
        Label title = new Label("Ghi ảnh lên thẻ");
        title.getStyleClass().add("la-title");
        VBox content = new VBox(UiScale.SPACE_2, title, upload);

        technical = UiRows.valueBlock("Chi tiết ảnh", valueStatus);
        UiLabels.show(technical, false);
        summaryStatus.getStyleClass().add("la-note");
        summaryStatus.setWrapText(true);
        Label warning = new Label("Ảnh lưu trên thẻ chưa được mã hóa AES.");
        warning.getStyleClass().add("la-note");
        UiLabels.applyNoteTone(warning, UiLabels.Tone.PENDING);
        warning.setGraphic(Icons.warning());
        warning.setWrapText(true);
        node = combinedPanel ? content : UiSection.of("Ảnh trên thẻ", why, content,
                new VBox(UiScale.SPACE_2, summaryStatus, technical), warning);
        refreshStatus();
        refreshActions();
    }

    private VBox previewColumn() {
        draftView.setFitWidth(PREVIEW_SIZE);
        draftView.setFitHeight(PREVIEW_SIZE);
        draftView.setPreserveRatio(true);
        draftView.setSmooth(false);
        Label placeholder = new Label("Chọn ảnh để xem trước");
        placeholder.getStyleClass().add("la-preview-empty");
        placeholder.setWrapText(true);
        placeholder.setMaxWidth(PREVIEW_SIZE - UiScale.SPACE_5 * 2);
        placeholder.visibleProperty().bind(draftView.imageProperty().isNull());
        placeholder.managedProperty().bind(placeholder.visibleProperty());
        StackPane frame = new StackPane(placeholder, draftView);
        frame.getStyleClass().add("la-preview");
        frame.setMinSize(PREVIEW_SIZE, PREVIEW_SIZE);
        frame.setPrefSize(PREVIEW_SIZE, PREVIEW_SIZE);
        frame.setMaxSize(PREVIEW_SIZE, PREVIEW_SIZE);
        draftName.setMinWidth(0);
        draftName.setMaxWidth(PREVIEW_SIZE);
        draftName.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        VBox column = new VBox(UiScale.SPACE_2, frame, draftName);
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
        refreshActions();
    }

    private void refreshActions() {
        btnChoose.setDisable(!panelEnabled);
        btnUpload.setDisable(!panelEnabled || draft == null || cardHasPhoto);
        fieldAdminPin.setDisable(!panelEnabled || cardHasPhoto);
        btnChoose.setText(combinedPanel && cardHasPhoto ? "Chọn ảnh đối chiếu…" : "Chọn ảnh…");
        String reason = lockReason != null ? lockReason
                : cardHasPhoto ? "Thẻ đã có ảnh, không thể ghi thêm."
                : draft == null ? "Tải ảnh lên: chọn ảnh trước." : null;
        UiLabels.setWhy(why, reason);
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

    private boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
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
            changed();
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
        changed();
    }

    void clearInputs() {
        fieldAdminPin.clear();
        UiLabels.clearFieldError(adminPinError);
    }

    void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!next.equals(currentCardId)) {
            resetView();
            currentCardId = next;
        }
    }

    void resetView() {
        generation++;
        clearInputs();
        currentCardId = "";
        cardHasPhoto = false;
        uploadFeedback = "";
        uploadFailed = false;
        clearDraft();
    }

    boolean hasDraft() {
        return draft != null;
    }

    byte[] draftBytes() {
        return draft == null ? null : draft.clone();
    }

    State state() {
        return new State(draftLabel, cardHasPhoto, uploadFeedback, uploadFailed);
    }

    record State(String draftLabel, boolean cardHasPhoto, String feedback, boolean failed) {
    }

    void photoReadSucceeded() {
        cardHasPhoto = true;
        uploadFeedback = "";
        uploadFailed = false;
        changed();
    }

    void fillAdminPin(char[] pin) {
        if (pin != null) {
            fieldAdminPin.setText(new String(pin));
        }
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
        byte[] image = draft.clone();
        long submittedGeneration = generation;
        boolean accepted = false;
        try {
            if (!confirmWrite()) {
                log.accept("[INFO] PHOTO upload: operator chưa xác nhận, không gửi lệnh ảnh");
                return;
            }
            if (submittedGeneration != generation || !panelEnabled || cardHasPhoto) {
                return;
            }
            fieldAdminPin.clear();
            accepted = runner.run("PHOTO upload", () -> {
                try {
                    return gateway.uploadPhoto(image, adminPin);
                } finally {
                    Arrays.fill(adminPin, '\0');
                }
            }, ok -> {
                if (submittedGeneration == generation) {
                    showUpload(ok, image);
                }
            });
        } finally {
            if (!accepted) {
                Arrays.fill(adminPin, '\0');
            }
        }
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
                : combinedPanel
                        ? "Chưa xác nhận ghi ảnh thành công. Đọc ảnh để kiểm tra trước khi ghi lại."
                        : "Chưa xác nhận ghi ảnh thành công. Không tự động ghi lại."
                                + " Kiểm tra trạng thái thẻ trước khi thử lại.";
        draftLabel = (ok ? "Bản nháp: đã nạp sha256 " : "Bản nháp: đã chọn, nạp THẤT BẠI sha256 ")
                + PhotoImage.fingerprint(image);
        changed();
    }

    private void changed() {
        refreshStatus();
        refreshActions();
        onChanged.run();
    }

    private void refreshStatus() {
        valueStatus.setText(draftLabel);
        summaryStatus.setText((draft == null ? "Chưa chọn ảnh nháp." : "Đã chọn ảnh nháp.")
                + (uploadFeedback.isEmpty() ? "" : " " + uploadFeedback));
        UiLabels.applyNoteTone(summaryStatus, uploadFailed ? UiLabels.Tone.ERROR
                : cardHasPhoto ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
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
