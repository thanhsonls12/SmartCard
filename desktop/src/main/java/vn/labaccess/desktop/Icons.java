package vn.labaccess.desktop;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Bộ icon của LabAccess.
 *
 * Mỗi hành động có nghĩa ánh xạ tới đúng một icon Feather; nơi khác không tự
 * chọn icon riêng để cùng một hành động không hiện hai hình khác nhau.
 */
final class Icons {

    private static final int SIZE = 14;

    private Icons() {
    }

    /** Kết nối tới thẻ. */
    static FontIcon connect() {
        return icon(Feather.WIFI, "la-icon--accent");
    }

    /** Ngắt kết nối. */
    static FontIcon disconnect() {
        return icon(Feather.WIFI_OFF);
    }

    /** Nhận diện thẻ (SELECT + GET_STATUS). */
    static FontIcon identify() {
        return icon(Feather.CREDIT_CARD);
    }

    /** Đọc receipt cuối. */
    static FontIcon receipt() {
        return icon(Feather.FILE_TEXT);
    }

    /** Đối soát giao dịch treo. */
    static FontIcon reconcile() {
        return icon(Feather.REFRESH_CW);
    }

    /** Cảnh báo cần chú ý nhưng không phải lỗi (pending, UNKNOWN). */
    static FontIcon warning() {
        return icon(Feather.ALERT_TRIANGLE, "la-icon--pending");
    }

    /** Lỗi hoặc thao tác bị từ chối. */
    static FontIcon error() {
        return icon(Feather.X_CIRCLE, "la-icon--danger");
    }

    /** Xác nhận thành công. */
    static FontIcon success() {
        return icon(Feather.CHECK_CIRCLE, "la-icon--accent");
    }

    private static FontIcon icon(Feather feather, String... extraStyleClasses) {
        FontIcon node = new FontIcon(feather);
        node.setIconSize(SIZE);
        node.getStyleClass().add("la-icon");
        node.getStyleClass().addAll(extraStyleClasses);
        return node;
    }
}
