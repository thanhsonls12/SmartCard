package vn.labaccess.card;

import javacard.framework.ISOException;

final class PinFormat {

    private PinFormat() {
    }

    static void requireDigits(byte[] buffer, short offset, byte length) {
        for (short index = (short) 0; index < length; index++) {
            byte value = buffer[(short) (offset + index)];
            if (value < Protocol.ASCII_DIGIT_ZERO || value > Protocol.ASCII_DIGIT_NINE) {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
        }
    }
}
