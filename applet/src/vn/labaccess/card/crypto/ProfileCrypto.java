package vn.labaccess.card;

import javacard.framework.ISOException;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.KeyBuilder;
import javacard.security.MessageDigest;
import javacard.security.RandomData;
import javacardx.crypto.AEADCipher;

final class ProfileCrypto {

    static final byte PROFILE_RECORD_VERSION = (byte) 0x01;
    static final byte PROFILE_ALGORITHM_ID_GCM = (byte) 0x01;
    static final byte PROFILE_SCHEMA_VERSION = (byte) 0x01;
    static final byte PROFILE_SCHEMA_VERSION_V2 = (byte) 0x02;
    static final short RECORD_HEADER_LENGTH = (short) 14;
    static final short RECORD_HEADER_OFFSET_NONCE = (short) 2;
    static final short GCM_NONCE_LENGTH = (short) 12;
    static final short GCM_TAG_LENGTH = (short) 16;
    static final short AES_KEY_LENGTH_BYTES = (short) 16;
    static final short RECORD_MIN_LENGTH = (short) (RECORD_HEADER_LENGTH + GCM_TAG_LENGTH + 1);

    private static final byte AES_GCM = (byte) 15;
    private static final byte MODE_ENCRYPT = (byte) 1;

    private final AEADCipher gcm;
    private final AESKey aesKey;
    private final MessageDigest digest;
    private final RandomData random;
    private final byte[] keySeed = new byte[AES_KEY_LENGTH_BYTES];

    ProfileCrypto() {
        gcm = (AEADCipher) AEADCipher.getInstance(AEADCipher.ALG_AES_GCM, false);
        aesKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES,
                KeyBuilder.LENGTH_AES_128, false);
        digest = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
        random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
    }

    void generateAesKey() {
        aesKey.clearKey();
        try {
            random.generateData(keySeed, (short) 0, AES_KEY_LENGTH_BYTES);
            aesKey.setKey(keySeed, (short) 0);
        } finally {
            Util.arrayFillNonAtomic(keySeed, (short) 0, AES_KEY_LENGTH_BYTES, (byte) 0);
        }
    }

    void clearAesKey() {
        aesKey.clearKey();
    }

    short encryptRecord(byte[] plaintext, short plaintextLength, byte[] target) {
        random.generateData(target, RECORD_HEADER_OFFSET_NONCE,
                GCM_NONCE_LENGTH);
        return encryptWithNonce(plaintext, plaintextLength, target);
    }

    short encryptRecordWithNonce(byte[] plaintext, short plaintextLength, byte[] target,
            byte[] nonce) {
        Util.arrayCopyNonAtomic(nonce, (short) 0, target, RECORD_HEADER_OFFSET_NONCE,
                GCM_NONCE_LENGTH);
        return encryptWithNonce(plaintext, plaintextLength, target);
    }

    private short encryptWithNonce(byte[] plaintext, short plaintextLength, byte[] target) {
        gcm.init(aesKey, AEADCipher.MODE_ENCRYPT, target, RECORD_HEADER_OFFSET_NONCE,
                GCM_NONCE_LENGTH);
        gcm.updateAAD(Protocol.MAGIC, (short) 0, Protocol.MAGIC_LENGTH);
        short ciphertextLength = gcm.doFinal(plaintext, (short) 0, plaintextLength,
                target, RECORD_HEADER_LENGTH);
        gcm.retrieveTag(target, (short) (RECORD_HEADER_LENGTH + ciphertextLength),
                GCM_TAG_LENGTH);
        target[(short) 0] = PROFILE_RECORD_VERSION;
        target[(short) 1] = PROFILE_ALGORITHM_ID_GCM;
        return (short) (RECORD_HEADER_LENGTH + ciphertextLength + GCM_TAG_LENGTH);
    }

    short decryptStoredRecord(byte[] record, short recordLength, byte[] plaintext) {
        requireStoredRecord(record, recordLength);
        short ciphertextLength = ciphertextLength(recordLength);
        short tagOffset = (short) (RECORD_HEADER_LENGTH + ciphertextLength);
        boolean tagVerified = false;
        try {
            gcm.init(aesKey, AEADCipher.MODE_DECRYPT, record, RECORD_HEADER_OFFSET_NONCE,
                    GCM_NONCE_LENGTH);
            gcm.updateAAD(Protocol.MAGIC, (short) 0, Protocol.MAGIC_LENGTH);
            short plaintextLength = gcm.doFinal(record, RECORD_HEADER_LENGTH, ciphertextLength,
                    plaintext, (short) 0);
            tagVerified = gcm.verifyTag(record, tagOffset, GCM_TAG_LENGTH, GCM_TAG_LENGTH);
            if (!tagVerified) {
                ISOException.throwIt(Sw.INTERNAL_ERROR);
            }
            return plaintextLength;
        } finally {
            if (!tagVerified) {
                Util.arrayFillNonAtomic(plaintext, (short) 0, (short) plaintext.length, (byte) 0);
            }
        }
    }

    short writeMetadata(byte[] record, short recordLength, byte[] plaintextScratch,
            byte[] target) {
        short plaintextLength = decryptStoredRecord(record, recordLength, plaintextScratch);
        ProfileCodec.requireValid(plaintextScratch, plaintextLength);
        short ciphertextLength = ciphertextLength(recordLength);
        target[Protocol.PROFILE_META_OFFSET_RECORD_VERSION] = PROFILE_RECORD_VERSION;
        target[Protocol.PROFILE_META_OFFSET_ALGORITHM_ID] = PROFILE_ALGORITHM_ID_GCM;
        target[Protocol.PROFILE_META_OFFSET_SCHEMA_VERSION] =
                plaintextScratch[Protocol.PROFILE_OFFSET_SCHEMA_VERSION];
        Util.setShort(target, Protocol.PROFILE_META_OFFSET_CIPHERTEXT_LENGTH, ciphertextLength);
        Util.arrayCopyNonAtomic(record, RECORD_HEADER_OFFSET_NONCE, target,
                Protocol.PROFILE_META_OFFSET_NONCE, GCM_NONCE_LENGTH);
        Util.arrayCopyNonAtomic(record, (short) (RECORD_HEADER_LENGTH + ciphertextLength),
                target, Protocol.PROFILE_META_OFFSET_TAG, GCM_TAG_LENGTH);
        digest.reset();
        digest.doFinal(record, RECORD_HEADER_LENGTH, ciphertextLength, target,
                Protocol.PROFILE_META_OFFSET_CIPHERTEXT_HASH);
        return Protocol.PROFILE_META_RESPONSE_LENGTH;
    }

    private static short ciphertextLength(short recordLength) {
        return (short) (recordLength - RECORD_HEADER_LENGTH - GCM_TAG_LENGTH);
    }

    private static void requireStoredRecord(byte[] record, short recordLength) {
        if (recordLength < RECORD_MIN_LENGTH
                || record[(short) 0] != PROFILE_RECORD_VERSION
                || record[(short) 1] != PROFILE_ALGORITHM_ID_GCM) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }
}
