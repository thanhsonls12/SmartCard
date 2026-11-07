/*
 * Gate 0 â€” G0-15: TransactionProbe
 *
 * Chá»©ng minh state vÃ  receipt commit/rollback CÃ™NG NHAU trÃªn layout persistent
 * Ä‘áº§y Ä‘á»§ dá»± kiáº¿n, vÃ  receipt Ä‘á»§ má»™t short response.
 *
 * KHONG phai Applet san pham: co cac nhanh gay loi chi de do ranh gioi
 * commit/response. Xem docs/plan/ket-qua-gate0.md.
 *
 * ---------------------------------------------------------------------------
 * LAYOUT PERSISTENT (mot mang byte duy nhat, co offset co dinh)
 *
 *   offset  size  truong
 *   ------  ----  --------------------------------------------------
 *        0     2  balance                 (uint16 BE)
 *        2     4  counter                 (uint32 BE)
 *        6     1  hasActiveVisit          (0/1)
 *        7    16  activeVisitId
 *       23    16  lastClosedVisitId
 *       39    34  receiptPayload          (canonical, version 1)
 *       73    72  receiptSignature        (ECDSA DER, toi da 72 byte)
 *      145     1  receiptSigLen           (0 = chua co receipt)
 *   ------  ----
 *   TONG    146 byte
 *
 * Receipt payload canonical (34 byte):
 *      0     1  version = 1
 *      1     8  cardId
 *      9     4  counterAfter   (uint32 BE)
 *     13     1  operation      (1=GRANT, 2=START, 3=END)
 *     14     2  delta          (int16 BE)
 *     16     2  balanceAfter   (uint16 BE)
 *     18    16  visitId        (GRANT dung sentinel toan 0)
 *
 * Khong co truong "payload length" vi version 1 co do dai co dinh 34 byte.
 * "receipt valid" duoc bieu dien bang receiptSigLen == 0.
 *
 * ---------------------------------------------------------------------------
 * INS
 *   0x10 INIT        data = cardId(8B). Sinh cap khoa EC, ghi du lieu A + receipt A.
 *   0x11 RESET_A     khoi phuc du lieu A va receipt A (giu nguyen cap khoa).
 *   0x12 READ_STATE  -> cardId(8) balance(2) counter(4) hasActive(1)
 *                       activeVisitId(16) lastClosedVisitId(16)          = 47 B
 *   0x14 CAPACITY    -> maxCap(2) unusedCap(2) depth(1)                   =  5 B
 *   0x16 GET_PUBKEY  P1=1 public 65B, P1=2 private(W) 65B                 = 65 B
 *   0x18 READ_RECEIPT-> payloadLen(1) payload(34) sigLen(1) sig(<=72)    <=108 B
 *   0x1A READ_STORE  -> toan bo 146 byte layout (snapshot byte-exact)
 *   0x1C LAST_TXN    -> unusedAfterWrites(2) unusedAfterEnd(2) depthInside(1)
 *                       depthAfter(1) scenario(1) payloadLen(1) sigLen(1) = 9 B
 *   0x30 TRANSACT    P1 = kich ban 1..4, data 42 byte:
 *                       balance(2) counter(4) hasActive(1) activeVisitId(16)
 *                       lastClosedVisitId(16) operation(1) delta(2)
 *
 * Kich ban:
 *   1  commit binh thuong            -> SW 9000, state+receipt = B
 *   2  abort sau khi ghi state       -> SW 6985, toan bo ve A
 *   3  abort sau khi ghi ca receipt  -> SW 6985, toan bo ve A
 *   4  commit xong roi tra loi loi   -> SW 6581, du lieu la B (mo phong mat response)
 */

package vn.labaccess.txprobe;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.ECKey;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.KeyPair;
import javacard.security.Signature;

public final class TransactionProbe extends Applet {

    /* ---------------- INS ---------------- */
    private static final byte INS_INIT = (byte) 0x10;
    private static final byte INS_RESET_A = (byte) 0x11;
    private static final byte INS_READ_STATE = (byte) 0x12;
    private static final byte INS_CAPACITY = (byte) 0x14;
    private static final byte INS_GET_PUBKEY = (byte) 0x16;
    private static final byte INS_READ_RECEIPT = (byte) 0x18;
    private static final byte INS_READ_STORE = (byte) 0x1A;
    private static final byte INS_LAST_TXN = (byte) 0x1C;
    private static final byte INS_TRANSACT = (byte) 0x30;

