package vn.labaccess.card;

import javacard.framework.ISOException;

final class StudentIdFormat {

    static final short MIN_LENGTH = (short) 8;
    static final short MAX_LENGTH = (short) 10;

    private static final byte ASCII_UPPER_A = (byte) 'A';
    private static final byte ASCII_UPPER_Z = (byte) 'Z';
    private static final byte ASCII_LOWER_A = (byte) 'a';
    private static final byte ASCII_LOWER_Z = (byte) 'z';
    private static final byte ASCII_CASE_OFFSET = (byte) ('a' - 'A');
    private static final short UNSIGNED_BYTE_MASK = (short) 0x00FF;

    private StudentIdFormat() {
    }

    static short length(byte[] buffer, short offset) {
        return (short) (buffer[offset] & UNSIGNED_BYTE_MASK);
    }

    static void requireValid(byte[] buffer, short offset, short length) {
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        for (short index = (short) 0; index < length; index++) {
            short position = (short) (offset + index);
            byte value = buffer[position];
            if (value >= ASCII_LOWER_A && value <= ASCII_LOWER_Z) {
                value = (byte) (value - ASCII_CASE_OFFSET);
                buffer[position] = value;
            }
            boolean allowed = (value >= ASCII_UPPER_A && value <= ASCII_UPPER_Z)
                    || (value >= Protocol.ASCII_DIGIT_ZERO && value <= Protocol.ASCII_DIGIT_NINE);
            if (!allowed) {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
        }
    }
}
