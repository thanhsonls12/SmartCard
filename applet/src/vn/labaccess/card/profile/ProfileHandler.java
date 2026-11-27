package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.ISOException;
import javacard.framework.Util;

final class ProfileHandler {

    private final CardState state;
    private final Personalization personalization;
    private final PinStore pins;

    ProfileHandler(CardState state, Personalization personalization, PinStore pins) {
        this.state = state;
        this.personalization = personalization;
        this.pins = pins;
    }

    void handleGetProfile(APDU apdu, byte[] buffer) {
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short expectedLength = (short) (state.profileCiphertextLength()
                    - ProfileCrypto.RECORD_HEADER_LENGTH - ProfileCrypto.GCM_TAG_LENGTH);
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < expectedLength) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            byte[] plaintext = state.profilePlaintext();
            short plaintextLength = personalization.readProfilePlaintext(plaintext);
            ProfileCodec.requireValid(plaintext, plaintextLength);
            Util.arrayCopyNonAtomic(plaintext, (short) 0, buffer, (short) 0, plaintextLength);
            apdu.setOutgoingLength(plaintextLength);
            apdu.sendBytes((short) 0, plaintextLength);
        } finally {
            state.clearWorkingBuffers();
            pins.clearStudentAuthorization();
        }
    }

    void handleUpdateProfile(APDU apdu, byte[] buffer) {
        byte[] data = state.commandData();
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, data,
                    Protocol.UPDATE_PROFILE_MAX_LENGTH);
            ApduChecks.requireCase3(apdu);
            short nameLength = UpdateProfilePayload.requireValid(data, lc);
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            AdminPinVerifier.verify(pins.admin(), data, Protocol.UPDATE_PROFILE_OFFSET_ADMIN_PIN);
            personalization.updateDisplayName(data, Protocol.UPDATE_PROFILE_OFFSET_NAME, nameLength,
                    buffer);
        } finally {
            pins.admin().reset();
            ApduChecks.clearApduCommandData(buffer);
            state.clearCommandData();
            state.clearWorkingBuffers();
            Util.arrayFillNonAtomic(buffer, (short) 0, (short) buffer.length, (byte) 0);
        }
    }

    void handleUpdateIdentity(APDU apdu, byte[] buffer) {
        byte[] data = state.commandData();
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, data,
                    Protocol.UPDATE_IDENTITY_MAX_LENGTH);
            ApduChecks.requireCase3(apdu);
            short nameLength = UpdateIdentityPayload.requireValid(data, lc);
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            AdminPinVerifier.verify(pins.admin(), data,
                    Protocol.UPDATE_IDENTITY_OFFSET_ADMIN_PIN);
            personalization.updateIdentity(data, Protocol.UPDATE_IDENTITY_OFFSET_VERSION,
                    nameLength, lc, buffer);
        } finally {
            pins.admin().reset();
            ApduChecks.clearApduCommandData(buffer);
            state.clearCommandData();
            state.clearWorkingBuffers();
            Util.arrayFillNonAtomic(buffer, (short) 0, (short) buffer.length, (byte) 0);
        }
    }

    void handleSignProfileChallenge(APDU apdu, byte[] buffer) {
        byte[] data = state.commandData();
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, data,
                    Protocol.PROFILE_SIGN_DATA_LENGTH);
            if (lc != Protocol.PROFILE_SIGN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            short ne = apdu.setOutgoing();
            if (ne < Protocol.PROFILE_SIGN_MIN_LE) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued() || !state.keysReady()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            AdminPinVerifier.verify(pins.admin(), data, Protocol.PROFILE_SIGN_OFFSET_ADMIN_PIN);
            short length = personalization.signProfileChallenge(data,
                    Protocol.PROFILE_SIGN_OFFSET_NONCE);
            apdu.setOutgoingLength(length);
            apdu.sendBytesLong(personalization.signature(), (short) 0, length);
        } finally {
            pins.admin().reset();
            ApduChecks.clearApduCommandData(buffer);
            state.clearCommandData();
            state.clearWorkingBuffers();
        }
    }

    void handleGetProfileCryptoMeta(APDU apdu, byte[] buffer) {
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < Protocol.PROFILE_META_RESPONSE_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            short metadataLength = personalization.readProfileMetadata(buffer);
            apdu.setOutgoingLength(metadataLength);
            apdu.sendBytes((short) 0, metadataLength);
        } finally {
            state.clearWorkingBuffers();
            pins.clearStudentAuthorization();
        }
    }
}
