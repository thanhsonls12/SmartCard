package vn.labaccess.desktop.card;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.IntConsumer;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.transport.CardTransport;

public final class CardProfileUpdateCommands {

    public record IdentityWrite(CardOperationResult result, int sw) {
    }

    static final int SIGNATURE_MAX_LENGTH = 72;

    private static final byte INS_UPDATE_IDENTITY = (byte) 0x42;
    private static final byte INS_SIGN_PROFILE_CHALLENGE = (byte) 0x64;
    private static final int NONCE_LENGTH = 32;
    private static final int SIGNATURE_LE = 256;
    private static final int DER_SIGNATURE_MIN = 8;
    private static final int DER_SEQUENCE_TAG = 0x30;
    private static final String NONCE_PATTERN = "[0-9A-Fa-f]{" + (NONCE_LENGTH * 2) + "}";

    private final CardTransport transport;
    private final IntConsumer responseSwSink;

    private byte[] lastSignature;

    public CardProfileUpdateCommands(CardTransport transport, IntConsumer responseSwSink) {
        this.transport = transport;
        this.responseSwSink = responseSwSink;
    }

    public IdentityWrite updateIdentity(ProfileEditRequest request, char[] adminPin, byte[] expectedVersion)
            throws CardException {
        lastSignature = null;
        if (expectedVersion == null || expectedVersion.length != ProfileEditRequest.EXPECTED_VERSION_LENGTH) {
            return new IdentityWrite(CardOperationResult.fail(
                    "expectedVersion phải đúng " + ProfileEditRequest.EXPECTED_VERSION_LENGTH
                            + " byte (chưa gửi APDU)"), -1);
        }
        byte[] payload = request.payload(adminPin, expectedVersion);
        try {
            transport.requireConnected();
            ResponseAPDU response = transport.transmit(new CommandAPDU(CardProtocol.CLA_APPLICATION,
                    INS_UPDATE_IDENTITY, 0x00, 0x00, payload));
            int sw = response.getSW();
            responseSwSink.accept(sw);
            String apdu = String.format(
                    "UPDATE_IDENTITY INS=42 P1P2=0000 case=3S Lc=%d Le=khong SW=%04X dataLen=%d",
                    payload.length, sw, response.getData().length);
            if (sw != Sw.OK) {
                return new IdentityWrite(CardOperationResult.fail(apdu + " | " + statusText(sw)), sw);
            }
            return new IdentityWrite(CardOperationResult.ok(apdu + " | " + request.evidence()
                    + " | expectedVersion=" + HexFormat.of().withUpperCase()
                            .formatHex(expectedVersion)), sw);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    public CardOperationResult signProfileChallenge(char[] adminPin, String nonceHex) throws CardException {
        transport.requireConnected();
        lastSignature = null;
        if (adminPin == null || adminPin.length != ProfileEditRequest.ADMIN_PIN_LENGTH) {
            return CardOperationResult.fail("Admin PIN phải đúng " + ProfileEditRequest.ADMIN_PIN_LENGTH
                    + " chữ số ASCII (chưa gửi APDU)");
        }
        if (nonceHex == null || !nonceHex.matches(NONCE_PATTERN)) {
            return CardOperationResult.fail("nonce phải đúng " + NONCE_LENGTH + " byte hex (chưa gửi APDU)");
        }
        byte[] nonce = HexFormat.of().parseHex(nonceHex);
        byte[] data = new byte[ProfileEditRequest.ADMIN_PIN_LENGTH + NONCE_LENGTH];
        try {
            for (int index = 0; index < ProfileEditRequest.ADMIN_PIN_LENGTH; index++) {
                data[index] = (byte) adminPin[index];
            }
            System.arraycopy(nonce, 0, data, ProfileEditRequest.ADMIN_PIN_LENGTH, NONCE_LENGTH);
            ResponseAPDU response = transport.transmit(new CommandAPDU(CardProtocol.CLA_APPLICATION,
                    INS_SIGN_PROFILE_CHALLENGE, 0x00, 0x00, data, SIGNATURE_LE));
            int sw = response.getSW();
            byte[] signature = response.getData();
            responseSwSink.accept(sw);
            String apdu = String.format(
                    "SIGN_PROFILE_CHALLENGE INS=64 P1P2=0000 case=4S Lc=%d Le=%d SW=%04X dataLen=%d",
                    data.length, SIGNATURE_LE, sw, signature.length);
            if (sw != Sw.OK) {
                return CardOperationResult.fail(apdu + " | " + signStatusText(sw));
            }
            if (signature.length < DER_SIGNATURE_MIN || signature.length > SIGNATURE_MAX_LENGTH
                    || signature[0] != DER_SEQUENCE_TAG) {
                return CardOperationResult.fail(apdu + " | chữ ký DER không hợp lệ: "
                        + signature.length + " byte");
            }
            lastSignature = signature.clone();
            return CardOperationResult.ok(apdu + " | chữ ký DER " + signature.length + " byte");
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(data, (byte) 0);
        }
    }

    public String lastSignatureHex() {
        return lastSignature == null ? "" : HexFormat.of().withUpperCase().formatHex(lastSignature);
    }

    void reset() {
        lastSignature = null;
    }

    private static String statusText(int sw) {
        if ((sw & 0xFFF0) == 0x63C0) {
            return String.format("Admin PIN sai, còn %d lượt thử", sw & 0x0F);
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "Admin PIN đã khóa; cần tái cấp thẻ";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED hoặc expectedVersion không khớp hồ sơ đang lưu";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc/Le sai hợp đồng Case 3S (không có Le)";
        }
        if (sw == Sw.WRONG_DATA) {
            return "định dạng dữ liệu sai (studentId/name/length)";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "bản ghi hỏng hoặc lỗi ghi";
        }
        return String.format("SW=%04X không ghi được hồ sơ", sw);
    }

    private static String signStatusText(int sw) {
        if ((sw & 0xFFF0) == 0x63C0) {
            return String.format("Admin PIN sai, còn %d lượt thử", sw & 0x0F);
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "Admin PIN đã khóa; cần tái cấp thẻ";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc/Le sai hợp đồng Case 4S (Le ≥ " + SIGNATURE_MAX_LENGTH + ")";
        }
        if (sw == Sw.WRONG_DATA) {
            return "định dạng Admin PIN hoặc nonce bị từ chối";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "không đọc được hồ sơ đang lưu để ký";
        }
        return String.format("SW=%04X không ký được challenge", sw);
    }
}
