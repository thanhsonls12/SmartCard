package vn.labaccess.card;

import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

final class CardState {

    private static final short PROFILE_CIPHERTEXT_MAX = (short) (Protocol.PROFILE_MAX_LENGTH
            + ProfileCrypto.RECORD_HEADER_LENGTH + ProfileCrypto.GCM_TAG_LENGTH);
    private static final short PROFILE_PLAINTEXT_MAX = Protocol.PROFILE_MAX_LENGTH;

    private final byte[] cardId = new byte[Protocol.CARD_ID_LENGTH];
    private final QuotaLedger quota = new QuotaLedger();
    private final VisitLedger visits = new VisitLedger();
    private final Receipt receipt = new Receipt();
    private final byte[] profileCiphertext = new byte[PROFILE_CIPHERTEXT_MAX];
    private short profileCiphertextLength;
    private final byte[] profileNonce = new byte[ProfileCrypto.GCM_NONCE_LENGTH];

    private byte[] profilePlaintext;
    private short profilePlaintextLength;

    private final byte[] commandData;

    private byte lifecycle;
    private boolean keysReady;
    private ReceiptSigner receiptSigner;
    private CardSigner cardSigner;

    CardState() {
        commandData = JCSystem.makeTransientByteArray(Protocol.COMMAND_DATA_MAX_LENGTH,
                JCSystem.CLEAR_ON_DESELECT);
    }

    byte[] commandData() {
        return commandData;
    }

    void clearCommandData() {
        Util.arrayFillNonAtomic(commandData, (short) 0,
                Protocol.COMMAND_DATA_MAX_LENGTH, (byte) 0);
    }

    void ensureWorkingBuffers() {
        if (profilePlaintext == null) {
            profilePlaintext = JCSystem.makeTransientByteArray(PROFILE_PLAINTEXT_MAX,
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
        Util.arrayFillNonAtomic(profileNonce, (short) 0, ProfileCrypto.GCM_NONCE_LENGTH,
                (byte) 0);
        clearWorkingBuffers();
    }

    void clearWorkingBuffers() {
        if (profilePlaintext != null) {
            Util.arrayFillNonAtomic(profilePlaintext, (short) 0, PROFILE_PLAINTEXT_MAX, (byte) 0);
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

    CardSigner cardSigner() {
        return cardSigner;
    }

    void setCardSigner(CardSigner signer) {
        cardSigner = signer;
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

    void setProfileCiphertextAtomic(byte[] source, short offset, short length) {
        Util.arrayCopy(source, offset, profileCiphertext, (short) 0, length);
        profileCiphertextLength = length;
    }

    byte[] profileNonce() {
        return profileNonce;
    }

    void setProfileNonce(byte[] source, short offset) {
        Util.arrayCopyNonAtomic(source, offset, profileNonce, (short) 0,
                ProfileCrypto.GCM_NONCE_LENGTH);
    }

    void advanceProfileNonce() {
        for (short index = (short) (ProfileCrypto.GCM_NONCE_LENGTH - 1); index >= (short) 0;
                index--) {
            byte next = (byte) (profileNonce[index] + (byte) 1);
            profileNonce[index] = next;
            if (next != (byte) 0) {
                return;
            }
        }
        ISOException.throwIt(Sw.INTERNAL_ERROR);
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

}
