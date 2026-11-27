package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;

final class EnrollmentHandler {
    private static final short PUBLIC_KEY_LENGTH = (short) 65;
    private static final byte PUBLIC_POINT_PREFIX = (byte) 0x04;

    private EnrollmentHandler() {
    }

    static void handleGetCardPublicKey(APDU apdu, byte[] buffer, CardState state,
            Personalization personalization) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
        if (!state.isIssued()) {
            ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
        }
        short ne = apdu.setOutgoing();
        if (ne == (short) 0 || ne < PUBLIC_KEY_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        personalization.readPublicKey(buffer, (short) 0);
        if (buffer[0] != PUBLIC_POINT_PREFIX) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        apdu.setOutgoingLength(PUBLIC_KEY_LENGTH);
        apdu.sendBytes((short) 0, PUBLIC_KEY_LENGTH);
    }

    static void handleSignRegistrationChallenge(APDU apdu, byte[] buffer, CardState state,
            Personalization personalization) {
        ApduChecks.requireZeroP1P2(buffer);
        short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                Protocol.ENROLLMENT_NONCE_LENGTH);
        if (lc != Protocol.ENROLLMENT_NONCE_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short ne = apdu.setOutgoing();
        if (ne < Protocol.ENROLLMENT_MIN_LE) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        if (!state.isIssued() || !state.keysReady()) {
            ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
        }
        CardSigner signer = state.cardSigner();
        if (signer == null) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        try {
            short length;
            try {
                length = signer.signEnrollment(state.commandData(), state.cardId(),
                        personalization.publicKey());
            } catch (RuntimeException exception) {
                ISOException.throwIt(Sw.INTERNAL_ERROR);
                return;
            }
            if (length <= (short) 0 || length > Protocol.ENROLLMENT_SIGNATURE_MAX_LENGTH) {
                ISOException.throwIt(Sw.INTERNAL_ERROR);
            }
            apdu.setOutgoingLength(length);
            apdu.sendBytesLong(signer.signature(), (short) 0, length);
        } finally {
            state.clearCommandData();
            state.clearWorkingBuffers();
        }
    }
}
