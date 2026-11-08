package vn.labaccess.tools.gate0;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

public final class LabAccessCheck {

    private static final String HOST = System.getProperty("labaccess.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("labaccess.port", "9025"));

    private static final String AID_HEX = "F04C4142010101";
    private static final String WRONG_AID_HEX = "F04C4142010199";

    private static final byte[] MAGIC = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S'
    };
    private static final int PROTOCOL_VERSION = 0x01;
    private static final int CARD_ID_LENGTH = 8;

    private static final int SW_OK = 0x9000;
    private static final int SW_WRONG_LENGTH = 0x6700;
    private static final int SW_WRONG_P1P2 = 0x6A86;
    private static final int SW_INS_NOT_SUPPORTED = 0x6D00;
    private static final int SW_CLA_NOT_SUPPORTED = 0x6E00;

    private static final List<String> ROWS = new ArrayList<>();
    private static int failed;

    public static void main(String[] args) throws Exception {
        String selectAid = selectArg(args, "-select=", AID_HEX);
        String rawHex = selectArg(args, "-raw=", null);

        Card card = connect();
        try {
            CardChannel channel = card.getBasicChannel();
            if (rawHex != null) {
                runSingle(channel, selectAid, rawHex);
            } else {
                runSuite(channel, selectAid);
            }
        } finally {
            card.disconnect(true);
        }
    }

    private static void runSingle(CardChannel channel, String selectAid, String rawHex) throws Exception {
        select(channel, selectAid);
        ResponseAPDU response = transmit(channel, rawHex);
        System.out.println("C   = " + rawHex);
        System.out.println("R   = " + hex(response.getData()) + sw(response.getSW()));
        System.out.println("SW  = " + String.format("%04X", response.getSW()));
        System.out.println("LEN = " + response.getData().length);
    }

