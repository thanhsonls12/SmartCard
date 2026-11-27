package vn.labaccess.card;

import javacard.framework.AID;
import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;

final class StatusHandler {
    private StatusHandler() {
    }

    static void handleGetStatus(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        requireGetStatusP1P2(buffer);
        sendStatus(apdu, buffer, state, pins);
    }

    private static void requireGetStatusP1P2(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1_GET_STATUS
                || buffer[ISO7816.OFFSET_P2] != Protocol.P2_GET_STATUS) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
    }

    private static void sendStatus(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        AID instanceAid = JCSystem.getAID();
        if (instanceAid == null) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }

        byte aidLength = instanceAid.getBytes(buffer, Protocol.OFFSET_INSTANCE_AID);
        boolean includeStudentState = state.isIssued() && pins.isStudentAuthorized();
        short responseLength = StatusResponse.length(aidLength, includeStudentState);

        short ne = apdu.setOutgoing();
        if (ne == (short) 0 || ne < responseLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }

        StatusResponse.write(state, buffer, aidLength, includeStudentState);
        apdu.setOutgoingLength(responseLength);
        apdu.sendBytes((short) 0, responseLength);
    }
}
