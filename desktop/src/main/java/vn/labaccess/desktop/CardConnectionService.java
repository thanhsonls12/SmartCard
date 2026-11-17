package vn.labaccess.desktop;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.function.Consumer;
import javax.smartcardio.CardException;

final class CardConnectionService {

    private final CardGateway gateway;
    private final BackendClient backend;
    private final Consumer<String> log;

    CardConnectionService(CardGateway gateway, BackendClient backend, Consumer<String> log) {
        this.gateway = gateway;
        this.backend = backend;
        this.log = log;
    }

    CardOperationResult connect() throws CardException, NoSuchAlgorithmException, NoSuchProviderException {
        CardOperationResult connected = gateway.connect();
        return connected.ok() ? identify() : connected;
    }

    CardOperationResult identify() throws CardException {
        CardOperationResult identified = gateway.identify();
        if (!identified.ok()) {
            return identified;
        }
        return CardOperationResult.ok(identified.text() + " | đăng ký thẻ: " + checkEnrollment());
    }

    private String checkEnrollment() {
        CardStatus current = gateway.status();
        try {
            boolean enrolled = backend.cardEnrolled(current.cardId());
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, enrolled,
                    enrolled ? "sổ giao dịch có mã thẻ này" : "sổ giao dịch chưa có mã thẻ này"));
            return enrolled ? "đã đăng ký" : "chưa đăng ký; mở tab Đăng ký thẻ trước khi giao dịch";
        } catch (BackendClient.Failure failure) {
            log.accept("Kiểm tra đăng ký thẻ: " + failure.getMessage());
            String guidance = "chưa xác nhận được đăng ký thẻ; bấm Kiểm tra hệ thống";
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(false, false, guidance));
            return guidance;
        }
    }
}
