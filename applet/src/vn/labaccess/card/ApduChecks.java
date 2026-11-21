package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

final class ApduChecks {

    private ApduChecks() {
    }

    static void requireZeroP1P2(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
    }

    static short receiveCommandData(APDU apdu, byte[] buffer, byte[] target,
            short maximumLength) {
        short received = apdu.setIncomingAndReceive();
        short lc = apdu.getIncomingLength();
        if (lc > maximumLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short cdataOffset = apdu.getOffsetCdata();
        short total = (short) 0;
        while (total < lc) {
            if (received <= (short) 0 || (short) (total + received) > lc) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            Util.arrayCopyNonAtomic(buffer, cdataOffset, target, total, received);
            total += received;
            if (total < lc) {
                received = apdu.receiveBytes(cdataOffset);
            }
        }
        return lc;
    }
}
