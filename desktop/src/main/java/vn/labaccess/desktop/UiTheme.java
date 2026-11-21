package vn.labaccess.desktop;

import java.net.URL;
import java.util.Objects;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;

final class UiTheme {

    private static final String THEME = new PrimerLight().getUserAgentStylesheet();

    private static final String STYLESHEET = "/vn/labaccess/desktop/labaccess.css";

    private UiTheme() {
    }

    static void apply(Scene scene) {
        Application.setUserAgentStylesheet(THEME);
        URL stylesheet = Objects.requireNonNull(UiTheme.class.getResource(STYLESHEET),
                "không tìm thấy stylesheet " + STYLESHEET);
        scene.getStylesheets().add(stylesheet.toExternalForm());
        followFocus(scene);
    }

    static void followFocus(Scene scene) {
        scene.focusOwnerProperty().addListener((observable, was, now) -> {
            if (now != null) {
                Platform.runLater(() -> {
                    if (scene.getFocusOwner() == now) {
                        scene.getRoot().applyCss();
                        scene.getRoot().layout();
                        UiSection.reveal(now);
                    }
                });
            }
        });
    }
}
