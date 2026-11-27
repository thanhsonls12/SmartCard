package vn.labaccess.card;

import javacard.framework.ISOException;

final class UpdateIdentityPayload {

    private static final short UNSIGNED_BYTE_MASK = (short) 0x00FF;

    private UpdateIdentityPayload() {
    }

    static short requireValid(byte[] data, short lc) {
        if (lc < Protocol.UPDATE_IDENTITY_MIN_LENGTH || lc > Protocol.UPDATE_IDENTITY_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short studentIdLength = studentIdLength(data);
        short nameLengthOffset = nameLengthOffset(data);
        if ((short) (nameLengthOffset + 1) > lc) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short nameLength = (short) (data[nameLengthOffset] & UNSIGNED_BYTE_MASK);
        if (nameLength < (short) 1 || nameLength > Protocol.DISPLAY_NAME_MAX_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        if ((short) (nameLengthOffset + 1 + nameLength) != lc) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        StudentIdFormat.requireValid(data, Protocol.UPDATE_IDENTITY_OFFSET_STUDENT_ID,
                studentIdLength);
        Utf8Format.requireValid(data, (short) (nameLengthOffset + 1), nameLength);
        requireNonZeroVersion(data);
        return nameLength;
    }

    static short studentIdLength(byte[] data) {
        return StudentIdFormat.length(data, Protocol.UPDATE_IDENTITY_OFFSET_STUDENT_ID_LENGTH);
    }

    static short nameOffset(byte[] data) {
        return (short) (nameLengthOffset(data) + 1);
    }

    private static short nameLengthOffset(byte[] data) {
        return (short) (Protocol.UPDATE_IDENTITY_OFFSET_STUDENT_ID + studentIdLength(data));
    }

    private static void requireNonZeroVersion(byte[] data) {
        for (short index = (short) 0; index < ProfileCrypto.GCM_NONCE_LENGTH; index++) {
            if (data[(short) (Protocol.UPDATE_IDENTITY_OFFSET_VERSION + index)] != (byte) 0) {
                return;
            }
        }
        ISOException.throwIt(Sw.WRONG_DATA);
    }
}
