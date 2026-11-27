package vn.labaccess.card;

import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.CryptoException;
import javacard.security.KeyBuilder;
import javacard.security.RandomData;
import javacardx.crypto.AEADCipher;

final class PhotoStore {

    static final byte RECORD_VERSION = (byte) 0x01;
    static final byte ALGORITHM_ID_GCM = (byte) 0x01;

    private static final short SESSION_OPEN = (short) 0;
    private static final short SESSION_UPLOAD_OFFSET = (short) 1;
    private static final short SESSION_READ_OFFSET = (short) 3;
    private static final short SESSION_OWNER = (short) 5;
    private static final short SESSION_LENGTH = (short) 6;

    private static final byte SLOT_FIRST = (byte) 0;
    private static final byte SLOT_SECOND = (byte) 1;

    private static final byte OPEN = (byte) 1;
    private static final byte CLOSED = (byte) 0;
    private static final byte OWNER_NONE = (byte) 0;
    private static final byte OWNER_UPLOAD = (byte) 1;
    private static final byte OWNER_READ = (byte) 2;

    private final byte[] firstCiphertext = new byte[Protocol.PHOTO_LENGTH];
    private final byte[] secondCiphertext = new byte[Protocol.PHOTO_LENGTH];
    private final byte[] firstNonce = new byte[ProfileCrypto.GCM_NONCE_LENGTH];
    private final byte[] secondNonce = new byte[ProfileCrypto.GCM_NONCE_LENGTH];
    private final byte[] firstTag = new byte[ProfileCrypto.GCM_TAG_LENGTH];
    private final byte[] secondTag = new byte[ProfileCrypto.GCM_TAG_LENGTH];
    private final byte[] plaintext;
    private final byte[] session;
    private final AESKey key;
    private final AEADCipher gcm;
    private final RandomData random;

    private byte activeSlot;
    private byte recordVersion;
    private byte algorithmId;
    private short photoLength;
    private boolean photoReady;

    PhotoStore() {
        plaintext = JCSystem.makeTransientByteArray(Protocol.PHOTO_LENGTH,
                JCSystem.CLEAR_ON_DESELECT);
        session = JCSystem.makeTransientByteArray(SESSION_LENGTH, JCSystem.CLEAR_ON_DESELECT);
        gcm = (AEADCipher) AEADCipher.getInstance(AEADCipher.ALG_AES_GCM, false);
        key = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
        random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
    }

    void generateKey() {
        key.clearKey();
        try {
            random.generateData(plaintext, (short) 0, ProfileCrypto.AES_KEY_LENGTH_BYTES);
            key.setKey(plaintext, (short) 0);
        } finally {
            wipePlaintext();
        }
    }

    void clearKey() {
        key.clearKey();
    }

    short capacity() {
        return Protocol.PHOTO_LENGTH;
    }

    boolean ready() {
        return photoReady;
    }

    short length() {
        return photoLength;
    }

    boolean uploadOpen() {
        return session[SESSION_OPEN] == OPEN;
    }

    short uploadOffset() {
        return Util.getShort(session, SESSION_UPLOAD_OFFSET);
    }

    void openUpload() {
        claimPlaintext(OWNER_UPLOAD);
        session[SESSION_OPEN] = OPEN;
        Util.setShort(session, SESSION_UPLOAD_OFFSET, (short) 0);
    }

    void closeUpload() {
        session[SESSION_OPEN] = CLOSED;
        Util.setShort(session, SESSION_UPLOAD_OFFSET, (short) 0);
        releasePlaintext(OWNER_UPLOAD);
    }

    void appendUploaded(byte[] source, short sourceOffset, short length) {
        short offset = uploadOffset();
        Util.arrayCopyNonAtomic(source, sourceOffset, plaintext, offset, length);
        Util.setShort(session, SESSION_UPLOAD_OFFSET, (short) (offset + length));
    }

