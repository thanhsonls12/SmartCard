package vn.labaccess.card;

import javacard.framework.ISOException;

final class ProfileExtension {

    private static final short UNSIGNED_BYTE_MASK = (short) 0x00FF;
    private static final byte ASCII_UPPER_A = (byte) 'A';
    private static final byte ASCII_UPPER_Z = (byte) 'Z';

    private ProfileExtension() {
    }

    static void requireValid(byte[] data, short offset, short totalLength) {
        if (offset >= totalLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short classLength = (short) (data[offset] & UNSIGNED_BYTE_MASK);
        if (classLength < Protocol.CLASS_MIN_LENGTH || classLength > Protocol.CLASS_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        short classOffset = (short) (offset + 1);
        short majorLengthOffset = (short) (classOffset + classLength);
        if (majorLengthOffset >= totalLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short majorLength = (short) (data[majorLengthOffset] & UNSIGNED_BYTE_MASK);
        if (majorLength > Protocol.MAJOR_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        short majorOffset = (short) (majorLengthOffset + 1);
        if ((short) (majorOffset + majorLength) != totalLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        for (short index = classOffset; index < majorLengthOffset; index++) {
            byte value = data[index];
            if (!((value >= ASCII_UPPER_A && value <= ASCII_UPPER_Z)
                    || (value >= Protocol.ASCII_DIGIT_ZERO && value <= Protocol.ASCII_DIGIT_NINE))) {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
        }
        Utf8Format.requireValid(data, majorOffset, majorLength);
    }
}
