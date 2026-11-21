package vn.labaccess.desktop;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class PhotoPanel {

    static final int PREVIEW_SIZE = 160;
    private static final double ACTION_WIDTH = 192;

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final PhotoUploadPanel uploadPanel;

    private final Button btnRead = new Button("Đọc ảnh");
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Label studentPinError = UiLabels.fieldError();
    private final Label valueStatus = UiLabels.value();
    private final Label summaryStatus = new Label();
    private final VBox technical;
    private final Label why = UiLabels.whyNote();
    private final ImageView cardView = new ImageView();
    private final VBox node;

    private byte[] cardImage;
    private String currentCardId = "";
    private String lockReason;
    private String cardLabel = "Chưa đọc ảnh từ thẻ";

    PhotoPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        uploadPanel = PhotoUploadPanel.forCombinedPanel(gateway, runner, log, this::uploadChanged);

        fieldStudentPin.setPrefColumnCount(8);
        fieldStudentPin.setPromptText(PersonalizeRequest.STUDENT_PIN_LENGTH + " chữ số");
        fieldStudentPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(studentPinError));
        btnRead.setOnAction(e -> read());
        UiLabels.apduHint(btnRead, "VERIFY_STUDENT_PIN + PHOTO_READ_CHUNK");
        btnRead.getStyleClass().add("la-action");
        fieldStudentPin.setMinWidth(0);
        fieldStudentPin.setMaxWidth(Double.MAX_VALUE);
        VBox readActions = new VBox(UiScale.SPACE_2,
                UiRows.field("PIN học viên", fieldStudentPin, studentPinError), btnRead);
        readActions.setMinWidth(ACTION_WIDTH);
        readActions.setPrefWidth(ACTION_WIDTH);
        readActions.setMaxWidth(ACTION_WIDTH);
        HBox read = new HBox(UiScale.SPACE_3,
                previewColumn("Nhập PIN và đọc ảnh từ thẻ", cardView,
                        UiLabels.caption(PhotoImage.WIDTH + " × " + PhotoImage.HEIGHT + " px · ảnh xám")),
                readActions);
        Label readTitle = new Label("Đọc ảnh từ thẻ");
        readTitle.getStyleClass().add("la-title");
        var body = new UiColumns(2 * (PREVIEW_SIZE + ACTION_WIDTH + UiScale.SPACE_3) + UiScale.SPACE_4, 50,
                uploadPanel.node(), new VBox(UiScale.SPACE_2, readTitle, read));

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
        lockReason = whyDisabled;
        uploadPanel.setEnabled(enabled, whyDisabled);
        btnRead.setDisable(!enabled);
        fieldStudentPin.setDisable(!enabled);
        refreshActions();
    }

    private void refreshActions() {
        String reason = lockReason != null ? lockReason
                : uploadPanel.state().cardHasPhoto() ? "Thẻ đã có ảnh, không thể ghi thêm."
                        + " Chọn ảnh gốc rồi bấm Đọc ảnh để đối chiếu."
                : !hasDraft() ? "Tải ảnh lên: chọn ảnh trước." : null;
        UiLabels.setWhy(why, reason);
    }

    private void uploadChanged() {
        refreshStatus();
        refreshActions();
    }

    void setUploadPrimary(boolean preferred) {
        uploadPanel.setUploadPrimary(preferred);
        UiLabels.markPrimary(btnRead, preferred && uploadPanel.state().cardHasPhoto() && !btnRead.isDisable());
    }

    boolean pressChoose() {
        return uploadPanel.pressChoose();
    }

    boolean pressUpload() {
        return uploadPanel.pressUpload();
    }

    boolean pressRead() {
        return press(btnRead);
    }

    boolean loadDraft(Path file) {
        return uploadPanel.loadDraft(file);
    }

    void clearDraft() {
        uploadPanel.clearDraft();
    }

    void clearInputs() {
        uploadPanel.clearInputs();
        fieldStudentPin.clear();
        UiLabels.clearFieldError(studentPinError);
    }

    void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!next.equals(currentCardId)) {
            resetView();
            currentCardId = next;
            uploadPanel.onCard(next);
        }
    }

    void resetView() {
        clearInputs();
        currentCardId = "";
        uploadPanel.resetView();
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
        return uploadPanel.hasDraft();
    }

    boolean hasCardImage() {
        return cardImage != null;
    }

    byte[] draftBytes() {
        return uploadPanel.draftBytes();
    }

    byte[] cardBytes() {
        return cardImage == null ? null : cardImage.clone();
    }

    String statusText() {
        return valueStatus.getText();
    }

    void fillAdminPin(char[] pin) {
        uploadPanel.fillAdminPin(pin);
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
        boolean accepted = false;
        try {
            accepted = runner.run("PHOTO read", () -> {
                try {
                    return gateway.readPhoto(studentPin);
                } finally {
                    Arrays.fill(studentPin, '\0');
                }
            }, this::showRead);
        } finally {
            if (!accepted) {
                Arrays.fill(studentPin, '\0');
            }
        }
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
        uploadPanel.photoReadSucceeded();
        log.accept("GUI hiển thị ảnh thẻ = " + cardLabel + " | trạng thái ảnh = "
                + valueStatus.getText());
    }

    private void refreshStatus() {
        PhotoUploadPanel.State upload = uploadPanel.state();
        byte[] draft = draftBytes();
        valueStatus.setText(upload.draftLabel() + " | " + cardLabel);
        summaryStatus.setText((draft == null ? "Chưa chọn ảnh nháp." : "Đã chọn ảnh nháp.")
                + (upload.feedback().isEmpty() ? "" : " " + upload.feedback())
                + (cardImage != null ? " Đã đọc ảnh từ thẻ."
                        : upload.cardHasPhoto() ? " Chưa đọc lại ảnh. Nhập PIN học viên và bấm Đọc ảnh để kiểm tra."
                        : " Chưa đọc ảnh từ thẻ.")
                + (draft != null && cardImage != null
                        ? Arrays.equals(draft, cardImage) ? " Hai ảnh khớp nhau." : " Hai ảnh khác nhau."
                        : ""));
        UiLabels.applyNoteTone(summaryStatus, summaryTone(upload, draft));
    }

    private UiLabels.Tone summaryTone(PhotoUploadPanel.State upload, byte[] draft) {
        if (upload.failed()) {
            return UiLabels.Tone.ERROR;
        }
        if (draft == null) {
            return UiLabels.Tone.NEUTRAL;
        }
        if (cardImage == null) {
            return upload.cardHasPhoto() ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL;
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
