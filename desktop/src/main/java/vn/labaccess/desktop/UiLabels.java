package vn.labaccess.desktop;

import javafx.scene.control.Label;

final class UiLabels {

    private UiLabels() {
    }

    static Label caption(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;"
                + " -fx-text-fill: #555555;");
        return label;
    }

    static Label value() {
        Label label = new Label("-");
        label.setId("value");
        label.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        return label;
    }
}
