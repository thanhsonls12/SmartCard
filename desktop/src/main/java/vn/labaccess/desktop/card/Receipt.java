package vn.labaccess.desktop.card;

public record Receipt(
        int version,
        byte[] cardId,
        long counterAfter,
        int operation,
        int delta,
        int balanceAfter,
        byte[] visitId,
        byte[] payload,
        byte[] signature) {

    public static final int OPERATION_GRANT = 0x01;
    public static final int OPERATION_START = 0x02;
    public static final int OPERATION_END = 0x03;

    public static final long COUNTER_MASK = 0xFFFFFFFFL;
    public static final int MAX_BALANCE = 9999;
    public static final int VISIT_ID_LENGTH = 16;

    public static String operationText(int operation) {
        if (operation == OPERATION_GRANT) {
            return "01 GRANT_QUOTA";
        }
        if (operation == OPERATION_START) {
            return "02 START_VISIT";
        }
        if (operation == OPERATION_END) {
            return "03 END_VISIT";
        }
        return String.format("%02X KHONG RO", operation);
    }

    public String payloadHex() {
        return CardGateway.hex(payload);
    }

    public String signatureHex() {
        return CardGateway.hex(signature);
    }

    public String visitIdHex() {
        return CardGateway.hex(visitId);
    }

    public String cardIdHex() {
        return CardGateway.hex(cardId);
    }

    @Override
    public String toString() {
        return String.format("counter=%d operation=%s delta=%d balance=%d cardId=%s visitId=%s",
                counterAfter, operationText(operation), delta, balanceAfter,
                cardIdHex(), visitIdHex());
    }
}
