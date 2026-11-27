package vn.labaccess.card;

import javacard.framework.Util;

final class StatusResponse {

    private StatusResponse() {
    }

    static short length(byte aidLength, boolean includeStudentState) {
        return includeStudentState
                ? Protocol.authenticatedStatusResponseLength(aidLength)
                : Protocol.statusResponseLength(aidLength);
    }

    static void write(CardState state, byte[] buffer, byte aidLength,
            boolean includeStudentState) {
        Util.arrayCopyNonAtomic(Protocol.MAGIC, (short) 0, buffer,
                Protocol.OFFSET_MAGIC, Protocol.MAGIC_LENGTH);
        buffer[Protocol.OFFSET_PROTOCOL_VERSION] = Protocol.PROTOCOL_VERSION;
        buffer[Protocol.OFFSET_AID_LENGTH] = aidLength;
        buffer[Protocol.lifecycleOffset(aidLength)] = state.lifecycle();
        Util.arrayCopyNonAtomic(state.cardId(), (short) 0, buffer,
                Protocol.cardIdOffset(aidLength), Protocol.CARD_ID_LENGTH);
        if (!includeStudentState) {
            return;
        }
        short publicLength = Protocol.statusResponseLength(aidLength);
        Util.setShort(buffer, publicLength, state.quota().balance());
        state.quota().writeCounter(buffer,
                (short) (publicLength + Protocol.STATUS_AUTH_OFFSET_COUNTER));
        VisitLedger visits = state.visits();
        buffer[(short) (publicLength + Protocol.STATUS_AUTH_OFFSET_ACTIVE_VISIT)] =
                visits.hasActiveVisit() ? Protocol.STATUS_ACTIVE_VISIT_OPEN
                        : Protocol.STATUS_ACTIVE_VISIT_CLOSED;
        visits.writeActiveVisitId(buffer,
                (short) (publicLength + Protocol.STATUS_AUTH_OFFSET_ACTIVE_VISIT_ID));
    }
}
