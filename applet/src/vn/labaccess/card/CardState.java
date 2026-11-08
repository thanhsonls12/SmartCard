package vn.labaccess.card;

import javacard.framework.JCSystem;
import javacard.framework.Util;

final class CardState {

    private static final short PROFILE_CIPHERTEXT_MAX = (short) 160;
    private static final short PROFILE_PLAINTEXT_MAX = (short) 144;

    private final byte[] cardId = new byte[Protocol.CARD_ID_LENGTH];
    private final QuotaLedger quota = new QuotaLedger();
    private final VisitLedger visits = new VisitLedger();
    private final Receipt receipt = new Receipt();
    private final byte[] profileCiphertext = new byte[PROFILE_CIPHERTEXT_MAX];
    private short profileCiphertextLength;

    private byte[] profilePlaintext;
    private short profilePlaintextLength;

    private byte[] scratch;
    private byte[] commandData;

    private byte lifecycle;
    private boolean keysReady;
    private ReceiptSigner receiptSigner;

    CardState() {
    }

    void ensureCommandBuffer() {
        if (commandData == null) {
            commandData = JCSystem.makeTransientByteArray(PersonalizePayload.MAX_LENGTH,
                    JCSystem.CLEAR_ON_DESELECT);
        }
    }

    byte[] commandData() {
        return commandData;
    }

    void clearCommandData() {
        if (commandData != null) {
            Util.arrayFillNonAtomic(commandData, (short) 0,
                    PersonalizePayload.MAX_LENGTH, (byte) 0);
        }
    }

    void ensureWorkingBuffers() {
        if (profilePlaintext == null) {
            profilePlaintext = JCSystem.makeTransientByteArray(PROFILE_PLAINTEXT_MAX,
                    JCSystem.CLEAR_ON_DESELECT);
            scratch = JCSystem.makeTransientByteArray(PROFILE_CIPHERTEXT_MAX,
                    JCSystem.CLEAR_ON_DESELECT);
        }
    }

    void resetUnissued() {
        lifecycle = Protocol.LIFECYCLE_UNPERSONALIZED;
        keysReady = false;
        profileCiphertextLength = 0;
        profilePlaintextLength = 0;
        quota.reset();
        visits.reset();
        receipt.reset();
        Util.arrayFillNonAtomic(cardId, (short) 0, Protocol.CARD_ID_LENGTH,
                Protocol.CARD_ID_UNASSIGNED);
        Util.arrayFillNonAtomic(profileCiphertext, (short) 0, PROFILE_CIPHERTEXT_MAX,
                (byte) 0);
        clearWorkingBuffers();
    }

    void clearWorkingBuffers() {
        if (profilePlaintext != null) {
            Util.arrayFillNonAtomic(profilePlaintext, (short) 0, PROFILE_PLAINTEXT_MAX, (byte) 0);
            Util.arrayFillNonAtomic(scratch, (short) 0, PROFILE_CIPHERTEXT_MAX, (byte) 0);
            profilePlaintextLength = 0;
        }
    }

    byte lifecycle() {
        return lifecycle;
    }

    boolean isIssued() {
        return lifecycle == Protocol.LIFECYCLE_ISSUED;
    }

    void markIssued() {
        lifecycle = Protocol.LIFECYCLE_ISSUED;
    }

    boolean keysReady() {
        return keysReady;
    }

    void setKeysReady() {
        keysReady = true;
    }

    byte[] cardId() {
        return cardId;
    }

    QuotaLedger quota() {
        return quota;
    }

    VisitLedger visits() {
        return visits;
    }

    Receipt receipt() {
        return receipt;
    }

    ReceiptSigner receiptSigner() {
        return receiptSigner;
    }

    void setReceiptSigner(ReceiptSigner signer) {
        receiptSigner = signer;
    }

    void setCardId(byte[] source, short offset) {
        Util.arrayCopyNonAtomic(source, offset, cardId, (short) 0, Protocol.CARD_ID_LENGTH);
    }

    byte[] profileCiphertext() {
        return profileCiphertext;
    }

    short profileCiphertextLength() {
        return profileCiphertextLength;
    }

    void setProfileCiphertext(byte[] source, short offset, short length) {
        Util.arrayCopyNonAtomic(source, offset, profileCiphertext, (short) 0, length);
        profileCiphertextLength = length;
    }

    byte[] profilePlaintext() {
        return profilePlaintext;
    }

    short profilePlaintextLength() {
        return profilePlaintextLength;
    }

    void setProfilePlaintextLength(short length) {
        profilePlaintextLength = length;
    }

    byte[] scratch() {
        return scratch;
    }
}
