package vn.labaccess.desktop;

import java.util.function.IntConsumer;

import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

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

        HBox left = new HBox(UiScale.SPACE_2, spinner, status);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(0);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(nextStep, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        node = new HBox(left, spacer, nextStep);
        node.getStyleClass().add("la-status-bar");
        node.setPadding(UiScale.statusBarInsets());
    }

    HBox node() {
        return node;
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
