package vn.labaccess.desktop;

import java.util.HexFormat;

final class ProfileMetaCodec {

    record Parse(boolean ok, String problem, ProfileCryptoMeta meta) {
        static Parse fail(String problem) {
            return new Parse(false, problem, null);
        }

        static Parse ok(ProfileCryptoMeta meta) {
            return new Parse(true, null, meta);
        }
    }

    static final int RESPONSE_LENGTH = 65;
    static final int RECORD_VERSION = 0x01;
    static final int OFFSET_RECORD_VERSION = 0;
    static final int OFFSET_ALGORITHM = 1;
    static final int OFFSET_SCHEMA_VERSION = 2;
    static final int OFFSET_CIPHERTEXT_LENGTH = 3;
    static final int OFFSET_NONCE = 5;
    static final int OFFSET_TAG = 17;
    static final int OFFSET_HASH = 33;

    private ProfileMetaCodec() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }

    static Parse parse(byte[] data) {
        if (data == null) {
            return Parse.fail("không có dữ liệu response");
        }
        if (data.length != RESPONSE_LENGTH) {
            return Parse.fail(String.format("response %d byte, metadata phải đúng %d byte",
                    data.length, RESPONSE_LENGTH));
        }

        int recordVersion = data[OFFSET_RECORD_VERSION] & 0xFF;
        if (recordVersion != RECORD_VERSION) {
            return Parse.fail(String.format("recordVersion=%02X, chỉ nhận %02X",
                    recordVersion, RECORD_VERSION));
        }

        int algorithmId = data[OFFSET_ALGORITHM] & 0xFF;
        if (algorithmId != ProfileCryptoMeta.ALGORITHM_AES_128_GCM) {
            return Parse.fail(String.format("algorithmId=%02X, chỉ nhận %02X AES-128-GCM",
                    algorithmId, ProfileCryptoMeta.ALGORITHM_AES_128_GCM));
        }

        int schemaVersion = data[OFFSET_SCHEMA_VERSION] & 0xFF;
        if (schemaVersion != ProfileCodec.SCHEMA_VERSION) {
            return Parse.fail(String.format("profileSchemaVersion=%02X, chỉ nhận %02X",
                    schemaVersion, ProfileCodec.SCHEMA_VERSION));
        }

        int ciphertextLength = ((data[OFFSET_CIPHERTEXT_LENGTH] & 0xFF) << 8)
                | (data[OFFSET_CIPHERTEXT_LENGTH + 1] & 0xFF);
        if (ciphertextLength < ProfileCodec.MIN_LENGTH
                || ciphertextLength > ProfileCodec.MAX_LENGTH) {
            return Parse.fail(String.format("ciphertextLength=%d ngoài khoảng %d..%d",
                    ciphertextLength, ProfileCodec.MIN_LENGTH, ProfileCodec.MAX_LENGTH));
        }

        HexFormat hex = HexFormat.of().withUpperCase();
        return Parse.ok(new ProfileCryptoMeta(recordVersion, algorithmId, schemaVersion,
                ciphertextLength,
                slice(hex, data, OFFSET_NONCE, ProfileCryptoMeta.NONCE_LENGTH),
                slice(hex, data, OFFSET_TAG, ProfileCryptoMeta.TAG_LENGTH),
                slice(hex, data, OFFSET_HASH, ProfileCryptoMeta.HASH_LENGTH)));
    }

    private static String slice(HexFormat hex, byte[] data, int offset, int length) {
        return hex.formatHex(data, offset, offset + length);
    }
}
