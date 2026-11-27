package vn.labaccess.desktop.card;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.transport.CardTransport;

public final class CardAdminCommands {

    record StatusRead(CardOperationResult result, CardStatus status) {
    }

    public record ReceiptRead(CardOperationResult result, Receipt receipt, boolean signatureChecked) {
    }

    private static final int INS_GET_ADMIN_STATUS = 0x11;
    private static final int INS_GET_ADMIN_LAST_RECEIPT = 0x63;
    private static final int ADMIN_PIN_LENGTH = 8;
    private static final int READ_LE = 256;

    private final CardTransport transport;
    private final IntConsumer responseSwSink;
    private Receipt verifiedReceipt;

    CardAdminCommands(CardTransport transport, IntConsumer responseSwSink) {
        this.transport = transport;
        this.responseSwSink = responseSwSink;
    }

    StatusRead readStatus(char[] adminPin, CardStatus selected) throws CardException {
        responseSwSink.accept(-1);
        String label = "GET_ADMIN_STATUS";
        String problem = inputProblem(adminPin, selected);
        if (problem != null) {
            return new StatusRead(CardOperationResult.fail(label + ": " + problem + " | không gửi APDU"), null);
        }
        ResponseAPDU response = transmit(INS_GET_ADMIN_STATUS, adminPin);
        String detail = responseText(label, INS_GET_ADMIN_STATUS, response);
        if (response.getSW() != Sw.OK) {
            return new StatusRead(CardOperationResult.fail(detail + " | " + statusText(response.getSW())), null);
        }
        CardStatusCodec.Parsed parsed = CardStatusCodec.parse(response,
                HexFormat.of().parseHex(selected.instanceAid()), CardStatus.StateSource.ADMIN);
        if (!parsed.ok()) {
            return new StatusRead(CardOperationResult.fail(detail + " | " + parsed.problem()), null);
        }
        CardStatus current = parsed.status();
        if (!current.cardId().equalsIgnoreCase(selected.cardId())) {
            return new StatusRead(CardOperationResult.fail(detail + " | cardId khác thẻ đã nhận diện"), null);
        }
        return new StatusRead(CardOperationResult.ok(String.format(
                "%s | nguồn=ADMIN cardId=%s balance=%d counter=%d activeVisit=%s",
                detail, current.cardId(), current.balance(), current.counter(), current.hasActiveVisit())), current);
    }

    ReceiptRead readLastReceipt(char[] adminPin, CardStatus selected, String enrolledCardId,
            byte[] enrolledPublicKey) throws CardException {
        verifiedReceipt = null;
        responseSwSink.accept(-1);
        String label = "GET_ADMIN_LAST_RECEIPT";
        String problem = inputProblem(adminPin, selected);
        if (problem != null) {
            return receiptFailure(label + ": " + problem + " | không gửi APDU", false);
        }
        if (enrolledCardId == null || !selected.cardId().equalsIgnoreCase(enrolledCardId)) {
            return receiptFailure(label + ": cardId đã enroll không khớp thẻ đã nhận diện | không gửi APDU", false);
        }
        ReceiptVerifier.ParsedKey key = ReceiptVerifier.parsePublicKey(enrolledPublicKey);
        if (!key.ok()) {
            return receiptFailure(label + ": khóa đã enroll không hợp lệ: " + key.problem()
                    + " | không gửi APDU", false);
        }
        ResponseAPDU response = transmit(INS_GET_ADMIN_LAST_RECEIPT, adminPin);
        String detail = responseText(label, INS_GET_ADMIN_LAST_RECEIPT, response);
        if (response.getSW() != Sw.OK) {
            return receiptFailure(detail + " | " + statusText(response.getSW()), false);
        }
        ReceiptCodec.Parse parsed = ReceiptCodec.parse(response.getData());
        if (!parsed.ok()) {
            return receiptFailure(detail + " | parser=FAIL (" + parsed.problem() + ")", false);
        }
        Receipt receipt = parsed.receipt();
        if (!receipt.cardIdHex().equalsIgnoreCase(enrolledCardId)) {
            return receiptFailure(detail + " | cardId receipt khác thẻ đã enroll/nhận diện", false);
        }
        ReceiptVerifier.Result verified = ReceiptVerifier.verifyReceipt(key.key(), receipt);
        if (!verified.ok()) {
            return receiptFailure(detail + " | ECDSA=FAIL (" + verified.problem() + ")", true);
        }
        verifiedReceipt = receipt;
        return new ReceiptRead(CardOperationResult.ok(detail + " | nguồn=ADMIN ECDSA=PASS | " + receipt),
                receipt, true);
    }

    Receipt verifiedReceipt(CardStatus current) {
        return current != null && verifiedReceipt != null
                && current.cardId().equals(verifiedReceipt.cardIdHex()) ? verifiedReceipt : null;
    }

    void clearVerifiedReceipt() {
        verifiedReceipt = null;
    }

    private ResponseAPDU transmit(int ins, char[] adminPin) throws CardException {
        byte[] data = new byte[ADMIN_PIN_LENGTH];
        try {
            for (int index = 0; index < data.length; index++) {
                data[index] = (byte) adminPin[index];
            }
            ResponseAPDU response = transport.transmit(new CommandAPDU(
                    CardProtocol.CLA_APPLICATION, ins, 0x00, 0x00, data, READ_LE));
            responseSwSink.accept(response.getSW());
            return response;
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static String inputProblem(char[] pin, CardStatus selected) {
        if (selected == null || selected.lifecycle() != CardLifecycle.ISSUED) {
            return "phải SELECT + GET_STATUS thẻ ISSUED trước";
        }
        if (pin == null || pin.length != ADMIN_PIN_LENGTH) {
            return "Admin PIN phải đúng 8 chữ số ASCII";
        }
        for (char digit : pin) {
            if (!PinFormat.isAsciiDigit(digit)) {
                return "Admin PIN chỉ gồm chữ số ASCII 0-9";
            }
        }
        return null;
    }

    private static ReceiptRead receiptFailure(String problem, boolean signatureChecked) {
        return new ReceiptRead(CardOperationResult.fail(problem), null, signatureChecked);
    }

    private static String responseText(String label, int ins, ResponseAPDU response) {
        return String.format("%s INS=%02X P1P2=0000 case=4S Lc=8 Le=00 SW=%04X dataLen=%d",
                label, ins, response.getSW(), response.getData().length);
    }

    private static String statusText(int sw) {
        if ((sw & 0xFFF0) == 0x63C0) {
            return String.format("Admin PIN sai, còn %d lượt thử", sw & 0x0F);
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "Admin PIN đã khóa; cần tái cấp thẻ, không có UNBLOCK_ADMIN";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED hoặc chưa có receipt";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc/Le không đúng hợp đồng Case 4S";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_DATA) {
            return "định dạng Admin PIN bị từ chối";
        }
        return "không đọc được dữ liệu Admin";
    }
}
