/*
 * Gate 0 — test nhan dien am (standalone, khong qua UI).
 *
 * Voi tung truong hop SELECT, ghi lai RAW command/response, roi chay probe ung dung
 * (echo cua HelloWorld) va ket luan. Muc dich: chung minh Simulator tra SW=9000
 * ca khi khong tim thay AID, nen KHONG duoc tin SW cua SELECT mot minh.
 *
 * Ba truong hop:
 *   1. SELECT dung AID cua HelloWorld
 *   2. SELECT AID khong ton tai
 *   3. SELECT khong co du lieu AID
 */

package vn.labaccess.tools.gate0;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

public final class IdentifyProbe {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] HELLOWORLD_AID = HEX.parseHex("A00000006203010C0101");
    private static final byte[] MISSING_AID = HEX.parseHex("A00000006203010C01FF");
    private static final byte[] ECHO_DATA = HEX.parseHex("3132333435");

    /** 80 20 00 00 05 3132333435 90 00 */
    private static final byte[] ECHO_EXPECTED = HEX.parseHex("802000000531323334359000");

    private static int failures;

    public static void main(String[] args) {
        int exit = 0;
        Card card = null;
        try {
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

            if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
                throw new IllegalStateException("Khong co the tren " + terminal.getName());
            }

            card = terminal.connect("*");
            CardChannel channel = card.getBasicChannel();
            System.out.println("Connection to simulator established: " + terminal.getName());
            System.out.println("ATR: " + HEX.formatHex(card.getATR().getBytes()));
            System.out.println();

            caseSelect(channel, "1. SELECT dung AID HelloWorld",
                    new CommandAPDU(0x00, 0xA4, 0x04, 0x00, HELLOWORLD_AID, 256), true,
                    "ky vong: SELECT 9000 (khong data) va probe khop");

            caseSelect(channel, "2. SELECT AID khong ton tai",
                    new CommandAPDU(0x00, 0xA4, 0x04, 0x00, MISSING_AID, 256), true,
                    "GHI NHAN: Applet cu VAN phan hoi (probe khop) => day la FALSE POSITIVE, "
                    + "khong the ket luan da chon dung AID");

            caseSelect(channel, "3. SELECT khong co du lieu AID",
                    new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[0]), false,
                    "ky vong: the chon ISD (tra FCI) nen probe hong voi 6D00");

            /* Khoi phuc trang thai de client khac dung duoc */
            System.out.println("--- khoi phuc: SELECT lai AID HelloWorld ---");
            ResponseAPDU restored = channel.transmit(
                    new CommandAPDU(0x00, 0xA4, 0x04, 0x00, HELLOWORLD_AID, 256));
            System.out.printf("R=%s -> SW=%04X%n", HEX.formatHex(restored.getBytes()), restored.getSW());

            System.out.println();
            System.out.println("========================================");
            System.out.println(failures == 0
                    ? "KET LUAN: ca 3 truong hop dung voi hanh vi da ghi nhan"
                    : "KET LUAN: co " + failures + " truong hop LECH hanh vi da ghi nhan");
            System.out.println("========================================");

        } catch (Exception e) {
            System.out.println("LOI: " + e);
            e.printStackTrace();
            exit = 1;
        } finally {
            if (card != null) {
                try {
                    card.disconnect(true);
                } catch (Exception ignored) {
                    /* dang don dep */
                }
            }
        }
        System.exit(exit);
    }

    private static void caseSelect(CardChannel channel, String title, CommandAPDU command,
            boolean expectProbeOk, String note) throws Exception {
        System.out.println("======== " + title + " ========");
        System.out.println("C=" + HEX.formatHex(command.getBytes()));

        ResponseAPDU selectResponse = channel.transmit(command);
        System.out.println("R=" + HEX.formatHex(selectResponse.getBytes())
                + String.format(" -> SW=%04X", selectResponse.getSW()));

        ResponseAPDU probe = channel.transmit(new CommandAPDU(0x80, 0x20, 0x00, 0x00, ECHO_DATA));
        String probeHex = HEX.formatHex(probe.getBytes());
        boolean probeOk = Arrays.equals(probe.getBytes(), ECHO_EXPECTED);

        System.out.println("probe C=80200000053132333435");
        System.out.println("probe R=" + probeHex + String.format(" -> SW=%04X", probe.getSW()));
        System.out.println("probe khop 802000000531323334359000: " + probeOk);

        if (selectResponse.getSW() == 0x9000 && !probeOk) {
            System.out.println("HIEN TUONG: SELECT tra 9000 nhung probe KHONG khop "
                    + "=> SW cua SELECT khong dang tin, phai kiem tra sau SELECT.");
        }
        if (selectResponse.getSW() == 0x9000 && probeOk) {
            System.out.println("HIEN TUONG: SELECT tra 9000 va probe van khop. "
                    + "Khong phan biet duoc 'vua chon dung AID' voi 'applet cu van dang duoc chon'.");
        }
        if (selectResponse.getSW() != 0x9000) {
            System.out.println("HIEN TUONG: SELECT bi tu choi dung theo ISO (SW="
                    + String.format("%04X", selectResponse.getSW()) + ").");
        }

        boolean verdict = probeOk == expectProbeOk;
        if (!verdict) {
            failures++;
        }
        System.out.println("ket luan: probe " + (probeOk ? "SAN SANG" : "CHUA SAN SANG")
                + (verdict ? " (dung hanh vi da ghi nhan)" : " (KHONG khop hanh vi da ghi nhan)"));
        System.out.println("ghi chu: " + note);
        System.out.println();
    }
}
