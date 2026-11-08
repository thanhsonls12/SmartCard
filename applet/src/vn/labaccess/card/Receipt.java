package vn.labaccess.card;

import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

final class Receipt {

    private static final byte UNASSIGNED_BYTE = (byte) 0x00;

    private final byte[] payload = new byte[Protocol.RECEIPT_PAYLOAD_LENGTH];
    private final byte[] signature = new byte[Protocol.RECEIPT_SIGNATURE_MAX_LENGTH];
    private final byte[] stagedPayload = JCSystem.makeTransientByteArray(
            Protocol.RECEIPT_PAYLOAD_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    private final byte[] stagedSignature = JCSystem.makeTransientByteArray(
            Protocol.RECEIPT_SIGNATURE_MAX_LENGTH, JCSystem.CLEAR_ON_DESELECT);

    private short signatureLength;
    private short stagedSignatureLength;
    private boolean stored;

    void reset() {
        stored = false;
        signatureLength = (short) 0;
        stagedSignatureLength = (short) 0;
        Util.arrayFillNonAtomic(payload, (short) 0, Protocol.RECEIPT_PAYLOAD_LENGTH,
                UNASSIGNED_BYTE);
        Util.arrayFillNonAtomic(signature, (short) 0, Protocol.RECEIPT_SIGNATURE_MAX_LENGTH,
                UNASSIGNED_BYTE);
        clearStaging();
    }

    void clearStaging() {
        stagedSignatureLength = (short) 0;
        Util.arrayFillNonAtomic(stagedPayload, (short) 0, Protocol.RECEIPT_PAYLOAD_LENGTH,
                UNASSIGNED_BYTE);
        Util.arrayFillNonAtomic(stagedSignature, (short) 0, Protocol.RECEIPT_SIGNATURE_MAX_LENGTH,
                UNASSIGNED_BYTE);
    }

    boolean isStored() {
        return stored;
    }

    short signatureLength() {
        return signatureLength;
    }

    short responseLength() {
        return (short) (Protocol.RECEIPT_PAYLOAD_LENGTH + Protocol.RECEIPT_LENGTH_FIELD_LENGTH
                + signatureLength);
    }

    byte[] stagedPayload() {
        return stagedPayload;
    }

    void stage(byte operation, short delta, short balanceAfter, byte[] cardId, byte[] visitIdSource,
            short visitIdOffset, ReceiptSigner signer, QuotaLedger quota) {
        stagedPayload[Protocol.RECEIPT_OFFSET_VERSION] = Protocol.RECEIPT_VERSION;
        Util.arrayCopyNonAtomic(cardId, (short) 0, stagedPayload, Protocol.RECEIPT_OFFSET_CARD_ID,
                Protocol.CARD_ID_LENGTH);
        quota.writeCounter(stagedPayload, Protocol.RECEIPT_OFFSET_COUNTER);
        QuotaLedger.addOneInPlace(stagedPayload, Protocol.RECEIPT_OFFSET_COUNTER);
        stagedPayload[Protocol.RECEIPT_OFFSET_OPERATION] = operation;
        Util.setShort(stagedPayload, Protocol.RECEIPT_OFFSET_DELTA, delta);
        Util.setShort(stagedPayload, Protocol.RECEIPT_OFFSET_BALANCE, balanceAfter);
        Util.arrayCopyNonAtomic(visitIdSource, visitIdOffset, stagedPayload,
                Protocol.RECEIPT_OFFSET_VISIT_ID, Protocol.VISIT_ID_LENGTH);
        stagedSignatureLength = signer.sign(stagedPayload, Protocol.RECEIPT_PAYLOAD_LENGTH,
                stagedSignature);
        if (stagedSignatureLength <= (short) 0
                || stagedSignatureLength > Protocol.RECEIPT_SIGNATURE_MAX_LENGTH) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }

    void store() {
        Util.arrayCopy(stagedPayload, (short) 0, payload, (short) 0,
                Protocol.RECEIPT_PAYLOAD_LENGTH);
        Util.arrayCopy(stagedSignature, (short) 0, signature, (short) 0, stagedSignatureLength);
        signatureLength = stagedSignatureLength;
        stored = true;
    }

    void writeResponse(byte[] target, short offset) {
        Util.arrayCopyNonAtomic(payload, (short) 0, target, offset, Protocol.RECEIPT_PAYLOAD_LENGTH);
        target[(short) (offset + Protocol.RECEIPT_PAYLOAD_LENGTH)] = (byte) signatureLength;
        Util.arrayCopyNonAtomic(signature, (short) 0,
                target, (short) (offset + Protocol.RECEIPT_PAYLOAD_LENGTH
                        + Protocol.RECEIPT_LENGTH_FIELD_LENGTH), signatureLength);
    }
}
