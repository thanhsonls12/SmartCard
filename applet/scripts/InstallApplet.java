
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import com.oracle.javacard.ams.AMService;
import com.oracle.javacard.ams.AMServiceFactory;
import com.oracle.javacard.ams.AMSession;
import com.oracle.javacard.ams.config.CAPFile;
import com.oracle.javacard.ams.script.ScriptFailedException;
import com.oracle.javacard.ams.script.ScriptVisitor;
import com.oracle.javacard.ams.config.CAPFile;

public final class InstallApplet {

    private static final String ISD_AID = "aid:A000000151000000";

    private static final String PKG_HEX = "F04C414201";
    private static final String APPLET_HEX = "F04C4142010101";
    private static final String INST_HEX = "F04C4142010101";

    private static final byte[] MAGIC = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S'
    };

    private static final int PROTOCOL_VERSION = 0x01;
    private static final int CARD_ID_LENGTH = 8;
    private static final int ADMIN_PIN_LENGTH = 8;
    private static final byte PROVISIONING_FORMAT = 0x01;

    private static final int SW_OK = 0x9000;
    private static final int SW_REFERENCED_DATA_NOT_FOUND = 0x6A88;

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_BAD_ARGUMENTS = 2;
    private static final int EXIT_INSTANCE_EXISTS = 3;

    private static final ScriptVisitor<CommandAPDU> LOG_COMMAND = cmd -> {
        byte[] b = cmd.getBytes();
        log(String.format("    -> %02X %02X %02X %02X  (%d byte)",
                b[0], b[1], b[2], b[3], b.length));
        return cmd;
    };

    private static final Predicate<ResponseAPDU> STOP_ON_ERROR = r -> {
        log(String.format("    <- SW=%04X  dataLen=%d", r.getSW(), r.getData().length));
        return r.getSW() != SW_OK;
    };

    private static int failures;

    public static void main(String[] args) {
        String mode = "install";
        String host = "127.0.0.1";
        int port = 9025;
        String capPath = null;
        String propsPath = null;

        for (String a : args) {
            if (a.startsWith("-mode=")) {
                mode = a.substring(6);
            } else if (a.startsWith("-host=")) {
                String[] p = a.substring(6).split(":");
                host = p[0];
                port = Integer.parseInt(p[1]);
            } else if (a.startsWith("-cap=")) {
                capPath = a.substring(5);
            } else if (a.startsWith("-props=")) {
                propsPath = a.substring(7);
            }
        }

        if (!mode.equals("install") && !mode.equals("replace")) {
            System.out.println("Che do khong hop le: " + mode + " (dung install hoac replace)");
            System.exit(EXIT_BAD_ARGUMENTS);
        }
        if (capPath == null) {
            System.out.println("Thieu -cap=<duong dan file .cap>");
            System.exit(EXIT_BAD_ARGUMENTS);
        }
        if (propsPath == null) {
            String simHome = System.getenv("JC_HOME_SIMULATOR");
            if (simHome != null) {
                propsPath = simHome + "\\samples\\client.config.properties";
            }
        }

        log("CAP    : " + capPath);
        log("PROPS  : " + propsPath);
        log("HOST   : " + host + ":" + port);
        log("Package: " + PKG_HEX + "   Applet: " + APPLET_HEX + "   Instance: " + INST_HEX);
        log("Che do : " + mode + (mode.equals("replace")
                ? "  (SE XOA instance + package truoc khi cai)"
                : "  (khong xoa gi)"));
        log("");

        byte[] adminPin = new byte[0];
        Card card = null;
        int exitCode = EXIT_FAILED;
        String currentStep = "doc admin PIN";
        try {
            adminPin = readAdminPin();
            currentStep = "ket noi the";
            card = connect(host, port);
            currentStep = "INSTALL va kiem tra";
            exitCode = runSteps(mode, capPath, propsPath, card.getBasicChannel(), adminPin);
        } catch (IllegalArgumentException e) {
            log("LOI tai buoc " + currentStep + ": IllegalArgumentException (chi tiet bo qua)");
            exitCode = currentStep.equals("doc admin PIN") ? EXIT_BAD_ARGUMENTS : EXIT_FAILED;
        } catch (Exception e) {
            log("LOI tai buoc " + currentStep + ": " + e.getClass().getSimpleName()
                    + " (chi tiet bo qua de tranh ghi du lieu nhay cam)");
            exitCode = EXIT_FAILED;
        } finally {
            java.util.Arrays.fill(adminPin, (byte) 0);
            exitCode = closeQuietly(card, exitCode);
        }

        report(exitCode);
        System.exit(exitCode);
    }

    private static int runSteps(String mode, String capPath, String propsPath,
            CardChannel channel, byte[] adminPin) throws Exception {
        log("[1] Kiem tinh trang instance");
        boolean instanceAlive = probeInstance(channel);
        log("    ket qua: " + (instanceAlive
                ? "co instance phan hoi dung schema LabAccess"
                : "khong co instance LabAccess phan hoi dung schema"));
        log("");

        if (instanceAlive && !mode.equals("replace")) {
            log("[DUNG] Instance " + INST_HEX + " da ton tai va dang phan hoi.");
            log("       Khong tu xoa instance co the dang chua du lieu.");
            log("       Muon ghi de, chay lai voi -mode=replace (thao tac pha huy).");
            return EXIT_INSTANCE_EXISTS;
        }

        if (mode.equals("replace")) {
            log("[2] Go instance + package (mode=replace)");
            if (!removeInstance(propsPath, channel)) {
                return EXIT_FAILED;
            }
            if (!removePackage(propsPath, channel)) {
                return EXIT_FAILED;
            }
            log("");
        }

        if (!stepLoad(capPath, propsPath, channel)) {
            log("");
            log("Goi y: neu package " + PKG_HEX + " da duoc load tu lan truoc,");
            log("       dung -mode=replace de go truoc khi cai lai.");
            return EXIT_FAILED;
        }
        if (!stepInstall(propsPath, channel, adminPin)) {
            return EXIT_FAILED;
        }

        log("[4] Kiem lai sau khi cai");
        boolean after = probeInstance(channel);
        record("instance phan hoi dung schema sau khi cai", after, "");
        log("");
        return after ? EXIT_OK : EXIT_FAILED;
    }

    private static int closeQuietly(Card card, int exitCode) {
        if (card == null) {
            return exitCode;
        }
        try {
            card.disconnect(true);
        } catch (Exception e) {
            log("Canh bao: dong ket noi loi: " + e.getClass().getSimpleName()
                    + " (chi tiet bo qua)");
        }
        return exitCode;
    }

    private static void report(int exitCode) {
        log("");
        log("================ KET QUA ================");
        if (exitCode == EXIT_OK && failures == 0) {
            log("Cai dat THANH CONG");
        } else if (exitCode == EXIT_INSTANCE_EXISTS) {
            log("DUNG: instance dang ton tai, khong xoa. Dung -mode=replace neu that su muon ghi de.");
        } else {
            log("Cai dat THAT BAI (" + failures + " buoc loi)");
        }
        log("========================================");
    }

    private static boolean stepLoad(String capPath, String propsPath, CardChannel channel)
            throws IOException, java.security.NoSuchProviderException {
        log("[2/3] LOAD package " + PKG_HEX);
        CAPFile capFile = CAPFile.from(capPath);
        AMService ams = openService(propsPath);
        AMSession session = ams.openSession(ISD_AID)
                .load("aid:" + PKG_HEX, capFile.getBytes())
                .close();
        List<ResponseAPDU> responses;
        boolean ok;
        try {
            responses = session.run(channel, LOG_COMMAND, STOP_ON_ERROR);
            ok = true;
            log("    load package: " + responses.size() + " response, tat ca 9000");
        } catch (ScriptFailedException e) {
            ok = false;
            log("    load package: DUNG vi response khac 9000");
        }
        record("load package " + PKG_HEX, ok, "");
        log("");
        return ok;
    }

    private static boolean stepInstall(String propsPath, CardChannel channel, byte[] adminPin)
            throws IOException, java.security.NoSuchProviderException {
        log("[3/3] INSTALL instance " + INST_HEX);
        boolean ok;
        byte[] payload = provisioningPayload(adminPin);
        try {
            log(String.format("    provisioning: format=%02X pinLength=%d hop le",
                    payload[0], payload[1]));
            AMService ams = openService(propsPath);
            AMSession session = ams.openSession(ISD_AID)
                    .install("aid:" + PKG_HEX, "aid:" + APPLET_HEX, "aid:" + INST_HEX, payload)
                    .close();
            ok = runScript(session, channel, "install instance");
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
        }
        record("install instance " + INST_HEX, ok, "");
        log("");
        return ok;
    }

    private static byte[] provisioningPayload(byte[] adminPin) {
        byte[] payload = new byte[2 + ADMIN_PIN_LENGTH];
        payload[0] = PROVISIONING_FORMAT;
        payload[1] = (byte) ADMIN_PIN_LENGTH;
        System.arraycopy(adminPin, 0, payload, 2, ADMIN_PIN_LENGTH);
        return payload;
    }

    private static byte[] readAdminPin() throws IOException {
        byte[] pin = new byte[ADMIN_PIN_LENGTH];
        boolean accepted = false;
        try {
            int count = 0;
            int value;
            while ((value = System.in.read()) != -1) {
                if (value == 10 || value == 13) {
                    break;
                }
                if (count == ADMIN_PIN_LENGTH) {
                    throw new IllegalArgumentException(
                            "Admin PIN phai co dung " + ADMIN_PIN_LENGTH + " chu so");
                }
                pin[count++] = (byte) value;
            }
            if (count != ADMIN_PIN_LENGTH) {
                throw new IllegalArgumentException(
                        "Admin PIN phai co dung " + ADMIN_PIN_LENGTH + " chu so");
            }
            for (int index = 0; index < ADMIN_PIN_LENGTH; index++) {
                if (pin[index] < 48 || pin[index] > 57) {
                    throw new IllegalArgumentException("Admin PIN chi duoc chua chu so 0-9");
                }
            }
            accepted = true;
            return pin;
        } finally {
            if (!accepted) {
                java.util.Arrays.fill(pin, (byte) 0);
            }
        }
    }

    private static boolean removeInstance(String propsPath, CardChannel channel)
            throws IOException, java.security.NoSuchProviderException {
        log("    xoa instance");
        AMService ams = openService(propsPath);
        AMSession session = ams.openSession(ISD_AID).uninstall("aid:" + INST_HEX).close();
        return runRemoval(session, channel, "uninstall instance", INST_HEX);
    }

    private static boolean removePackage(String propsPath, CardChannel channel)
            throws IOException, java.security.NoSuchProviderException {
        log("    xoa package");
        AMService ams = openService(propsPath);
        AMSession session = ams.openSession(ISD_AID).unload("aid:" + PKG_HEX).close();
        return runRemoval(session, channel, "unload package", PKG_HEX);
    }

    private static boolean runRemoval(AMSession session, CardChannel channel, String label,
            String expectedAidHex) {
        int[] deleteCount = { 0 };
        int[] notFoundCount = { 0 };
        boolean[] deleteTargetsExpectedAid = { false };
        ScriptVisitor<CommandAPDU> visitor = command -> {
            byte[] bytes = command.getBytes();
            log(String.format("    -> %02X %02X %02X %02X  (%d byte)",
                    bytes[0], bytes[1], bytes[2], bytes[3], bytes.length));
            deleteTargetsExpectedAid[0] = isDeleteForAid(command, expectedAidHex);
            return command;
        };
        Predicate<ResponseAPDU> stopUnlessRemoved = response -> {
            log(String.format("    <- SW=%04X  dataLen=%d", response.getSW(), response.getData().length));
            if (response.getSW() == SW_OK) {
                deleteCount[0]++;
                return false;
            }
            if (response.getSW() == SW_REFERENCED_DATA_NOT_FOUND
                    && deleteTargetsExpectedAid[0]) {
                notFoundCount[0]++;
                return false;
            }
            return true;
        };
        try {
            List<ResponseAPDU> responses = session.run(channel, visitor, stopUnlessRemoved);
            log(String.format("    %s: %d response, %d response 9000, %d response 6A88 on expected DELETE",
                    label, responses.size(), deleteCount[0], notFoundCount[0]));
            return true;
        } catch (ScriptFailedException e) {
            log("    " + label + ": DUNG vi SW khong duoc phep");
            return false;
        } catch (RuntimeException e) {
            log("    " + label + ": loi " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isDeleteForAid(CommandAPDU command, String expectedAidHex) {
        if (command.getCLA() != 0x80 || command.getINS() != 0xE4
                || command.getP1() != 0 || command.getP2() != 0) {
            return false;
        }
        byte[] data = command.getData();
        byte[] expectedAid = hex(expectedAidHex);
        if (data.length != expectedAid.length + 2 || (data[0] & 0xFF) != 0x4F
                || (data[1] & 0xFF) != expectedAid.length) {
            return false;
        }
        for (int index = 0; index < expectedAid.length; index++) {
            if (data[index + 2] != expectedAid[index]) {
                return false;
            }
        }
        return true;
    }

    private static AMService openService(String propsPath)
            throws IOException, java.security.NoSuchProviderException {
        AMService ams = AMServiceFactory.getInstance("GP2.2");
        if (propsPath != null) {
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(propsPath)) {
                props.load(in);
            }
            ams.setProperties(props);
        }
        return ams;
    }

    private static boolean runScript(AMSession session, CardChannel channel, String label) {
        try {
            List<ResponseAPDU> responses = session.run(channel, LOG_COMMAND, STOP_ON_ERROR);
            log("    " + label + ": " + responses.size() + " response, tat ca 9000");
            return true;
        } catch (ScriptFailedException e) {
            log("    " + label + ": DUNG vi SW khac 9000");
            return false;
        } catch (RuntimeException e) {
            log("    " + label + ": loi " + e.getClass().getSimpleName()
                    + " (chi tiet bo qua de tranh ghi du lieu nhay cam)");
            return false;
        }
    }

    private static boolean probeInstance(CardChannel channel) throws Exception {
        ResponseAPDU select = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, hex(INST_HEX), 256));
        log(String.format("    SELECT %s -> SW=%04X", INST_HEX, select.getSW()));
        if (select.getSW() != SW_OK) {
            return false;
        }

        ResponseAPDU status = channel.transmit(new CommandAPDU(0x80, 0x10, 0x00, 0x00, 256));
        byte[] data = status.getData();
        log(String.format("    GET_STATUS   -> SW=%04X dataLen=%d", status.getSW(), data.length));
        if (status.getSW() != SW_OK) {
            return false;
        }
        return matchesStatusSchema(data);
    }

    private static boolean matchesStatusSchema(byte[] data) {
        if (data.length < 11) {
            log("    schema: response ngan hon 11 byte header");
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                log("    schema: magic khong khop");
                return false;
            }
        }
        if ((data[9] & 0xFF) != PROTOCOL_VERSION) {
            log(String.format("    schema: protocol version %02X khong ho tro", data[9] & 0xFF));
            return false;
        }
        int aidLength = data[10] & 0xFF;
        int expectedLength = 11 + aidLength + 1 + CARD_ID_LENGTH;
        if (data.length != expectedLength) {
            log(String.format("    schema: do dai %d khac %d theo aidLen=%d",
                    data.length, expectedLength, aidLength));
            return false;
        }
        String returnedAid = hexOf(data, 11, aidLength);
        log("    instance AID tra ve: " + returnedAid);
        if (!INST_HEX.equalsIgnoreCase(returnedAid)) {
            log("    schema: AID tra ve khac AID da yeu cau");
            return false;
        }
        log(String.format("    lifecycle=%02X cardId=%s",
                data[11 + aidLength] & 0xFF, hexOf(data, 12 + aidLength, CARD_ID_LENGTH)));
        return true;
    }

    private static Card connect(String host, int port) throws Exception {
        CardTerminal terminal = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(host, port)),
                "SocketCardTerminalProvider").terminals().list().get(0);
        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new IllegalStateException("Khong co the");
        }
        Card card = terminal.connect("*");
        log("Connection: " + terminal.getName());
        return card;
    }

    private static void record(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        log(String.format("    [%s] %s %s", ok ? "PASS" : "FAIL", name, detail));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexOf(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X", data[offset + i]));
        }
        return sb.toString();
    }

    private static void log(String s) {
        System.out.println(s);
    }
}
