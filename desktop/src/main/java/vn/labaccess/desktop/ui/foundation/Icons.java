package vn.labaccess.desktop.ui.foundation;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

public final class Icons {

    private static final int SIZE = 14;

    private Icons() {
    }

    public static FontIcon connect() {
        return icon(Feather.WIFI, "la-icon--accent");
    }

    public static FontIcon disconnect() {
        return icon(Feather.WIFI_OFF);
    }

    public static FontIcon identify() {
        return icon(Feather.CREDIT_CARD);
    }

    public static FontIcon receipt() {
        return icon(Feather.FILE_TEXT);
    }

    public static FontIcon reconcile() {
        return icon(Feather.REFRESH_CW);
    }

    public static FontIcon warning() {
        return icon(Feather.ALERT_TRIANGLE, "la-icon--pending");
    }

    public static FontIcon error() {
        return icon(Feather.X_CIRCLE, "la-icon--danger");
    }

    public static FontIcon success() {
        return icon(Feather.CHECK_CIRCLE, "la-icon--accent");
    }

    public static FontIcon info() {
        return icon(Feather.INFO);
    }

    public static FontIcon copy() {
        return icon(Feather.COPY);
    }

    private static FontIcon icon(Feather feather, String... extraStyleClasses) {
        FontIcon node = new FontIcon(feather);
        node.setIconSize(SIZE);
        node.getStyleClass().add("la-icon");
        node.getStyleClass().addAll(extraStyleClasses);
        return node;
    }
}
