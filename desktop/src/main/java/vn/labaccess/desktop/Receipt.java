package vn.labaccess.desktop;

record Receipt(
        int version,
        byte[] cardId,
        long counterAfter,
        int operation,
        int delta,
        int balanceAfter,
        byte[] visitId,
        byte[] payload,
        byte[] signature) {

    static final int OPERATION_GRANT = 0x01;
    static final int OPERATION_START = 0x02;
    static final int OPERATION_END = 0x03;

    static final long COUNTER_MASK = 0xFFFFFFFFL;
    static final int MAX_BALANCE = 9999;
    static final int VISIT_ID_LENGTH = 16;

    static String operationText(int operation) {
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

    String payloadHex() {
        return CardGateway.hex(payload);
    }

    String signatureHex() {
        return CardGateway.hex(signature);
    }

    String visitIdHex() {
        return CardGateway.hex(visitId);
    }

    String cardIdHex() {
        return CardGateway.hex(cardId);
    }

    @Override
    public String toString() {
        return String.format("counter=%d operation=%s delta=%d balance=%d cardId=%s visitId=%s",
                counterAfter, operationText(operation), delta, balanceAfter,
                cardIdHex(), visitIdHex());
    }
}
