package vn.labaccess.card;

final class Protocol {

    static final byte CLA_APPLICATION = (byte) 0x80;
    static final byte INS_GET_STATUS = (byte) 0x10;
    static final byte INS_VERIFY_STUDENT_PIN = (byte) 0x20;
    static final byte INS_CHANGE_STUDENT_PIN = (byte) 0x21;
    static final byte INS_UNBLOCK_STUDENT_PIN = (byte) 0x23;
    static final byte INS_PERSONALIZE = (byte) 0x30;
    static final byte INS_GET_PROFILE = (byte) 0x31;
    static final byte INS_GET_PROFILE_CRYPTO_META = (byte) 0x40;
    static final byte INS_PHOTO_BEGIN = (byte) 0x32;
    static final byte INS_PHOTO_WRITE_CHUNK = (byte) 0x33;
    static final byte INS_PHOTO_READ_CHUNK = (byte) 0x34;
    static final byte INS_PHOTO_FINISH = (byte) 0x35;
    static final byte INS_GRANT_QUOTA = (byte) 0x50;
    static final byte INS_START_VISIT = (byte) 0x51;
    static final byte INS_END_VISIT = (byte) 0x52;
    static final byte INS_GET_LAST_RECEIPT = (byte) 0x60;
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
    static final byte ASCII_DIGIT_ZERO = (byte) '0';
    static final byte ASCII_DIGIT_NINE = (byte) '9';

    static final short STUDENT_ID_MAX_LENGTH = (short) 32;
    static final short DISPLAY_NAME_MAX_LENGTH = (short) 96;

    static final short PROFILE_OFFSET_SCHEMA_VERSION = (short) 0;
    static final short PROFILE_OFFSET_STUDENT_ID_LENGTH = (short) 1;
    static final short PROFILE_OFFSET_STUDENT_ID = (short) 2;
    static final short PROFILE_MIN_LENGTH = (short) 5;
    static final short PROFILE_MAX_LENGTH = (short) 131;

    static final short PROFILE_META_OFFSET_RECORD_VERSION = (short) 0;
    static final short PROFILE_META_OFFSET_ALGORITHM_ID = (short) 1;
    static final short PROFILE_META_OFFSET_SCHEMA_VERSION = (short) 2;
    static final short PROFILE_META_OFFSET_CIPHERTEXT_LENGTH = (short) 3;
    static final short PROFILE_META_OFFSET_NONCE =
            (short) (PROFILE_META_OFFSET_CIPHERTEXT_LENGTH + 2);
    static final short PROFILE_META_OFFSET_TAG =
            (short) (PROFILE_META_OFFSET_NONCE + ProfileCrypto.GCM_NONCE_LENGTH);
    static final short PROFILE_META_OFFSET_CIPHERTEXT_HASH =
            (short) (PROFILE_META_OFFSET_TAG + ProfileCrypto.GCM_TAG_LENGTH);
    static final short PROFILE_META_CIPHERTEXT_HASH_LENGTH = (short) 32;
    static final short PROFILE_META_RESPONSE_LENGTH =
            (short) (PROFILE_META_OFFSET_CIPHERTEXT_HASH + PROFILE_META_CIPHERTEXT_HASH_LENGTH);

    static final short MAX_BALANCE = (short) 9999;

    static final short PHOTO_WIDTH = (short) 64;
    static final short PHOTO_HEIGHT = (short) 64;
    static final short PHOTO_LENGTH = (short) (PHOTO_WIDTH * PHOTO_HEIGHT);
    static final byte PHOTO_FORMAT_GRAYSCALE_RAW8 = (byte) 0x01;
    static final short PHOTO_BEGIN_DATA_LENGTH = (short) 13;
    static final short PHOTO_BEGIN_OFFSET_ADMIN_PIN = (short) 0;
    static final short PHOTO_BEGIN_OFFSET_FORMAT = (short) (PHOTO_BEGIN_OFFSET_ADMIN_PIN
            + ADMIN_PIN_LENGTH);
    static final short PHOTO_BEGIN_OFFSET_WIDTH = (short) (PHOTO_BEGIN_OFFSET_FORMAT + 1);
    static final short PHOTO_BEGIN_OFFSET_HEIGHT = (short) (PHOTO_BEGIN_OFFSET_WIDTH + 1);
    static final short PHOTO_BEGIN_OFFSET_TOTAL = (short) (PHOTO_BEGIN_OFFSET_HEIGHT + 1);
    static final short PHOTO_CHUNK_MAX_LENGTH = (short) 128;
    static final short PHOTO_READ_MAX_LENGTH = (short) 128;
    static final short COUNTER_LENGTH = (short) 4;
    static final short GRANT_QUOTA_DATA_LENGTH = (short) (ADMIN_PIN_LENGTH + 2);
    static final short GRANT_ADMIN_PIN_OFFSET = (short) 0;
    static final short GRANT_DELTA_OFFSET = (short) ADMIN_PIN_LENGTH;
    static final short STATE_OFFSET_BALANCE = (short) 0;
    static final short STATE_OFFSET_COUNTER = (short) 2;
    static final short STATE_RESPONSE_LENGTH = (short) (STATE_OFFSET_COUNTER + COUNTER_LENGTH);

