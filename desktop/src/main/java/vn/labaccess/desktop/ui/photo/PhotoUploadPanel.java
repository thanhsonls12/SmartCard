package vn.labaccess.desktop.ui.photo;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.PhotoImage;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.PinOperation;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class PhotoUploadPanel {

    private static final double ACTION_WIDTH = 192;

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Runnable onChanged;
    private final boolean combinedPanel;
    private final PhotoDraftPicker picker;
    private final Button btnUpload = new Button("Tải ảnh lên");
    private final PasswordField fieldAdminPin = new PasswordField();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label valueStatus = UiLabels.value();
    private final Label summaryStatus = new Label();
    private final Label why = UiLabels.whyNote();
    private final VBox technical;
    private final VBox node;

    private String currentCardId = "";
    private boolean cardHasPhoto;
    private String uploadFeedback = "";
    private boolean uploadFailed;
    private boolean panelEnabled;
    private String lockReason;
    private long generation;

    public PhotoUploadPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
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
        this.picker = new PhotoDraftPicker(log, this::changed);

        fieldAdminPin.setPrefColumnCount(10);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldAdminPin.setMinWidth(0);
        fieldAdminPin.setMaxWidth(Double.MAX_VALUE);
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> UiLabels.clearFieldError(adminPinError));
        btnUpload.setOnAction(e -> upload());
        UiLabels.apduHint(btnUpload, "PHOTO_BEGIN + PHOTO_WRITE_CHUNK + PHOTO_FINISH");
        btnUpload.getStyleClass().add("la-action");
        VBox actions = new VBox(UiScale.SPACE_2,
                UiRows.field("PIN quản trị", fieldAdminPin, adminPinError), btnUpload);
        actions.setMinWidth(ACTION_WIDTH);
        actions.setPrefWidth(ACTION_WIDTH);
        actions.setMaxWidth(ACTION_WIDTH);
        HBox upload = new HBox(UiScale.SPACE_3, picker.node(), actions);
        Label title = new Label("Ghi ảnh lên thẻ");
        title.getStyleClass().add("la-title");
        VBox content = new VBox(UiScale.SPACE_2, title, upload);

        technical = UiRows.valueBlock("Chi tiết ảnh", valueStatus);
        UiLabels.show(technical, false);
        summaryStatus.getStyleClass().add("la-note");
        summaryStatus.setWrapText(true);
        node = combinedPanel ? content : UiSection.of("Ảnh trên thẻ", why, content,
                new VBox(UiScale.SPACE_2, summaryStatus, technical));
        refreshStatus();
        refreshActions();
    }

    public VBox node() {
        return node;
    }

    public void showTechnical(boolean visible) {
        UiLabels.show(technical, visible);
    }

    public void setEnabled(boolean enabled, String whyDisabled) {
        panelEnabled = enabled;
        lockReason = whyDisabled;
        refreshActions();
    }

    private void refreshActions() {
        picker.setEnabled(panelEnabled);
        btnUpload.setDisable(!panelEnabled || !picker.hasDraft() || cardHasPhoto);
        fieldAdminPin.setDisable(!panelEnabled || cardHasPhoto);
        picker.setChooseCaption(combinedPanel && cardHasPhoto ? "Chọn ảnh đối chiếu…" : "Chọn ảnh…");
        String reason = lockReason != null ? lockReason
                : cardHasPhoto ? "Thẻ đã có ảnh, không thể ghi thêm."
                : !picker.hasDraft() ? "Tải ảnh lên: chọn ảnh trước." : null;
        UiLabels.setWhy(why, reason);
    }

    public void setUploadPrimary(boolean preferred) {
        UiLabels.markPrimary(btnUpload, preferred && !btnUpload.isDisable());
    }

    public boolean pressChoose() {
        return picker.pressChoose();
    }

    public boolean pressUpload() {
        if (btnUpload.isDisabled()) {
            return false;
        }
        btnUpload.fire();
        return true;
    }

    public boolean loadDraft(Path file) {
        return picker.loadDraft(file);
    }

    void clearDraft() {
        picker.clearDraft();
    }

    public void clearInputs() {
        fieldAdminPin.clear();
        UiLabels.clearFieldError(adminPinError);
    }

    public void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!next.equals(currentCardId)) {
            resetView();
            currentCardId = next;
        }
    }

    public void resetView() {
        generation++;
        clearInputs();
        currentCardId = "";
        cardHasPhoto = false;
        uploadFeedback = "";
        uploadFailed = false;
        picker.clearDraft();
    }

    public boolean hasDraft() {
        return picker.hasDraft();
    }

    public byte[] draftBytes() {
        return picker.draftBytes();
    }

    public State state() {
        return new State(picker.label(), cardHasPhoto, uploadFeedback, uploadFailed);
    }

    public record State(String draftLabel, boolean cardHasPhoto, String feedback, boolean failed) {
    }

    void photoReadSucceeded() {
        cardHasPhoto = true;
        uploadFeedback = "";
        uploadFailed = false;
        changed();
    }

    public void fillAdminPin(char[] pin) {
        if (pin != null) {
            fieldAdminPin.setText(new String(pin));
        }
    }

    private void upload() {
        if (!picker.hasDraft()) {
            log.accept("[FAIL] PHOTO upload: chưa chọn ảnh");
            return;
        }
        char[] adminPin = fieldAdminPin.getText().toCharArray();
        if (!PinFormat.isDigits(adminPin, PersonalizeRequest.ADMIN_PIN_LENGTH)) {
            Arrays.fill(adminPin, '\0');
            UiLabels.setFieldError(adminPinError, PersonalizeRequest.adminPinProblem() + ".");
            UiLabels.inputProblem(log, fieldAdminPin, "Ghi ảnh: " + PersonalizeRequest.adminPinProblem());
            return;
        }
        UiLabels.clearFieldError(adminPinError);
        byte[] image = picker.draftBytes();
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
            accepted = runner.run("PHOTO upload",
                    PinOperation.of(() -> gateway.uploadPhoto(image, adminPin), adminPin), ok -> {
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
        picker.setLabel((ok ? "Bản nháp: đã nạp sha256 " : "Bản nháp: đã chọn, nạp THẤT BẠI sha256 ")
                + PhotoImage.fingerprint(image));
        changed();
    }

    private void changed() {
        refreshStatus();
        refreshActions();
        onChanged.run();
    }

    private void refreshStatus() {
        valueStatus.setText(picker.label());
        summaryStatus.setText((picker.hasDraft() ? "Đã chọn ảnh nháp." : "Chưa chọn ảnh nháp.")
                + (uploadFeedback.isEmpty() ? "" : " " + uploadFeedback));
        UiLabels.applyNoteTone(summaryStatus, uploadFailed ? UiLabels.Tone.ERROR
                : cardHasPhoto ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
    }
}
