package vn.labaccess.desktop;

import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

final class ActionNote {

    private final Label label = new Label();
    private final Hyperlink details = new Hyperlink("Xem nhật ký");
    private final HBox node;

    ActionNote(Runnable openLog) {
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

    HBox node() {
        return node;
    }

    void show(String text, UiLabels.Tone tone, boolean showDetails) {
        boolean visible = text != null && !text.isBlank();
        label.setText(visible ? text : "");
        UiLabels.applyNoteTone(label, tone);
        UiLabels.show(details, showDetails);
        UiLabels.show(node, visible);
    }
}