    static final short VISIT_ID_LENGTH = (short) 16;
    static final byte[] UNASSIGNED_VISIT_ID = new byte[VISIT_ID_LENGTH];

    static final byte OPERATION_GRANT_QUOTA = (byte) 0x01;
    static final byte OPERATION_START_VISIT = (byte) 0x02;
    static final byte OPERATION_END_VISIT = (byte) 0x03;
    static final short GRANT_VISIT_DELTA = (short) 1;
    static final short START_VISIT_DELTA = (short) -1;
    static final short END_VISIT_DELTA = (short) 0;

    static final byte RECEIPT_VERSION = (byte) 0x01;
    static final short RECEIPT_OFFSET_VERSION = (short) 0;
    static final short RECEIPT_OFFSET_CARD_ID = (short) 1;
    static final short RECEIPT_OFFSET_COUNTER = (short) 9;
    static final short RECEIPT_OFFSET_OPERATION = (short) 13;
    static final short RECEIPT_OFFSET_DELTA = (short) 14;
    static final short RECEIPT_OFFSET_BALANCE = (short) 16;
    static final short RECEIPT_OFFSET_VISIT_ID = (short) 18;
    static final short RECEIPT_PAYLOAD_LENGTH = (short) 34;
    static final short RECEIPT_SIGNATURE_MAX_LENGTH = (short) 72;
    static final short RECEIPT_LENGTH_FIELD_LENGTH = (short) 1;
    static final short RECEIPT_RESPONSE_MAX_LENGTH = (short) (RECEIPT_PAYLOAD_LENGTH
            + RECEIPT_LENGTH_FIELD_LENGTH + RECEIPT_SIGNATURE_MAX_LENGTH);
    static final byte[] RECEIPT_DOMAIN = {
            (byte) 'L', (byte) 'A', (byte) 'B', (byte) 'A', (byte) 'C', (byte) 'C',
            (byte) 'E', (byte) 'S', (byte) 'S', (byte) '-', (byte) 'R', (byte) 'E',
            (byte) 'C', (byte) 'E', (byte) 'I', (byte) 'P', (byte) 'T', (byte) '-',
            (byte) 'v', (byte) '1'
    };
    static final short RECEIPT_DOMAIN_LENGTH = (short) 20;

    static final byte VERIFY_STUDENT_PIN_DATA_LENGTH = STUDENT_PIN_LENGTH;
    static final byte CHANGE_STUDENT_PIN_DATA_LENGTH = (byte) (STUDENT_PIN_LENGTH * 2);
    static final byte UNBLOCK_STUDENT_PIN_DATA_LENGTH = (byte) (ADMIN_PIN_LENGTH + STUDENT_PIN_LENGTH);
    static final short CHANGE_OLD_PIN_OFFSET = (short) 0;
    static final short CHANGE_NEW_PIN_OFFSET = (short) STUDENT_PIN_LENGTH;
    static final short UNBLOCK_ADMIN_PIN_OFFSET = (short) 0;
    static final short UNBLOCK_NEW_STUDENT_PIN_OFFSET = (short) ADMIN_PIN_LENGTH;

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

    static short profileDisplayNameLengthOffset(short studentIdLength) {
        return (short) (PROFILE_OFFSET_STUDENT_ID + studentIdLength);
    }

    static short profileResponseLength(short studentIdLength, short displayNameLength) {
        return (short) (profileDisplayNameLengthOffset(studentIdLength) + 1 + displayNameLength);
    }
}
