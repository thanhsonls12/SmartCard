package vn.labaccess.card;

import javacard.framework.ISOException;

final class UpdateProfilePayload {

    private static final short UNSIGNED_BYTE_MASK = (short) 0x00FF;

    private UpdateProfilePayload() {
    }

    static short requireValid(byte[] data, short lc) {
        if (lc < Protocol.UPDATE_PROFILE_MIN_LENGTH || lc > Protocol.UPDATE_PROFILE_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short nameLength = (short) (data[Protocol.UPDATE_PROFILE_OFFSET_NAME_LENGTH]
                & UNSIGNED_BYTE_MASK);
        if (nameLength < (short) 1 || nameLength > Protocol.DISPLAY_NAME_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        if ((short) (Protocol.UPDATE_PROFILE_OFFSET_NAME + nameLength) != lc) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        Utf8Format.requireValid(data, Protocol.UPDATE_PROFILE_OFFSET_NAME, nameLength);
        return nameLength;
    }
}
