package vn.labaccess.card;

import javacard.framework.ISOException;

final class PersonalizePayload {

    static final short MIN_LENGTH = (short) 25;
    static final short MAX_LENGTH = (short) 153;

    static final short OFFSET_ADMIN_PIN_LENGTH = (short) 0;
    static final short OFFSET_ADMIN_PIN = (short) 1;
    static final short OFFSET_CARD_ID = (short) 9;
    static final short OFFSET_STUDENT_ID_LENGTH = (short) 17;
    static final short OFFSET_STUDENT_ID = (short) 18;

    static final byte ADMIN_PIN_LENGTH_FIELD = (byte) 8;

    static final short STUDENT_ID_MAX_LENGTH = (short) 32;
    static final short DISPLAY_NAME_MAX_LENGTH = (short) 96;

    private static final short UNSIGNED_BYTE_MASK = 0x00FF;

    private static final byte ASCII_DASH = (byte) '-';
    private static final byte ASCII_UPPER_A = (byte) 'A';
    private static final byte ASCII_UPPER_Z = (byte) 'Z';

    private PersonalizePayload() {
    }

    static short studentIdLength(byte[] buffer) {
        return (short) (buffer[OFFSET_STUDENT_ID_LENGTH] & UNSIGNED_BYTE_MASK);
    }

    static short studentPinOffset(byte[] buffer) {
        return (short) (OFFSET_STUDENT_ID + studentIdLength(buffer));
    }

    static short displayNameLengthOffset(byte[] buffer) {
        return (short) (studentPinOffset(buffer) + Protocol.STUDENT_PIN_LENGTH);
    }

    static short displayNameOffset(byte[] buffer) {
        return (short) (displayNameLengthOffset(buffer) + 1);
    }

    static short displayNameLength(byte[] buffer) {
        return (short) (buffer[displayNameLengthOffset(buffer)] & UNSIGNED_BYTE_MASK);
    }

    static short totalLength(byte[] buffer) {
        return (short) (displayNameOffset(buffer) + displayNameLength(buffer));
    }

    static void validate(byte[] buffer, short lc) {
        if (lc < MIN_LENGTH || lc > MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        if (buffer[OFFSET_ADMIN_PIN_LENGTH] != ADMIN_PIN_LENGTH_FIELD) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        requireNonZeroCardId(buffer);
        short studentIdLength = studentIdLength(buffer);
        if (studentIdLength < (short) 1 || studentIdLength > STUDENT_ID_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        short studentPinOffset = studentPinOffset(buffer);
        short nameLengthOffset = (short) (studentPinOffset + Protocol.STUDENT_PIN_LENGTH);
        if ((short) (nameLengthOffset + 1) > lc) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short displayNameLength = (short) (buffer[nameLengthOffset] & UNSIGNED_BYTE_MASK);
        if (displayNameLength < (short) 1 || displayNameLength > DISPLAY_NAME_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        if ((short) (nameLengthOffset + 1 + displayNameLength) != lc) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        PinFormat.requireDigits(buffer, OFFSET_ADMIN_PIN, Protocol.ADMIN_PIN_LENGTH);
        requireStudentIdCharacters(buffer, studentIdLength);
        PinFormat.requireDigits(buffer, studentPinOffset, Protocol.STUDENT_PIN_LENGTH);
    }

    private static void requireNonZeroCardId(byte[] buffer) {
        for (short index = (short) 0; index < Protocol.CARD_ID_LENGTH; index++) {
            if (buffer[(short) (OFFSET_CARD_ID + index)] != Protocol.CARD_ID_UNASSIGNED) {
                return;
            }
        }
        ISOException.throwIt(Sw.WRONG_DATA);
    }

    private static void requireStudentIdCharacters(byte[] buffer, short length) {
        for (short index = (short) 0; index < length; index++) {
            byte value = buffer[(short) (OFFSET_STUDENT_ID + index)];
            boolean allowed = (value >= ASCII_UPPER_A && value <= ASCII_UPPER_Z)
                    || (value >= Protocol.ASCII_DIGIT_ZERO
                            && value <= Protocol.ASCII_DIGIT_NINE)
                    || value == ASCII_DASH;
            if (!allowed) {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
        }
    }

}
