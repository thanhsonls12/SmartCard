/*
 * LabAccess — Gate 0 crypto probe client (dong lenh).
 *
 * Gui APDU toi Applet probe F04C4142470001 va kiem chung:
 *   - AES-128-GCM: round-trip + 4 test am (ciphertext, tag, nonce, AAD)
 *   - ECDSA P-256 / SHA-256: xuat public key + chu ky ra artifact cho Node.js
 *
 * Khong in khoa bi mat (Applet khong bao gio tra khoa ra).
 */
package vn.labaccess.probe.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

public final class ProbeClient {

    /* ---- INS theo tai lieu probe ---- */
    static final int INS_INIT = 0x10;
    static final int INS_GCM_ENCRYPT = 0x12;
    static final int INS_GCM_DECRYPT = 0x14;
    static final int INS_GCM_INFO = 0x16;
    static final int INS_GET_PUBKEY = 0x20;
    static final int INS_SIGN = 0x22;
    static final int INS_LAST_ERROR = 0x30;

    static final byte[] APPLET_AID = HexFormat.of().parseHex("F04C4142470001");
    static final byte[] GCM_AAD = "LABACCESS-G0-AES-v1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PLAINTEXT = "LabAccess G0 probe plaintext 001".getBytes(StandardCharsets.US_ASCII);
    static final byte[] MESSAGE = "LABACCESS-G0-ECDSA-message-v1".getBytes(StandardCharsets.US_ASCII);

    static final int NONCE_LEN = 12;
    static final int TAG_LEN = 16;

    static final HexFormat HEX = HexFormat.of();

    private final List<String> rows = new ArrayList<>();
    private CardChannel ch;
    private int lastError;

