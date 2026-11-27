package vn.labaccess.desktop.card;

public final class ReceiptReadRecovery {

    private ReceiptReadRecovery() {
    }

    public static String problem(CardStatus state, Receipt receipt, boolean verified, CardGateway.ReceiptSink sink) {
        if (state == null || !state.studentStateAvailable()) {
            return "chưa đọc được trạng thái thẻ có xác thực";
        }
        if (state.counter() == 0) {
            if (receipt != null || state.balance() != 0 || state.hasActiveVisit()) {
                return "trạng thái thẻ không khớp mốc chưa có giao dịch";
            }
        } else if (receipt == null || !verified
                || !receipt.cardIdHex().equalsIgnoreCase(state.cardId())
                || receipt.counterAfter() != state.counter() || receipt.balanceAfter() != state.balance()) {
            return "biên nhận đã xác minh không khớp trạng thái thẻ";
        }
        if (sink == null) {
            return "chưa cấu hình sổ giao dịch để đối chiếu";
        }
        CardGateway.LedgerCheck ledger = sink.ledger(state.cardId());
        if (!ledger.ok()) {
            return "chưa đọc được sổ giao dịch: " + ledger.detail();
        }
        String visit = state.hasActiveVisit() ? state.activeVisitId() : null;
        boolean sameVisit = visit == null ? ledger.activeVisitId() == null
                : ledger.activeVisitId() != null && visit.equalsIgnoreCase(ledger.activeVisitId());
        if (ledger.lastCounter() != state.counter() || ledger.lastBalance() != state.balance() || !sameVisit) {
            return "sổ giao dịch không khớp counter, số lượt hoặc ca đang mở của thẻ";
        }
        return null;
    }
}
