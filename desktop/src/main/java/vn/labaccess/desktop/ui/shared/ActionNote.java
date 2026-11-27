package vn.labaccess.desktop.ui.shared;

import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import vn.labaccess.desktop.ui.foundation.UiScale;

import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Reconciliation;

public final class ActionNote {

    private final Label label = new Label();
    private final Hyperlink details = new Hyperlink("Xem nhật ký");
    private final HBox node;

    public ActionNote(Runnable openLog) {
        label.getStyleClass().add("la-note");
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        details.setMinWidth(Region.USE_PREF_SIZE);
        details.setOnAction(event -> openLog.run());
        node = new HBox(UiScale.SPACE_2, label, details);
        node.setAlignment(Pos.CENTER_LEFT);
        show(null, UiLabels.Tone.NEUTRAL, false);
    }

    public HBox node() {
        return node;
    }

    public void showSession(PendingJournal.Loaded loaded, boolean reconnect, String mismatch,
            Reconciliation reconciliation, String lastFailure, UiLabels.Tone lastTone) {
        if (JournalRecoveryView.needed(loaded)) {
            show(JournalRecoveryView.summary(loaded) + " Mở Hướng dẫn khôi phục.",
                    UiLabels.Tone.ERROR, false);
        } else if (reconnect) {
            String channel = lastFailure != null && lastFailure.contains("Ngắt kết nối")
                    ? lastFailure
                    : (lastFailure == null ? "" : lastFailure + " ")
                            + "Kênh thẻ không còn sử dụng được. Ngắt kết nối rồi Kết nối lại.";
            show(channel + (loaded.present()
                    ? " Giữ nguyên giao dịch chờ để đối soát; không thực hiện lại." : ""),
                    UiLabels.Tone.PENDING, true);
        } else if (mismatch != null) {
            show(mismatch, UiLabels.Tone.PENDING, false);
        } else if (reconciliation.receiptUnverified()) {
            show("Chưa xác minh được biên nhận. Giao dịch đang khóa; nhập PIN rồi bấm Đọc thẻ"
                    + " để xác minh lại với sổ giao dịch.", UiLabels.Tone.PENDING, true);
        } else if (loaded.present() || !reconciliation.transactionAllowed()) {
            show("Có giao dịch chưa rõ kết quả. Không thực hiện lại; kiểm tra và đối soát trước.",
                    UiLabels.Tone.PENDING, true);
        } else if (lastFailure != null) {
            show(lastFailure, lastTone, true);
        } else {
            show(null, UiLabels.Tone.NEUTRAL, false);
        }

    }

    void show(String text, UiLabels.Tone tone, boolean showDetails) {
        boolean visible = text != null && !text.isBlank();
        label.setText(visible ? text : "");
        UiLabels.applyNoteTone(label, tone);
        UiLabels.show(details, showDetails);
        UiLabels.show(node, visible);
    }
}
