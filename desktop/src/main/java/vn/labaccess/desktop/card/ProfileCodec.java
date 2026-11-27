package vn.labaccess.desktop.card;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import vn.labaccess.desktop.validation.ClassCode;
import vn.labaccess.desktop.validation.StudentMajor;

public final class ProfileCodec {

    public record Parse(boolean ok, String problem, Profile profile) {
        static Parse fail(String problem) {
            return new Parse(false, problem, null);
        }

        static Parse ok(Profile profile) {
            return new Parse(true, null, profile);
        }
    }

    static final int LEGACY_SCHEMA_VERSION = 0x01;
    static final int SCHEMA_VERSION = 0x02;
    static final int MIN_LENGTH = 5;
    static final int MAX_LENGTH = 204;
    static final int MAX_STUDENT_ID_LENGTH = 32;
    static final int MAX_DISPLAY_NAME_LENGTH = 96;
    static final int MAX_MAJOR_LENGTH = 64;
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
        plaintext[OFFSET_SCHEMA_VERSION] = (byte) LEGACY_SCHEMA_VERSION;
        plaintext[OFFSET_STUDENT_ID_LENGTH] = (byte) studentIdBytes.length;
        System.arraycopy(studentIdBytes, 0, plaintext, OFFSET_STUDENT_ID, studentIdBytes.length);
        int nameLengthOffset = OFFSET_STUDENT_ID + studentIdBytes.length;
        plaintext[nameLengthOffset] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, plaintext, nameLengthOffset + 1, nameBytes.length);
        return plaintext;
    }

    static byte[] extension(String studentId, String classCode) {
        String normalized = ClassCode.normalize(classCode);
        String problem = ClassCode.problem(normalized);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        byte[] classBytes = normalized.getBytes(StandardCharsets.US_ASCII);
        byte[] majorBytes = StudentMajor.fromStudentId(studentId).getBytes(StandardCharsets.UTF_8);
        byte[] extension = new byte[2 + classBytes.length + majorBytes.length];
        extension[0] = (byte) classBytes.length;
        System.arraycopy(classBytes, 0, extension, 1, classBytes.length);
        extension[1 + classBytes.length] = (byte) majorBytes.length;
        System.arraycopy(majorBytes, 0, extension, 2 + classBytes.length, majorBytes.length);
        return extension;
    }

    static byte[] plaintext(String studentId, String displayName, String classCode) {
        byte[] legacy = plaintext(studentId, displayName);
        byte[] extension = extension(studentId, classCode);
        byte[] plaintext = Arrays.copyOf(legacy, legacy.length + extension.length);
        plaintext[OFFSET_SCHEMA_VERSION] = (byte) SCHEMA_VERSION;
        System.arraycopy(extension, 0, plaintext, legacy.length, extension.length);
        return plaintext;
    }

    public static String hashHex(String studentId, String displayName) {
        return hash(plaintext(studentId, displayName));
    }

    public static String hashHex(String studentId, String displayName, String classCode) {
        return hash(plaintext(studentId, displayName, classCode));
    }

    public static String hashHex(Profile profile) {
        if (profile.schemaVersion() == LEGACY_SCHEMA_VERSION) {
            return hashHex(profile.studentId(), profile.displayName());
        }
        byte[] base = plaintext(profile.studentId(), profile.displayName());
        byte[] classBytes = profile.classCode().getBytes(StandardCharsets.US_ASCII);
        byte[] majorBytes = profile.major().getBytes(StandardCharsets.UTF_8);
        byte[] data = Arrays.copyOf(base, base.length + 2 + classBytes.length + majorBytes.length);
        data[0] = (byte) SCHEMA_VERSION;
        data[base.length] = (byte) classBytes.length;
        System.arraycopy(classBytes, 0, data, base.length + 1, classBytes.length);
        data[base.length + 1 + classBytes.length] = (byte) majorBytes.length;
        System.arraycopy(majorBytes, 0, data, base.length + 2 + classBytes.length, majorBytes.length);
        return hash(data);
    }

    private static String hash(byte[] plaintext) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE thiếu SHA-256", ex);
        }
    }

    public static Parse parse(byte[] data) {
        if (data == null || data.length < MIN_LENGTH || data.length > MAX_LENGTH) {
            return Parse.fail("response hồ sơ thiếu dữ liệu hoặc vượt giới hạn " + MAX_LENGTH + " byte");
        }
        int schemaVersion = data[OFFSET_SCHEMA_VERSION] & 0xFF;
        if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != SCHEMA_VERSION) {
            return Parse.fail(String.format("schemaVersion=%02X, chỉ nhận 01 hoặc 02", schemaVersion));
        }
        int studentIdLength = data[OFFSET_STUDENT_ID_LENGTH] & 0xFF;
        if (studentIdLength < 1 || studentIdLength > MAX_STUDENT_ID_LENGTH) {
            return Parse.fail("studentIdLength ngoài 1.." + MAX_STUDENT_ID_LENGTH);
        }
        int nameLengthOffset = OFFSET_STUDENT_ID + studentIdLength;
        if (nameLengthOffset + 1 > data.length) {
            return Parse.fail("response không đủ cho studentIdLength");
        }
        String studentId = readStudentId(data, studentIdLength);
        if (studentId == null) {
            return Parse.fail("studentId chứa ký tự ngoài A-Z, 0-9, '-'");
        }
        int nameLength = data[nameLengthOffset] & 0xFF;
        int endName = nameLengthOffset + 1 + nameLength;
        if (nameLength < 1 || nameLength > MAX_DISPLAY_NAME_LENGTH || endName > data.length) {
            return Parse.fail("displayNameLength sai hoặc response không đủ tên");
        }
        String name = decodeUtf8(data, nameLengthOffset + 1, nameLength);
        if (name == null) {
            return Parse.fail("displayName không phải UTF-8 hợp lệ");
        }
        if (schemaVersion == LEGACY_SCHEMA_VERSION) {
            return data.length == endName
                    ? Parse.ok(new Profile(schemaVersion, studentId, name, nameLength))
                    : Parse.fail("response thừa byte so với schema v1");
        }
        return parseExtension(data, endName, studentId, name, nameLength);
    }

    private static Parse parseExtension(byte[] data, int offset, String studentId, String name, int nameLength) {
        if (offset >= data.length) {
            return Parse.fail("schema v2 thiếu lớp và chuyên ngành");
        }
        int classLength = data[offset] & 0xFF;
        int majorLengthOffset = offset + 1 + classLength;
        if (classLength < 5 || classLength > 7 || majorLengthOffset >= data.length) {
            return Parse.fail("độ dài lớp sai hoặc response bị cắt");
        }
        String classCode = new String(data, offset + 1, classLength, StandardCharsets.US_ASCII);
        if (!classCode.matches("[A-Z0-9]{5,7}")) {
            return Parse.fail("lớp phải chứa chữ hoa A-Z hoặc số");
        }
        int majorLength = data[majorLengthOffset] & 0xFF;
        if (majorLength > MAX_MAJOR_LENGTH || majorLengthOffset + 1 + majorLength != data.length) {
            return Parse.fail("độ dài chuyên ngành sai hoặc response thừa/thiếu byte");
        }
        String major = decodeUtf8(data, majorLengthOffset + 1, majorLength);
        if (major == null) {
            return Parse.fail("chuyên ngành không phải UTF-8 hợp lệ");
        }
        return Parse.ok(new Profile(SCHEMA_VERSION, studentId, name, nameLength, classCode, major));
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
