package vn.labaccess.desktop;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

final class PersonalizeRequest {

    static final int ADMIN_PIN_LENGTH = 8;
    static final int STUDENT_PIN_LENGTH = 6;
    static final int CARD_ID_LENGTH = 8;
    static final int STUDENT_ID_MAX_LENGTH = 32;
    static final int DISPLAY_NAME_MAX_LENGTH = 96;
    static final int MIN_PAYLOAD_LENGTH = 25;
    static final int MAX_PAYLOAD_LENGTH = 153;

    private static final int OFFSET_ADMIN_PIN_LENGTH = 0;
    private static final int OFFSET_ADMIN_PIN = 1;
    private static final int OFFSET_CARD_ID = 9;
    private static final int OFFSET_STUDENT_ID_LENGTH = 17;
    private static final int OFFSET_STUDENT_ID = 18;
    private static final byte ADMIN_PIN_LENGTH_FIELD = (byte) 8;
    private static final String CARD_ID_PATTERN = "[0-9A-Fa-f]{16}";
    private static final String STUDENT_ID_PATTERN = "[A-Z0-9-]{1,32}";

    private PersonalizeRequest() {
    }

    static String problem(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) {
        if (!isDigits(adminPin, ADMIN_PIN_LENGTH)) {
            return "admin PIN phải đúng " + ADMIN_PIN_LENGTH + " chữ số";
        }
        String cardIdProblem = cardIdProblem(cardIdHex);
        if (cardIdProblem != null) {
            return cardIdProblem;
        }
        String studentIdProblem = studentIdProblem(studentId);
        if (studentIdProblem != null) {
            return studentIdProblem;
        }
        if (!isDigits(studentPin, STUDENT_PIN_LENGTH)) {
            return "student PIN phải đúng " + STUDENT_PIN_LENGTH + " chữ số";
        }
        return displayNameProblem(displayName);
    }

    static String cardIdProblem(String cardIdHex) {
        if (cardIdHex == null || cardIdHex.isBlank()) {
            return "cardId không được để trống";
        }
        String value = cardIdHex.strip();
        if (!value.matches(CARD_ID_PATTERN)) {
            return "cardId phải đúng " + (CARD_ID_LENGTH * 2) + " ký tự hex (" + CARD_ID_LENGTH + " byte)";
        }
        if (isAllZero(HexFormat.of().parseHex(value))) {
            return "cardId không được toàn 00";
        }
        return null;
    }

    static String studentIdProblem(String studentId) {
        if (studentId == null || studentId.isEmpty()) {
            return "studentId phải 1–" + STUDENT_ID_MAX_LENGTH + " ký tự A–Z, 0–9, -";
        }
        if (!studentId.matches(STUDENT_ID_PATTERN)) {
            return "studentId chỉ nhận A–Z, 0–9 và -, tối đa " + STUDENT_ID_MAX_LENGTH + " byte";
        }
        return null;
    }

    static String displayNameProblem(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "tên hiển thị phải 1–" + DISPLAY_NAME_MAX_LENGTH + " byte UTF-8";
        }
        int length = displayName.getBytes(StandardCharsets.UTF_8).length;
        if (length > DISPLAY_NAME_MAX_LENGTH) {
            return "tên hiển thị " + length + " byte, tối đa " + DISPLAY_NAME_MAX_LENGTH + " byte UTF-8";
        }
        return null;
    }

    static byte[] payload(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) {
        byte[] cardId = HexFormat.of().parseHex(cardIdHex.strip());
        byte[] studentIdBytes = studentId.getBytes(StandardCharsets.US_ASCII);
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[MIN_PAYLOAD_LENGTH + studentIdBytes.length + nameBytes.length];
        try {
            out[OFFSET_ADMIN_PIN_LENGTH] = ADMIN_PIN_LENGTH_FIELD;
            for (int index = 0; index < ADMIN_PIN_LENGTH; index++) {
                out[OFFSET_ADMIN_PIN + index] = (byte) adminPin[index];
            }
            System.arraycopy(cardId, 0, out, OFFSET_CARD_ID, CARD_ID_LENGTH);
            out[OFFSET_STUDENT_ID_LENGTH] = (byte) studentIdBytes.length;
            System.arraycopy(studentIdBytes, 0, out, OFFSET_STUDENT_ID, studentIdBytes.length);
            int studentPinOffset = OFFSET_STUDENT_ID + studentIdBytes.length;
            for (int index = 0; index < STUDENT_PIN_LENGTH; index++) {
                out[studentPinOffset + index] = (byte) studentPin[index];
            }
            int nameLengthOffset = studentPinOffset + STUDENT_PIN_LENGTH;
            out[nameLengthOffset] = (byte) nameBytes.length;
            System.arraycopy(nameBytes, 0, out, nameLengthOffset + 1, nameBytes.length);
            return out;
        } finally {
            Arrays.fill(cardId, (byte) 0);
            Arrays.fill(studentIdBytes, (byte) 0);
            Arrays.fill(nameBytes, (byte) 0);
        }
    }

    private static boolean isDigits(char[] value, int expectedLength) {
        if (value == null || value.length != expectedLength) {
            return false;
        }
        for (char digit : value) {
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

}
