package vn.labaccess.card;

import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.KeyBuilder;
import javacard.security.RandomData;
import javacardx.crypto.AEADCipher;

final class ProfileCrypto {

    static final byte PROFILE_RECORD_VERSION = (byte) 0x01;
    static final byte PROFILE_ALGORITHM_ID_GCM = (byte) 0x01;
    static final byte PROFILE_SCHEMA_VERSION = (byte) 0x01;
    static final short RECORD_HEADER_LENGTH = (short) 14;
    static final short RECORD_HEADER_OFFSET_NONCE = (short) 2;
    static final short GCM_NONCE_LENGTH = (short) 12;
    static final short GCM_TAG_LENGTH = (short) 16;
    static final short AES_KEY_LENGTH_BYTES = (short) 16;

    private static final byte AES_GCM = (byte) 15;
    private static final byte MODE_ENCRYPT = (byte) 1;

    private final AEADCipher gcm;
    private final AESKey aesKey;
    private final RandomData random;
    private final byte[] keySeed = new byte[AES_KEY_LENGTH_BYTES];

    ProfileCrypto() {
        gcm = (AEADCipher) AEADCipher.getInstance(AEADCipher.ALG_AES_GCM, false);
        aesKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES,
                KeyBuilder.LENGTH_AES_128, false);
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
}
