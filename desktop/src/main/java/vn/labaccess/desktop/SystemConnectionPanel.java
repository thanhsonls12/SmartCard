package vn.labaccess.desktop;

import java.util.function.BiConsumer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

final class SystemConnectionPanel {

    private record Check(String cardId, boolean enrolled) {
    }

    private final BackendClient backend;
    private final CardGateway gateway;
    private final EnrollmentRunner runner;
    private final BiConsumer<Boolean, String> finished;
    private final Label status = UiLabels.chip();
    private final Button check = new Button("Kiểm tra hệ thống");

    SystemConnectionPanel(BackendClient backend, CardGateway gateway, EnrollmentRunner runner,
            BiConsumer<Boolean, String> finished) {
        this.backend = backend;
        this.gateway = gateway;
        this.runner = runner;
        this.finished = finished;
        check.getStyleClass().add("la-action");
        check.setId("check-system");
        check.setTooltip(new Tooltip("Kiểm tra kết nối sổ giao dịch và đăng ký thẻ; không thực hiện lại giao dịch."));
        check.setOnAction(event -> check());
        status.setText("Chưa kiểm tra");
        UiLabels.applyChip(status, UiLabels.Tone.NEUTRAL);
    }

    Node statusNode() {
        return status;
    }

    Node actionNode() {
        return check;
    }

    void setBusy(boolean busy) {
        check.setDisable(busy);
    }

    void showBackendAccess() {
        BackendClient.Access access = backend.lastAccess();
        if (access == null) {
            status.setText("Chưa kiểm tra");
            UiLabels.applyChip(status, UiLabels.Tone.NEUTRAL);
            return;
        }
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(access.checkedAt());
        String caption = access.available() ? "Có phản hồi" : access.httpStatus() == 0
                ? "Mất kết nối" : "Hệ thống báo lỗi";
        status.setText(caption + " · " + time);
        status.setTooltip(new Tooltip("Lần truy cập gần nhất: " + access.checkedAt()
                + (access.httpStatus() == 0 ? " — không nhận được HTTP" : " — HTTP " + access.httpStatus())
                + ". Đây không phải trạng thái kết nối trực tiếp hoặc kết quả lưu giao dịch."));
        UiLabels.applyChip(status, access.available() ? UiLabels.Tone.OK : UiLabels.Tone.ERROR);
    }

    private void check() {
        CardStatus current = gateway.isReady() ? gateway.status() : null;
        String cardId = current == null ? null : current.cardId();
        status.setText("Đang kiểm tra…");
        UiLabels.applyChip(status, UiLabels.Tone.PENDING);
        runner.run("CHECK_SYSTEM", () -> {
            backend.checkDatabase();
            return new Check(cardId, cardId != null && backend.cardEnrolled(cardId));
        }, result -> {
            if (sameCard(result.cardId())) {
                gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, result.enrolled(),
                        result.enrolled() ? "đã có đăng ký trong hệ thống" : "chưa đăng ký thẻ"));
            }
            showBackendAccess();
            finished.accept(true, result.cardId() == null ? "Đã kết nối sổ giao dịch."
                    : result.enrolled() ? "Đã kết nối sổ giao dịch; thẻ đã đăng ký."
                    : "Đã kết nối sổ giao dịch; mở tab Đăng ký thẻ để tiếp tục.");
        }, failure -> {
            if (sameCard(cardId)) {
                gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(false, false,
                        "chưa kết nối được với sổ giao dịch; bấm Kiểm tra hệ thống"));
            }
            showBackendAccess();
            finished.accept(false, UiLabels.failureMessage("CHECK_SYSTEM", "", failure));
        });
    }

    private boolean sameCard(String cardId) {
        CardStatus current = gateway.isReady() ? gateway.status() : null;
        return cardId != null && current != null && cardId.equalsIgnoreCase(current.cardId());
    }
}
