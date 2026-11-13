package vn.labaccess.desktop;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

/**
 * Nhãn dùng chung. Cách trình bày nằm trong labaccess.css qua styleClass,
 * không đặt inline style ở đây.
 */
final class UiLabels {

    private UiLabels() {
    }

    static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("la-caption");
        return label;
    }

    static Label value() {
        Label label = new Label("-");
        label.setId("value");
        label.getStyleClass().add("la-value");
        return label;
    }

    /** Chip trạng thái: chỉ dùng cho trạng thái, không dùng cho mọi giá trị. */
    static Label chip() {
        Label label = new Label("-");
        label.getStyleClass().addAll("la-chip", "la-chip--neutral");
        return label;
    }

    static void applyChip(Label label, Tone tone) {
        label.getStyleClass().removeAll("la-chip--neutral", "la-chip--ok",
                "la-chip--pending", "la-chip--error");
        String modifier = switch (tone) {
            case OK -> "la-chip--ok";
            case PENDING -> "la-chip--pending";
            case ERROR -> "la-chip--error";
            case NEUTRAL -> "la-chip--neutral";
        };
        label.getStyleClass().add(modifier);
    }

    /** Sắc thái của một dòng trạng thái; màu tương ứng nằm trong labaccess.css. */
    enum Tone {
        NEUTRAL, OK, PENDING, ERROR
    }

    static void applyTone(Label label, Tone tone) {
        label.getStyleClass().removeAll("la-status--ok", "la-status--pending", "la-status--error");
        String modifier = switch (tone) {
            case OK -> "la-status--ok";
            case PENDING -> "la-status--pending";
            case ERROR -> "la-status--error";
            case NEUTRAL -> null;
        };
        if (modifier != null) {
            label.getStyleClass().add(modifier);
        }
    }

    /** Dòng thông báo: nền và chữ đổi theo sắc thái, dùng cùng bộ màu với chip. */
    static void applyNoteTone(Label label, Tone tone) {
        label.getStyleClass().removeAll("la-note--neutral", "la-note--ok",
                "la-note--pending", "la-note--error");
        label.getStyleClass().add(switch (tone) {
            case OK -> "la-note--ok";
            case PENDING -> "la-note--pending";
            case ERROR -> "la-note--error";
            case NEUTRAL -> "la-note--neutral";
        });
    }

    /**
     * Mã APDU gắn vào control dưới dạng tooltip.
     *
     * Nhãn nút dùng ngôn ngữ nghiệp vụ; mã lệnh chỉ còn ở đây và trong log.
     * Nút bị khoá không nhận input nên tooltip có thể không hiện — log mới là nguồn chính.
     */
    static void apduHint(Node control, String apdu) {
        Tooltip.install(control, new Tooltip("APDU: " + apdu));
    }

    static void markPrimary(Button button, boolean primary) {
        button.getStyleClass().remove("la-primary");
        if (primary) {
            button.getStyleClass().add("la-primary");
        }
    }

    static String outcome(boolean ok, String operation) {
        return (ok ? "Xong: " : "Lỗi: ") + actionName(operation);
    }

    static String actionName(String operation) {
        return switch (operation) {
            case "CONNECT" -> "kết nối";
            case "DISCONNECT" -> "ngắt kết nối";
            case "SELECT + GET_STATUS" -> "nhận diện thẻ";
            case "VERIFY + GET_LAST_RECEIPT + GET_STATUS state" -> "đọc biên nhận";
            case "RECONCILE_PENDING" -> "đối soát giao dịch";
            case "GRANT_QUOTA" -> "cấp quota";
            case "START_VISIT" -> "mở ca";
            case "END_VISIT" -> "đóng ca";
            case "PERSONALIZE" -> "phát hành thẻ";
            case "GET_CARD_PUBLIC_KEY" -> "đọc khóa công khai";
            case "GET_PROFILE" -> "đọc hồ sơ";
            case "GET_PROFILE_CRYPTO_META" -> "đọc thông tin mã hóa";
            case "VERIFY_STUDENT_PIN" -> "xác thực PIN";
            case "CHANGE_STUDENT_PIN" -> "đổi PIN";
            case "UNBLOCK_STUDENT_PIN" -> "mở khóa PIN";
            case "PHOTO upload" -> "upload ảnh";
            case "PHOTO read" -> "đọc ảnh";
            default -> operation;
        };
    }
}
