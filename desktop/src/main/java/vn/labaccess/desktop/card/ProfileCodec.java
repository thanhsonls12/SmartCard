package vn.labaccess.desktop.card;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ProfileCodec {

    public record Parse(boolean ok, String problem, Profile profile) {
        static Parse fail(String problem) {
            return new Parse(false, problem, null);
        }

        static Parse ok(Profile profile) {
            return new Parse(true, null, profile);
        }
    }

    static final int SCHEMA_VERSION = 0x01;
    static final int MIN_LENGTH = 5;
    static final int MAX_LENGTH = 131;
    static final int MAX_STUDENT_ID_LENGTH = 32;
    static final int MAX_DISPLAY_NAME_LENGTH = 96;
    static final int OFFSET_SCHEMA_VERSION = 0;
    static final int OFFSET_STUDENT_ID_LENGTH = 1;
    static final int OFFSET_STUDENT_ID = 2;

    private ProfileCodec() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }

    static byte[] plaintext(String studentId, String displayName) {
        byte[] studentIdBytes = studentId.getBytes(StandardCharsets.US_ASCII);
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = new byte[OFFSET_STUDENT_ID + studentIdBytes.length + 1 + nameBytes.length];
        plaintext[OFFSET_SCHEMA_VERSION] = (byte) SCHEMA_VERSION;
        plaintext[OFFSET_STUDENT_ID_LENGTH] = (byte) studentIdBytes.length;
        System.arraycopy(studentIdBytes, 0, plaintext, OFFSET_STUDENT_ID, studentIdBytes.length);
        int nameLengthOffset = OFFSET_STUDENT_ID + studentIdBytes.length;
        plaintext[nameLengthOffset] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, plaintext, nameLengthOffset + 1, nameBytes.length);
        return plaintext;
    }

    public static String hashHex(String studentId, String displayName) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(plaintext(studentId, displayName)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE thiếu SHA-256", ex);
        }
    }

    public static Parse parse(byte[] data) {
        if (data == null) {
            return Parse.fail("không có dữ liệu response");
        }
        if (data.length < MIN_LENGTH || data.length > MAX_LENGTH) {
            return Parse.fail(String.format("response %d byte ngoài khoảng %d..%d",
                    data.length, MIN_LENGTH, MAX_LENGTH));
        }

        int schemaVersion = data[OFFSET_SCHEMA_VERSION] & 0xFF;
        if (schemaVersion != SCHEMA_VERSION) {
            return Parse.fail(String.format("schemaVersion=%02X, chỉ nhận %02X",
                    schemaVersion, SCHEMA_VERSION));
        }

        int studentIdLength = data[OFFSET_STUDENT_ID_LENGTH] & 0xFF;
        if (studentIdLength < 1 || studentIdLength > MAX_STUDENT_ID_LENGTH) {
            return Parse.fail(String.format("studentIdLength=%d ngoài 1..%d",
                    studentIdLength, MAX_STUDENT_ID_LENGTH));
        }
        int nameLengthOffset = OFFSET_STUDENT_ID + studentIdLength;
        if (nameLengthOffset + 1 > data.length) {
            return Parse.fail(String.format("response %d byte không đủ cho studentIdLength=%d",
                    data.length, studentIdLength));
        }

        String studentId = readStudentId(data, studentIdLength);
        if (studentId == null) {
            return Parse.fail("studentId chứa ký tự ngoài A-Z, 0-9, '-'");
        }

        int displayNameLength = data[nameLengthOffset] & 0xFF;
        if (displayNameLength < 1 || displayNameLength > MAX_DISPLAY_NAME_LENGTH) {
            return Parse.fail(String.format("displayNameLength=%d ngoài 1..%d",
                    displayNameLength, MAX_DISPLAY_NAME_LENGTH));
        }
        int expectedLength = nameLengthOffset + 1 + displayNameLength;
        if (data.length != expectedLength) {
            return Parse.fail(String.format("response %d byte nhưng schema v1 đòi %d byte",
                    data.length, expectedLength));
        }

        String displayName = decodeUtf8(data, nameLengthOffset + 1, displayNameLength);
        if (displayName == null) {
            return Parse.fail("displayName không phải UTF-8 hợp lệ");
        }
        return Parse.ok(new Profile(schemaVersion, studentId, displayName, displayNameLength));
    }

    private static String readStudentId(byte[] data, int length) {
        StringBuilder id = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char value = (char) (data[OFFSET_STUDENT_ID + index] & 0xFF);
            boolean valid = (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '-';
            if (!valid) {
                return null;
            }
            id.append(value);
        }
        return id.toString();
    }

    private static String decodeUtf8(byte[] data, int offset, int length) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }
}
