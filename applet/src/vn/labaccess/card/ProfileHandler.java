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
            ProfileCodec.requireSchemaV1(plaintext, plaintextLength);
            Util.arrayCopyNonAtomic(plaintext, (short) 0, buffer, (short) 0, plaintextLength);
            apdu.setOutgoingLength(plaintextLength);
            apdu.sendBytes((short) 0, plaintextLength);
        } finally {
            state.clearWorkingBuffers();
            pins.clearStudentAuthorization();
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
