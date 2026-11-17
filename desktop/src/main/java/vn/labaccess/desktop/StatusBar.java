package vn.labaccess.desktop;

import java.util.function.IntConsumer;

import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

final class StatusBar {

    private final Label status = new Label("Chưa kết nối");
    private final Hyperlink nextStep = new Hyperlink();
    private final ProgressIndicator spinner =
            new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
    private final HBox node;
    private int nextStepTab = -1;

    StatusBar(IntConsumer tabOpener) {
        status.setId("status");
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.getStyleClass().add("la-status");

        spinner.getStyleClass().add("la-spinner");
        spinner.setPrefSize(UiScale.SPACE_3, UiScale.SPACE_3);
        spinner.setMinSize(UiScale.SPACE_3, UiScale.SPACE_3);
        spinner.setMaxSize(UiScale.SPACE_3, UiScale.SPACE_3);
        spinner.setVisible(false);
        spinner.setManaged(false);

        nextStep.setId("next-step");
        nextStep.setWrapText(true);
        nextStep.setMinWidth(0);
        nextStep.setMaxWidth(Double.MAX_VALUE);
        nextStep.getStyleClass().add("la-next-step");
        nextStep.setFocusTraversable(true);
        nextStep.setOnAction(event -> tabOpener.accept(nextStepTab));

        HBox feedback = new HBox(UiScale.SPACE_2, spinner, status);
        feedback.setAlignment(Pos.CENTER_RIGHT);
        feedback.setMinWidth(0);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox.setHgrow(feedback, Priority.ALWAYS);

        node = new HBox(UiScale.SPACE_3, nextStep, feedback);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("la-status-bar");
        node.setPadding(UiScale.statusBarInsets());
    }

    HBox node() {
        return node;
    }

    void showFeedback(boolean busy, String busyLabel, PendingJournal.Loaded loaded,
            Reconciliation reconciliation, String inputProblem, String lastFeedback, UiLabels.Tone lastTone,
            String expectedCardId) {
        String feedback;
        UiLabels.Tone tone;
        if (busy) {
            feedback = "Đang xử lý: " + (busyLabel == null ? "…" : UiLabels.actionName(busyLabel));
            tone = UiLabels.Tone.PENDING;
        } else if (expectedCardId != null) {
            feedback = "Giao dịch đang chờ đúng thẻ " + expectedCardId;
            tone = UiLabels.Tone.PENDING;
        } else if (loaded.present()) {
            feedback = loaded.valid() && loaded.status() == PendingJournal.Status.PENDING
                    ? "Có biên nhận chờ đối soát — đối soát trước khi giao dịch tiếp"
                    : "Giao dịch đang khóa. Xem Hướng dẫn khôi phục.";
            tone = loaded.valid() && loaded.status() == PendingJournal.Status.PENDING
                    ? UiLabels.Tone.PENDING : UiLabels.Tone.ERROR;
        } else if (reconciliation.receiptUnverified()) {
            feedback = "Chưa xác minh được biên nhận — Đọc thẻ để xác minh lại";
            tone = UiLabels.Tone.PENDING;
        } else if (!reconciliation.transactionAllowed()) {
            feedback = "Cần đối soát giao dịch trước khi tiếp tục";
            tone = UiLabels.Tone.ERROR;
        } else if (inputProblem != null) {
            feedback = inputProblem;
            tone = UiLabels.Tone.ERROR;
        } else {
            feedback = lastFeedback;
            tone = lastTone;
        }
        setStatus(feedback, tone, busy);

    }

    void setStatus(String text, UiLabels.Tone tone, boolean busy) {
        status.setText(text);
        UiLabels.applyTone(status, tone);
        spinner.setVisible(busy);
        spinner.setManaged(busy);
    }

    void setNextStep(String text, int tab) {
        nextStep.setText("Tiếp theo: " + text);
        nextStepTab = tab;
        nextStep.setFocusTraversable(tab >= 0);
        nextStep.setMouseTransparent(tab < 0);
        nextStep.getStyleClass().remove("la-next-step--link");
        if (tab < 0) {
            return;
        }
        nextStep.getStyleClass().add("la-next-step--link");
    }

    String statusText() {
        return status.getText();
    }
}
