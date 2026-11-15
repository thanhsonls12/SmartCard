package vn.labaccess.desktop;

import javafx.geometry.Insets;

final class UiScale {

    static final double SPACE_1 = 4;
    static final double SPACE_2 = 8;
    static final double SPACE_3 = 12;
    static final double SPACE_4 = 16;
    static final double SPACE_5 = 24;
    static final double LOG_MIN_HEIGHT = 130;
    static final double WORKSPACE_COLUMNS_WIDTH = 1000;
    static final double PROFILE_COLUMNS_WIDTH = 640;

    private UiScale() {
    }

    static Insets blockInsets() {
        return new Insets(SPACE_3);
    }

    static Insets rowInsets() {
        return new Insets(0, SPACE_3, SPACE_2, SPACE_3);
    }

    static Insets statusBarInsets() {
        return new Insets(SPACE_2, SPACE_5, SPACE_2, SPACE_5);
    }

    static Insets headerInsets() {
        return new Insets(SPACE_2, SPACE_5, SPACE_2, SPACE_5);
    }

    static Insets centerInsets() {
        return new Insets(SPACE_3, SPACE_5, SPACE_4, SPACE_5);
    }
}
