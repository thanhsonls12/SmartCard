package vn.labaccess.desktop.card;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;

public final class ReceiptCodec {

    public record Parse(boolean ok, String problem, Receipt receipt) {
        static Parse fail(String problem) {
            return new Parse(false, problem, null);
        }

        static Parse ok(Receipt receipt) {
            return new Parse(true, null, receipt);
        }
    }

    public static final int PAYLOAD_LENGTH = 34;
    static final int LENGTH_FIELD_LENGTH = 1;
    static final int SIGNATURE_MIN_LENGTH = 8;
    static final int SIGNATURE_MAX_LENGTH = 72;
    static final int RESPONSE_MIN_LENGTH = PAYLOAD_LENGTH + LENGTH_FIELD_LENGTH
            + SIGNATURE_MIN_LENGTH;
    static final int RESPONSE_MAX_LENGTH = PAYLOAD_LENGTH + LENGTH_FIELD_LENGTH
            + SIGNATURE_MAX_LENGTH;

    static final int OFFSET_VERSION = 0;
    static final int OFFSET_CARD_ID = 1;
    static final int CARD_ID_LENGTH = 8;
    static final int OFFSET_COUNTER = 9;
    static final int COUNTER_LENGTH = 4;
    static final int OFFSET_OPERATION = 13;
    static final int OFFSET_DELTA = 14;
    public static final int OFFSET_BALANCE = 16;
    static final int OFFSET_VISIT_ID = 18;
    static final int RECEIPT_VERSION = 0x01;

    private ReceiptCodec() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }

    public static Parse parse(byte[] data) {
        if (data == null) {
            return Parse.fail("không có dữ liệu response");
        }
        if (data.length < PAYLOAD_LENGTH + LENGTH_FIELD_LENGTH) {
            return Parse.fail(String.format(
                    "response %d byte, tối thiểu phải có %d byte payload + 1 byte length",
                    data.length, PAYLOAD_LENGTH));
        }

        int signatureLength = data[PAYLOAD_LENGTH] & 0xFF;
        if (signatureLength < SIGNATURE_MIN_LENGTH || signatureLength > SIGNATURE_MAX_LENGTH) {
            return Parse.fail(String.format(
                    "signatureLength=%d ngoài khoảng %d..%d",
                    signatureLength, SIGNATURE_MIN_LENGTH, SIGNATURE_MAX_LENGTH));
        }

        int expectedLength = PAYLOAD_LENGTH + LENGTH_FIELD_LENGTH + signatureLength;
        if (data.length != expectedLength) {
            return Parse.fail(String.format(
                    "response %d byte nhưng signatureLength=%d đòi %d byte",
                    data.length, signatureLength, expectedLength));
        }
        if (data.length < RESPONSE_MIN_LENGTH || data.length > RESPONSE_MAX_LENGTH) {
            return Parse.fail(String.format("response %d byte ngoài khoảng %d..%d",
                    data.length, RESPONSE_MIN_LENGTH, RESPONSE_MAX_LENGTH));
        }

        return parsePayload(Arrays.copyOf(data, PAYLOAD_LENGTH),
                Arrays.copyOfRange(data, PAYLOAD_LENGTH + LENGTH_FIELD_LENGTH, data.length));
    }

    public static byte[] intentPayload(PendingJournal.Entry entry) {
        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_LENGTH);
        payload.put((byte) RECEIPT_VERSION).put(HexFormat.of().parseHex(entry.cardId()))
                .putInt((int) entry.expectedCounter()).put((byte) entry.operation())
                .putShort((short) entry.delta()).putShort((short) entry.expectedBalance());
        if (entry.visitId() != null) {
            payload.put(entry.visitId());
        }
        return payload.array();
    }

    public static boolean differentReceiptAtOrAfterIntent(PendingJournal.Entry entry, Receipt receipt) {
        return receipt != null && receipt.counterAfter() >= entry.expectedCounter()
                && !Arrays.equals(receipt.payload(), intentPayload(entry));
    }

    public static Parse parsePayload(byte[] payload, byte[] signature) {
        if (payload.length != PAYLOAD_LENGTH) {
            return Parse.fail("payload không đủ 34 byte");
        }

        int version = payload[OFFSET_VERSION] & 0xFF;
        if (version != RECEIPT_VERSION) {
            return Parse.fail(String.format("version=%02X, chỉ nhận %02X",
                    version, RECEIPT_VERSION));
        }

        int operation = payload[OFFSET_OPERATION] & 0xFF;
        if (operation != Receipt.OPERATION_GRANT
                && operation != Receipt.OPERATION_START
                && operation != Receipt.OPERATION_END) {
            return Parse.fail(String.format("operation=%02X không thuộc 01/02/03", operation));
        }

        int delta = readShort(payload, OFFSET_DELTA);
        String deltaProblem = checkDelta(operation, delta);
        if (deltaProblem != null) {
            return Parse.fail(deltaProblem);
        }

        int balanceAfter = readShort(payload, OFFSET_BALANCE) & 0xFFFF;
        if (balanceAfter > Receipt.MAX_BALANCE) {
            return Parse.fail(String.format("balanceAfter=%d vượt %d",
                    balanceAfter, Receipt.MAX_BALANCE));
        }

        byte[] visitId = new byte[Receipt.VISIT_ID_LENGTH];
        System.arraycopy(payload, OFFSET_VISIT_ID, visitId, 0, Receipt.VISIT_ID_LENGTH);
        if ((operation == Receipt.OPERATION_GRANT) != isAllZero(visitId)) {
            return Parse.fail("visitId không hợp lệ cho loại giao dịch");
        }

        byte[] cardId = new byte[CARD_ID_LENGTH];
        System.arraycopy(payload, OFFSET_CARD_ID, cardId, 0, CARD_ID_LENGTH);

        long counterAfter = readCounter(payload, OFFSET_COUNTER);

        return Parse.ok(new Receipt(version, cardId, counterAfter, operation, delta,
                balanceAfter, visitId, payload, signature));
    }

    private static String checkDelta(int operation, int delta) {
        if (operation == Receipt.OPERATION_GRANT && delta <= 0) {
            return String.format("GRANT_QUOTA phải có delta dương, nhận %d", delta);
        }
        if (operation == Receipt.OPERATION_START && delta != -1) {
            return String.format("START_VISIT phải có delta -1, nhận %d", delta);
        }
        if (operation == Receipt.OPERATION_END && delta != 0) {
            return String.format("END_VISIT phải có delta 0, nhận %d", delta);
        }
        return null;
    }

    private static int readShort(byte[] source, int offset) {
        return (short) (((source[offset] & 0xFF) << 8) | (source[offset + 1] & 0xFF));
    }

    private static long readCounter(byte[] source, int offset) {
        long value = 0;
        for (int i = 0; i < COUNTER_LENGTH; i++) {
            value = (value << 8) | (source[offset + i] & 0xFF);
        }
        return value & Receipt.COUNTER_MASK;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