    private static void runSuite(CardChannel channel, String selectAid) throws Exception {
        System.out.println("================ KET QUA ================");

        ResponseAPDU selectResponse = select(channel, selectAid);
        record("T01", "SELECT dung instance AID", "00A4040007" + selectAid,
                SW_OK, selectResponse);

        ResponseAPDU le27 = transmit(channel, "801000001B");
        recordStatus("T02", "GET_STATUS voi Le=1B", "801000001B", le27);

        ResponseAPDU le256 = transmit(channel, "8010000000");
        recordStatus("T03", "GET_STATUS voi Le=00", "8010000000", le256);

        record("T04", "CLA khac 80", "9010000000", SW_CLA_NOT_SUPPORTED,
                transmit(channel, "9010000000"));

        record("T05", "INS chua trien khai", "807F000000", SW_INS_NOT_SUPPORTED,
                transmit(channel, "807F000000"));

        record("T06", "P1 khac 00", "8010010000", SW_WRONG_P1P2,
                transmit(channel, "8010010000"));

        record("T07", "P2 khac 00", "8010000100", SW_WRONG_P1P2,
                transmit(channel, "8010000100"));

        recordSameAsBaseline("T08", "Co command data (bo qua theo hop dong)",
                "8010000001FF00", transmit(channel, "8010000001FF00"), le256.getData());

        record("T09", "Le=26 qua ngan (short APDU)", "801000001A", SW_WRONG_LENGTH,
                transmit(channel, "801000001A"));

        ResponseAPDU afterErrors = transmit(channel, "8010000000");
        recordStatus("T10", "Doc lai sau cac ca loi", "8010000000", afterErrors);

        System.out.println();
        System.out.println("--- Quan sat, khong tinh PASS/FAIL ---");
        observe(channel, "Thieu Le (Case 1, 4 byte)", "80100000");
        observe(channel, "Extended APDU, khong ho tro", "8010000000001B");

        System.out.println();
        System.out.println("========================================");
        for (String row : ROWS) {
            System.out.println(row);
        }
        System.out.println("========================================");
        System.out.println("KET LUAN: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")"));
        System.out.println("========================================");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void observe(CardChannel channel, String label, String commandHex) throws Exception {
        try {
            ResponseAPDU response = transmit(channel, commandHex);
            System.out.println(String.format("OBS  | %-30s | C=%s | R=%s%s | SW=%04X dataLen=%d",
                    label, commandHex, hex(response.getData()), sw(response.getSW()),
                    response.getSW(), response.getData().length));
        } catch (RuntimeException e) {
            System.out.println(String.format("OBS  | %-30s | C=%s | loi: %s",
                    label, commandHex, e.getMessage()));
        }
    }

    private static void recordStatus(String id, String name, String commandHex, ResponseAPDU response) {
        if (response.getSW() != SW_OK) {
            record(id, name, commandHex, SW_OK, response);
            return;
        }
        String problem = statusSchemaProblem(response.getData());
        if (problem == null) {
            record(id, name, commandHex, SW_OK, response);
            return;
        }
        failed++;
        ROWS.add(String.format("%s | %-30s | C=%s | R=%s%s | SW=%04X | expected: 27 byte dung schema | FAIL: %s",
                id, name, commandHex, hex(response.getData()), sw(response.getSW()),
                response.getSW(), problem));
    }

    private static void recordSameAsBaseline(String id, String name, String commandHex,
            ResponseAPDU response, byte[] baseline) {
        String problem = response.getSW() == SW_OK
                ? statusSchemaProblem(response.getData())
                : "SW khac 9000";
        if (problem == null && !Arrays.equals(response.getData(), baseline)) {
            problem = "noi dung khac lan doc chuan";
        }
        if (problem != null) {
            failed++;
        }
        ROWS.add(String.format("%s | %-30s | C=%s | R=%s%s | SW=%04X | expected: response chuan + 9000 %s",
                id, name, commandHex, hex(response.getData()), sw(response.getSW()),
                response.getSW(), problem == null ? "| PASS" : "| FAIL: " + problem));
    }
    private static String statusSchemaProblem(byte[] data) {
        if (data.length < 11) {
            return "response ngan hon header";
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return "magic sai";
            }
        }
        if ((data[9] & 0xFF) != PROTOCOL_VERSION) {
            return "protocol version sai";
        }
        int aidLength = data[10] & 0xFF;
        if (data.length != 11 + aidLength + 1 + CARD_ID_LENGTH) {
            return "do dai khong khop aidLen";
        }
        if (aidLength != AID_HEX.length() / 2) {
            return "aidLen khac 7";
        }
        if (!AID_HEX.equalsIgnoreCase(hexOf(data, 11, aidLength))) {
            return "instance AID tra ve khac AID yeu cau";
        }
        if ((data[11 + aidLength] & 0xFF) != 0x00) {
            return "lifecycle khac 00 UNPERSONALIZED";
        }
        for (int i = 12 + aidLength; i < 12 + aidLength + CARD_ID_LENGTH; i++) {
            if (data[i] != 0x00) {
                return "cardId khong phai toan 00 khi chua ca nhan hoa";
            }
        }
        return null;
    }

    private static void record(String id, String name, String commandHex, int expectedSw,
            ResponseAPDU response) {
        boolean ok = response.getSW() == expectedSw;
        if (!ok) {
            failed++;
        }
        ROWS.add(String.format("%s | %-30s | C=%s | R=%s%s | SW=%04X | expected: %04X %s",
                id, name, commandHex, hex(response.getData()), sw(response.getSW()),
                response.getSW(), expectedSw, ok ? "| PASS" : "| FAIL"));
    }

    private static ResponseAPDU select(CardChannel channel, String aidHex) throws Exception {
        return channel.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, hex(aidHex), 256));
    }

    private static ResponseAPDU transmit(CardChannel channel, String commandHex) throws Exception {
        return channel.transmit(new CommandAPDU(hex(commandHex)));
    }

    private static Card connect() throws Exception {
        CardTerminal terminal = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(HOST, PORT)),
                "SocketCardTerminalProvider").terminals().list().get(0);
        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new IllegalStateException("Khong co the");
        }
        return terminal.connect("*");
    }

    private static String selectArg(String[] args, String prefix, String fallback) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return fallback;
    }

    private static String sw(int sw) {
        return String.format("%04X", sw);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String hexOf(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X", data[offset + i]));
        }
        return sb.toString();
    }
}
