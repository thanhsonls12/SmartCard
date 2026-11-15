package vn.labaccess.desktop;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;

final class UiSection {

    private UiSection() {
    }

    static VBox of(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("la-section-title");
        heading.setWrapText(true);
        VBox block = new VBox(UiScale.SPACE_3, heading);
        block.setMinWidth(0);
        block.getChildren().addAll(content);
        block.getStyleClass().add("la-section");
        return block;
    }

    static Tab tab(String title, Node... content) {
        VBox body = new VBox(UiScale.SPACE_4, content);
        body.getStyleClass().add("la-tab-body");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("la-scroll");
        scroll.setMinHeight(0);
        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
    }
}
