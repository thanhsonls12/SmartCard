package vn.labaccess.desktop.ui.shared;

import vn.labaccess.desktop.card.CardStatus;

public final class CardDisplayState {

    private CardDisplayState() {
    }

    public static String of(CardStatus status, boolean verified, boolean unresolved, boolean reread, boolean busy) {
        if (unresolved || busy) {
            return "Chưa rõ kết quả";
        }
        if (reread) {
            return "Cần đọc lại";
        }
        if (verified && status != null && status.stateAvailable()) {
            return "Đã xác minh · " + sourceText(status.stateSource());
        }
        return "Chưa xác minh";
    }

    static String sourceText(CardStatus.StateSource source) {
        return source == CardStatus.StateSource.ADMIN ? "PIN quản trị" : "PIN sinh viên";
    }
}
