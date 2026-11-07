/*
 * LabAccess — Gate 0 crypto probe.
 *
 * Applet CHI de kiem chung thuat toan, khong co nghiep vu.
 *   - AES-128-GCM qua javacardx.crypto.AEADCipher (API AEAD that cua SDK)
 *   - ECDSA P-256 / SHA-256 qua javacard.security.Signature
 *
 * Khong bao gio xuat khoa bi mat ra APDU.
 *
 * INS (tai lieu probe):
 *   0x10 INIT          khoi tao GCM + 2 keypair EC; tra chan doan tung buoc
 *   0x12 GCM_ENCRYPT   data = plaintext -> nonce(12) || ciphertext || tag(16)
 *   0x14 GCM_DECRYPT   data = nonce(12) || ciphertext || tag(16)
 *                      P1 = bien the thu tu goi API (1..5)
 *                      P2 = 0x00 AAD chuan | 0x01 AAD bi sua
 *   0x16 GCM_INFO      thong tin cipher/khoa/tag/nonce
 *   0x20 GET_PUBKEY    P1 = 0x01 keypair 1 | 0x02 keypair 2 -> 04||X||Y (65 byte)
 *   0x22 SIGN          data = message -> chu ky DER
 *   0x30 LAST_ERROR    ma CryptoException cua lan loi gan nhat
 */
package vn.labaccess.probe;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.CryptoException;
import javacard.security.ECKey;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.NamedParameterSpec;
import javacard.security.RandomData;
import javacard.security.Signature;
import javacardx.crypto.AEADCipher;

public class CryptoProbe extends Applet {

    /* ---------------- INS ---------------- */
    static final byte INS_INIT        = 0x10;
    static final byte INS_GCM_ENCRYPT = 0x12;
    static final byte INS_GCM_DECRYPT = 0x14;
    static final byte INS_GCM_INFO    = 0x16;
    static final byte INS_GET_PUBKEY  = 0x20;
    static final byte INS_SIGN        = 0x22;
    static final byte INS_LAST_ERROR  = 0x30;

    /* ---------------- tham so ---------------- */
    static final short NONCE_LEN = (short) 12;
    static final short TAG_LEN   = (short) 16;
    static final short KEY_LEN   = (short) 16;
    static final short MAX_PT    = (short) 64;
    static final short MAX_MSG   = (short) 96;

