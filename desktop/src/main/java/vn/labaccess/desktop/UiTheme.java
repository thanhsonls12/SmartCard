package vn.labaccess.desktop;

import java.net.URL;
import java.util.Objects;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;

/**
 * Nạp theme cho cửa sổ: một theme AtlantaFX duy nhất, rồi stylesheet LabAccess phủ lên.
 *
 * Chỉ nơi này biết tên theme và đường dẫn stylesheet; component không tự nạp CSS.
 */
final class UiTheme {

    /** Theme sáng: app vận hành thẻ cần nền sáng, tương phản rõ, trạng thái dễ đọc. */
    private static final String THEME = new PrimerLight().getUserAgentStylesheet();

    private static final String STYLESHEET = "/vn/labaccess/desktop/labaccess.css";

    private UiTheme() {
    }

    static void apply(Scene scene) {
        Application.setUserAgentStylesheet(THEME);
        URL stylesheet = Objects.requireNonNull(UiTheme.class.getResource(STYLESHEET),
                "không tìm thấy stylesheet " + STYLESHEET);
        scene.getStylesheets().add(stylesheet.toExternalForm());
    }
}
