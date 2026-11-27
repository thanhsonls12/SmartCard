package vn.labaccess.desktop.card;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.transport.CardTransport;

final class CardEnrollmentCommands {
    private static final byte INS_GET_CARD_PUBLIC_KEY = (byte) 0x61;
    private static final byte INS_SIGN_REGISTRATION_CHALLENGE = (byte) 0x62;
    private static final int PUBLIC_KEY_LE = 256;
    private static final int SIGNATURE_LE = 256;
    private static final int NONCE_LENGTH = 32;
    private static final int DER_SIGNATURE_MIN = 8;
    private static final int DER_SIGNATURE_MAX = 72;

    private final CardTransport transport;
    private final Supplier<CardStatus> status;
    private final IntConsumer responseStatus;
    private java.security.PublicKey cardPublicKey;
    private byte[] cardPublicKeyBytes;
    private byte[] enrollmentSignature;

    CardEnrollmentCommands(CardTransport transport, Supplier<CardStatus> status, IntConsumer responseStatus) {
        this.transport = transport;
        this.status = status;
        this.responseStatus = responseStatus;
    }

    java.security.PublicKey cardPublicKey() {
        return cardPublicKey;
    }

    void clearVerificationKey() {
        cardPublicKey = null;
    }

    void reset() {
        clearVerificationKey();
        cardPublicKeyBytes = null;
        enrollmentSignature = null;
    }

    CardOperationResult readCardPublicKey() throws CardException {
        transport.requireConnected();
        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_GET_CARD_PUBLIC_KEY, 0x00, 0x00, PUBLIC_KEY_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        responseStatus.accept(sw);
        if (sw != Sw.OK) {
            cardPublicKey = null;
            cardPublicKeyBytes = null;
            return CardOperationResult.fail(String.format(
                    "GET_CARD_PUBLIC_KEY INS=61 P1P2=0000 Le=%d SW=%04X | %s | kết luận: CHƯA SẴN SÀNG",
                    PUBLIC_KEY_LE, sw, publicKeyStatusText(sw)));
        }
        ReceiptVerifier.ParsedKey parsed = ReceiptVerifier.parsePublicKey(data);
        if (!parsed.ok()) {
            cardPublicKey = null;
            cardPublicKeyBytes = null;
            return CardOperationResult.fail(String.format(
                    "GET_CARD_PUBLIC_KEY INS=61 SW=9000 dataLen=%d | %s | kết luận: CHƯA SẴN SÀNG",
                    data.length, parsed.problem()));
        }
        cardPublicKey = parsed.key();
        cardPublicKeyBytes = data.clone();
        CardStatus current = status.get();
        String cardId = current == null ? "chưa nhận diện" : current.cardId();
        return CardOperationResult.ok(String.format(
                "GET_CARD_PUBLIC_KEY INS=61 SW=9000 dataLen=%d | cardId=%s | P-256 point=%s",
                data.length, cardId, CardGateway.hex(data)));
    }

    byte[] lastCardPublicKeyBytes() {
        byte[] value = cardPublicKeyBytes;
        return value == null ? null : value.clone();
    }

    byte[] lastEnrollmentSignature() {
        byte[] value = enrollmentSignature;
        return value == null ? null : value.clone();
    }

    CardOperationResult signRegistrationChallenge(byte[] nonce) throws CardException {
        transport.requireConnected();
        enrollmentSignature = null;
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            return CardOperationResult.fail("SIGN_REGISTRATION_CHALLENGE: nonce phải đúng 32 byte | không gửi APDU");
        }
        CardStatus current = status.get();
        if (current == null || current.lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail("SIGN_REGISTRATION_CHALLENGE: thẻ chưa ISSUED | không gửi APDU");
        }

        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_SIGN_REGISTRATION_CHALLENGE, 0x00, 0x00, nonce, SIGNATURE_LE));
        int sw = response.getSW();
        byte[] signature = response.getData();
        responseStatus.accept(sw);
        String apdu = String.format(
                "SIGN_REGISTRATION_CHALLENGE INS=62 P1P2=0000 case=4S Le=00 Lc=%d SW=%04X",
                nonce.length, sw);
        if (sw != Sw.OK) {
            return CardOperationResult.fail(apdu + " | " + signChallengeStatusText(sw)
                    + " | kết luận: CHƯA SẴN SÀNG");
        }
        if (signature.length < DER_SIGNATURE_MIN || signature.length > DER_SIGNATURE_MAX
                || signature[0] != 0x30) {
            return CardOperationResult.fail(String.format(
                    "%s sigLen=%d | không phải DER SEQUENCE hợp lệ | kết luận: CHƯA SẴN SÀNG",
                    apdu, signature.length));
        }
        enrollmentSignature = signature;
        return CardOperationResult.ok(String.format(
                "%s sigLen=%d | DER SEQUENCE | nonce 32 byte đã ký (không ghi nội dung)",
                apdu, signature.length));
    }

    private static String signChallengeStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED — chỉ ký challenge sau PERSONALIZE";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc khác 32 hoặc Le khác 00 (Le tường minh 1..71 bị từ chối)";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_DATA) {
            return "nonce bị từ chối";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "lỗi nội bộ khi ký";
        }
        return "không xác định được kết quả ký";
    }

    private static String publicKeyStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED — GET_CARD_PUBLIC_KEY chỉ đọc được sau PERSONALIZE";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Le nhỏ hơn 65 byte của public point";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        return "không đọc được public key";
    }
}
