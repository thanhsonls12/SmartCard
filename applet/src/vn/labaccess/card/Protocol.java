package vn.labaccess.card;

final class Protocol {

    static final byte CLA_APPLICATION = (byte) 0x80;
    static final byte INS_GET_STATUS = (byte) 0x10;
    static final byte INS_PERSONALIZE = (byte) 0x30;
    static final byte INS_GET_CARD_PUBLIC_KEY = (byte) 0x61;

    static final byte PROTOCOL_VERSION = (byte) 0x01;

    static final byte[] MAGIC = {
            (byte) 'L', (byte) 'A', (byte) 'B', (byte) 'A', (byte) 'C',
            (byte) 'C', (byte) 'E', (byte) 'S', (byte) 'S'
    };
    static final short MAGIC_LENGTH = (short) 9;

    static final short MIN_LE = (short) 27;

    static final byte PROVISIONING_VERSION = (byte) 0x01;

    static final byte ADMIN_PIN_LENGTH = (byte) 8;
    static final byte ADMIN_PIN_MAX_TRIES = (byte) 5;

    static final byte STUDENT_PIN_LENGTH = (byte) 6;
    static final byte STUDENT_PIN_MAX_TRIES = (byte) 3;

    static final byte P1_GET_STATUS = (byte) 0x00;
    static final byte P2_GET_STATUS = (byte) 0x00;
    static final byte P1P2_ZERO = (byte) 0x00;

    static final byte LIFECYCLE_UNPERSONALIZED = (byte) 0x00;
    static final byte LIFECYCLE_ISSUED = (byte) 0x01;
    static final short CARD_ID_LENGTH = (short) 8;
    static final byte CARD_ID_UNASSIGNED = (byte) 0x00;

    static final short OFFSET_MAGIC = (short) 0;
    static final short OFFSET_PROTOCOL_VERSION = (short) 9;
    static final short OFFSET_AID_LENGTH = (short) 10;
    static final short OFFSET_INSTANCE_AID = (short) 11;

    private Protocol() {
    }

    static short statusResponseLength(byte instanceAidLength) {
        return (short) (OFFSET_INSTANCE_AID + instanceAidLength + 1 + CARD_ID_LENGTH);
    }

    static short lifecycleOffset(byte instanceAidLength) {
        return (short) (OFFSET_INSTANCE_AID + instanceAidLength);
    }

    static short cardIdOffset(byte instanceAidLength) {
        return (short) (lifecycleOffset(instanceAidLength) + 1);
    }
}
