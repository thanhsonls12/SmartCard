package vn.labaccess.card;

import javacard.framework.ISO7816;
import javacard.framework.ISOException;

final class ApduChecks {

    private ApduChecks() {
    }

    static void requireZeroP1P2(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
    }
}
