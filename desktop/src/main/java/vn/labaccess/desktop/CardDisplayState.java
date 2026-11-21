package vn.labaccess.desktop;

final class CardDisplayState {

    private CardDisplayState() {
    }

    static String of(CardStatus status, boolean verified, boolean unresolved, boolean reread, boolean busy) {
        if (unresolved || busy) {
            return "Chưa rõ kết quả";
        }
        if (reread) {
            return "Cần đọc lại";
        }
        if (verified && status != null && status.stateAvailable()) {
            return "Đã xác minh · " + status.stateSource();
        }
        return "Công khai · chưa xác minh";
    }
}
