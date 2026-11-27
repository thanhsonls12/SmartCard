package vn.labaccess.desktop.ui.foundation;

import javafx.geometry.Insets;

public final class UiScale {

    public static final double FONT_CAPTION = 12;
    public static final double FONT_BODY = 14;
    public static final double FONT_LEAD = 16;
    public static final double FONT_TITLE = 18;
    public static final double FONT_DISPLAY = 24;

    public static final double SPACE_1 = 4;
    public static final double SPACE_2 = 8;
    public static final double SPACE_3 = 12;
    public static final double SPACE_4 = 16;
    public static final double SPACE_5 = 24;
    public static final double SPACE_6 = 32;

    public static final double RADIUS_SM = 4;
    public static final double RADIUS_MD = 8;
    public static final double RADIUS_LG = 12;
    public static final double CONTROL_HEIGHT = 40;
    public static final double CONTROL_HEIGHT_COMPACT = 36;
    public static final double KIOSK_CONTROL_HEIGHT = 56;
    public static final double FIELD_ERROR_HEIGHT = 18;
    public static final double LOG_MIN_HEIGHT = 130;
    public static final double WORKSPACE_COLUMNS_WIDTH = 1000;
    public static final double PROFILE_COLUMNS_WIDTH = 640;
    public static final double PROFILE_PHOTO_WIDTH = 300;

    private UiScale() {
    }

    public static Insets blockInsets() {
        return new Insets(SPACE_3);
    }

    public static Insets rowInsets() {
        return new Insets(0, SPACE_3, SPACE_2, SPACE_3);
    }

    public static Insets statusBarInsets() {
        return new Insets(SPACE_2, SPACE_5, SPACE_2, SPACE_5);
    }

    public static Insets headerInsets() {
        return new Insets(SPACE_2, SPACE_5, SPACE_2, SPACE_5);
    }

    public static Insets centerInsets() {
        return new Insets(SPACE_3, SPACE_5, SPACE_4, SPACE_5);
    }
}
