package vn.labaccess.desktop.card;

public final class CardLifecycle {

    public static final int UNPERSONALIZED = 0x00;
    public static final int ISSUED = 0x01;

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

    public static String caption(int lifecycle) {
        if (lifecycle == UNPERSONALIZED) {
            return "Chưa phát hành";
        }
        if (lifecycle == ISSUED) {
            return "Đã phát hành";
        }
        return "Không rõ";
    }
}
