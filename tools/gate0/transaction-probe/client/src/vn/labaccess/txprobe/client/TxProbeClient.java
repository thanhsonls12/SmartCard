/*
 * Gate 0 — G0-15 client: 5 phep thu transaction.
 *
 * Chay tren simulator 127.0.0.1:9025. Xuat artifact de Node.js kiem chu ky.
 *
 * Nguyen tac:
 *  - Moi phep thu bat dau tu du lieu A (goi RESET_A), tru phep thu 1.
 *  - Sau moi phep thu, doc lai TOAN BO layout 146 byte va so voi A/B.
 *  - Sau moi phep thu, doc transaction depth: phai bang 0 (khong treo transaction).
 */

package vn.labaccess.txprobe.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

public final class TxProbeClient {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte INS_INIT = (byte) 0x10;
    private static final byte INS_RESET_A = (byte) 0x11;
    private static final byte INS_READ_STATE = (byte) 0x12;
    private static final byte INS_CAPACITY = (byte) 0x14;
    private static final byte INS_GET_PUBKEY = (byte) 0x16;
    private static final byte INS_READ_RECEIPT = (byte) 0x18;
    private static final byte INS_READ_STORE = (byte) 0x1A;
    private static final byte INS_LAST_TXN = (byte) 0x1C;
    private static final byte INS_TRANSACT = (byte) 0x30;

    private static final byte[] AID = HEX.parseHex("F04C4142470101");
    private static final byte[] CARD_ID = HEX.parseHex("4C41423030303031");   /* "LAB00001" */

    /* Bo B: co y khac A o moi truong */
    private static final short B_BALANCE = (short) 4;
    private static final byte[] B_COUNTER = { 0x00, 0x00, 0x00, 0x02 };
    private static final byte B_HAS_ACTIVE = (byte) 0x01;
    private static final byte[] B_ACTIVE_ID = HEX.parseHex("A1A2A3A4A5A6A7A8A9AAABACADAEAFB0");
    private static final byte[] B_LAST_CLOSED = HEX.parseHex("B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0");
    private static final byte B_OP = (byte) 0x02;      /* START_VISIT */
    private static final short B_DELTA = (short) -1;

    /* Bo A phai khop hang so trong applet */
    private static final short A_BALANCE = (short) 5;
    private static final byte[] A_COUNTER = { 0x00, 0x00, 0x00, 0x01 };
    private static final byte A_HAS_ACTIVE = (byte) 0x00;
    private static final byte[] A_ACTIVE_ID = new byte[16];
    private static final byte[] A_LAST_CLOSED = HEX.parseHex("11111111111111111111111111111111");

    private static final int STORE_LEN = 146;

    private static final List<String> results = new ArrayList<>();
    private static int failures;

    private static CardChannel channel;

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 9025;
        String artifactDir = "D:\\SmartCard\\artifacts\\tx-probe";
        for (String a : args) {
            if (a.startsWith("-host=")) {
                String[] p = a.substring(6).split(":");
                host = p[0];
                port = Integer.parseInt(p[1]);
            } else if (a.startsWith("-out=")) {
                artifactDir = a.substring(5);
            }
        }

        Card card = null;
        try {
            CardTerminal terminal = TerminalFactory.getInstance(
                    "SocketCardTerminalFactoryType",
                    List.of(new InetSocketAddress(host, port)),
                    "SocketCardTerminalProvider").terminals().list().get(0);
            if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
                throw new IllegalStateException("Khong co the");
            }
            card = terminal.connect("*");
            channel = card.getBasicChannel();
            System.out.println("Connection to simulator established: " + terminal.getName());
            System.out.println("ATR: " + HEX.formatHex(card.getATR().getBytes()));
            System.out.println();