    /* ---------------- kich ban ---------------- */
    private static final byte SC_COMMIT = (byte) 0x01;
    private static final byte SC_ABORT_AFTER_STATE = (byte) 0x02;
    private static final byte SC_ABORT_AFTER_RECEIPT = (byte) 0x03;
    private static final byte SC_COMMIT_THEN_ERROR = (byte) 0x04;

    /* ---------------- offset trong layout ---------------- */
    private static final short OFF_BALANCE = (short) 0;
    private static final short LEN_BALANCE = (short) 2;
    private static final short OFF_COUNTER = (short) 2;
    private static final short LEN_COUNTER = (short) 4;
    private static final short OFF_HAS_ACTIVE = (short) 6;
    private static final short OFF_ACTIVE_ID = (short) 7;
    private static final short LEN_VISIT_ID = (short) 16;
    private static final short OFF_LAST_CLOSED = (short) 23;
    private static final short OFF_PAYLOAD = (short) 39;
    private static final short OFF_SIG = (short) 73;
    private static final short LEN_SIG_SLOT = (short) 72;
    private static final short OFF_SIG_LEN = (short) 145;

    private static final short STORE_LEN = (short) 146;

    /* ---------------- receipt payload ---------------- */
    private static final short PAYLOAD_LEN = (short) 34;
    private static final byte PAYLOAD_VERSION = (byte) 0x01;

    private static final byte OP_GRANT = (byte) 0x01;
    private static final byte OP_START = (byte) 0x02;

