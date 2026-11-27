package vn.labaccess.desktop.card;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import vn.labaccess.desktop.validation.PinFormat;
import vn.labaccess.desktop.validation.ClassCode;

public final class PersonalizeRequest {

    public static final int ADMIN_PIN_LENGTH = 8;
    public static final int STUDENT_PIN_LENGTH = 6;
    public static final int CARD_ID_LENGTH = 8;
    public static final int STUDENT_ID_MIN_LENGTH = 8;
    public static final int STUDENT_ID_MAX_LENGTH = 10;
    public static final int DISPLAY_NAME_MAX_LENGTH = 96;
    static final int MIN_PAYLOAD_LENGTH = 25;
    static final int MAX_PAYLOAD_LENGTH = 204;

    private static final int OFFSET_ADMIN_PIN_LENGTH = 0;
    private static final int OFFSET_ADMIN_PIN = 1;
    private static final int OFFSET_CARD_ID = 9;
    private static final int OFFSET_STUDENT_ID_LENGTH = 17;
    private static final int OFFSET_STUDENT_ID = 18;
    private static final byte ADMIN_PIN_LENGTH_FIELD = (byte) 8;
    private static final SecureRandom CARD_ID_RANDOM = new SecureRandom();
    private static final String CARD_ID_PATTERN = "[0-9A-Fa-f]{16}";
    private static final String STUDENT_ID_PATTERN = "[A-Za-z0-9]{"
            + STUDENT_ID_MIN_LENGTH + "," + STUDENT_ID_MAX_LENGTH + "}";

    private PersonalizeRequest() {
    }

    public static String problem(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) {
        if (!PinFormat.isDigits(adminPin, ADMIN_PIN_LENGTH)) {
            return adminPinProblem();
        }
        String cardIdProblem = cardIdProblem(cardIdHex);
        if (cardIdProblem != null) {
            return cardIdProblem;
        }
        String studentIdProblem = studentIdProblem(studentId);
        if (studentIdProblem != null) {
            return studentIdProblem;
        }
        if (!PinFormat.isDigits(studentPin, STUDENT_PIN_LENGTH)) {
            return studentPinProblem();
        }
        return displayNameProblem(displayName);
    }

    public static String problem(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName, String classCode) {
        String problem = problem(adminPin, cardIdHex, studentId, studentPin, displayName);
        return problem == null ? ClassCode.problem(classCode) : problem;
    }

    public static String studentPinProblem() {
        return "PIN sinh viên phải đúng " + STUDENT_PIN_LENGTH + " chữ số";
    }

    public static String adminPinProblem() {
        return "PIN quản trị phải đúng " + ADMIN_PIN_LENGTH + " chữ số";
    }

    public static String cardIdProblem(String cardIdHex) {
        if (cardIdHex == null || cardIdHex.isBlank()) {
            return "mã thẻ không được để trống";
        }
        String value = cardIdHex.strip();
        if (!value.matches(CARD_ID_PATTERN)) {
            return "mã thẻ phải đúng " + (CARD_ID_LENGTH * 2) + " ký tự hex";
        }
        if (isAllZero(HexFormat.of().parseHex(value))) {
            return "mã thẻ không được toàn 00";
        }
        return null;
    }

    public static String randomCardId() {
        byte[] bytes = new byte[CARD_ID_LENGTH];
        try {
            String value;
            do {
                CARD_ID_RANDOM.nextBytes(bytes);
                value = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
            } while (cardIdProblem(value) != null);
            return value;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String studentIdProblem(String studentId) {
        if (studentId == null || !studentId.matches(STUDENT_ID_PATTERN)) {
            return "Mã sinh viên phải " + STUDENT_ID_MIN_LENGTH + "–" + STUDENT_ID_MAX_LENGTH
                    + " ký tự chữ A–Z hoặc số 0–9.";
        }
        return null;
    }

    public static String normalizeStudentId(String studentId) {
        String problem = studentIdProblem(studentId);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return studentId.toUpperCase(Locale.ROOT);
    }

    public static String displayNameProblem(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "tên hiển thị phải 1–" + DISPLAY_NAME_MAX_LENGTH + " byte UTF-8";
        }
        int length = displayName.getBytes(StandardCharsets.UTF_8).length;
        if (length > DISPLAY_NAME_MAX_LENGTH) {
            return "tên hiển thị " + length + " byte, tối đa " + DISPLAY_NAME_MAX_LENGTH + " byte UTF-8";
        }
        return null;
    }

    public static byte[] payload(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) {
        byte[] cardId = HexFormat.of().parseHex(cardIdHex.strip());
        byte[] studentIdBytes = normalizeStudentId(studentId).getBytes(StandardCharsets.US_ASCII);
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

    public static byte[] payload(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName, String classCode) {
        byte[] base = payload(adminPin, cardIdHex, studentId, studentPin, displayName);
        byte[] extension = ProfileCodec.extension(studentId, classCode);
        try {
            byte[] out = Arrays.copyOf(base, base.length + extension.length);
            System.arraycopy(extension, 0, out, base.length, extension.length);
            return out;
        } finally {
            Arrays.fill(base, (byte) 0);
            Arrays.fill(extension, (byte) 0);
        }
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
