package vn.labaccess.desktop;

final class CardLifecycle {

    static final int UNPERSONALIZED = 0x00;
    static final int ISSUED = 0x01;

    private CardLifecycle() {
    }

    static String text(int lifecycle) {
        if (lifecycle == UNPERSONALIZED) {
            return "00 UNPERSONALIZED";
        }
        if (lifecycle == ISSUED) {
            return "01 ISSUED";
        }
        return String.format("%02X KHÔNG RÕ", lifecycle);
    }

    static String caption(int lifecycle) {
        if (lifecycle == UNPERSONALIZED) {
            return "Chưa phát hành";
        }
        if (lifecycle == ISSUED) {
            return "Đã phát hành";
        }
        return "Không rõ";
    }
}
