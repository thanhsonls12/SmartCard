package vn.labaccess.desktop.card;

import java.util.function.IntConsumer;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.transport.CardTransport;

final class CardProfileCommands {

    private static final byte INS_GET_PROFILE = (byte) 0x31;
    private static final byte INS_GET_PROFILE_CRYPTO_META = (byte) 0x40;
    private static final int PROFILE_LE = 256;

    private final CardTransport transport;
    private final CardPinCommands pinCommands;
    private final IntConsumer responseSwSink;

    private Profile lastProfile;
    private ProfileCryptoMeta lastProfileMeta;

    CardProfileCommands(CardTransport transport, CardPinCommands pinCommands,
            IntConsumer responseSwSink) {
        this.transport = transport;
        this.pinCommands = pinCommands;
        this.responseSwSink = responseSwSink;
    }

    CardOperationResult readProfile() throws CardException {
        return readProfile(0x00, 0x00);
    }

    CardOperationResult readProfile(int p1, int p2) throws CardException {
        transport.requireConnected();
        lastProfile = null;
        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_GET_PROFILE, p1, p2, PROFILE_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        responseSwSink.accept(sw);
        String apdu = String.format(
                "GET_PROFILE INS=31 P1P2=%02X%02X Le=00 SW=%04X dataLen=%d",
                p1, p2, sw, data.length);

        if (sw != Sw.OK) {
            return CardOperationResult.fail(apdu + " | " + profileStatusText(sw));
        }
        ProfileCodec.Parse parse = ProfileCodec.parse(data);
        if (!parse.ok()) {
            return CardOperationResult.fail(apdu + " | parser=FAIL (" + parse.problem() + ")");
        }
        lastProfile = parse.profile();
        return CardOperationResult.ok(apdu + " | parser=PASS | " + lastProfile.evidence()
                + " | UTF-8 nghiêm ngặt=PASS");
    }

    CardOperationResult readProfileCryptoMeta() throws CardException {
        return readProfileCryptoMeta(0x00, 0x00);
    }

    CardOperationResult readProfileCryptoMeta(int p1, int p2) throws CardException {
        transport.requireConnected();
        lastProfileMeta = null;
        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_GET_PROFILE_CRYPTO_META, p1, p2, PROFILE_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        responseSwSink.accept(sw);
        String apdu = String.format(
                "GET_PROFILE_CRYPTO_META INS=40 P1P2=%02X%02X Le=00 SW=%04X dataLen=%d",
                p1, p2, sw, data.length);

        if (sw != Sw.OK) {
            return CardOperationResult.fail(apdu + " | " + profileStatusText(sw));
        }
        ProfileMetaCodec.Parse parse = ProfileMetaCodec.parse(data);
        if (!parse.ok()) {
            return CardOperationResult.fail(apdu + " | parser=FAIL (" + parse.problem() + ")");
        }
        lastProfileMeta = parse.meta();
        return CardOperationResult.ok(apdu + " | parser=PASS | " + lastProfileMeta.evidence());
    }

    CardOperationResult readProfileWithPin(char[] pin) throws CardException {
        return withFreshAuthorization("GET_PROFILE", pin, this::readProfile);
    }

    CardOperationResult readProfileCryptoMetaWithPin(char[] pin) throws CardException {
        return withFreshAuthorization("GET_PROFILE_CRYPTO_META", pin,
                this::readProfileCryptoMeta);
    }

    private CardOperationResult withFreshAuthorization(String label, char[] pin, ReadOperation read)
            throws CardException {
        lastProfile = null;
        lastProfileMeta = null;
        CardOperationResult verified = pinCommands.verifyStudentPin(pin);
        if (!verified.ok()) {
            return CardOperationResult.fail(label + " không chạy — VERIFY: " + verified.text());
        }
        CardOperationResult result = read.run();
        if (!result.ok()) {
            return result;
        }
        return CardOperationResult.ok("VERIFY SW=9000 (quyền chỉ dùng cho lệnh vừa gửi) | " + result.text());
    }

    interface ReadOperation {
        CardOperationResult run() throws CardException;
    }

    Profile lastProfile() {
        return lastProfile;
    }

    ProfileCryptoMeta lastProfileMeta() {
        return lastProfileMeta;
    }

    void reset() {
        lastProfile = null;
        lastProfileMeta = null;
    }

    private static String profileStatusText(int sw) {
        if (sw == Sw.SECURITY_NOT_SATISFIED) {
            return "thiếu quyền student — VERIFY_STUDENT_PIN ngay trước lệnh đọc,"
                    + " quyền chỉ dùng cho một lệnh";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED — chưa có hồ sơ để đọc";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Le nhỏ hơn độ dài response";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "record hồ sơ hỏng (tag/version/schema) — không trả dữ liệu";
        }
        return String.format("SW=%04X không đọc được hồ sơ", sw);
    }
}
