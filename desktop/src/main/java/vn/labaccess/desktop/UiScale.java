package vn.labaccess.desktop;

import javafx.geometry.Insets;

/**
 * Thang khoảng cách dùng cho layout.
 *
 * Insets và gap của HBox/VBox không đặt được bằng CSS nên thang này nằm ở Java;
 * LabAccess không dùng số lẻ ngoài các bước dưới đây.
 */
final class UiScale {

    static final double SPACE_1 = 4;
    static final double SPACE_2 = 8;
    static final double SPACE_3 = 12;
    static final double SPACE_4 = 16;
    static final double SPACE_5 = 24;

    private UiScale() {
    }

    /** Lề ngoài của một khối nội dung. */
    static Insets blockInsets() {
        return new Insets(SPACE_3);
    }

    /** Lề ngang của một hàng control, không có lề trên/dưới. */
    static Insets rowInsets() {
        return new Insets(0, SPACE_3, SPACE_2, SPACE_3);
    }

    /** Lề của thanh trạng thái dưới cùng. */
    static Insets statusBarInsets() {
        return new Insets(SPACE_2, SPACE_3, SPACE_2, SPACE_3);
    }

    /** Lề của vùng header cố định trên cùng. */
    static Insets headerInsets() {
        return new Insets(SPACE_2, SPACE_3, SPACE_2, SPACE_3);
    }

    /** Lề quanh vùng nội dung tab và nhật ký. */
    static Insets centerInsets() {
        return new Insets(SPACE_2, SPACE_3, SPACE_2, SPACE_3);
    }
}
