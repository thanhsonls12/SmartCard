package vn.labaccess.desktop.card;

import vn.labaccess.desktop.validation.ClassCode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record ProfileEditRequest(String studentId, String displayName, String classCode) {

    static final int ADMIN_PIN_LENGTH = 8;
    static final int EXPECTED_VERSION_LENGTH = 12;
    static final int PAYLOAD_OVERHEAD = 22;

    public static ProfileEditRequest normalized(String studentId, String displayName, String classCode) {
        return new ProfileEditRequest(PersonalizeRequest.normalizeStudentId(studentId), displayName,
                ClassCode.normalize(classCode));
    }

    int studentIdLength() {
        return studentId.getBytes(StandardCharsets.US_ASCII).length;
    }

    public int displayNameLength() {
        return displayName.getBytes(StandardCharsets.UTF_8).length;
    }

    public int payloadLength() {
        return PAYLOAD_OVERHEAD + studentIdLength() + displayNameLength();
    }

    public byte[] payload(char[] adminPin, byte[] expectedVersion) {
        byte[] studentIdBytes = studentId.getBytes(StandardCharsets.US_ASCII);
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[payloadLength()];
        try {
            for (int index = 0; index < ADMIN_PIN_LENGTH; index++) {
                out[index] = (byte) adminPin[index];
            }
            System.arraycopy(expectedVersion, 0, out, ADMIN_PIN_LENGTH, EXPECTED_VERSION_LENGTH);
            int offset = ADMIN_PIN_LENGTH + EXPECTED_VERSION_LENGTH;
            out[offset] = (byte) studentIdBytes.length;
            System.arraycopy(studentIdBytes, 0, out, offset + 1, studentIdBytes.length);
            offset += 1 + studentIdBytes.length;
            out[offset] = (byte) nameBytes.length;
            System.arraycopy(nameBytes, 0, out, offset + 1, nameBytes.length);
            return out;
        } finally {
            Arrays.fill(studentIdBytes, (byte) 0);
            Arrays.fill(nameBytes, (byte) 0);
        }
    }

    String evidence() {
        return "studentIdLen=" + studentIdLength() + " nameBytes=" + displayNameLength()
                + " classCode=" + classCode;
    }
}
