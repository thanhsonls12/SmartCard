package vn.labaccess.desktop;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Khối nội dung phẳng có tiêu đề ngắn: một khối, một viền, không lồng khối trong khối.
 *
 * Mỗi panel tự bọc nội dung của mình bằng khối này để giữ ranh giới component (Q-34):
 * khối là cách trình bày của panel, không phải khung của cửa sổ.
 */
final class UiSection {

    private UiSection() {
    }

    static VBox of(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("la-section-title");
        VBox block = new VBox(UiScale.SPACE_2, heading);
        block.getChildren().addAll(content);
        block.getStyleClass().add("la-section");
        return block;
    }
}
