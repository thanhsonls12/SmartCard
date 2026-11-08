package vn.labaccess.desktop;

import java.util.HexFormat;

import javax.smartcardio.ResponseAPDU;

public final class CardGatewayVerify {

    private static final byte[] AID = HexFormat.of().parseHex("F04C4142010101");
    private static final byte[] WRONG_AID = HexFormat.of().parseHex("F04C4142010199");

    private static final int SW_OK = 0x9000;

    private static final String HOST = System.getProperty("labaccess.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("labaccess.port", 9025);

    private static int failed;

    public static void main(String[] args) throws Exception {
        CardGateway gateway = new CardGateway(HOST, PORT);
        try {
            checkLifecycleRules(gateway);
            checkIdentifyWithRealCard(gateway);
        } finally {
            if (gateway.isConnected()) {
                gateway.disconnect();
            }
        }
        System.out.println();
        System.out.println("========================================");
        System.out.println("KET LUAN: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")"));
        System.out.println("========================================");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void checkLifecycleRules(CardGateway gateway) {
        System.out.println("--- Nhom A: kiem lifecycle/cardId bang response tong hop ---");

        record("V03", "lifecycle 00 + cardId toan 00 chap nhan",
                gateway.checkStatus(statusApdu(0x00, "0000000000000000"), AID).ok(), true);

        record("V04", "lifecycle 02 chua dinh nghia bi tu choi",
                gateway.checkStatus(statusApdu(0x02, "0000000000000000"), AID).ok(), false);

        record("V05", "lifecycle 00 nhung cardId da gan bi tu choi",
                gateway.checkStatus(statusApdu(0x00, "0123456789ABCDEF"), AID).ok(), false);

        record("V06", "lifecycle 01 ISSUED nhung cardId toan 00 bi tu choi",
                gateway.checkStatus(statusApdu(0x01, "0000000000000000"), AID).ok(), false);

        record("V07", "lifecycle 01 ISSUED + cardId da gan duoc chap nhan",
                gateway.checkStatus(statusApdu(0x01, "0123456789ABCDEF"), AID).ok(), true);

        record("V08", "AID tra ve khac AID yeu cau bi tu choi",
                gateway.checkStatus(statusApdu(0x00, "0000000000000000"), WRONG_AID).ok(), false);
    }

    private static void checkIdentifyWithRealCard(CardGateway gateway) throws Exception {
        System.out.println();
        System.out.println("--- Nhom B: nhan dien tren the that qua CardGateway ---");

        gateway.connect();
        CardGateway.Result positive = gateway.identify();
        System.out.println("    identify() -> " + positive.text());
        record("V01", "identify() thanh cong va dat ready",
                positive.ok() && gateway.isReady() && gateway.status() != null, true);
        if (gateway.status() != null) {
            System.out.println(String.format("    status: protocol=%02X lifecycle=%02X cardId=%s",
                    gateway.status().protocolVersion(), gateway.status().lifecycle(),
                    gateway.status().cardId()));
        }

        CardGateway.Result negative = gateway.identifyWith(WRONG_AID);
        System.out.println("    identifyWith(AID sai) -> " + negative.text());
        record("V02", "AID sai: that bai, ready=false, status=null",
                !negative.ok() && !gateway.isReady() && gateway.status() == null, true);

        CardGateway.Result again = gateway.identify();
        record("V09", "nhan dien lai sau ca sai phuc hoi duoc", again.ok() && gateway.isReady(), true);
    }

    private static ResponseAPDU statusApdu(int lifecycle, String cardIdHex) {
        byte[] cardId = HexFormat.of().parseHex(cardIdHex);
        byte[] data = new byte[11 + AID.length + 1 + cardId.length];
        byte[] magic = { 'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S' };
        System.arraycopy(magic, 0, data, 0, magic.length);
        data[9] = 0x01;
        data[10] = (byte) AID.length;
        System.arraycopy(AID, 0, data, 11, AID.length);
        data[11 + AID.length] = (byte) lifecycle;
        System.arraycopy(cardId, 0, data, 12 + AID.length, cardId.length);
        byte[] raw = new byte[data.length + 2];
        System.arraycopy(data, 0, raw, 0, data.length);
        raw[data.length] = (byte) (SW_OK >> 8);
        raw[data.length + 1] = (byte) SW_OK;
        return new ResponseAPDU(raw);
    }

    private static void record(String id, String name, boolean actual, boolean expected) {
        boolean ok = actual == expected;
        if (!ok) {
            failed++;
        }
        System.out.println(String.format("%s | %-52s | expected=%s actual=%s | %s",
                id, name, expected, actual, ok ? "PASS" : "FAIL"));
    }
}
