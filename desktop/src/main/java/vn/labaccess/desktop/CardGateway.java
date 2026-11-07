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

/**
 * Lop giu ket noi the, tach khoi su kien nut bam.
 *
 * Phai goi tuan tu tren cung mot luong nen (executor cua App). Cac getter KHONG
 * bi khoa boi thao tac I/O de FX thread khong bi treo khi connect dang cho the.
 *
 * Nhan dien applet khong tin vao SW cua SELECT: sau SELECT phai chay mot probe
 * ung dung va doi chieu TOAN BO data + SW, vi Simulator tra 9000 ca khi khong
 * tim thay AID (xem docs/plan/ket-qua-gate0.md).
 */
final class CardGateway {

    /** Ket qua mot thao tac: ok = thanh cong, text = mo ta de hien thi. */
    record Result(boolean ok, String text) {
        static Result ok(String text) {
            return new Result(true, text);
        }

        static Result fail(String text) {
            return new Result(false, text);
        }
    }

    /** AID Applet HelloWorld (instance = class AID). */
    private static final byte[] HELLOWORLD_AID = HexFormat.of().parseHex("A00000006203010C0101");

    /** Du lieu echo co dinh cua sample: ASCII "12345". */
    private static final byte[] ECHO_DATA = HexFormat.of().parseHex("3132333435");

    /** Phan hoi echo hop le: 80 20 00 00 05 3132333435 90 00. */
    private static final byte[] ECHO_EXPECTED = {
            (byte) 0x80, 0x20, 0x00, 0x00, 0x05, 0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0x90, 0x00
    };

    private final String host;
    private final int port;

    /* Chi duoc dong vao tu luong nen. */
    private TerminalFactory factory;
    private CardTerminal terminal;
    private Card card;
    private CardChannel channel;

    /** true chi khi SELECT tra 9000 VA probe ung dung khop tung byte. */
    private volatile boolean ready;

    /** Ban sao trang thai cho UI, cap nhat tren luong nen. */
    private volatile boolean connectedFlag;

    CardGateway(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Mo terminal qua socket provider, cho the, roi connect("*"). */
    Result connect() throws CardException, java.security.NoSuchAlgorithmException,
            java.security.NoSuchProviderException {

        if (card != null) {
            return Result.fail("Da ket noi roi, phai Disconnect truoc");
        }

        factory = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(host, port)),
                "SocketCardTerminalProvider");

        List<CardTerminal> terminals = factory.terminals().list();
        if (terminals.isEmpty()) {
            throw new CardException("Khong co terminal nao tu socket provider");
        }
        terminal = terminals.get(0);

        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new CardException("Khong co the tren terminal " + terminal.getName());
        }

        card = terminal.connect("*");
        channel = card.getBasicChannel();
        ready = false;
        connectedFlag = true;

        ATR atr = card.getATR();
        return Result.ok("terminal=" + terminal.getName() + " | ATR=" + hex(atr.getBytes()));
    }

    /**
     * Nhan dien Applet HelloWorld: SELECT AID roi chay probe ung dung.
     *
     * HelloWorld chua co GET_STATUS nen probe tam thoi la lenh echo. Khi chuyen
     * sang Applet LabAccess, thay probeEcho() bang GET_STATUS theo header da chot.
     */
    Result identify() throws CardException {
        return identify(HELLOWORLD_AID, false);
    }

    /** Nhan dien voi SELECT khong co du lieu AID (dung cho test am). */
    Result identifyNoAid() throws CardException {
        return identify(null, true);
    }

    /** Nhan dien voi mot AID bat ky (dung cho test am: AID khong ton tai). */
    Result identifyWith(byte[] aid) throws CardException {
        return identify(aid, false);
    }

    private Result identify(byte[] aid, boolean noAid) throws CardException {
        requireConnected();

        /* Moi lan nhan dien deu phai bat dau tu trang thai chua san sang */
        ready = false;

        CommandAPDU select = noAid
                ? new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[0])
                : new CommandAPDU(0x00, 0xA4, 0x04, 0x00, aid, 256);

        ResponseAPDU selectResponse = channel.transmit(select);
        boolean selectOk = selectResponse.getSW() == Sw.OK;

        /* Luon chay probe ung dung, ke ca khi SELECT bao loi */
        Result probe = probeEcho();

        /* Chi ghi AID cong khai va SW; khong log raw CommandAPDU.
           Cac lenh nghiep vu sau nay co the chua PIN ngay trong APDU. */
        String detail = String.format(
                "SELECT AID=%s SW=%04X | probe=%s | ket luan: %s",
                noAid ? "<rong>" : hex(aid),
                selectResponse.getSW(),
                probe.text(),
                (selectOk && probe.ok()) ? "SAN SANG" : "CHUA SAN SANG");

        if (!selectOk) {
            return Result.fail(detail + String.format(" (SELECT SW=%04X)", selectResponse.getSW()));
        }
        if (!probe.ok()) {
            /* SELECT 9000 nhung applet khong tra loi dung: khong duoc coi la san sang */
            return Result.fail(detail + " (" + probe.text() + ")");
        }

        ready = true;
        return Result.ok(detail);
    }

    /**
     * Gui echo va doi chieu toan bo data + SW voi phan hoi mong doi.
     * KHONG phu thuoc trang thai ready — day chinh la buoc dung de xac lap ready.
     */
    Result probeEcho() throws CardException {
        requireConnected();
        CommandAPDU echo = new CommandAPDU(0x80, 0x20, 0x00, 0x00, ECHO_DATA);
        return checkEcho(channel.transmit(echo));
    }

    /** Echo danh cho nguoi dung: chi khi da san sang. */
    Result echo() throws CardException {
        requireConnected();
        if (!ready) {
            return Result.fail("Chua san sang (phai SELECT + kiem tra thanh cong)");
        }
        return probeEcho();
    }

    private Result checkEcho(ResponseAPDU response) {
        byte[] data = response.getData();
        byte[] actual = new byte[data.length + 2];
        System.arraycopy(data, 0, actual, 0, data.length);
        actual[data.length] = (byte) (response.getSW() >> 8);
        actual[data.length + 1] = (byte) (response.getSW() & 0xFF);

        String text = String.format("Data=%s SW=%04X", hex(data), response.getSW());

        if (response.getSW() != Sw.OK) {
            return Result.fail(text);
        }
        if (!Arrays.equals(actual, ECHO_EXPECTED)) {
            return Result.fail(text + " | du lieu echo khong khop 80200000053132333435");
        }
        return Result.ok(text);
    }

    /**
     * Gui mot INS khong duoc ho tro de tao ra mot lan that bai THAT SU tren the.
     * Chi dung cho buoc kiem tra am cua demo.
     */
    Result failProbe() throws CardException {
        requireConnected();
        CommandAPDU command = new CommandAPDU(0x80, 0xFF, 0x00, 0x00);
        ResponseAPDU response = channel.transmit(command);
        ready = false;
        return Result.fail(String.format("INS khong ho tro -> SW=%04X", response.getSW()));
    }

    /** Dong ket noi, giai phong terminal. Khong gui DELETE nao len the. */
    Result disconnect() throws CardException {
        ready = false;
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
        return wasConnected ? Result.ok("Da dong ket noi") : Result.ok("Von da ngat ket noi");
    }

    /** Co the goi tu bat ky luong nao (chi doc co volatile). */
    boolean isConnected() {
        return connectedFlag;
    }

    boolean isReady() {
        return ready;
    }

    private void requireConnected() {
        if (card == null || channel == null) {
            throw new IllegalStateException("Chua ket noi");
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
