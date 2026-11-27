package vn.labaccess.desktop.card;

import javax.smartcardio.CardException;

final class CardOverviewReader {

    private final CardGateway gateway;

    CardOverviewReader(CardGateway gateway) {
        this.gateway = gateway;
    }

    CardOperationResult read(char[] pin) throws CardException {
        gateway.clearAuthenticatedStatus();
        gateway.clearCardIdentity();
        boolean complete = false;
        try {
            CardOperationResult key = gateway.prepareReceiptVerification();
            if (!key.ok()) {
                return key;
            }
            CardOperationResult verified = gateway.verifyStudentPin(pin);
            if (!verified.ok()) {
                return verified;
            }
            CardOperationResult receipt = gateway.readLastReceipt(null);
            if (!receipt.ok() && gateway.lastResponseSw() != Sw.CONDITIONS_NOT_SATISFIED) {
                return receipt;
            }
            CardOperationResult profile = gateway.readProfileWithPin(pin);
            if (!profile.ok()) {
                return profile;
            }
            CardOperationResult photo = gateway.readPhoto(pin);
            if (!photo.ok() && !gateway.photoAbsent()) {
                return photo;
            }
            CardOperationResult state = gateway.readAuthenticatedStatus(pin);
            if (!state.ok()) {
                return state;
            }
            CardOperationResult confirmed = gateway.confirmReceiptRead();
            if (!confirmed.ok()) {
                return confirmed;
            }
            complete = true;
            String receiptDetail = receipt.ok() ? receipt.text() : "Thẻ chưa có biên nhận.";
            return CardOperationResult.ok(receiptDetail + " | " + state.text() + " | Đã đọc hồ sơ sinh viên. "
                    + (gateway.photoAbsent() ? "Thẻ chưa có ảnh." : "Đã đọc ảnh từ thẻ."));
        } finally {
            if (!complete) {
                gateway.clearCardIdentity();
                gateway.clearAuthenticatedStatus();
            }
        }
    }
}
