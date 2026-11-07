/*
 * Gate 0 — cài HelloWorld CAP và GIỮ Applet trên Simulator.
 *
 * Khác biệt so với client Oracle gốc (AMSHelloWorldClient):
 *   1. Bỏ hoàn toàn session "undeploy" (.uninstall(...).unload(...)).
 *   2. Bỏ vòng lặp in toàn bộ properties -> không in khóa SCP03 ra console.
 *   3. CAP lấy từ artifact ổn định đã verify, không lấy từ deliverables.
 *   4. Kết thúc bằng disconnect; không gửi DELETE nào.
 *
 * Không sửa bất kỳ file nào trong SDK.
 */

package vn.labaccess.tools.gate0;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import com.oracle.javacard.ams.AMService;
import com.oracle.javacard.ams.AMServiceFactory;
import com.oracle.javacard.ams.AMSession;
import com.oracle.javacard.ams.config.AID;
import com.oracle.javacard.ams.config.CAPFile;
import com.oracle.javacard.ams.script.APDUScript;
import com.oracle.javacard.ams.script.ScriptFailedException;

public class InstallKeepApplet {

    /* Địa chỉ mặc định — có thể override bằng -cap= , -props= , -host= */
    private static final String CAP_DEFAULT   = "D:\\SmartCard\\artifacts\\helloworld\\helloworld.cap";
    private static final String PROPS_DEFAULT = "D:\\JavaCard\\sdk-26\\simulator\\samples\\client.config.properties";
    private static final String HOST_DEFAULT  = "socket:127.0.0.1:9025";

    private static final String ISD_AID = "aid:A000000151000000";

    private static final String ECHO_HEX = "3132333435";

    public static void main(String[] args) {
        int exit = 0;
        Card card = null;

        try {
            String capPath   = getArg(args, "cap", CAP_DEFAULT);
            String propsPath = getArg(args, "props", PROPS_DEFAULT);
            String host      = getArg(args, "host", HOST_DEFAULT);

            /* AID cua goi / applet / instance: mac dinh la HelloWorld */
            String pkgHex    = getArg(args, "pkg",    "a00000006203010C01");
            String classHex  = getArg(args, "applet", "a00000006203010C0101");
            String instHex   = getArg(args, "inst",   classHex);
            /* "full" = install + SELECT + ECHO | "install" = chi install */
            String scriptMode = getArg(args, "script", "full");

            String capAid        = "aid:" + pkgHex;
            String appletClassAid = "aid:" + classHex;
            String appletInstAid  = "aid:" + instHex;

            log("CAP   : " + capPath);
            log("PROPS : " + propsPath);
            log("HOST  : " + host);
            log("Package AID  : " + pkgHex);
            log("Applet AID   : " + classHex);
            log("Instance AID : " + instHex);
            log("Che do: " + scriptMode + ", KHONG undeploy");
            log("");

            CAPFile appFile = CAPFile.from(capPath);
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(propsPath)) {
                props.load(in);
            }

            AMService ams = AMServiceFactory.getInstance("GP2.2");
            ams.setProperties(props);
            /* KHÔNG in ams.getPropertiesKeys() — tránh lộ khóa SCP03 */

            AMSession deploy = ams.openSession(ISD_AID)
                    .load(capAid, appFile.getBytes())
                    .install(capAid, appletClassAid, appletInstAid)
                    .close();

            /* Script gồm deploy; SELECT + ECHO chỉ khi duoc yeu cau. Không có undeploy. */
            Script script = new Script().append(deploy);

            if ("full".equals(scriptMode)) {
                byte[] echoData = hexToBytes(ECHO_HEX);
                byte[] echoExpected = new byte[4 + 1 + echoData.length + 2];
                echoExpected[0] = (byte) 0x80;
                echoExpected[1] = 0x20;
                echoExpected[2] = 0x00;
                echoExpected[3] = 0x00;
                echoExpected[4] = (byte) echoData.length;
                System.arraycopy(echoData, 0, echoExpected, 5, echoData.length);
                echoExpected[5 + echoData.length] = (byte) 0x90;
                echoExpected[6 + echoData.length] = 0x00;

                script.append(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, AID.from(appletInstAid).toBytes(), 256))
                      .append(new CommandAPDU(0x80, 0x20, 0x00, 0x00, echoData),
                              new ResponseAPDU(echoExpected));
            }