    public static void main(String[] args) {
        int exit;
        ProbeClient client = new ProbeClient();
        try {
            exit = client.run(args) ? 0 : 1;
        } catch (Exception e) {
            System.out.println("LOI: " + e);
            e.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private boolean run(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 9025;
        for (String a : args) {
            if (a.startsWith("-host=")) {
                String[] p = a.substring(6).split(":");
                host = p[0];
                port = Integer.parseInt(p[1]);
            }
        }

        CardTerminal terminal = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(host, port)),
                "SocketCardTerminalProvider").terminals().list().get(0);

        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10000)) {
            throw new IllegalStateException("Khong co the tren " + terminal.getName());
        }

        Card card = terminal.connect("*");
        try {
            ch = card.getBasicChannel();
            System.out.println("Connection to simulator established: " + terminal.getName());
            System.out.println("ATR: " + HEX.formatHex(card.getATR().getBytes()));

            ResponseAPDU sel = ch.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, APPLET_AID, 256));
            System.out.printf("SELECT %s -> SW=%04X%n", HEX.formatHex(APPLET_AID), sel.getSW());
            if (sel.getSW() != 0x9000) {
                return false;
            }

            boolean inited = doInit();
            boolean gcmOk = inited && gcmReady && runGcmTests();
            boolean ecdsaOk = inited && ecReady && runEcdsaTests();

            if (!gcmReady) {
                row("GCM kha dung", false, String.format("buoc %d, CryptoException reason=%d", gcmStep, gcmErr));
            }
            if (!ecReady) {
                row("ECDSA kha dung", false, String.format("buoc %d, CryptoException reason=%d", ecStep, ecErr));
            }

            System.out.println();
            System.out.println("================ KET QUA ================");
            for (String r : rows) {
                System.out.println(r);
            }
            System.out.println("========================================");
            System.out.println("GCM  : " + (gcmOk ? "PASS" : "FAIL"));
            System.out.println("ECDSA: " + (ecdsaOk ? "PASS" : "FAIL"));
            return gcmOk && ecdsaOk;

        } finally {
            card.disconnect(true);
        }
    }

    /* ==================== INIT ==================== */

    private boolean gcmReady;
    private boolean ecReady;
    private int ecPath;
    private int gcmStep;
    private int gcmErr;
    private int ecStep;
    private int ecErr;

    private boolean doInit() throws CardException {
        ResponseAPDU init = ch.transmit(new CommandAPDU(0x00, INS_INIT, 0x00, 0x00));
        byte[] d = init.getData();
        System.out.printf("INIT SW=%04X data=%s%n", init.getSW(), HEX.formatHex(d));
        if (init.getSW() != 0x9000 || d.length < 7) {
            return false;
        }
        int flags = d[0] & 0xFF;
        gcmReady = (flags & 0x01) != 0;
        ecReady = (flags & 0x02) != 0;
        gcmStep = d[1] & 0xFF;
        gcmErr = d[2] & 0xFF;
        ecStep = d[3] & 0xFF;
        ecErr = d[4] & 0xFF;
        ecPath = d.length > 10 ? (d[10] & 0xFF) : 0;
        int curveId = (d[5] & 0xFF) | ((d[6] & 0xFF) << 8);
        int keyBits = d.length > 7 ? (d[7] & 0xFF) : -1;
        int tagLen = d.length > 8 ? (d[8] & 0xFF) : -1;
        int nonceLen = d.length > 9 ? (d[9] & 0xFF) : -1;

        System.out.printf("  gcmReady=%b (step=%d, reason=%d) | ecReady=%b (step=%d, reason=%d)%n",
                gcmReady, gcmStep, gcmErr, ecReady, ecStep, ecErr);
        System.out.printf("  curveId=%d keyBits=%d tagLen=%d nonceLen=%d ecPath=%d%n",
                curveId, keyBits, tagLen, nonceLen, ecPath);

        /* chan doan chi tiet tung lan thu dat tham so EC */
        if (d.length > 12) {
            int tries = d[11] & 0xFF;
            int repLen = d[12] & 0xFF;
            System.out.printf("  so lan thu EC=%d, so byte chan doan=%d%n", tries, repLen);
            for (int k = 0; k + 2 < repLen; k += 3) {
                System.out.printf("    bien the %d: buoc %d -> CryptoException reason=%d%n",
                        d[13 + k] & 0xFF, d[14 + k] & 0xFF, d[15 + k] & 0xFF);
            }
        }
        return true;
    }

    /* ==================== AES-GCM ==================== */

    private boolean runGcmTests() throws CardException {
        ResponseAPDU info = ch.transmit(new CommandAPDU(0x00, INS_GCM_INFO, 0x00, 0x00));
        byte[] i = info.getData();
        if (i.length >= 6) {
            System.out.printf("INFO: ready=%d cipherAlg=%d keyBytes=%d tagLen=%d nonceLen=%d lastErr=%d%n",
                    i[0], i[1], i[2], i[3], i[4], i[5]);
        }

        ResponseAPDU enc = ch.transmit(new CommandAPDU(0x00, INS_GCM_ENCRYPT, 0x00, 0x00, PLAINTEXT));
        if (enc.getSW() != 0x9000) {
            readLastError();
            row("GCM encrypt", false, String.format("SW=%04X reason=%d", enc.getSW(), lastError));
            return false;
        }
        byte[] blob = enc.getData();
        System.out.println("ENCRYPT: nonce||ct||tag = " + HEX.formatHex(blob));
        System.out.println("  nonce      = " + HEX.formatHex(slice(blob, 0, NONCE_LEN)));
        System.out.println("  ciphertext = " + HEX.formatHex(slice(blob, NONCE_LEN, blob.length - TAG_LEN)));
        System.out.println("  tag        = " + HEX.formatHex(slice(blob, blob.length - TAG_LEN, blob.length)));

        /* Chon bien the thu tu goi API nao hoat dong tren nen tang nay */
        int path = 0;
        for (int p1 = 1; p1 <= 5; p1++) {
            ResponseAPDU r = decrypt(blob, p1, 0x00);
            boolean okp = r.getSW() == 0x9000 && equal(r.getData(), PLAINTEXT);
            System.out.printf("  thu P1=%d -> SW=%04X, so byte=%d, %s%n",
                    p1, r.getSW(), r.getData().length, okp ? "KHOP plaintext" : "khong khop");
            if (okp) {
                path = p1;
                break;
            }
            readLastError();
            System.out.printf("     CryptoException reason=%d%n", lastError);
        }
        System.out.println("Bien the decrypt dung tren nen tang nay: P1=" + path);
        row("GCM decrypt bien the P1=" + path, path != 0, path == 0
                ? "ca 5 bien the deu khong giai ma duoc"
                : "chon P1=" + path);

        if (path == 0) {
            readLastError();
            row("GCM round-trip", false, "khong co duong decrypt nao hoat dong, reason=" + lastError);
            return false;
        }

        /* T1: round-trip dung tung byte */
        ResponseAPDU ok = decrypt(blob, path, 0x00);
        boolean t1 = ok.getSW() == 0x9000 && equal(ok.getData(), PLAINTEXT);
        row("GCM round-trip tra dung tung byte", t1,
                t1 ? "plaintext khop (" + new String(ok.getData(), StandardCharsets.US_ASCII) + ")"
                        : String.format("SW=%04X data=%s", ok.getSW(), HEX.formatHex(ok.getData())));

        byte[] bad = blob.clone();
        bad[NONCE_LEN + 3] ^= 0x01;
        t2("Sua 1 byte ciphertext -> bi tu choi", bad, path);

        byte[] badTag = blob.clone();
        badTag[blob.length - 1] ^= 0x01;
        t2("Sua 1 byte tag -> bi tu choi", badTag, path);

        byte[] badNonce = blob.clone();
        badNonce[0] ^= 0x01;
        t2("Sua 1 byte nonce -> bi tu choi", badNonce, path);

        /* T5: AAD khac */
        ResponseAPDU aad = decrypt(blob, path, 0x01);
        boolean t5 = aad.getSW() != 0x9000 && aad.getData().length == 0;
        row("AAD khac -> bi tu choi", t5,
                String.format("SW=%04X, so byte tra ve=%d", aad.getSW(), aad.getData().length));

        return t1 && t5;
    }

    private void t2(String label, byte[] blob, int path) throws CardException {
        ResponseAPDU r = decrypt(blob, path, 0x00);
        boolean pass = r.getSW() != 0x9000 && r.getData().length == 0;
        row(label, pass, String.format("SW=%04X, so byte tra ve=%d", r.getSW(), r.getData().length));
    }

    private ResponseAPDU decrypt(byte[] blob, int p1, int p2) throws CardException {
        return ch.transmit(new CommandAPDU(0x00, INS_GCM_DECRYPT, p1, p2, blob));
    }

    /* ==================== ECDSA ==================== */

    private boolean runEcdsaTests() throws CardException, IOException {
        ResponseAPDU pub1 = ch.transmit(new CommandAPDU(0x00, INS_GET_PUBKEY, 0x01, 0x00));
        ResponseAPDU pub2 = ch.transmit(new CommandAPDU(0x00, INS_GET_PUBKEY, 0x02, 0x00));
        if (pub1.getSW() != 0x9000 || pub2.getSW() != 0x9000) {
            row("ECDSA get pubkey", false, String.format("SW1=%04X SW2=%04X", pub1.getSW(), pub2.getSW()));
            return false;
        }
        byte[] w1 = pub1.getData();
        byte[] w2 = pub2.getData();
        System.out.println("PUBKEY1 = " + HEX.formatHex(w1));
        System.out.println("PUBKEY2 = " + HEX.formatHex(w2));
        boolean lenOk = w1.length == 65 && w1[0] == 0x04;
        row("Public key dang EC point 65 byte (04||X||Y)", lenOk, "do dai=" + w1.length);

        ResponseAPDU sig = ch.transmit(new CommandAPDU(0x00, INS_SIGN, 0x00, 0x00, MESSAGE));
        if (sig.getSW() != 0x9000) {
            readLastError();
            row("ECDSA sign", false, String.format("SW=%04X reason=%d", sig.getSW(), lastError));
            return false;
        }
        byte[] sigBytes = sig.getData();
        boolean der = sigBytes.length > 2 && (sigBytes[0] & 0xFF) == 0x30;
        System.out.println("MESSAGE  = " + new String(MESSAGE, StandardCharsets.US_ASCII));
        System.out.println("SIGNATURE = " + HEX.formatHex(sigBytes));
        row("Chu ky dang DER (bat dau 0x30)", der, "do dai=" + sigBytes.length);

        writeArtifact(w1, w2, sigBytes);
        row("Ghi artifact cho Node.js", true, ARTIFACT.toString());
        return lenOk && der;
    }

    static final Path ARTIFACT = Path.of("D:\\SmartCard\\artifacts\\crypto-probe\\ecdsa-proof.json");

    private void writeArtifact(byte[] w1, byte[] w2, byte[] sig) throws IOException {
        String json = "{\n"
                + "  \"curve\": \"P-256\",\n"
                + "  \"curveSource\": \"" + curveSource() + "\",\n"
                + "  \"hash\": \"SHA-256\",\n"
                + "  \"signatureFormat\": \"DER\",\n"
                + "  \"messageUtf8\": \"" + new String(MESSAGE, StandardCharsets.US_ASCII) + "\",\n"
                + "  \"messageHex\": \"" + HEX.formatHex(MESSAGE) + "\",\n"
                + "  \"publicKey1Hex\": \"" + HEX.formatHex(w1) + "\",\n"
                + "  \"publicKey2Hex\": \"" + HEX.formatHex(w2) + "\",\n"
                + "  \"signatureHex\": \"" + HEX.formatHex(sig) + "\"\n"
                + "}\n";
        Files.createDirectories(ARTIFACT.getParent());
        Files.writeString(ARTIFACT, json, StandardCharsets.UTF_8);
    }

    /** Mo ta dung cach dat tham so duong cong da chay duoc tren the. */
    private String curveSource() {
        return switch (ecPath) {
            case 1 -> "NamedParameterSpec.SECP256R1";
            case 6 -> "tham so P-256 tuong minh (p,a,b,G,r), G 65 byte co tien to 0x04";
            case 2, 3, 4, 5 -> "tham so P-256 tuong minh, bien the " + (ecPath - 1);
            default -> "khong xac dinh";
        };
    }

    /* ==================== helper ==================== */

    private void readLastError() throws CardException {
        ResponseAPDU r = ch.transmit(new CommandAPDU(0x00, INS_LAST_ERROR, 0x00, 0x00));
        if (r.getSW() == 0x9000 && r.getData().length == 1) {
            lastError = r.getData()[0] & 0xFF;
        }
    }

    private void row(String test, boolean pass, String detail) {
        rows.add(String.format("%-4s | %-45s | %s", pass ? "PASS" : "FAIL", test, detail));
        System.out.printf("%-4s | %-45s | %s%n", pass ? "PASS" : "FAIL", test, detail);
    }

    private static byte[] slice(byte[] a, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }

    private static boolean equal(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
