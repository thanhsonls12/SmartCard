package vn.labaccess.desktop;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

final class PhotoImage {

    static final int WIDTH = 64;
    static final int HEIGHT = 64;
    static final int LENGTH = WIDTH * HEIGHT;
    static final int CHUNK_MAX_LENGTH = 128;
    static final int BEGIN_DATA_LENGTH = 13;
    static final int ADMIN_PIN_LENGTH = 8;
    static final int OFFSET_FORMAT = 8;
    static final int OFFSET_WIDTH = 9;
    static final int OFFSET_HEIGHT = 10;
    static final int OFFSET_TOTAL = 11;
    static final byte FORMAT_GRAYSCALE_RAW8 = (byte) 0x01;
    static final int FINGERPRINT_BYTES = 4;

    private PhotoImage() {
    }

    static byte[] beginPayload(char[] adminPin) {
        byte[] payload = new byte[BEGIN_DATA_LENGTH];
        for (int index = 0; index < ADMIN_PIN_LENGTH; index++) {
            payload[index] = (byte) adminPin[index];
        }
        payload[OFFSET_FORMAT] = FORMAT_GRAYSCALE_RAW8;
        payload[OFFSET_WIDTH] = (byte) WIDTH;
        payload[OFFSET_HEIGHT] = (byte) HEIGHT;
        payload[OFFSET_TOTAL] = (byte) (LENGTH >> 8);
        payload[OFFSET_TOTAL + 1] = (byte) LENGTH;
        return payload;
    }

    static byte[] chunk(byte[] image, int offset, int length) {
        return Arrays.copyOfRange(image, offset, offset + length);
    }

    static int chunkLength(int offset) {
        return Math.min(CHUNK_MAX_LENGTH, LENGTH - offset);
    }

    static String fingerprint(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().withUpperCase().formatHex(hash, 0, FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK không có SHA-256", exception);
        }
    }

    static String firstMismatch(byte[] expected, byte[] actual) {
        if (actual == null) {
            return "không có dữ liệu đọc lại";
        }
        if (expected.length != actual.length) {
            return String.format("độ dài lệch: nguồn=%d đọc lại=%d", expected.length, actual.length);
        }
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != actual[index]) {
                return String.format("lệch tại byte %d: nguồn=%02X đọc lại=%02X",
                        index, expected[index] & 0xFF, actual[index] & 0xFF);
            }
        }
        return null;
    }
}