            CardTerminal terminal = getTerminal(host.split(":"));
            if (terminal != null && terminal.waitForCardPresent(10000)) {
                log("Connection to simulator established: " + terminal.getName());

                card = terminal.connect("*");
                log(getFormattedATR(card.getATR().getBytes()));

                List<ResponseAPDU> responses = script.execute(card.getBasicChannel());

                log("");
                log("Responses count: " + responses.size());
                log("Khong co lenh DELETE nao duoc gui.");
                /* Phai kiem SW cua lenh cuoi (INSTALL) chu khong mac dinh SUCCESS */
                int lastSw = responses.get(responses.size() - 1).getSW();
                if (lastSw == 0x9000) {
                    log("Ket qua: SUCCESS");
                } else {
                    log(String.format("Ket qua: FAIL (lenh cuoi SW=%04X)", lastSw));
                    exit = 1;
                }

                card.disconnect(true);
                card = null;
            } else {
                log("Connection to simulator failed");
                exit = -1;
            }

        } catch (NoSuchAlgorithmException | NoSuchProviderException | CardException
                 | ScriptFailedException | IOException e) {
            e.printStackTrace();
            exit = -1;
        } finally {
            if (card != null) {
                try {
                    card.disconnect(true);
                } catch (CardException ignored) {
                    /* đang dọn dẹp */
                }
            }
        }
        System.exit(exit);
    }

    /* ------------------------------------------------------------------ */

    private static String getArg(String[] args, String name, String def) throws IllegalArgumentException {
        String value = def;
        for (String param : args) {
            if (param.startsWith("-" + name + "=")) {
                value = param.substring(param.indexOf('=') + 1);
            }
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Mandatory argument [" + name + "] is missing");
        }
        return value;
    }

    private static CardTerminal getTerminal(String... params)
            throws NoSuchAlgorithmException, NoSuchProviderException, CardException {

        if (!"socket".equals(params[0])) {
            throw new IllegalArgumentException("Chi ho tro ket noi socket trong cong cu nay");
        }
        TerminalFactory tf = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(params[1], Integer.parseInt(params[2]))),
                "SocketCardTerminalProvider");
        return tf.terminals().list().get(0);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String getFormattedATR(byte[] atr) {
        StringBuilder sb = new StringBuilder();
        for (byte b : atr) {
            sb.append(String.format("%02X ", b));
        }
        return String.format("ATR: [%s]", sb.toString().trim());
    }

    private static void log(String msg) {
        System.out.println(msg);
    }

    /* ------------------------------------------------------------------ */

    /** APDUScript tối giản: ghi log từng APDU, so khớp response nếu có kỳ vọng. */
    private static class Script extends APDUScript {

        private final List<CommandAPDU> commands = new LinkedList<>();
        private final List<ResponseAPDU> expected = new LinkedList<>();

        /* KHÔNG đặt tên là run(): sẽ trùng erasure với Scriptable.run(T) */
        public List<ResponseAPDU> execute(CardChannel channel) throws ScriptFailedException {
            /* tham so thu 2 = "nen dung lai": dung khi response KHONG khop ky vong */
            return super.run(channel, c -> lookup(c), r -> !isExpected(r));
        }

        @Override
        public Script append(com.oracle.javacard.ams.script.Scriptable<CardChannel, CommandAPDU, ResponseAPDU> other) {
            super.append(other);
            return this;
        }

        public Script append(CommandAPDU apdu, ResponseAPDU expectedResponse) {
            super.append(apdu);
            this.commands.add(apdu);
            this.expected.add(expectedResponse);
            return this;
        }

        public Script append(CommandAPDU apdu) {
            super.append(apdu);
            return this;
        }

        private CommandAPDU lookup(CommandAPDU apdu) {
            printCommand(apdu);
            for (int i = 0; i < commands.size(); i++) {
                if (commands.get(i) == apdu) {
                    index = i;
                    return apdu;
                }
            }
            index = -1;
            return apdu;
        }

        private int index = -1;

        private boolean isExpected(ResponseAPDU response) {
            printResponse(response);
            if (index < 0) {
                return true; /* không đặt kỳ vọng: chỉ ghi log */
            }
            ResponseAPDU exp = expected.get(index);
            if (!response.equals(exp)) {
                log("MISMATCH — nhan duoc: " + hex(response.getBytes())
                        + " | ky vong: " + hex(exp.getBytes()));
                return false;
            }
            return true;
        }

        private static void printCommand(CommandAPDU apdu) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%02X%02X%02X%02X %02X[",
                    apdu.getCLA(), apdu.getINS(), apdu.getP1(), apdu.getP2(), apdu.getNc()));
            for (byte b : apdu.getData()) {
                sb.append(String.format("%02X", b));
            }
            sb.append(']');
            System.out.format("[%1$tF %1$tT.%1$tL] [APDU-C] %2$s%n", System.currentTimeMillis(), sb);
        }

        private static void printResponse(ResponseAPDU apdu) {
            System.out.format("[%1$tF %1$tT.%1$tL] [APDU-R] [%2$s] SW:%3$04X%n",
                    System.currentTimeMillis(), hex(apdu.getData()), apdu.getSW());
        }

        private static String hex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }
    }
}
