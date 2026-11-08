package vn.labaccess.card;

final class Sw {

    static final short OK = (short) 0x9000;

    static final short WRONG_LENGTH = (short) 0x6700;

    static final short WRONG_DATA = (short) 0x6A80;

    static final short WRONG_P1P2 = (short) 0x6A86;

    static final short INS_NOT_SUPPORTED = (short) 0x6D00;

    static final short CLA_NOT_SUPPORTED = (short) 0x6E00;

    private Sw() {
    }
}
