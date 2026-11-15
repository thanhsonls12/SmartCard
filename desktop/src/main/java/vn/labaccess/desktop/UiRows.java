package vn.labaccess.desktop;

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

/**
 * Hàng control tự xuống dòng khi thiếu chỗ.
 *
 * HBox cố định làm label và nút bị cắt khi cửa sổ hẹp hoặc khi Windows tăng
 * scaling (không gian logic ít đi); FlowPane giữ nguyên control và xuống dòng.
 */
final class UiRows {

    private static final String FIELD_MARKER = "labaccess.field";

    private UiRows() {
    }

    static FlowPane wrapping(Node... children) {
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

    /** Nhãn đi liền control của nó: khi hàng xuống dòng, nhãn không bị tách khỏi ô nhập. */
    static HBox pair(Node label, Node control) {
        HBox pair = new HBox(UiScale.SPACE_1, label, control);
        pair.setAlignment(Pos.CENTER_LEFT);
        return pair;
    }

    static VBox field(String caption, Node control) {
        return field(caption, control, UiLabels.fieldError());
    }

    static VBox field(String caption, Node control, Label errorSlot) {
        Label label = UiLabels.caption(caption);
        label.setLabelFor(control);
        UiLabels.reserveFieldError(errorSlot);
        VBox column = new VBox(UiScale.SPACE_1, label, control, errorSlot) {
            @Override
            protected double computePrefHeight(double width) {
                // FlowPane measures rows without supplying each field's wrapping width.
                return super.computePrefHeight(width < 0 ? prefWidth(-1) : width);
            }
        };
        column.setAlignment(Pos.TOP_LEFT);
        column.getProperties().put(FIELD_MARKER, Boolean.TRUE);
        return column;
    }

    /** Giá trị dài: nhãn trên, giá trị xuống dòng theo bề rộng khối thay vì tràn ngang. */
    static VBox valueBlock(String caption, Label value) {
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
