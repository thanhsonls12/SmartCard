package vn.labaccess.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;
import javafx.util.Duration;

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
        Tooltip.install(control, new Tooltip("Lệnh thẻ: " + apdu));
    }

    static Label fieldError() {
        Label label = new Label();
        label.getStyleClass().add("la-field-error");
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setPrefWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPrefHeight(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        reserveFieldError(label);
        return label;
    }

    static Label fieldErrorSpacer() {
        return fieldError();
    }

    static void reserveFieldError(Label label) {
        label.setManaged(true);
        label.setVisible(label.getText() != null && !label.getText().isEmpty());
    }

    static void clearFieldError(Label label) {
        setFieldError(label, null);
    }

    static void setFieldError(Label label, String message) {
        label.setText(message == null ? "" : message);
        label.setVisible(message != null);
    }

    static void markPrimary(Button button, boolean primary) {
        button.getStyleClass().remove("la-primary");
        if (primary) {
            button.getStyleClass().add("la-primary");
        }
    }

    static Label whyNote() {
        Label note = new Label();
        note.getStyleClass().add("la-note");
        applyNoteTone(note, Tone.NEUTRAL);
        note.setWrapText(true);
        note.setManaged(false);
        note.setVisible(false);
        return note;
    }

    static void setWhy(Label note, String text) {
        boolean show = text != null && !text.isBlank();
        note.setText(show ? text : "");
        note.setVisible(show);
        note.setManaged(show);
    }

    static String whyLine(Object... pairs) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Button button = (Button) pairs[i];
            String reason = (String) pairs[i + 1];
            if (reason != null && button.isDisable()) {
                grouped.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(button.getText());
            }
        }
        if (grouped.isEmpty()) {
            return null;
        }
        if (grouped.size() == 1) {
            String reason = grouped.keySet().iterator().next();
            return switch (reason) {
                case "chưa kết nối thẻ" -> "Kết nối thẻ để sử dụng các thao tác này.";
                case "chưa nhận diện thẻ" -> "Nhận diện thẻ để tiếp tục.";
                default -> Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
            };
        }
        return String.join(" · ", grouped.entrySet().stream()
                .map(entry -> String.join(", ", entry.getValue()) + ": " + entry.getKey()).toList());
    }

    static Button copyButton(Label label, String caption) {
        Button button = new Button("Sao chép");
        button.getStyleClass().add("la-action");
        button.setAccessibleText("Sao chép " + caption);
        String hint = "Sao chép " + caption;
        Tooltip tooltip = new Tooltip(hint);
        button.setTooltip(tooltip);
        button.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            String text = label.getText();
            return text == null || text.isBlank() || "-".equals(text) || text.startsWith("Chưa ");
        }, label.textProperty()));
        PauseTransition restore = new PauseTransition(Duration.seconds(1.5));
        restore.setOnFinished(done -> {
            tooltip.hide();
            tooltip.setText(hint);
        });
        button.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(label.getText());
            Clipboard.getSystemClipboard().setContent(content);
            tooltip.setText("Đã sao chép " + caption);
            var bounds = button.localToScreen(button.getBoundsInLocal());
            if (bounds != null) {
                tooltip.show(button, bounds.getMinX(), bounds.getMaxY());
            }
            restore.playFromStart();
        });
        return button;
    }

    static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    static void inputProblem(Consumer<String> log, TextInputControl field, String message) {
        field.requestFocus();
        log.accept("[INPUT] " + message);
        field.fireEvent(new InputFeedback.Problem(field, message));
        javafx.application.Platform.runLater(() -> {
            if (field.getScene() == null) {
                return;
            }
            field.getScene().getRoot().applyCss();
            field.getScene().getRoot().layout();
            for (Node parent = field.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof javafx.scene.control.ScrollPane scroll) {
                    Node target = field.getParent();
                    var bounds = scroll.getContent().sceneToLocal(target.localToScene(target.getBoundsInLocal()));
                    double viewport = scroll.getViewportBounds().getHeight();
                    double excess = scroll.getContent().getLayoutBounds().getHeight() - viewport;
                    double top = scroll.getVvalue() * Math.max(0, excess);
                    if (excess > 0 && (bounds.getMinY() < top || bounds.getMaxY() > top + viewport)) {
                        scroll.setVvalue(Math.max(0, Math.min(1, (bounds.getMinY() - viewport / 3) / excess)));
                    }
                    break;
                }
            }
        });
    }

    static boolean confirm(Alert dialog, Node owner, String action) {
        ButtonType confirm = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getButtonTypes().setAll(cancel, confirm);
        attachDialog(dialog, owner);
        return dialog.showAndWait().filter(confirm::equals).isPresent();
    }

    static void attachDialog(Dialog<?> dialog, Node ownerNode) {
        if (ownerNode == null || ownerNode.getScene() == null) {
            return;
        }
        Window owner = ownerNode.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getStylesheets().addAll(ownerNode.getScene().getStylesheets());
    }

    static String failureMessage(String operation, String detail, Throwable failure) {
        String reason = "Mở Nhật ký để xem nguyên nhân trước khi thao tác lại.";
        if (failure != null) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            if (cause instanceof javax.smartcardio.CardException
                    && cause.getMessage() != null
                    && cause.getMessage().startsWith("Không có thẻ trên terminal ")) {
                reason = "Không tìm thấy thẻ. Đặt thẻ vào đầu đọc hoặc kiểm tra Simulator, rồi kết nối lại.";
            } else if (cause instanceof java.net.ConnectException) {
                reason = "Không liên lạc được với đầu đọc/Simulator. Kiểm tra thiết bị và cấu hình kết nối.";
            } else if (cause instanceof java.net.SocketTimeoutException) {
                reason = "Hết thời gian chờ phản hồi. Kiểm tra kết nối; không gửi lại thao tác khi chưa rõ kết quả.";
            } else if (cause instanceof java.net.UnknownHostException) {
                reason = "Không tìm thấy địa chỉ kết nối. Kiểm tra cấu hình đầu đọc/Simulator.";
            } else if (cause instanceof java.security.NoSuchProviderException
                    || cause instanceof java.security.NoSuchAlgorithmException) {
                reason = "Chưa nạp được bộ kết nối thẻ. Kiểm tra cấu hình Java và thư viện đầu đọc.";
            }
        } else {
            var status = Pattern.compile("\\bSW=(63C[0-9A-F]|6983|6982|6985|6A82)\\b",
                    Pattern.CASE_INSENSITIVE).matcher(detail);
            if (status.find()) {
                String sw = status.group(1).toUpperCase(Locale.ROOT);
                reason = sw.startsWith("63C")
                        ? "PIN không đúng; còn " + Integer.parseInt(sw.substring(3), 16)
                                + " lượt thử. Kiểm tra PIN trước khi nhập lại."
                        : switch (sw) {
                            case "6983" -> "PIN đang bị khóa. Kiểm tra trạng thái trong tab Cấp thẻ & bảo mật.";
                            case "6982" -> "Chưa đủ quyền thực hiện. Kiểm tra PIN và nhận diện thẻ.";
                            case "6985" -> "Trạng thái thẻ chưa cho phép thao tác. Kiểm tra điều kiện trong Nhật ký.";
                            case "6A82" -> "Không tìm thấy ứng dụng cần chọn trên thẻ. Kiểm tra applet và mã ứng dụng.";
                            default -> throw new IllegalStateException(sw);
                        };
            }
        }
        return "Lỗi khi " + actionName(operation) + ". " + reason;
    }

    static String outcome(boolean ok, String operation) {
        return (ok ? "Xong: " : "Lỗi: ") + actionName(operation);
    }

    static String actionName(String operation) {
        return switch (operation) {
            case "CONNECT" -> "kết nối";
            case "DISCONNECT" -> "ngắt kết nối";
            case "SELECT + GET_STATUS" -> "nhận diện thẻ";
            case "VERIFY + GET_LAST_RECEIPT + GET_STATUS state" -> "đọc trạng thái thẻ";
            case "RECONCILE_PENDING" -> "đối soát giao dịch";
            case "GRANT_QUOTA" -> "cấp hạn mức";
            case "START_VISIT" -> "mở ca";
            case "END_VISIT" -> "đóng ca";
            case "PERSONALIZE" -> "phát hành thẻ";
            case "GET_CARD_PUBLIC_KEY" -> "đọc khóa công khai";
            case "GET_PROFILE" -> "đọc hồ sơ";
            case "GET_PROFILE_CRYPTO_META" -> "đọc thông tin mã hóa";
            case "VERIFY_STUDENT_PIN" -> "xác thực PIN";
            case "CHANGE_STUDENT_PIN" -> "đổi PIN";
            case "UNBLOCK_STUDENT_PIN" -> "mở khóa PIN";
            case "PHOTO upload" -> "tải ảnh lên";
            case "PHOTO read" -> "đọc ảnh";
            default -> operation;
        };
    }
}
