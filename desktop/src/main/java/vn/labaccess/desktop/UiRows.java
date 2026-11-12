package vn.labaccess.desktop;

import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;

/**
 * Hàng control tự xuống dòng khi thiếu chỗ.
 *
 * HBox cố định làm label và nút bị cắt khi cửa sổ hẹp hoặc khi Windows tăng
 * scaling (không gian logic ít đi); FlowPane giữ nguyên control và xuống dòng.
 */
final class UiRows {

    private UiRows() {
    }

    static FlowPane wrapping(Node... children) {
        FlowPane row = new FlowPane(UiScale.SPACE_2, UiScale.SPACE_2, children);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setRowValignment(VPos.BOTTOM);
        return row;
    }

    /** Nhãn đi liền control của nó: khi hàng xuống dòng, nhãn không bị tách khỏi ô nhập. */
    static javafx.scene.layout.HBox pair(Node label, Node control) {
        javafx.scene.layout.HBox pair = new javafx.scene.layout.HBox(UiScale.SPACE_1,
                label, control);
        pair.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return pair;
    }

    /** Form nhiều trường: nhãn nằm trên ô nhập, cả cụm xuống dòng cùng nhau. */
    static javafx.scene.layout.VBox field(String caption, Node control) {
        javafx.scene.control.Label label = UiLabels.caption(caption);
        if (control instanceof javafx.scene.control.Labeled labeled) {
            label.setLabelFor(labeled);
        }
        javafx.scene.layout.VBox column = new javafx.scene.layout.VBox(UiScale.SPACE_1,
                label, control);
        column.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return column;
    }

    /** Giá trị dài: nhãn trên, giá trị xuống dòng theo bề rộng khối thay vì tràn ngang. */
    static javafx.scene.layout.VBox valueBlock(String caption, javafx.scene.control.Label value) {
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.VBox block = new javafx.scene.layout.VBox(UiScale.SPACE_1,
                UiLabels.caption(caption), value);
        block.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return block;
    }
}
