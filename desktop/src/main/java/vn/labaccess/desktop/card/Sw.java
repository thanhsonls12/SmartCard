package vn.labaccess.desktop.card;

public final class Sw {

    public static final int OK = 0x9000;

    public static final int SECURITY_NOT_SATISFIED = 0x6982;

    public static final int AUTH_BLOCKED = 0x6983;

    public static final int CONDITIONS_NOT_SATISFIED = 0x6985;

    public static final int WRONG_LENGTH = 0x6700;

    public static final int WRONG_DATA = 0x6A80;

    public static final int WRONG_P1P2 = 0x6A86;

    public static final int INS_NOT_SUPPORTED = 0x6D00;

    static final int CLA_NOT_SUPPORTED = 0x6E00;

    public static final int INTERNAL_ERROR = 0x6F00;

    private Sw() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }
}
