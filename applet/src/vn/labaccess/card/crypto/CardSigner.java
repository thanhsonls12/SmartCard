package vn.labaccess.card;

import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.MessageDigest;
import javacard.security.Signature;

final class CardSigner {

    private static final short PUBLIC_POINT_LENGTH = (short) 65;

    private final Signature ecdsa;
    private final MessageDigest sha256;
    private final ECPrivateKey privateKey;
    private final byte[] message = JCSystem.makeTransientByteArray(
            Protocol.PROFILE_MESSAGE_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    private final byte[] publicKeyHash = JCSystem.makeTransientByteArray(
            Protocol.ENROLLMENT_PUBLIC_KEY_HASH_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    private final byte[] signature = JCSystem.makeTransientByteArray(
            Protocol.ENROLLMENT_SIGNATURE_MAX_LENGTH, JCSystem.CLEAR_ON_DESELECT);

    CardSigner(ECPrivateKey privateKey) {
        this.privateKey = privateKey;
        ecdsa = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
        sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
    }

    byte[] signature() {
        return signature;
    }

    short signEnrollment(byte[] nonce, byte[] cardId, ECPublicKey cardPublicKey) {
        hashPublicKey(cardPublicKey);
        Util.arrayCopyNonAtomic(Protocol.ENROLLMENT_DOMAIN, (short) 0, message, (short) 0,
                Protocol.ENROLLMENT_DOMAIN_LENGTH);
        Util.arrayCopyNonAtomic(cardId, (short) 0, message, Protocol.ENROLLMENT_OFFSET_CARD_ID,
                Protocol.CARD_ID_LENGTH);
        Util.arrayCopyNonAtomic(publicKeyHash, (short) 0, message,
                Protocol.ENROLLMENT_OFFSET_PUBLIC_KEY_HASH,
                Protocol.ENROLLMENT_PUBLIC_KEY_HASH_LENGTH);
        Util.arrayCopyNonAtomic(nonce, (short) 0, message, Protocol.ENROLLMENT_OFFSET_NONCE,
                Protocol.ENROLLMENT_NONCE_LENGTH);
        return sign(Protocol.ENROLLMENT_MESSAGE_LENGTH);
    }

    short signProfile(byte[] nonce, short nonceOffset, byte[] cardId, ECPublicKey cardPublicKey,
            byte[] plaintext, short plaintextLength) {
        hashPublicKey(cardPublicKey);
        Util.arrayCopyNonAtomic(Protocol.PROFILE_DOMAIN, (short) 0, message, (short) 0,
                Protocol.PROFILE_DOMAIN_LENGTH);
        Util.arrayCopyNonAtomic(cardId, (short) 0, message, Protocol.PROFILE_OFFSET_CARD_ID,
                Protocol.CARD_ID_LENGTH);
        Util.arrayCopyNonAtomic(publicKeyHash, (short) 0, message,
                Protocol.PROFILE_OFFSET_PUBLIC_KEY_HASH,
                Protocol.ENROLLMENT_PUBLIC_KEY_HASH_LENGTH);
        Util.arrayCopyNonAtomic(nonce, nonceOffset, message, Protocol.PROFILE_OFFSET_NONCE,
                Protocol.ENROLLMENT_NONCE_LENGTH);
        sha256.reset();
        sha256.doFinal(plaintext, (short) 0, plaintextLength, message,
                Protocol.PROFILE_OFFSET_HASH);
        return sign(Protocol.PROFILE_MESSAGE_LENGTH);
    }

    private void hashPublicKey(ECPublicKey cardPublicKey) {
        cardPublicKey.getW(message, (short) 0);
        sha256.reset();
        sha256.doFinal(message, (short) 0, PUBLIC_POINT_LENGTH, publicKeyHash, (short) 0);
    }

    private short sign(short messageLength) {
        ecdsa.init(privateKey, Signature.MODE_SIGN);
        return ecdsa.sign(message, (short) 0, messageLength, signature, (short) 0);
    }
}
