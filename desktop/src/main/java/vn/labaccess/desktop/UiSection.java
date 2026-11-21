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
        Tab tab = new Tab(title, scroll(body));
        tab.setClosable(false);
        return tab;
    }

    static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setFocusTraversable(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("la-scroll");
        scroll.setMinHeight(0);
        return scroll;
    }

    static void reveal(Node target) {
        for (Node parent = target.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getContent() != null) {
                var bounds = scroll.getContent().sceneToLocal(target.localToScene(target.getBoundsInLocal()));
                double height = scroll.getViewportBounds().getHeight();
                double excess = scroll.getContent().getLayoutBounds().getHeight() - height;
                double range = scroll.getVmax() - scroll.getVmin();
                if (height <= 0 || excess <= 0 || range <= 0) {
                    continue;
                }
                double top = (scroll.getVvalue() - scroll.getVmin()) / range * excess;
                double next = bounds.getMinY() < top || bounds.getHeight() > height
                        ? bounds.getMinY() - UiScale.SPACE_2
                        : bounds.getMaxY() > top + height
                        ? bounds.getMaxY() - height + UiScale.SPACE_2 : top;
                scroll.setVvalue(scroll.getVmin() + Math.max(0, Math.min(1, next / excess)) * range);
                scroll.layout();
            }
        }
    }
}
