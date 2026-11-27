package vn.labaccess.desktop.ui.shared;

import java.util.Arrays;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiScale;

public final class UiRows {

    private static final String FIELD_MARKER = "labaccess.field";

    private UiRows() {
    }

    public static FlowPane wrapping(Node... children) {
        boolean hasField = Arrays.stream(children).anyMatch(UiRows::isField);
        Node[] nodes = new Node[children.length];
        for (int index = 0; index < children.length; index++) {
            nodes[index] = hasField && isRowButton(children[index])
                    ? buttonColumn(children[index])
                    : children[index];
        }
        FlowPane row = new FlowPane(UiScale.SPACE_2, UiScale.SPACE_2, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setRowValignment(hasField ? VPos.TOP : VPos.CENTER);
        return row;
    }

    public static HBox pair(Node label, Node control) {
        HBox pair = new HBox(UiScale.SPACE_1, label, control);
        pair.setAlignment(Pos.CENTER_LEFT);
        return pair;
    }

    public static VBox field(String caption, Node control) {
        return field(caption, control, UiLabels.fieldError());
    }

    public static VBox field(String caption, Node control, Label errorSlot) {
        Label label = UiLabels.caption(caption);
        label.getStyleClass().add("la-field-label");
        label.setLabelFor(control);
        UiLabels.reserveFieldError(errorSlot);
        VBox column = new VBox(UiScale.SPACE_1, label, control, errorSlot) {
            @Override
            protected double computePrefHeight(double width) {

                return super.computePrefHeight(width < 0 ? prefWidth(-1) : width);
            }
        };
        column.setAlignment(Pos.TOP_LEFT);
        column.setMaxWidth(Double.MAX_VALUE);
        if (control instanceof javafx.scene.layout.Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        column.getProperties().put(FIELD_MARKER, Boolean.TRUE);
        return column;
    }

    public static VBox valueBlock(String caption, Label value) {
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);
        VBox block = new VBox(UiScale.SPACE_1, UiLabels.caption(caption), value);
        block.setAlignment(Pos.TOP_LEFT);
        return block;
    }

    private static boolean isField(Node node) {
        return node instanceof VBox box && box.getProperties().containsKey(FIELD_MARKER);
    }

    private static boolean isRowButton(Node node) {
        return node instanceof Button || node instanceof ToggleButton;
    }

    private static VBox buttonColumn(Node control) {
        Label captionLine = UiLabels.caption("\u00A0");
        captionLine.setMinWidth(0);
        captionLine.setPrefWidth(0);
        captionLine.setMaxWidth(0);
        VBox column = new VBox(UiScale.SPACE_1, captionLine, control, UiLabels.fieldErrorSpacer());
        column.setAlignment(Pos.TOP_LEFT);
        return column;
    }
}
