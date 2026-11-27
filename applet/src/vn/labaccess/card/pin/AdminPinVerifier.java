package vn.labaccess.card;

import javacard.framework.ISOException;
import javacard.framework.OwnerPIN;

final class AdminPinVerifier {

    private AdminPinVerifier() {
    }

    static void verify(OwnerPIN adminPin, byte[] buffer, short offset) {
        if (adminPin.getTriesRemaining() == (byte) 0) {
            ISOException.throwIt(Sw.PIN_BLOCKED);
        }
        if (!adminPin.check(buffer, offset, Protocol.ADMIN_PIN_LENGTH)) {
            byte triesRemaining = adminPin.getTriesRemaining();
            if (triesRemaining == (byte) 0) {
                ISOException.throwIt(Sw.PIN_BLOCKED);
            }
            ISOException.throwIt((short) (Sw.VERIFY_FAIL_WITH_RETRIES + triesRemaining));
        }
    }
}
