package vn.labaccess.desktop.card;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.transport.CardTransport;

final class CardPersonalizationCommands {
    private static final byte INS_PERSONALIZE = (byte) 0x30;

    private final CardTransport transport;
    private final IntConsumer responseStatus;
    private final IdentifyCard identify;
    private final Supplier<CardStatus> status;

    CardPersonalizationCommands(CardTransport transport, IntConsumer responseStatus,
            IdentifyCard identify, Supplier<CardStatus> status) {
        this.transport = transport;
        this.responseStatus = responseStatus;
        this.identify = identify;
        this.status = status;
    }

    @FunctionalInterface
    interface IdentifyCard {
        CardOperationResult run() throws CardException;
    }

    CardOperationResult personalize(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) throws CardException {
        return personalize(adminPin, cardIdHex, studentId, studentPin, displayName, null);
    }

    CardOperationResult personalize(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName, String classCode) throws CardException {
        transport.requireConnected();

        String problem = classCode == null
                ? PersonalizeRequest.problem(adminPin, cardIdHex, studentId, studentPin, displayName)
                : PersonalizeRequest.problem(adminPin, cardIdHex, studentId, studentPin, displayName, classCode);
        if (problem != null) {
            return CardOperationResult.fail("PERSONALIZE: " + problem + " | không gửi APDU");
        }

        String expectedCardId = cardIdHex.strip().toUpperCase();
        byte[] data = classCode == null
                ? PersonalizeRequest.payload(adminPin, cardIdHex, studentId, studentPin, displayName)
                : PersonalizeRequest.payload(adminPin, cardIdHex, studentId, studentPin, displayName, classCode);
        int lc = data.length;
        ResponseAPDU response;
        try {
            response = transport.transmit(new CommandAPDU(
                    CardProtocol.CLA_APPLICATION, INS_PERSONALIZE, 0x00, 0x00, data, 256));
        } finally {
            Arrays.fill(data, (byte) 0);
        }

        int sw = response.getSW();
        responseStatus.accept(sw);
        String apdu = String.format(
                "PERSONALIZE INS=30 P1P2=0000 case=4S Le=00 Lc=%d SW=%04X", lc, sw);
        if (sw != Sw.OK) {
            return CardOperationResult.fail(apdu + " | " + personalizeStatusText(sw)
                    + " | kết luận: CHƯA SẴN SÀNG");
        }

        CardOperationResult reidentified = identify.run();
        if (!reidentified.ok()) {
            return CardOperationResult.fail(apdu + " | " + reidentified.text());
        }
        CardStatus current = status.get();
        if (current == null || current.lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail(apdu + " | sau PERSONALIZE lifecycle không phải ISSUED"
                    + " | kết luận: CHƯA SẴN SÀNG");
        }
        if (!current.cardId().equalsIgnoreCase(expectedCardId)) {
            return CardOperationResult.fail(String.format("%s | cardId trên thẻ=%s khác yêu cầu=%s"
                    + " | kết luận: CHƯA SẴN SÀNG", apdu, current.cardId(), expectedCardId));
        }
        return CardOperationResult.ok(apdu + " | " + reidentified.text());
    }

    private static String personalizeStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ đã ISSUED — PERSONALIZE chỉ chạy một lần";
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "admin PIN đã bị khóa do hết lượt thử";
        }
        if ((sw & 0xFF00) == 0x6300) {
            return String.format("admin PIN sai, còn %d lượt thử", sw & 0x0F);
        }
        if (sw == Sw.WRONG_DATA) {
            return "payload bị từ chối: cấu trúc/định dạng/giới hạn sai";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc không khớp schema hoặc Le khác 00";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "lỗi nội bộ khi sinh khóa/mã hóa; thẻ vẫn UNPERSONALIZED";
        }
        return "không xác định được kết quả";
    }
}
