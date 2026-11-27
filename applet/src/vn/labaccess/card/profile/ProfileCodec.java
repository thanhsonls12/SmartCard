package vn.labaccess.card;

import javacard.framework.ISOException;

final class ProfileCodec {

    private static final short UNSIGNED_BYTE_MASK = 0x00FF;

    private ProfileCodec() {
    }

    static short studentIdLength(byte[] plaintext) {
        return StudentIdFormat.length(plaintext, Protocol.PROFILE_OFFSET_STUDENT_ID_LENGTH);
    }

    static short extensionOffset(byte[] plaintext) {
        short nameLengthOffset = Protocol.profileDisplayNameLengthOffset(studentIdLength(plaintext));
        return (short) (nameLengthOffset + 1 + (plaintext[nameLengthOffset] & UNSIGNED_BYTE_MASK));
    }

    static boolean isV2(byte[] plaintext) {
        return plaintext[Protocol.PROFILE_OFFSET_SCHEMA_VERSION] == ProfileCrypto.PROFILE_SCHEMA_VERSION_V2;
    }

    static void requireValid(byte[] plaintext, short plaintextLength) {
        if (plaintextLength < Protocol.PROFILE_MIN_LENGTH
                || plaintextLength > Protocol.PROFILE_MAX_LENGTH) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        if (plaintext[Protocol.PROFILE_OFFSET_SCHEMA_VERSION] != ProfileCrypto.PROFILE_SCHEMA_VERSION
                && !isV2(plaintext)) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        short studentIdLength = studentIdLength(plaintext);
        if (studentIdLength < (short) 1 || studentIdLength > Protocol.STUDENT_ID_MAX_LENGTH) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        short displayNameLengthOffset = Protocol.profileDisplayNameLengthOffset(studentIdLength);
        if ((short) (displayNameLengthOffset + 1) > plaintextLength) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        short displayNameLength = (short) (plaintext[displayNameLengthOffset] & UNSIGNED_BYTE_MASK);
        if (displayNameLength < (short) 1
                || displayNameLength > Protocol.DISPLAY_NAME_MAX_LENGTH) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        short extensionOffset = extensionOffset(plaintext);
        if (extensionOffset > plaintextLength) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        if (isV2(plaintext)) {
            try {
                ProfileExtension.requireValid(plaintext, extensionOffset, plaintextLength);
            } catch (ISOException exception) {
                ISOException.throwIt(Sw.INTERNAL_ERROR);
            }
        } else if (extensionOffset != plaintextLength) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }
}
