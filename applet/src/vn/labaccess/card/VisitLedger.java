package vn.labaccess.card;

import javacard.framework.ISOException;
import javacard.framework.Util;

final class VisitLedger {

    private static final byte UNASSIGNED_BYTE = (byte) 0x00;

    private final byte[] activeVisitId = new byte[Protocol.VISIT_ID_LENGTH];
    private final byte[] lastClosedVisitId = new byte[Protocol.VISIT_ID_LENGTH];

    private boolean activeVisit;

    static void requireNotAllZero(byte[] source, short offset) {
        for (short index = (short) 0; index < Protocol.VISIT_ID_LENGTH; index++) {
            if (source[(short) (offset + index)] != UNASSIGNED_BYTE) {
                return;
            }
        }
        ISOException.throwIt(Sw.WRONG_DATA);
    }

    void reset() {
        activeVisit = false;
        Util.arrayFill(activeVisitId, (short) 0, Protocol.VISIT_ID_LENGTH, UNASSIGNED_BYTE);
        Util.arrayFill(lastClosedVisitId, (short) 0, Protocol.VISIT_ID_LENGTH,
                UNASSIGNED_BYTE);
    }

    boolean hasActiveVisit() {
        return activeVisit;
    }

    boolean matchesActiveVisit(byte[] source, short offset) {
        return activeVisit && Util.arrayCompare(source, offset, activeVisitId, (short) 0,
                Protocol.VISIT_ID_LENGTH) == (short) 0;
    }

    boolean matchesLastClosedVisit(byte[] source, short offset) {
        return Util.arrayCompare(source, offset, lastClosedVisitId, (short) 0,
                Protocol.VISIT_ID_LENGTH) == (short) 0;
    }

    void open(byte[] source, short offset) {
        Util.arrayCopy(source, offset, activeVisitId, (short) 0,
                Protocol.VISIT_ID_LENGTH);
        activeVisit = true;
    }

    void writeActiveVisitId(byte[] target, short offset) {
        Util.arrayCopyNonAtomic(activeVisitId, (short) 0, target, offset,
                Protocol.VISIT_ID_LENGTH);
    }

    void close() {
        Util.arrayCopy(activeVisitId, (short) 0, lastClosedVisitId, (short) 0,
                Protocol.VISIT_ID_LENGTH);
        Util.arrayFill(activeVisitId, (short) 0, Protocol.VISIT_ID_LENGTH,
                UNASSIGNED_BYTE);
        activeVisit = false;
    }
}
