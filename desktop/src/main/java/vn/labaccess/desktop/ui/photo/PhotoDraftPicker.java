package vn.labaccess.desktop.ui.photo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import vn.labaccess.desktop.ui.foundation.UiScale;

import vn.labaccess.desktop.card.PhotoImage;

import vn.labaccess.desktop.ui.shared.PhotoPreview;
import vn.labaccess.desktop.ui.shared.UiLabels;

public final class PhotoDraftPicker {

    private static final String EMPTY_LABEL = "Chưa chọn ảnh";
    private static final String CHOOSE_CAPTION = "Chọn ảnh…";

    private final Consumer<String> log;
    private final Runnable onChanged;
    private final Button btnChoose = new Button(CHOOSE_CAPTION);
    private final Label draftError = UiLabels.fieldError();
    private final Label draftName = UiLabels.caption("Chưa chọn tệp");
    private final ImageView draftView = new ImageView();
    private final StackPane preview;
    private final VBox node;

    private byte[] draft;
    private String label = EMPTY_LABEL;

    public PhotoDraftPicker(Consumer<String> log, Runnable onChanged) {
        this.log = log;
        this.onChanged = onChanged;
        btnChoose.setOnAction(event -> choose());
        btnChoose.getStyleClass().add("la-action");
        UiLabels.apduHint(btnChoose, "chọn tệp trên máy, không gửi APDU");
        preview = previewFrame();
        node = new VBox(UiScale.SPACE_2, preview, draftName, btnChoose, draftError);
        node.setAlignment(Pos.TOP_LEFT);
        node.setMaxHeight(Double.MAX_VALUE);
    }

    public VBox node() {
        return node;
    }

    public void setEnabled(boolean enabled) {
        btnChoose.setDisable(!enabled);
    }

    void setChooseCaption(String caption) {
        btnChoose.setText(caption);
    }

    boolean pressChoose() {
        if (btnChoose.isDisabled()) {
            return false;
        }
        btnChoose.fire();
        return true;
    }

    public boolean loadDraft(Path file) {
        try {
            byte[] converted = PhotoDraft.fromFile(file);
            draft = converted;
            draftView.setImage(toImage(converted));
            draftName.setText(String.valueOf(file.getFileName()));
            draftName.setTooltip(new Tooltip(draftName.getText()));
            label = "Bản nháp: " + draftName.getText() + " sha256 " + PhotoImage.fingerprint(converted);
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

    public void clearDraft() {
        UiLabels.clearFieldError(draftError);
        draft = null;
        draftView.setImage(null);
        draftName.setText("Chưa chọn tệp");
        draftName.setTooltip(null);
        label = EMPTY_LABEL;
        changed();
    }

    public boolean hasDraft() {
        return draft != null;
    }

    public byte[] draftBytes() {
        return draft == null ? null : draft.clone();
    }

    String label() {
        return label;
    }

    public void setLabel(String text) {
        label = text;
    }

    private StackPane previewFrame() {
        Label placeholder = new Label("Chọn ảnh để xem trước");
        placeholder.getStyleClass().add("la-preview-empty");
        StackPane frame = PhotoPreview.frame(draftView, placeholder);
        draftName.setMinWidth(0);
        draftName.maxWidthProperty().bind(frame.widthProperty());
        draftName.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        return frame;
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

    private void changed() {
        onChanged.run();
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
