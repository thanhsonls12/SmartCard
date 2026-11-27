package vn.labaccess.desktop.session;

import javax.smartcardio.CardException;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;

public final class ConfirmedOperationRefresh {

    private ConfirmedOperationRefresh() {
    }

    public static CardOperationResult read(CardGateway gateway, CardOperationResult confirmed, char[] pin) {
        if (!confirmed.ok()) {
            return confirmed;
        }
        try {
            CardOperationResult refreshed = gateway.readAuthenticatedStatus(pin);
            if (refreshed.ok()) {
                return CardOperationResult.ok(confirmed.text() + " | " + refreshed.text());
            }
            return incomplete(gateway, confirmed, refreshed.text());
        } catch (CardException | RuntimeException failure) {
            return incomplete(gateway, confirmed, failure.getClass().getSimpleName());
        }
    }

    private static CardOperationResult incomplete(CardGateway gateway, CardOperationResult confirmed, String detail) {
        gateway.clearAuthenticatedStatus();
        String next = gateway.needsReconnect()
                ? "Ngắt kết nối rồi Kết nối lại và Đọc thẻ."
                : "Nhập PIN rồi bấm Đọc thẻ.";
        return CardOperationResult.warning(confirmed.text() + " | Chưa cập nhật trạng thái: " + detail,
                "Chưa cập nhật trạng thái. " + next + " Không thực hiện lại giao dịch đã hoàn tất.");
    }
}
