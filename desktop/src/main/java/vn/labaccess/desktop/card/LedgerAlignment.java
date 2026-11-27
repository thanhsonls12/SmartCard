package vn.labaccess.desktop.card;

public final class LedgerAlignment {

    private LedgerAlignment() {
    }

    public static String mismatch(CardStatus state, String cardId, long counter, int balance, String activeVisitId) {
        return String.format("Thẻ và sổ giao dịch chưa khớp (cardId %s/%s, counter %d/%d, số lượt %d/%d, ca %s/%s)."
                        + " Quay lại máy đang giữ journal để đối soát;"
                        + " không ghi journal, không gửi lệnh đổi trạng thái.",
                state.cardId(), cardId, state.counter(), counter, state.balance(), balance,
                state.hasActiveVisit() ? state.activeVisitId() : "không",
                activeVisitId == null ? "không" : activeVisitId);
    }

    public static boolean matches(CardStatus state, CardStatus.StateSource source, String cardId,
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
