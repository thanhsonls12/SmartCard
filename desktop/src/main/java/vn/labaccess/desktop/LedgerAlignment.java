package vn.labaccess.desktop;

final class LedgerAlignment {

    private LedgerAlignment() {
    }

    static boolean matches(CardStatus state, CardStatus.StateSource source, String cardId,
            long counter, int balance, String activeVisitId) {
        if (state == null || state.stateSource() != source || cardId == null
                || !state.cardId().equalsIgnoreCase(cardId) || state.counter() != counter
                || state.balance() != balance) {
            return false;
        }
        if (!state.hasActiveVisit()) {
            return activeVisitId == null;
        }
        return activeVisitId != null && state.activeVisitId().equalsIgnoreCase(activeVisitId);
    }
}
