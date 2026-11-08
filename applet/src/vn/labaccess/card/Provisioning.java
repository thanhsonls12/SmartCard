package vn.labaccess.card;

import javacard.framework.ISOException;

final class Provisioning {

    static final short ADMIN_PIN_PAYLOAD_LENGTH = (short) 10;

    private static final short UNSIGNED_BYTE_MASK = 0x00FF;

    private static final byte PAYLOAD_VERSION_OFFSET = (byte) 0;
    private static final byte PAYLOAD_PIN_LENGTH_OFFSET = (byte) 1;
    private static final byte PAYLOAD_PIN_OFFSET = (byte) 2;

    private Provisioning() {
    }

    static short adminPinOffset(byte[] buffer, short offset, byte length) {
        short end = (short) (offset + length);
        short cursor = offset;

        requireAvailable(cursor, end, (short) 1);
        short instanceAidLength = (short) (buffer[cursor] & UNSIGNED_BYTE_MASK);
        cursor += 1;

        requireAvailable(cursor, end, instanceAidLength);
        cursor += instanceAidLength;

        requireAvailable(cursor, end, (short) 1);
        short controlInfoLength = (short) (buffer[cursor] & UNSIGNED_BYTE_MASK);
        cursor += 1;

        requireAvailable(cursor, end, controlInfoLength);
        cursor += controlInfoLength;

        requireAvailable(cursor, end, (short) 1);
        short dataLength = (short) (buffer[cursor] & UNSIGNED_BYTE_MASK);
        cursor += 1;

        requireAvailable(cursor, end, dataLength);
        if ((short) (cursor + dataLength) != end) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        if (dataLength != ADMIN_PIN_PAYLOAD_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        if (buffer[(short) (cursor + PAYLOAD_VERSION_OFFSET)] != Protocol.PROVISIONING_VERSION) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        if (buffer[(short) (cursor + PAYLOAD_PIN_LENGTH_OFFSET)] != Protocol.ADMIN_PIN_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }

        short pinOffset = (short) (cursor + PAYLOAD_PIN_OFFSET);
        requireDigits(buffer, pinOffset, Protocol.ADMIN_PIN_LENGTH);
        return pinOffset;
    }

    private static void requireAvailable(short cursor, short end, short length) {
        if ((short) (cursor + length) > end) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
    }

    private static void requireDigits(byte[] buffer, short offset, byte length) {
        for (short index = (short) 0; index < length; index++) {
            byte value = buffer[(short) (offset + index)];
            if (value < (byte) '0' || value > (byte) '9') {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
        }
    }
}