    /* domain tach biet cho chu ky receipt */
    private static final byte[] DOMAIN = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S', '-',
            'R', 'E', 'C', 'E', 'I', 'P', 'T', '-', 'v', '1'
    };
    private static final short DOMAIN_LEN = (short) 20;
    private static final short SIGN_LEN = (short) (DOMAIN_LEN + PAYLOAD_LEN);   /* 54 */

    private static final short CARD_ID_LEN = (short) 8;
    private static final short PUB_LEN = (short) 65;

    /* ---------------- du lieu A (khoi tao) ---------------- */
    private static final short A_BALANCE = (short) 5;
    private static final byte[] A_COUNTER = { 0x00, 0x00, 0x00, 0x01 };
    private static final byte A_HAS_ACTIVE = (byte) 0x00;
    private static final byte[] A_ACTIVE_ID = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    private static final byte[] A_LAST_CLOSED = {
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11,
            0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11
    };
    /* receipt A: counterAfter=1, GRANT, delta=+5, balanceAfter=5, visitId=0 */
    private static final byte A_OP = OP_GRANT;
    private static final short A_DELTA = (short) 5;

    /* ---------------- vung nho ---------------- */
    private final byte[] store = new byte[STORE_LEN];        /* persistent */
    private final byte[] cardId = new byte[CARD_ID_LEN];      /* persistent */
    private final byte[] pubKeyBuf = new byte[PUB_LEN];       /* persistent */

    /* transient staging: payload + chu ky moi, tach khoi receipt active */
    private final byte[] stagePayload;
    private final byte[] stageSig;
    private final byte[] stageSign;      /* DOMAIN || payload */

    /* do luong, ghi de moi lan TRANSACT */
    private final short[] lastTxn;       /* 7 phan tu, persistent cho tien doc */

    private final KeyPair kp;
    /** Buffer 65 byte (04 || Gx || Gy) dung cho setG; cap phat mot lan luc nap. */
    private static final byte[] g65 = new byte[(short) 65];
    private final Signature ecdsa;

    private TransactionProbe() {
        stagePayload = JCSystem.makeTransientByteArray(PAYLOAD_LEN, JCSystem.CLEAR_ON_RESET);
        stageSig = JCSystem.makeTransientByteArray(LEN_SIG_SLOT, JCSystem.CLEAR_ON_RESET);
        stageSign = JCSystem.makeTransientByteArray(SIGN_LEN, JCSystem.CLEAR_ON_RESET);
        lastTxn = new short[7];

        kp = new KeyPair(KeyPair.ALG_EC_FP, (short) 256);
        ecdsa = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);

        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new TransactionProbe();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }

        byte[] buf = apdu.getBuffer();
        byte ins = buf[ISO7816.OFFSET_INS];

        switch (ins) {
            case INS_INIT:
                doInit(apdu, buf);
                return;
            case INS_RESET_A:
                doResetA();
                return;
            case INS_READ_STATE:
                doReadState(apdu, buf);
                return;
            case INS_CAPACITY:
                doCapacity(apdu, buf);
                return;
            case INS_GET_PUBKEY:
                doGetPubKey(apdu, buf);
                return;
            case INS_READ_RECEIPT:
                doReadReceipt(apdu, buf);
                return;
            case INS_READ_STORE:
                doReadStore(apdu, buf);
                return;
            case INS_LAST_TXN:
                doLastTxn(apdu, buf);
                return;
            case INS_TRANSACT:
                doTransact(apdu, buf);
                return;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Khoi tao                                                           */
    /* ------------------------------------------------------------------ */

    private void doInit(APDU apdu, byte[] buf) {
        short len = apdu.setIncomingAndReceive();
        if (len != CARD_ID_LEN) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, cardId, (short) 0, CARD_ID_LEN);

        /*
         * Sinh cap khoa o day, KHONG phai trong constructor: genKeyPair() trong
         * luc INSTALL bi JCRE tu choi (INSTALL tra 6A80).
         */
        buildEcPair();

        /* Xuat khoa cong khai de client/Node.js kiem tra chu ky */
        short n = ((ECPublicKey) kp.getPublic()).getW(pubKeyBuf, (short) 0);
        if (n != PUB_LEN) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }

        /* Ghi du lieu A va receipt A ngoai transaction: day la buoc ca nhan hoa */
        writeDatasetA();
        lastTxn[0] = (short) -1;
        lastTxn[1] = (short) -1;
        lastTxn[2] = (short) -1;
        lastTxn[3] = (short) -1;
        lastTxn[4] = (short) -1;
        lastTxn[5] = PAYLOAD_LEN;
        lastTxn[6] = (short) 0;
    }

    private void doResetA() {
        writeDatasetA();
    }

    /** Ghi bo A: state + receipt A da ky. Dung ngoai transaction (ca nhan hoa). */
    private void writeDatasetA() {
        Util.arrayCopyNonAtomic(shortToBytes(A_BALANCE), (short) 0, store, OFF_BALANCE, LEN_BALANCE);
        Util.arrayCopyNonAtomic(A_COUNTER, (short) 0, store, OFF_COUNTER, LEN_COUNTER);
        store[OFF_HAS_ACTIVE] = A_HAS_ACTIVE;
        Util.arrayCopyNonAtomic(A_ACTIVE_ID, (short) 0, store, OFF_ACTIVE_ID, LEN_VISIT_ID);
        Util.arrayCopyNonAtomic(A_LAST_CLOSED, (short) 0, store, OFF_LAST_CLOSED, LEN_VISIT_ID);

        /* Dung payload A trong staging roi ky */
        buildPayload(A_COUNTER, A_OP, A_DELTA, A_BALANCE, A_ACTIVE_ID);
        short sigLen = signStaged();

        Util.arrayCopyNonAtomic(stagePayload, (short) 0, store, OFF_PAYLOAD, PAYLOAD_LEN);
        Util.arrayCopyNonAtomic(stageSig, (short) 0, store, OFF_SIG, sigLen);
        /* Phan duoi khong dung duoc dien 0 (yeu cau cua de bai) */
        Util.arrayFillNonAtomic(store, (short) (OFF_SIG + sigLen),
                (short) (LEN_SIG_SLOT - sigLen), (byte) 0x00);
        store[OFF_SIG_LEN] = (byte) sigLen;
    }

    /* ------------------------------------------------------------------ */
    /* Doc                                                                */
    /* ------------------------------------------------------------------ */

    private void doReadState(APDU apdu, byte[] buf) {
        short p = 0;
        Util.arrayCopyNonAtomic(cardId, (short) 0, buf, p, CARD_ID_LEN);
        p += CARD_ID_LEN;
        Util.arrayCopyNonAtomic(store, OFF_BALANCE, buf, p, LEN_BALANCE);
        p += LEN_BALANCE;
        Util.arrayCopyNonAtomic(store, OFF_COUNTER, buf, p, LEN_COUNTER);
        p += LEN_COUNTER;
        Util.arrayCopyNonAtomic(store, OFF_HAS_ACTIVE, buf, p, (short) 1);
        p += 1;
        Util.arrayCopyNonAtomic(store, OFF_ACTIVE_ID, buf, p, LEN_VISIT_ID);
        p += LEN_VISIT_ID;
        Util.arrayCopyNonAtomic(store, OFF_LAST_CLOSED, buf, p, LEN_VISIT_ID);
        p += LEN_VISIT_ID;
        apdu.setOutgoingAndSend((short) 0, p);
    }

    private void doCapacity(APDU apdu, byte[] buf) {
        short max = JCSystem.getMaxCommitCapacity();
        short unused = JCSystem.getUnusedCommitCapacity();
        byte depth = JCSystem.getTransactionDepth();

        shortToBytesInto(max, buf, (short) 0);
        shortToBytesInto(unused, buf, (short) 2);
        buf[4] = depth;
        apdu.setOutgoingAndSend((short) 0, (short) 5);
    }

    /**
     * Chi xuat khoa CONG KHAI (P1=1). Khong co nhanh xuat khoa bi mat:
     * khong phep thu nao can, va khong duoc de lo khoa ra khoi the.
     */
    private void doGetPubKey(APDU apdu, byte[] buf) {
        byte p1 = buf[ISO7816.OFFSET_P1];
        if (p1 != (byte) 0x01) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
        short n = ((ECPublicKey) kp.getPublic()).getW(pubKeyBuf, (short) 0);
        if (n != PUB_LEN) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
        Util.arrayCopyNonAtomic(pubKeyBuf, (short) 0, buf, (short) 0, PUB_LEN);
        apdu.setOutgoingAndSend((short) 0, PUB_LEN);
    }

    /** Framing: payloadLen(1) || payload(34) || sigLen(1) || sig(sigLen). */
    private void doReadReceipt(APDU apdu, byte[] buf) {
        byte sigLen = store[OFF_SIG_LEN];
        short p = 0;
        buf[p++] = (byte) PAYLOAD_LEN;
        Util.arrayCopyNonAtomic(store, OFF_PAYLOAD, buf, p, PAYLOAD_LEN);
        p += PAYLOAD_LEN;
        buf[p++] = sigLen;
        if (sigLen > 0) {
            Util.arrayCopyNonAtomic(store, OFF_SIG, buf, p, (short) sigLen);
            p += sigLen;
        }
        apdu.setOutgoingAndSend((short) 0, p);
    }

    private void doReadStore(APDU apdu, byte[] buf) {
        Util.arrayCopyNonAtomic(store, (short) 0, buf, (short) 0, STORE_LEN);
        apdu.setOutgoingAndSend((short) 0, STORE_LEN);
    }

    private void doLastTxn(APDU apdu, byte[] buf) {
        shortToBytesInto(lastTxn[0], buf, (short) 0);   /* unused after writes */
        shortToBytesInto(lastTxn[1], buf, (short) 2);   /* unused after end    */
        buf[4] = (byte) lastTxn[2];                     /* depth inside        */
        buf[5] = (byte) lastTxn[3];                     /* depth after         */
        buf[6] = (byte) lastTxn[4];                     /* scenario            */
        buf[7] = (byte) lastTxn[5];                     /* payload len         */
        buf[8] = (byte) lastTxn[6];                     /* sig len             */
        apdu.setOutgoingAndSend((short) 0, (short) 9);
    }

    /* ------------------------------------------------------------------ */
    /* Transaction                                                        */
    /* ------------------------------------------------------------------ */

    private void doTransact(APDU apdu, byte[] buf) {
        /* Khong bao gio de transaction treo tu APDU truoc */
        if (JCSystem.getTransactionDepth() != (byte) 0) {
            JCSystem.abortTransaction();
        }

        byte scenario = buf[ISO7816.OFFSET_P1];
        short len = apdu.setIncomingAndReceive();
        if (len != (short) 42) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        short d = ISO7816.OFFSET_CDATA;

        /* Payload + chu ky duoc chuan bi TRUOC transaction, trong staging */
        buildPayloadFromApdu(buf, d);
        short sigLen = signStaged();

        if (scenario == SC_ABORT_AFTER_STATE) {
            transactAbortAfterState(buf, d);
            return;   /* khong bao gio toi day */
        }
        if (scenario == SC_ABORT_AFTER_RECEIPT) {
            transactAbortAfterReceipt(buf, d, sigLen);
            return;
        }
        if (scenario == SC_COMMIT_THEN_ERROR) {
            transactCommitThenError(buf, d, sigLen);
            return;
        }
        if (scenario != SC_COMMIT) {
            ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }

        /* --- commit binh thuong --- */
        JCSystem.beginTransaction();
        writeStateFields(buf, d);
        writeReceiptFields(sigLen);
        lastTxn[0] = JCSystem.getUnusedCommitCapacity();
        lastTxn[2] = JCSystem.getTransactionDepth();
        JCSystem.commitTransaction();
        lastTxn[1] = JCSystem.getUnusedCommitCapacity();
        lastTxn[3] = JCSystem.getTransactionDepth();
        lastTxn[4] = scenario;
        lastTxn[5] = PAYLOAD_LEN;
        lastTxn[6] = sigLen;
    }

    /** Ghi state roi abort â€” moi thu phai ve A. */
    private void transactAbortAfterState(byte[] buf, short d) {
        JCSystem.beginTransaction();
        try {
            writeStateFields(buf, d);
            lastTxn[0] = JCSystem.getUnusedCommitCapacity();
            lastTxn[2] = JCSystem.getTransactionDepth();
        } catch (Throwable t) {
            if (JCSystem.getTransactionDepth() != (byte) 0) {
                JCSystem.abortTransaction();
            }
            throw t;
        }
        JCSystem.abortTransaction();
        lastTxn[1] = JCSystem.getUnusedCommitCapacity();
        lastTxn[3] = JCSystem.getTransactionDepth();
        lastTxn[4] = SC_ABORT_AFTER_STATE;
        lastTxn[5] = PAYLOAD_LEN;
        lastTxn[6] = (short) 0;
        ISOException.throwIt((short) 0x6985);
    }

    /** Ghi ca receipt roi abort â€” receipt A phai con nguyen. */
    private void transactAbortAfterReceipt(byte[] buf, short d, short sigLen) {
        JCSystem.beginTransaction();
        try {
            writeStateFields(buf, d);
            writeReceiptFields(sigLen);
            lastTxn[0] = JCSystem.getUnusedCommitCapacity();
            lastTxn[2] = JCSystem.getTransactionDepth();
        } catch (Throwable t) {
            if (JCSystem.getTransactionDepth() != (byte) 0) {
                JCSystem.abortTransaction();
            }
            throw t;
        }
        JCSystem.abortTransaction();
        lastTxn[1] = JCSystem.getUnusedCommitCapacity();
        lastTxn[3] = JCSystem.getTransactionDepth();
        lastTxn[4] = SC_ABORT_AFTER_RECEIPT;
        lastTxn[5] = PAYLOAD_LEN;
        lastTxn[6] = sigLen;
        ISOException.throwIt((short) 0x6985);
    }

    /**
     * Commit thanh cong roi moi tra loi loi â€” mo phong mat response sau commit.
     * KHONG phai bang chung chiu mat dien that.
     */
    private void transactCommitThenError(byte[] buf, short d, short sigLen) {
        JCSystem.beginTransaction();
        writeStateFields(buf, d);
        writeReceiptFields(sigLen);
        lastTxn[0] = JCSystem.getUnusedCommitCapacity();
        lastTxn[2] = JCSystem.getTransactionDepth();
        JCSystem.commitTransaction();
        lastTxn[1] = JCSystem.getUnusedCommitCapacity();
        lastTxn[3] = JCSystem.getTransactionDepth();
        lastTxn[4] = SC_COMMIT_THEN_ERROR;
        lastTxn[5] = PAYLOAD_LEN;
        lastTxn[6] = sigLen;
        ISOException.throwIt((short) 0x6581);
    }

    /** Ghi 5 truong state. Chi goi trong transaction. */
    private void writeStateFields(byte[] buf, short d) {
        /* balance(2), counter(4), hasActive(1), activeVisitId(16), lastClosed(16) */
        Util.arrayCopy(buf, d, store, OFF_BALANCE, LEN_BALANCE);
        Util.arrayCopy(buf, (short) (d + 2), store, OFF_COUNTER, LEN_COUNTER);
        Util.arrayCopy(buf, (short) (d + 6), store, OFF_HAS_ACTIVE, (short) 1);
        Util.arrayCopy(buf, (short) (d + 7), store, OFF_ACTIVE_ID, LEN_VISIT_ID);
        Util.arrayCopy(buf, (short) (d + 23), store, OFF_LAST_CLOSED, LEN_VISIT_ID);
    }

    /**
     * Ghi receipt payload + chuyen chu ky tu staging vao slot 72 byte.
     * Ghi DU ca slot 72 byte (phan duoi don 0) de phep thu suc chua la that.
     * Chi goi trong transaction.
     */
    private void writeReceiptFields(short sigLen) {
        Util.arrayCopy(stagePayload, (short) 0, store, OFF_PAYLOAD, PAYLOAD_LEN);
        Util.arrayCopy(stageSig, (short) 0, store, OFF_SIG, sigLen);
        Util.arrayFill(store, (short) (OFF_SIG + sigLen),
                (short) (LEN_SIG_SLOT - sigLen), (byte) 0x00);
        store[OFF_SIG_LEN] = (byte) sigLen;
    }

    /* ------------------------------------------------------------------ */
    /* Payload + chu ky                                                   */
    /* ------------------------------------------------------------------ */

    private void buildPayloadFromApdu(byte[] buf, short d) {
        /* balance(2) counter(4) hasActive(1) activeVisitId(16) lastClosed(16) op(1) delta(2) */
        byte op = buf[(short) (d + 39)];
        short delta = (short) (((buf[(short) (d + 40)] & 0xFF) << 8) | (buf[(short) (d + 41)] & 0xFF));
        short balance = (short) (((buf[d] & 0xFF) << 8) | (buf[(short) (d + 1)] & 0xFF));

        short visitOff;
        if (op == OP_GRANT) {
            /* GRANT dung sentinel visitId rong */
            visitOff = -1;
        } else {
            visitOff = (short) (d + 7);
        }
        buildPayloadFrom(buf, (short) (d + 2), op, delta, balance,
                (visitOff < 0) ? null : buf, visitOff);
    }

    private void buildPayload(byte[] counter, byte op, short delta, short balance, byte[] visitId) {
        buildPayloadFrom(counter, (short) 0, op, delta, balance, visitId, (short) 0);
    }

    /** Dung payload canonical 34 byte trong staging. */
    private void buildPayloadFrom(byte[] src, short counterOff, byte op, short delta,
            short balance, byte[] visitSrc, short visitOff) {
        stagePayload[0] = PAYLOAD_VERSION;
        Util.arrayCopyNonAtomic(cardId, (short) 0, stagePayload, (short) 1, CARD_ID_LEN);
        Util.arrayCopyNonAtomic(src, counterOff, stagePayload, (short) 9, LEN_COUNTER);
        stagePayload[13] = op;
        stagePayload[14] = (byte) (delta >> 8);
        stagePayload[15] = (byte) delta;
        stagePayload[16] = (byte) (balance >> 8);
        stagePayload[17] = (byte) balance;
        if (visitSrc == null) {
            Util.arrayFillNonAtomic(stagePayload, (short) 18, LEN_VISIT_ID, (byte) 0x00);
        } else {
            Util.arrayCopyNonAtomic(visitSrc, visitOff, stagePayload, (short) 18, LEN_VISIT_ID);
        }
    }

    /** Ky DOMAIN || payload, tra ve do dai DER. Chay ngoai transaction. */
    private short signStaged() {
        Util.arrayCopyNonAtomic(DOMAIN, (short) 0, stageSign, (short) 0, DOMAIN_LEN);
        Util.arrayCopyNonAtomic(stagePayload, (short) 0, stageSign, DOMAIN_LEN, PAYLOAD_LEN);
        ecdsa.init(kp.getPrivate(), Signature.MODE_SIGN);
        short n = ecdsa.sign(stageSign, (short) 0, SIGN_LEN, stageSig, (short) 0);
        if (n <= 0 || n > LEN_SIG_SLOT) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
        return n;
    }

    /* ------------------------------------------------------------------ */
    /* Tham so duong cong P-256 (giong CryptoProbe da kiem chung)         */
    /* ------------------------------------------------------------------ */

    private static final byte[] P256_P = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF
    };

    private static final byte[] P256_A = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFC
    };

    private static final byte[] P256_B = {
            0x5A, (byte) 0xC6, 0x35, (byte) 0xD8, (byte) 0xAA, 0x3A, (byte) 0x93, (byte) 0xE7,
            (byte) 0xB3, (byte) 0xEB, (byte) 0xBD, 0x55, 0x76, (byte) 0x98, (byte) 0x86,
            (byte) 0xBC, 0x65, 0x1D, 0x06, (byte) 0xB0, (byte) 0xCC, 0x53, (byte) 0xB0,
            (byte) 0xF6, 0x3B, (byte) 0xCE, 0x3C, 0x3E, 0x27, (byte) 0xD2, 0x60, 0x4B
    };

    /**
     * G = Gx || Gy : 64 byte, KHONG co tien to.
     * Gx = 6B17D1F2 E12C4247 F8BCE6E5 63A440F2 77037D81 2DEB33A0 F4A13945 D898C296
     * Gy = 4FE342E2 FE1A7F9B 8EE7EB4A 7C0F9E16 2BCE3357 6B315ECE CBB64068 37BF51F5
     * (Doi chieu byte-exact voi CryptoProbe da chay duoc o G0-13.)
     */
    private static final byte[] P256_G = {
            /* Gx */
            0x6B, 0x17, (byte) 0xD1, (byte) 0xF2, (byte) 0xE1, 0x2C, 0x42, 0x47,
            (byte) 0xF8, (byte) 0xBC, (byte) 0xE6, (byte) 0xE5, 0x63, (byte) 0xA4, 0x40, (byte) 0xF2,
            0x77, 0x03, 0x7D, (byte) 0x81, 0x2D, (byte) 0xEB, 0x33, (byte) 0xA0,
            (byte) 0xF4, (byte) 0xA1, 0x39, 0x45, (byte) 0xD8, (byte) 0x98, (byte) 0xC2, (byte) 0x96,
            /* Gy */
            0x4F, (byte) 0xE3, 0x42, (byte) 0xE2, (byte) 0xFE, 0x1A, 0x7F, (byte) 0x9B,
            (byte) 0x8E, (byte) 0xE7, (byte) 0xEB, 0x4A, 0x7C, 0x0F, (byte) 0x9E, 0x16,
            0x2B, (byte) 0xCE, 0x33, 0x57, 0x6B, 0x31, 0x5E, (byte) 0xCE,
            (byte) 0xCB, (byte) 0xB6, 0x40, 0x68, 0x37, (byte) 0xBF, 0x51, (byte) 0xF5
    };

    private static final byte[] P256_R = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xBC, (byte) 0xE6, (byte) 0xFA, (byte) 0xAD,
            (byte) 0xA7, 0x17, (byte) 0x9E, (byte) 0x84, (byte) 0xF3, (byte) 0xB9, (byte) 0xCA,
            (byte) 0xC2, (byte) 0xFC, 0x63, 0x25, 0x51
    };

    /** setG voi 65 byte co tien to 0x04, dat cho CA pub va priv (da kiem chung o G0-13). */
    private void buildEcPair() {
        setParams((ECKey) kp.getPublic());
        setParams((ECKey) kp.getPrivate());
        kp.genKeyPair();
    }

    /**
     * Thu tu da kiem chung o G0-13 (CryptoProbe bien the 5):
     * setFieldFP -> setA -> setB -> setG(65B co tien to 0x04) -> setR.
     * KHONG goi setK: setK lam genKeyPair nem CryptoException ILLEGAL_VALUE tren SDK 26.0.
     */
    private static void setParams(ECKey k) {
        k.setFieldFP(P256_P, (short) 0, (short) 32);
        k.setA(P256_A, (short) 0, (short) 32);
        k.setB(P256_B, (short) 0, (short) 32);
        /* setG BAT BUOC 65 byte co tien to 0x04, khong phai 64 byte X||Y */
        g65[0] = (byte) 0x04;
        Util.arrayCopyNonAtomic(P256_G, (short) 0, g65, (short) 1, (short) 64);
        k.setG(g65, (short) 0, (short) 65);
        k.setR(P256_R, (short) 0, (short) 32);
    }

    /* ------------------------------------------------------------------ */
    /* Tien ich                                                           */
    /* ------------------------------------------------------------------ */

    private static final byte[] SCRATCH = new byte[4];

    private static byte[] shortToBytes(short v) {
        SCRATCH[0] = (byte) (v >> 8);
        SCRATCH[1] = (byte) v;
        return SCRATCH;
    }

    private static void shortToBytesInto(short v, byte[] out, short off) {
        out[off] = (byte) (v >> 8);
        out[(short) (off + 1)] = (byte) v;
    }
}