    void commitPhoto(short total) {
        byte target = spareSlot();
        encryptPlaintext(target);
        boolean transactionStarted = false;
        try {
            JCSystem.beginTransaction();
            transactionStarted = true;
            activeSlot = target;
            photoLength = total;
            recordVersion = RECORD_VERSION;
            algorithmId = ALGORITHM_ID_GCM;
            photoReady = true;
            JCSystem.commitTransaction();
            transactionStarted = false;
        } catch (RuntimeException exception) {
            if (transactionStarted) {
                JCSystem.abortTransaction();
            }
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        releasePlaintext(OWNER_UPLOAD);
    }

    short readOffset() {
        return Util.getShort(session, SESSION_READ_OFFSET);
    }

    void setReadOffset(short offset) {
        Util.setShort(session, SESSION_READ_OFFSET, offset);
    }

    void clearReadStream() {
        Util.setShort(session, SESSION_READ_OFFSET, (short) 0);
        releasePlaintext(OWNER_READ);
    }

    void checkRecord() {
        if (recordVersion != RECORD_VERSION || algorithmId != ALGORITHM_ID_GCM
                || photoLength != Protocol.PHOTO_LENGTH) {
            failIntegrity();
        }
    }

    void loadForRead() {
        claimPlaintext(OWNER_READ);
        try {
            gcm.init(key, AEADCipher.MODE_DECRYPT, nonceOf(activeSlot), (short) 0,
                    ProfileCrypto.GCM_NONCE_LENGTH);
            gcm.updateAAD(Protocol.MAGIC, (short) 0, Protocol.MAGIC_LENGTH);
            gcm.doFinal(ciphertextOf(activeSlot), (short) 0, photoLength, plaintext, (short) 0);
            if (!gcm.verifyTag(tagOf(activeSlot), (short) 0, ProfileCrypto.GCM_TAG_LENGTH,
                    ProfileCrypto.GCM_TAG_LENGTH)) {
                failIntegrity();
            }
        } catch (CryptoException exception) {
            failIntegrity();
        }
    }

    void copyPhoto(short offset, byte[] target, short targetOffset, short length) {
        Util.arrayCopyNonAtomic(plaintext, offset, target, targetOffset, length);
    }

    void wipeAll() {
        wipePlaintext();
        session[SESSION_OWNER] = OWNER_NONE;
    }

    private byte spareSlot() {
        return activeSlot == SLOT_FIRST ? SLOT_SECOND : SLOT_FIRST;
    }

    private byte[] ciphertextOf(byte slot) {
        return slot == SLOT_FIRST ? firstCiphertext : secondCiphertext;
    }

    private byte[] nonceOf(byte slot) {
        return slot == SLOT_FIRST ? firstNonce : secondNonce;
    }

    private byte[] tagOf(byte slot) {
        return slot == SLOT_FIRST ? firstTag : secondTag;
    }

    private void encryptPlaintext(byte slot) {
        byte[] target = ciphertextOf(slot);
        byte[] targetNonce = nonceOf(slot);
        random.generateData(targetNonce, (short) 0, ProfileCrypto.GCM_NONCE_LENGTH);
        gcm.init(key, AEADCipher.MODE_ENCRYPT, targetNonce, (short) 0,
                ProfileCrypto.GCM_NONCE_LENGTH);
        gcm.updateAAD(Protocol.MAGIC, (short) 0, Protocol.MAGIC_LENGTH);
        gcm.doFinal(plaintext, (short) 0, Protocol.PHOTO_LENGTH, target, (short) 0);
        gcm.retrieveTag(tagOf(slot), (short) 0, ProfileCrypto.GCM_TAG_LENGTH);
    }

    private void failIntegrity() {
        wipeAll();
        ISOException.throwIt(Sw.INTERNAL_ERROR);
    }

    private void claimPlaintext(byte owner) {
        wipePlaintext();
        session[SESSION_OWNER] = owner;
    }

    private void releasePlaintext(byte owner) {
        if (session[SESSION_OWNER] == owner) {
            wipePlaintext();
            session[SESSION_OWNER] = OWNER_NONE;
        }
    }

    private void wipePlaintext() {
        Util.arrayFillNonAtomic(plaintext, (short) 0, Protocol.PHOTO_LENGTH, (byte) 0);
    }
}
