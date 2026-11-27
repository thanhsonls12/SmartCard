package vn.labaccess.card;

import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.MessageDigest;
import javacard.security.Signature;

/**
 * Signs the enrollment challenge over a domain separated from the receipt domain, so a signature
 * produced here can never be replayed as a receipt or vice versa.
 */
final class EnrollmentSigner {

    private static final short PUBLIC_POINT_LENGTH = (short) 65;

    private final Signature ecdsa;
    private final MessageDigest sha256;
    private final ECPrivateKey privateKey;
    private final byte[] message = JCSystem.makeTransientByteArray(
            Protocol.ENROLLMENT_MESSAGE_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    private final byte[] publicKeyHash = JCSystem.makeTransientByteArray(
            Protocol.ENROLLMENT_PUBLIC_KEY_HASH_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    private final byte[] publicPoint = JCSystem.makeTransientByteArray(
            PUBLIC_POINT_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    private final byte[] signature = JCSystem.makeTransientByteArray(
            Protocol.ENROLLMENT_SIGNATURE_MAX_LENGTH, JCSystem.CLEAR_ON_DESELECT);

    EnrollmentSigner(ECPrivateKey privateKey) {
        this.privateKey = privateKey;
        ecdsa = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
        sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
    }

    byte[] signature() {
        return signature;
    }

    short sign(byte[] nonce, byte[] cardId, ECPublicKey cardPublicKey) {
        cardPublicKey.getW(publicPoint, (short) 0);
        sha256.reset();
        sha256.doFinal(publicPoint, (short) 0, PUBLIC_POINT_LENGTH, publicKeyHash, (short) 0);
        Util.arrayCopyNonAtomic(Protocol.ENROLLMENT_DOMAIN, (short) 0, message,
                (short) 0, Protocol.ENROLLMENT_DOMAIN_LENGTH);
        Util.arrayCopyNonAtomic(cardId, (short) 0, message,
                Protocol.ENROLLMENT_OFFSET_CARD_ID, Protocol.CARD_ID_LENGTH);
        Util.arrayCopyNonAtomic(publicKeyHash, (short) 0, message,
                Protocol.ENROLLMENT_OFFSET_PUBLIC_KEY_HASH, Protocol.ENROLLMENT_PUBLIC_KEY_HASH_LENGTH);
        Util.arrayCopyNonAtomic(nonce, (short) 0, message,
                Protocol.ENROLLMENT_OFFSET_NONCE, Protocol.ENROLLMENT_NONCE_LENGTH);
        ecdsa.init(privateKey, Signature.MODE_SIGN);
        return ecdsa.sign(message, (short) 0, Protocol.ENROLLMENT_MESSAGE_LENGTH,
                signature, (short) 0);
    }
}
