package vn.labaccess.card;

final class Sw {

    static final short OK = (short) 0x9000;

    static final short WRONG_LENGTH = (short) 0x6700;

    static final short WRONG_DATA = (short) 0x6A80;

    static final short WRONG_P1P2 = (short) 0x6A86;

    static final short INS_NOT_SUPPORTED = (short) 0x6D00;

    static final short CLA_NOT_SUPPORTED = (short) 0x6E00;

    static final short VERIFY_FAIL_WITH_RETRIES = (short) 0x63C0;

    static final short PIN_BLOCKED = (short) 0x6983;

    static final short SECURITY_STATUS_NOT_SATISFIED = (short) 0x6982;

    static final short CONDITIONS_NOT_SATISFIED = (short) 0x6985;

    static final short INTERNAL_ERROR = (short) 0x6F00;

    private Sw() {
    }
}
