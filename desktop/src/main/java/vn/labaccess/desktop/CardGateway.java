package vn.labaccess.desktop;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

final class CardGateway {

    record Result(boolean ok, String text) {
        static Result ok(String text) {
            return new Result(true, text);
        }

        static Result fail(String text) {
            return new Result(false, text);
        }
    }

    record Status(int protocolVersion, int lifecycle, String instanceAid, String cardId) {
    }

    private static final byte[] INSTANCE_AID = HexFormat.of().parseHex("F04C4142010101");

    private static final byte CLA_APPLICATION = (byte) 0x80;
    private static final byte INS_GET_STATUS = (byte) 0x10;
    private static final int PROTOCOL_VERSION = 0x01;

    private static final int LIFECYCLE_UNPERSONALIZED = 0x00;
    private static final int LIFECYCLE_ISSUED = 0x01;

    private static final byte[] MAGIC = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S'
    };

    private static final int OFFSET_PROTOCOL_VERSION = 9;
    private static final int OFFSET_AID_LENGTH = 10;
    private static final int OFFSET_INSTANCE_AID = 11;
    private static final int CARD_ID_LENGTH = 8;


    private final String host;
    private final int port;

    private TerminalFactory factory;
    private CardTerminal terminal;
    private Card card;
    private CardChannel channel;

    private volatile boolean ready;
    private volatile boolean connectedFlag;
    private volatile Status status;

    CardGateway(String host, int port) {
        this.host = host;
        this.port = port;
    }

    Result connect() throws CardException, java.security.NoSuchAlgorithmException,
            java.security.NoSuchProviderException {

        if (card != null) {
            return Result.fail("Đã kết nối rồi, phải Disconnect trước");
        }

        factory = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(host, port)),
                "SocketCardTerminalProvider");

        List<CardTerminal> terminals = factory.terminals().list();
        if (terminals.isEmpty()) {
            throw new CardException("Không có terminal nào từ socket provider");
        }
        terminal = terminals.get(0);

        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new CardException("Không có thẻ trên terminal " + terminal.getName());
        }

        card = terminal.connect("*");
        channel = card.getBasicChannel();
        ready = false;
        status = null;
        connectedFlag = true;

        ATR atr = card.getATR();
        return Result.ok("terminal=" + terminal.getName() + " | ATR=" + hex(atr.getBytes()));
    }

    Result identify() throws CardException {
        return identifyWith(INSTANCE_AID);
    }

    Result identifyWith(byte[] instanceAid) throws CardException {
        requireConnected();
        ready = false;
        status = null;

        ResponseAPDU selectResponse = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, instanceAid, 256));
        if (selectResponse.getSW() != Sw.OK) {
            return Result.fail(String.format(
                    "SELECT AID=%s SW=%04X | kết luận: CHƯA SẴN SÀNG",
                    hex(instanceAid), selectResponse.getSW()));
        }

        ResponseAPDU response = channel.transmit(
                new CommandAPDU(CLA_APPLICATION, INS_GET_STATUS, 0x00, 0x00, 256));
        Result checked = checkStatus(response, instanceAid);
        if (!checked.ok()) {
            return checked;
        }
        ready = true;
        return checked;
    }

    Result checkStatus(ResponseAPDU response, byte[] requestedAid) {
        byte[] data = response.getData();
        int sw = response.getSW();

        if (sw != Sw.OK) {
            return Result.fail(String.format(
                    "GET_STATUS SW=%04X | kết luận: CHƯA SẴN SÀNG", sw));
        }
        if (data.length < OFFSET_INSTANCE_AID) {
            return Result.fail(String.format(
                    "GET_STATUS SW=%04X nhưng chỉ có %d byte | kết luận: CHƯA SẴN SÀNG",
                    sw, data.length));
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return Result.fail("GET_STATUS sai magic: " + hex(Arrays.copyOf(data, MAGIC.length))
                        + " | kết luận: CHƯA SẴN SÀNG");
            }
        }

        int version = data[OFFSET_PROTOCOL_VERSION] & 0xFF;
        if (version != PROTOCOL_VERSION) {
            return Result.fail(String.format(
                    "GET_STATUS protocol=%02X không hỗ trợ | kết luận: CHƯA SẴN SÀNG", version));
        }

        int aidLength = data[OFFSET_AID_LENGTH] & 0xFF;
        int expectedLength = OFFSET_INSTANCE_AID + aidLength + 1 + CARD_ID_LENGTH;
        if (data.length != expectedLength) {
            return Result.fail(String.format(
                    "GET_STATUS %d byte, theo aidLen=%d phải là %d | kết luận: CHƯA SẴN SÀNG",
                    data.length, aidLength, expectedLength));
        }

        byte[] returnedAid = Arrays.copyOfRange(data, OFFSET_INSTANCE_AID,
                OFFSET_INSTANCE_AID + aidLength);
        if (!Arrays.equals(returnedAid, requestedAid)) {
            return Result.fail(String.format(
                    "GET_STATUS trả AID=%s khác AID đã yêu cầu=%s | kết luận: CHƯA SẴN SÀNG",
                    hex(returnedAid), hex(requestedAid)));
        }

        int lifecycle = data[OFFSET_INSTANCE_AID + aidLength] & 0xFF;
        byte[] cardId = Arrays.copyOfRange(data, OFFSET_INSTANCE_AID + aidLength + 1,
                expectedLength);

        Result lifecycleProblem = checkLifecycle(lifecycle, cardId);
        if (lifecycleProblem != null) {
            return lifecycleProblem;
        }

        Status current = new Status(version, lifecycle, hex(returnedAid), hex(cardId));
        status = current;

        return Result.ok(String.format(
                "AID=%s protocol=%02X lifecycle=%s cardId=%s | kết luận: SẴN SÀNG",
                current.instanceAid(), current.protocolVersion(),
                lifecycleText(current.lifecycle()), current.cardId()));
    }

    private Result checkLifecycle(int lifecycle, byte[] cardId) {
        if (lifecycle != LIFECYCLE_UNPERSONALIZED && lifecycle != LIFECYCLE_ISSUED) {
            return Result.fail(String.format(
                    "GET_STATUS lifecycle=%02X chưa được giao thức định nghĩa | kết luận: CHƯA SẴN SÀNG",
                    lifecycle));
        }
        boolean cardIdAssigned = !isAllZero(cardId);
        if (lifecycle == LIFECYCLE_UNPERSONALIZED && cardIdAssigned) {
            return Result.fail(String.format(
                    "lifecycle=00 UNPERSONALIZED nhưng cardId=%s đã gán | kết luận: CHƯA SẴN SÀNG",
                    hex(cardId)));
        }
        if (lifecycle == LIFECYCLE_ISSUED && !cardIdAssigned) {
            return Result.fail(String.format(
                    "lifecycle=01 ISSUED nhưng cardId toàn 00 | kết luận: CHƯA SẴN SÀNG"));
        }
        return null;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
    static String lifecycleText(int lifecycle) {
        if (lifecycle == 0x00) {
            return "00 UNPERSONALIZED";
        }
        if (lifecycle == 0x01) {
            return "01 ISSUED";
        }
        return String.format("%02X KHÔNG RÕ", lifecycle);
    }

    Result disconnect() throws CardException {
        ready = false;
        status = null;
        channel = null;
        terminal = null;
        factory = null;
        boolean wasConnected = card != null;
        try {
            if (card != null) {
                card.disconnect(true);
            }
        } finally {
            card = null;
            connectedFlag = false;
        }
        return wasConnected ? Result.ok("Đã đóng kết nối") : Result.ok("Vốn đã ngắt kết nối");
    }

    boolean isConnected() {
        return connectedFlag;
    }

    boolean isReady() {
        return ready;
    }

    Status status() {
        return status;
    }

    private void requireConnected() {
        if (card == null || channel == null) {
            throw new IllegalStateException("Chưa kết nối");
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "<rong>";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