    /* AAD co dinh: "LABACCESS-G0-AES-v1" (19 byte ASCII) */
    static final byte[] AAD = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S', '-',
            'G', '0', '-', 'A', 'E', 'S', '-', 'v', '1'
    };

    /* Tham so duong cong NIST P-256 (secp256r1), big-endian, moi so 32 byte */
    static final byte[] P256_P = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF
    };
    static final byte[] P256_A = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFC
    };
    static final byte[] P256_B = {
            0x5A, (byte) 0xC6, 0x35, (byte) 0xD8, (byte) 0xAA, 0x3A, (byte) 0x93, (byte) 0xE7,
            (byte) 0xB3, (byte) 0xEB, (byte) 0xBD, 0x55, 0x76, (byte) 0x98, (byte) 0x86, (byte) 0xBC,
            0x65, 0x1D, 0x06, (byte) 0xB0, (byte) 0xCC, 0x53, (byte) 0xB0, (byte) 0xF6,
            0x3B, (byte) 0xCE, 0x3C, 0x3E, 0x27, (byte) 0xD2, 0x60, 0x4B
    };
    /* G = Gx || Gy : 64 byte */
    static final byte[] P256_G = {
            /* Gx = 6B17D1F2 E12C4247 F8BCE6E5 63A440F2 77037D81 2DEB33A0 F4A13945 D898C296 */
            0x6B, 0x17, (byte) 0xD1, (byte) 0xF2, (byte) 0xE1, 0x2C, 0x42, 0x47,
            (byte) 0xF8, (byte) 0xBC, (byte) 0xE6, (byte) 0xE5, 0x63, (byte) 0xA4, 0x40, (byte) 0xF2,
            0x77, 0x03, 0x7D, (byte) 0x81, 0x2D, (byte) 0xEB, 0x33, (byte) 0xA0,
            (byte) 0xF4, (byte) 0xA1, 0x39, 0x45, (byte) 0xD8, (byte) 0x98, (byte) 0xC2, (byte) 0x96,
            /* Gy = 4FE342E2 FE1A7F9B 8EE7EB4A 7C0F9E16 2BCE3357 6B315ECE CBB64068 37BF51F5 */
            0x4F, (byte) 0xE3, 0x42, (byte) 0xE2, (byte) 0xFE, 0x1A, 0x7F, (byte) 0x9B,
            (byte) 0x8E, (byte) 0xE7, (byte) 0xEB, 0x4A, 0x7C, 0x0F, (byte) 0x9E, 0x16,
            0x2B, (byte) 0xCE, 0x33, 0x57, 0x6B, 0x31, 0x5E, (byte) 0xCE,
            (byte) 0xCB, (byte) 0xB6, 0x40, 0x68, 0x37, (byte) 0xBF, 0x51, (byte) 0xF5
    };
    /* r = FFFFFFFF 00000000 FFFFFFFF FFFFFFFF BCE6FAAD A7179E84 F3B9CAC2 FC632551 */
    static final byte[] P256_R = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF,
            (byte) 0xBC, (byte) 0xE6, (byte) 0xFA, (byte) 0xAD, (byte) 0xA7, 0x17,
            (byte) 0x9E, (byte) 0x84, (byte) 0xF3, (byte) 0xB9, (byte) 0xCA, (byte) 0xC2,
            (byte) 0xFC, 0x63, 0x25, 0x51
    };

    private short lastError;
    private short ecPath;      /* 1 = NamedParameterSpec, 2..4 = bien the tham so tuong minh */
    private short diag;        /* buoc chan doan chi tiet trong qua trinh dat tham so EC */
    private byte[] ecReport;   /* (bien the, buoc, ly do) cho tung lan thu that bai */

    private AESKey aesKey;
    private KeyPair kp1;
    private KeyPair kp2;
    private Signature ecdsa;
    private AEADCipher gcm;
    private RandomData rng;

    private byte[] nonce;
    private byte[] work;
    private byte[] tagBuf;
    private byte[] inBuf;
    private byte[] decBuf;
    private byte[] keyBytes;
    private byte[] g65;

    private boolean gcmReady;
    private boolean ecReady;

    protected CryptoProbe() {
        nonce    = new byte[NONCE_LEN];
        work     = new byte[MAX_PT];
        tagBuf   = new byte[TAG_LEN];
        inBuf    = new byte[(short) (MAX_PT + TAG_LEN)];
        decBuf   = new byte[MAX_PT];
        keyBytes = new byte[KEY_LEN];
        g65 = new byte[(short) 65];
        ecReport = new byte[(short) 24];
        lastError = 0;
        diag = 0;
        ecPath = 0;
        gcmReady = false;
        ecReady = false;
        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new CryptoProbe();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }
        byte[] buf = apdu.getBuffer();
        switch (buf[ISO7816.OFFSET_INS]) {
            case INS_INIT:        doInit(apdu);        break;
            case INS_GCM_ENCRYPT: doGcmEncrypt(apdu);  break;
            case INS_GCM_DECRYPT: doGcmDecrypt(apdu);  break;
            case INS_GCM_INFO:    doInfo(apdu);        break;
            case INS_GET_PUBKEY:  doGetPubkey(apdu);   break;
            case INS_SIGN:        doSign(apdu);        break;
            case INS_LAST_ERROR:  doLastError(apdu);   break;
            default:              ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    /* ================= INIT ================= */

    private void doInit(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        lastError = 0;
        ecPath = 0;

        short gcmStep = 0;
        short gcmErr = 0;
        short ecStep = 0;
        short ecErr = 0;
        short curveId = 0;

        /* ---- GCM ---- */
        try {
            gcmStep = 1;
            rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
            gcmStep = 2;
            aesKey = (AESKey) KeyBuilder.buildKey(
                    KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
            gcmStep = 3;
            rng.nextBytes(keyBytes, (short) 0, KEY_LEN);
            aesKey.setKey(keyBytes, (short) 0);
            clearArray(keyBytes);
            gcmStep = 4;
            gcm = (AEADCipher) AEADCipher.getInstance(AEADCipher.ALG_AES_GCM, false);
            gcmStep = 0;
        } catch (CryptoException e) {
            gcmErr = e.getReason();
            gcm = null;
        }

        /* ---- EC: uu tien duong cong co ten, sau do thu cac cach dat tham so ---- */
        short ecRep = 0;
        byte ecTries = 0;

        try {
            ecStep = 5;
            NamedParameterSpec p256 = NamedParameterSpec.getInstance(NamedParameterSpec.SECP256R1);
            curveId = p256.getName();
            ecStep = 6;
            kp1 = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            ecStep = 7;
            kp1.genKeyPair(p256);
            ecStep = 8;
            kp2 = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
            ecStep = 9;
            kp2.genKeyPair(p256);
            ecPath = 1;
            ecStep = 0;
            ecErr = 0;
        } catch (CryptoException e) {
            ecErr = e.getReason();
            kp1 = null;
            kp2 = null;
            ecPath = 0;
            ecReport[ecRep++] = (byte) 0;
            ecReport[ecRep++] = (byte) ecStep;
            ecReport[ecRep++] = (byte) ecErr;
        }
        /* Thu cac cach dat tham so P-256 tuong minh.
         * Thu tu uu tien: 5 (da chung minh chay duoc tren Simulator 26.0),
         * sau do 1..4 va 6 de giu bang chung chan doan. */
        byte[] order = {5, 1, 2, 3, 4, 6};
        for (byte i = 0; i < (byte) order.length && ecPath == 0; i++) {
            byte v = order[i];
            ecTries++;
            try {
                kp1 = buildEcPair(v);
                ecPath = (byte) (v + 1);
                ecErr = 0;
                ecStep = 0;
            } catch (CryptoException e) {
                ecErr = e.getReason();
                ecStep = diag;
                kp1 = null;
                if ((short) (ecRep + 3) <= (short) ecReport.length) {
                    ecReport[ecRep++] = v;
                    ecReport[ecRep++] = (byte) diag;
                    ecReport[ecRep++] = (byte) ecErr;
                }
            }
        }

        /* Keypair thu hai: cung cach voi cai da thanh cong */
        if (ecPath > 1) {
            try {
                kp2 = buildEcPair((byte) (ecPath - 1));
            } catch (CryptoException e) {
                ecErr = e.getReason();
                ecStep = diag;
                kp2 = null;
            }
        }

        if (ecPath != 0 && kp2 != null) {
            try {
                ecdsa = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
            } catch (CryptoException e) {
                ecErr = e.getReason();
                ecdsa = null;
            }
        }

        gcmReady = (gcm != null) && (gcmErr == 0);
        ecReady = (ecdsa != null) && (ecErr == 0) && (ecPath != 0);

        short o = (short) 0;
        buf[o++] = (byte) ((gcmReady ? 0x01 : 0x00) | (ecReady ? 0x02 : 0x00));
        buf[o++] = (byte) gcmStep;
        buf[o++] = (byte) gcmErr;
        buf[o++] = (byte) ecStep;
        buf[o++] = (byte) ecErr;
        buf[o++] = (byte) (curveId & 0xFF);
        buf[o++] = (byte) ((curveId >> 8) & 0xFF);
        buf[o++] = (byte) 128;
        buf[o++] = (byte) TAG_LEN;
        buf[o++] = (byte) NONCE_LEN;
        buf[o++] = (byte) ecPath;
        buf[o++] = ecTries;
        buf[o++] = (byte) ecRep;
        for (short i = 0; i < ecRep; i++) {
            buf[o++] = ecReport[i];
        }
        apdu.setOutgoingAndSend((short) 0, o);
    }

    /**
     * Tao keypair EC P-256 theo 6 cach khac nhau de xac dinh cach nao chay duoc
     * tren nen tang that. Nem CryptoException de noi goi ghi nhan buoc va ly do.
     */
    private KeyPair buildEcPair(byte v) {
        short base = (short) (v * 10);
        diag = base;

        if (v == 4) {
            /* Cach chuan Java Card 2.x: KeyBuilder tao khoa chua khoi tao, roi ghep KeyPair */
            ECPublicKey pub = (ECPublicKey) KeyBuilder.buildKey(
                    KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false);
            diag = (short) (base + 1);
            ECPrivateKey prv = (ECPrivateKey) KeyBuilder.buildKey(
                    KeyBuilder.TYPE_EC_FP_PRIVATE, KeyBuilder.LENGTH_EC_FP_256, false);
            setParams(pub, base);
            setParams(prv, diag);
            diag = (short) (base + 14);
            KeyPair pair = new KeyPair(pub, prv);
            diag = (short) (base + 15);
            pair.genKeyPair();
            return pair;
        }

        KeyPair pair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);
        diag = (short) (base + 1);

        if (v == 1) {
            setParams((ECKey) pair.getPublic(), diag);
            setParams((ECKey) pair.getPrivate(), diag);
        } else if (v == 2) {
            setParams((ECKey) pair.getPrivate(), diag);
        } else if (v == 3) {
            setParams((ECKey) pair.getPublic(), diag);
        } else if (v == 5) {
            /* G co tien to 0x04 (65 byte) */
            setParamsG65((ECKey) pair.getPublic(), diag);
            setParamsG65((ECKey) pair.getPrivate(), diag);
        } else {
            /* v == 6: dat G truoc A va B */
            setParamsGFirst((ECKey) pair.getPublic(), diag);
            setParamsGFirst((ECKey) pair.getPrivate(), diag);
        }

        diag = (short) (base + 12);
        pair.genKeyPair();
        return pair;
    }

    private void setParams(ECKey k, short start) {
        diag = (short) (start + 1);
        k.setFieldFP(P256_P, (short) 0, (short) 32);
        diag = (short) (start + 2);
        k.setA(P256_A, (short) 0, (short) 32);
        diag = (short) (start + 3);
        k.setB(P256_B, (short) 0, (short) 32);
        diag = (short) (start + 4);
        k.setG(P256_G, (short) 0, (short) 64);
        diag = (short) (start + 5);
        k.setR(P256_R, (short) 0, (short) 32);
    }

    private void setParamsG65(ECKey k, short start) {
        g65[0] = (byte) 0x04;
        Util.arrayCopyNonAtomic(P256_G, (short) 0, g65, (short) 1, (short) 64);
        diag = (short) (start + 1);
        k.setFieldFP(P256_P, (short) 0, (short) 32);
        diag = (short) (start + 2);
        k.setA(P256_A, (short) 0, (short) 32);
        diag = (short) (start + 3);
        k.setB(P256_B, (short) 0, (short) 32);
        diag = (short) (start + 4);
        k.setG(g65, (short) 0, (short) 65);
        diag = (short) (start + 5);
        k.setR(P256_R, (short) 0, (short) 32);
    }

    private void setParamsGFirst(ECKey k, short start) {
        diag = (short) (start + 1);
        k.setFieldFP(P256_P, (short) 0, (short) 32);
        diag = (short) (start + 2);
        k.setG(P256_G, (short) 0, (short) 64);
        diag = (short) (start + 3);
        k.setR(P256_R, (short) 0, (short) 32);
        diag = (short) (start + 4);
        k.setA(P256_A, (short) 0, (short) 32);
        diag = (short) (start + 5);
        k.setB(P256_B, (short) 0, (short) 32);
    }

    /* ================= GCM ================= */

    private void doGcmEncrypt(APDU apdu) {
        requireGcm();
        byte[] buf = apdu.getBuffer();
        short len = receive(apdu, work, MAX_PT);

        try {
            rng.nextBytes(nonce, (short) 0, NONCE_LEN);
            gcm.init(aesKey, AEADCipher.MODE_ENCRYPT, nonce, (short) 0, NONCE_LEN);
            gcm.updateAAD(AAD, (short) 0, (short) AAD.length);

            short ctLen = gcm.doFinal(work, (short) 0, len, buf, NONCE_LEN);
            short tagLen = gcm.retrieveTag(buf, (short) (NONCE_LEN + ctLen), TAG_LEN);
            Util.arrayCopyNonAtomic(nonce, (short) 0, buf, (short) 0, NONCE_LEN);

            apdu.setOutgoingAndSend((short) 0, (short) (NONCE_LEN + ctLen + tagLen));

        } catch (CryptoException e) {
            lastError = e.getReason();
            ISOException.throwIt((short) 0x6F00);
        }
    }

    /**
     * P1 chon bien the thu tu goi API — dung de xac dinh dung nghia tren nen tang that.
     * P2 = 0x00 AAD chuan, 0x01 AAD bi sua (test am).
     * Plaintext chi ra khoi Applet sau khi xac thuc thanh cong.
     */
    private void doGcmDecrypt(APDU apdu) {
        requireGcm();
        byte[] buf = apdu.getBuffer();
        byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];

        short total = receive(apdu, inBuf, (short) (MAX_PT + TAG_LEN));
        if (total < (short) (NONCE_LEN + TAG_LEN + 1)) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        short ctLen = (short) (total - NONCE_LEN - TAG_LEN);

        Util.arrayCopyNonAtomic(inBuf, (short) 0, nonce, (short) 0, NONCE_LEN);
        Util.arrayCopyNonAtomic(inBuf, (short) (NONCE_LEN + ctLen), tagBuf, (short) 0, TAG_LEN);
        Util.arrayCopyNonAtomic(inBuf, NONCE_LEN, work, (short) 0, ctLen);

        try {
            gcm.init(aesKey, AEADCipher.MODE_DECRYPT, nonce, (short) 0, NONCE_LEN);
            if (p2 == (byte) 0x01) {
                gcm.updateAAD(AAD, (short) 0, (short) (AAD.length - 1));
            } else {
                gcm.updateAAD(AAD, (short) 0, (short) AAD.length);
            }

            short ptLen;
            switch (p1) {
                case 1: {   /* doFinal tren ct||tag lien nhau */
                    Util.arrayCopyNonAtomic(work, (short) 0, inBuf, (short) 0, ctLen);
                    Util.arrayCopyNonAtomic(tagBuf, (short) 0, inBuf, ctLen, TAG_LEN);
                    ptLen = gcm.doFinal(inBuf, (short) 0, (short) (ctLen + TAG_LEN), decBuf, (short) 0);
                    break;
                }
                case 2: {   /* update -> verifyTag -> doFinal(0) */
                    ptLen = gcm.update(work, (short) 0, ctLen, decBuf, (short) 0);
                    if (!gcm.verifyTag(tagBuf, (short) 0, TAG_LEN, TAG_LEN)) {
                        clearArray(decBuf);
                        ISOException.throwIt((short) 0x6982);
                    }
                    ptLen += gcm.doFinal(work, (short) 0, (short) 0, decBuf, ptLen);
                    break;
                }
                case 3: {   /* update -> doFinal(0) -> verifyTag */
                    ptLen = gcm.update(work, (short) 0, ctLen, decBuf, (short) 0);
                    ptLen += gcm.doFinal(work, (short) 0, (short) 0, decBuf, ptLen);
                    if (!gcm.verifyTag(tagBuf, (short) 0, TAG_LEN, TAG_LEN)) {
                        clearArray(decBuf);
                        ISOException.throwIt((short) 0x6982);
                    }
                    break;
                }
                case 4: {   /* doFinal(ct) -> verifyTag */
                    ptLen = gcm.doFinal(work, (short) 0, ctLen, decBuf, (short) 0);
                    if (!gcm.verifyTag(tagBuf, (short) 0, TAG_LEN, TAG_LEN)) {
                        clearArray(decBuf);
                        ISOException.throwIt((short) 0x6982);
                    }
                    break;
                }
                case 5: {   /* update -> verifyTag (khong doFinal) */
                    ptLen = gcm.update(work, (short) 0, ctLen, decBuf, (short) 0);
                    if (!gcm.verifyTag(tagBuf, (short) 0, TAG_LEN, TAG_LEN)) {
                        clearArray(decBuf);
                        ISOException.throwIt((short) 0x6982);
                    }
                    break;
                }
                default:
                    ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
                    return;
            }

            /* Chi toi day moi tra plaintext ra */
            Util.arrayCopyNonAtomic(decBuf, (short) 0, buf, (short) 0, ptLen);
            clearArray(decBuf);
            apdu.setOutgoingAndSend((short) 0, ptLen);

        } catch (CryptoException e) {
            lastError = e.getReason();
            clearArray(decBuf);
            ISOException.throwIt((short) 0x6982);
        }
    }

    private void doInfo(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short o = (short) 0;
        buf[o++] = (byte) ((gcmReady ? 0x01 : 0x00) | (ecReady ? 0x02 : 0x00));
        buf[o++] = gcm == null ? (byte) 0x00 : gcm.getCipherAlgorithm();
        buf[o++] = (byte) KEY_LEN;
        buf[o++] = (byte) TAG_LEN;
        buf[o++] = (byte) NONCE_LEN;
        buf[o++] = (byte) lastError;
        buf[o++] = (byte) ecPath;
        apdu.setOutgoingAndSend((short) 0, o);
    }

    /* ================= ECDSA ================= */

    private void doGetPubkey(APDU apdu) {
        requireEc();
        byte[] buf = apdu.getBuffer();
        ECPublicKey pub = (buf[ISO7816.OFFSET_P1] == (byte) 0x02)
                ? (ECPublicKey) kp2.getPublic()
                : (ECPublicKey) kp1.getPublic();
        short len = pub.getW(buf, (short) 0);
        apdu.setOutgoingAndSend((short) 0, len);
    }

    private void doSign(APDU apdu) {
        requireEc();
        byte[] buf = apdu.getBuffer();
        short len = receive(apdu, work, MAX_MSG);
        try {
            ecdsa.init((ECPrivateKey) kp1.getPrivate(), Signature.MODE_SIGN);
            short sigLen = ecdsa.sign(work, (short) 0, len, buf, (short) 0);
            apdu.setOutgoingAndSend((short) 0, sigLen);
        } catch (CryptoException e) {
            lastError = e.getReason();
            ISOException.throwIt((short) 0x6F00);
        }
    }

    /* ================= helper ================= */

    private void doLastError(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        buf[0] = (byte) (lastError & 0xFF);
        apdu.setOutgoingAndSend((short) 0, (short) 1);
    }

    private short receive(APDU apdu, byte[] dst, short max) {
        byte[] buf = apdu.getBuffer();
        short len = apdu.setIncomingAndReceive();
        if (len < 0 || len > max) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, dst, (short) 0, len);
        return len;
    }

    private void requireGcm() {
        if (!gcmReady) {
            ISOException.throwIt((short) 0x6985);
        }
    }

    private void requireEc() {
        if (!ecReady) {
            ISOException.throwIt((short) 0x6985);
        }
    }

    private static void clearArray(byte[] a) {
        for (short i = 0; i < (short) a.length; i++) {
            a[i] = 0;
        }
    }
}