            ResponseAPDU sel = channel.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, AID, 256));
            System.out.printf("SELECT %s -> SW=%04X%n%n", HEX.formatHex(AID), sel.getSW());
            if (sel.getSW() != 0x9000) {
                throw new IllegalStateException("SELECT that bai");
            }

            /* ---- khoi tao ---- */
            ResponseAPDU init = channel.transmit(new CommandAPDU(0x80, INS_INIT, 0, 0, CARD_ID));
            System.out.printf("INIT cardId=%s -> SW=%04X%n", HEX.formatHex(CARD_ID), init.getSW());
            if (init.getSW() != 0x9000) {
                throw new IllegalStateException("INIT that bai");
            }

            byte[] pub = transmit(INS_GET_PUBKEY, 1, 0, new byte[0]).getData();
            System.out.println("PUBKEY = " + HEX.formatHex(pub));
            System.out.println();

            /* ---- capacity truoc moi transaction ---- */
            byte[] cap0 = transmit(INS_CAPACITY, 0, 0, new byte[0]).getData();
            int maxCap = u16(cap0, 0);
            int unused0 = u16(cap0, 2);
            System.out.printf("CAPACITY: maxCommitCapacity=%d unusedCommitCapacity=%d depth=%d%n%n",
                    maxCap, unused0, cap0[4] & 0xFF);

            byte[] snapshotA = readStore();
            byte[] receiptA = readReceipt();
            System.out.println("SNAPSHOT A (146B) = " + HEX.formatHex(snapshotA));
            System.out.println("RECEIPT  A        = " + HEX.formatHex(receiptA));
            System.out.println();

            Path out = Path.of(artifactDir);
            Files.createDirectories(out);
            Files.writeString(out.resolve("pubkey.hex"), HEX.formatHex(pub) + "\n");
            Files.writeString(out.resolve("receipt-A.json"), receiptJson("A", receiptA, pub));
            Files.writeString(out.resolve("snapshot-A.hex"), HEX.formatHex(snapshotA) + "\n");

            /* ================= 1. commit binh thuong ================= */
            System.out.println("======== 1. Commit binh thuong ========");
            ResponseAPDU r1 = transmit(INS_TRANSACT, 0x01, 0, txData());
            byte[] last1 = transmit(INS_LAST_TXN, 0, 0, new byte[0]).getData();
            byte[] store1 = readStore();
            byte[] receipt1 = readReceipt();
            System.out.printf("TRANSACT(commit) -> SW=%04X%n", r1.getSW());
            printTxn(last1, unused0);
            System.out.println("SNAPSHOT sau commit = " + HEX.formatHex(store1));
            System.out.println("RECEIPT  sau commit = " + HEX.formatHex(receipt1));
            check("1. Commit binh thuong", r1.getSW() == 0x9000
                    && matchesB(store1)
                    && receiptMatches(receipt1, B_COUNTER, B_OP, B_DELTA, B_BALANCE, B_ACTIVE_ID)
                    && depthZero(),
                    "state+receipt = B, depth=0");
            Files.writeString(out.resolve("receipt-B.json"), receiptJson("B", receipt1, pub));
            Files.writeString(out.resolve("snapshot-B.hex"), HEX.formatHex(store1) + "\n");
            System.out.println();

            /* ================= 2. abort sau state ================= */
            System.out.println("======== 2. Abort sau khi ghi state, truoc receipt ========");
            /*
             * ECDSA la xac suat: RESET_A ky lai receipt A nen chu ky khac lan truoc.
             * Vi vay phai chup snapshot NGAY SAU reset lam moc so sanh.
             */
            resetA();
            byte[] snapA2 = readStore();
            ResponseAPDU r2 = transmit(INS_TRANSACT, 0x02, 0, txData());
            byte[] last2 = transmit(INS_LAST_TXN, 0, 0, new byte[0]).getData();
            byte[] store2 = readStore();
            System.out.printf("TRANSACT(abort sau state) -> SW=%04X%n", r2.getSW());
            printTxn(last2, unused0);
            System.out.println("MOC A (sau reset)  = " + HEX.formatHex(snapA2));
            System.out.println("SNAPSHOT sau abort = " + HEX.formatHex(store2));
            check("2. Abort sau khi ghi state -> ve A", r2.getSW() == 0x6985
                    && Arrays.equals(store2, snapA2)
                    && depthZero(),
                    "toan bo 146 byte bang moc A, depth=0");
            System.out.println();

            /* ================= 3. abort sau receipt ================= */
            System.out.println("======== 3. Abort sau khi ghi receipt, truoc commit ========");
            resetA();
            byte[] snapA3 = readStore();
            byte[] recA3 = readReceipt();
            ResponseAPDU r3 = transmit(INS_TRANSACT, 0x03, 0, txData());
            byte[] last3 = transmit(INS_LAST_TXN, 0, 0, new byte[0]).getData();
            byte[] store3 = readStore();
            byte[] receipt3 = readReceipt();
            System.out.printf("TRANSACT(abort sau receipt) -> SW=%04X%n", r3.getSW());
            printTxn(last3, unused0);
            System.out.println("MOC A (sau reset)  = " + HEX.formatHex(snapA3));
            System.out.println("SNAPSHOT sau abort = " + HEX.formatHex(store3));
            System.out.println("RECEIPT  sau abort = " + HEX.formatHex(receipt3));
            check("3. Abort sau khi ghi receipt -> ve A", r3.getSW() == 0x6985
                    && Arrays.equals(store3, snapA3)
                    && Arrays.equals(receipt3, recA3)
                    && depthZero(),
                    "toan bo 146 byte bang moc A, receipt A con nguyen (verify lai duoc), depth=0");
            Files.writeString(out.resolve("receipt-A-after-abort.json"), receiptJson("A_after_abort", receipt3, pub));
            System.out.println();

            /* ================= 4. commit roi tra loi loi ================= */
            System.out.println("======== 4. Loi sau commit, truoc response thanh cong ========");
            resetA();
            ResponseAPDU r4 = transmit(INS_TRANSACT, 0x04, 0, txData());
            byte[] last4 = transmit(INS_LAST_TXN, 0, 0, new byte[0]).getData();
            byte[] store4 = readStore();
            byte[] receipt4 = readReceipt();
            System.out.printf("TRANSACT(commit roi loi) -> SW=%04X (client thay LOI)%n", r4.getSW());
            printTxn(last4, unused0);
            System.out.println("SNAPSHOT doc lai = " + HEX.formatHex(store4));
            System.out.println("RECEIPT  doc lai = " + HEX.formatHex(receipt4));
            check("4. Loi sau commit -> du lieu la B (can doi soat)", r4.getSW() == 0x6581
                    && matchesB(store4)
                    && receiptMatches(receipt4, B_COUNTER, B_OP, B_DELTA, B_BALANCE, B_ACTIVE_ID)
                    && depthZero(),
                    "SW loi nhung state+receipt da la B, depth=0");
            Files.writeString(out.resolve("receipt-B-after-error.json"), receiptJson("B_error", receipt4, pub));
            System.out.println();

            /* ================= 5. doc receipt mot short APDU ================= */
            System.out.println("======== 5. Doc receipt bang mot short APDU ========");
            ResponseAPDU rr = transmit(INS_READ_RECEIPT, 0, 0, new byte[0]);
            byte[] raw = rr.getBytes();
            int expectedMax = 1 + 34 + 1 + 72 + 2;   /* framing + payload + sigLen + sig72 + SW */
            System.out.printf("Response day du = %d byte (data %d + SW 2), gioi han 256%n",
                    raw.length, rr.getData().length);
            System.out.println("RAW = " + HEX.formatHex(raw));
            System.out.printf("Chieu dai lon nhat theo layout (sig 72B) = %d byte data + 2 SW = %d%n",
                    1 + 34 + 1 + 72, 1 + 34 + 1 + 72 + 2);
            check("5. Doc receipt trong mot short APDU", rr.getSW() == 0x9000
                    && raw.length <= 256
                    && rr.getData().length == 1 + 34 + 1 + (rr.getData()[35] & 0xFF)
                    && expectedMax <= 256,
                    "framing+payload+signature du, " + raw.length + " byte <= 256");

            Files.writeString(out.resolve("receipt-final.json"), receiptJson("final", rr.getData(), pub));
            Files.writeString(out.resolve("capacity.txt"),
                    "maxCommitCapacity=" + maxCap + "\nunusedBefore=" + unused0 + "\n");

            /* ---- ket qua ---- */
            System.out.println();
            System.out.println("================ KET QUA ================");
            for (String s : results) {
                System.out.println(s);
            }
            System.out.println("========================================");
            System.out.println(failures == 0 ? "G0-15: PASS" : "G0-15: FAIL (" + failures + ")");
            System.out.println("========================================");

        } finally {
            if (card != null) {
                try {
                    card.disconnect(true);
                } catch (Exception ignored) {
                    /* don dep */
                }
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /* ------------------------------------------------------------------ */

    private static ResponseAPDU transmit(byte ins, int p1, int p2, byte[] data) throws Exception {
        return channel.transmit(new CommandAPDU(0x80, ins, p1, p2, data, 256));
    }

    private static void resetA() throws Exception {
        ResponseAPDU r = transmit(INS_RESET_A, 0, 0, new byte[0]);
        if (r.getSW() != 0x9000) {
            throw new IllegalStateException(String.format("RESET_A SW=%04X", r.getSW()));
        }
    }

    private static byte[] readStore() throws Exception {
        byte[] d = transmit(INS_READ_STORE, 0, 0, new byte[0]).getData();
        if (d.length != STORE_LEN) {
            throw new IllegalStateException("READ_STORE do dai " + d.length);
        }
        return d;
    }

    private static byte[] readReceipt() throws Exception {
        return transmit(INS_READ_RECEIPT, 0, 0, new byte[0]).getData();
    }

    private static byte[] txData() {
        byte[] d = new byte[42];
        d[0] = (byte) (B_BALANCE >> 8);
        d[1] = (byte) B_BALANCE;
        System.arraycopy(B_COUNTER, 0, d, 2, 4);
        d[6] = B_HAS_ACTIVE;
        System.arraycopy(B_ACTIVE_ID, 0, d, 7, 16);
        System.arraycopy(B_LAST_CLOSED, 0, d, 23, 16);
        d[39] = B_OP;
        d[40] = (byte) (B_DELTA >> 8);
        d[41] = (byte) B_DELTA;
        return d;
    }

    private static boolean matchesB(byte[] store) {
        boolean ok = true;
        ok &= u16(store, 0) == B_BALANCE;
        ok &= Arrays.equals(Arrays.copyOfRange(store, 2, 6), B_COUNTER);
        ok &= store[6] == B_HAS_ACTIVE;
        ok &= Arrays.equals(Arrays.copyOfRange(store, 7, 23), B_ACTIVE_ID);
        ok &= Arrays.equals(Arrays.copyOfRange(store, 23, 39), B_LAST_CLOSED);
        return ok;
    }

    /** Kiem payload 34 byte: version=1, cardId, counter, op, delta, balance, visitId. */
    private static boolean receiptMatches(byte[] receipt, byte[] counter, byte op, short delta,
            short balance, byte[] visitId) {
        if (receipt.length < 1 + 34 + 1) {
            return false;
        }
        int payloadLen = receipt[0] & 0xFF;
        if (payloadLen != 34) {
            return false;
        }
        byte[] p = Arrays.copyOfRange(receipt, 1, 35);
        boolean ok = true;
        ok &= p[0] == 0x01;
        ok &= Arrays.equals(Arrays.copyOfRange(p, 1, 9), CARD_ID);
        ok &= Arrays.equals(Arrays.copyOfRange(p, 9, 13), counter);
        ok &= p[13] == op;
        ok &= (short) (((p[14] & 0xFF) << 8) | (p[15] & 0xFF)) == delta;
        ok &= (short) (((p[16] & 0xFF) << 8) | (p[17] & 0xFF)) == balance;
        ok &= Arrays.equals(Arrays.copyOfRange(p, 18, 34), visitId);
        ok &= receipt[35] > 0;   /* co chu ky */
        return ok;
    }

    private static boolean depthZero() throws Exception {
        byte[] cap = transmit(INS_CAPACITY, 0, 0, new byte[0]).getData();
        return cap[4] == 0;
    }

    private static void printTxn(byte[] last, int unusedBefore) {
        int unusedAfterWrites = u16(last, 0);
        int unusedAfterEnd = u16(last, 2);
        int depthInside = last[4] & 0xFF;
        int depthAfter = last[5] & 0xFF;
        int scenario = last[6] & 0xFF;
        int payloadLen = last[7] & 0xFF;
        int sigLen = last[8] & 0xFF;
        System.out.printf("  kich ban=%d payloadLen=%d sigLen=%d%n", scenario, payloadLen, sigLen);
        System.out.printf("  depth trong transaction=%d, sau khi xong=%d%n", depthInside, depthAfter);
        System.out.printf("  unused commit TRUOC khi ghi = %d%n", unusedBefore);
        System.out.printf("  unused commit SAU khi ghi (trong transaction) = %d%n", unusedAfterWrites);
        System.out.printf("  unused commit SAU commit/abort = %d%n", unusedAfterEnd);
        System.out.printf("  byte da dung trong transaction = %d%n", unusedBefore - unusedAfterWrites);
    }

    private static String receiptJson(String label, byte[] receipt, byte[] pub) {
        int payloadLen = receipt[0] & 0xFF;
        int off = 1 + payloadLen;
        int sigLen = (off < receipt.length) ? (receipt[off] & 0xFF) : 0;
        byte[] payload = Arrays.copyOfRange(receipt, 1, 1 + payloadLen);
        byte[] sig = (sigLen > 0)
                ? Arrays.copyOfRange(receipt, off + 1, off + 1 + sigLen)
                : new byte[0];
        return "{\n"
                + "  \"label\": \"" + label + "\",\n"
                + "  \"domain\": \"LABACCESS-RECEIPT-v1\",\n"
                + "  \"cardIdHex\": \"" + HEX.formatHex(CARD_ID) + "\",\n"
                + "  \"payloadHex\": \"" + HEX.formatHex(payload) + "\",\n"
                + "  \"signatureDerHex\": \"" + HEX.formatHex(sig) + "\",\n"
                + "  \"publicKeyHex\": \"" + HEX.formatHex(pub) + "\",\n"
                + "  \"payloadLength\": " + payloadLen + ",\n"
                + "  \"signatureLength\": " + sigLen + "\n"
                + "}\n";
    }

    private static void check(String name, boolean ok, String detail) {
        String line = String.format("%s | %-48s | %s", ok ? "PASS" : "FAIL", name, detail);
        results.add(line);
        System.out.println(line);
        if (!ok) {
            failures++;
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
