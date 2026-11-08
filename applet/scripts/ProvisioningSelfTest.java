
import java.net.InetSocketAddress;
import java.util.Arrays;
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
import com.oracle.javacard.ams.script.ScriptFailedException;
import com.oracle.javacard.ams.script.ScriptVisitor;
import com.oracle.javacard.ams.config.CAPFile;

public final class ProvisioningSelfTest {

    private static final String ISD_AID = "aid:A000000151000000";
    private static final String PKG_HEX = "F04C414201";
    private static final String APPLET_HEX = "F04C4142010101";
    private static final String DEFAULT_TEST_INSTANCE_HEX = "F04C414201EE01";

    private static String testInstanceHex = DEFAULT_TEST_INSTANCE_HEX;
    private static boolean negativeOnly;
    private static String loadCapPath;
    private static String propsPath;

    private static final int SW_OK = 0x9000;
    private static final int ADMIN_PIN_LENGTH = 8;
    private static final byte PROVISIONING_FORMAT = 0x01;

    private static final ScriptVisitor<CommandAPDU> LOG_HEADER = cmd -> {
        byte[] b = cmd.getBytes();
        log(String.format("    -> %02X %02X %02X %02X  (%d byte)",
                b[0], b[1], b[2], b[3], b.length));
        return cmd;
    };

    private static int failed;

    public static void main(String[] args) throws Exception {
        propsPath = args.length > 0 ? args[0] : null;
        if (args.length > 1) {
            testInstanceHex = args[1];
        }
        negativeOnly = args.length > 2 && args[2].equals("negative");
        loadCapPath = args.length > 3 ? args[3] : null;
        byte[] pin = readAdminPin();
        Card card = connect();
        try {
            CardChannel channel = card.getBasicChannel();
            AMService ams = openService(propsPath);
            runCases(ams, channel, pin);
        } finally {
            Arrays.fill(pin, (byte) 0);
            card.disconnect(true);
        }
        log("");
        log("========================================");
        log("KET LUAN: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")"));
        log("========================================");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void runCases(AMService ams, CardChannel channel, byte[] pin) throws Exception {
        log("--- Provisioning install parameters (instance " + testInstanceHex + ") ---");
        if (loadCapPath != null) {
            loadPackage(loadCapPath, channel);
        }
        byte[] valid = payload(PROVISIONING_FORMAT, (byte) ADMIN_PIN_LENGTH, pin);
        if (!negativeOnly) {
            removeTestInstance(ams, channel);
            int swValid = attemptInstall(ams, channel, valid);
            record("P01", "payload dung (format 01, len 08, 8 chu so)", 0, swValid);
            removeTestInstance(ams, channel);
        }

        record("P02", "thieu application data", -1, attemptInstall(ams, channel, new byte[0]));
        recordAbsent(channel, "P02", "thieu application data");

        byte[] badVersion = payload((byte) 0x02, (byte) ADMIN_PIN_LENGTH, pin);
        record("P03", "version khong ho tro (02)", -1, attemptInstall(ams, channel, badVersion));
        recordAbsent(channel, "P03", "version khong ho tro");

        byte[] shortPin = payload(PROVISIONING_FORMAT, (byte) 7, Arrays.copyOf(pin, 7));
        record("P04", "khai bao PIN 7 chu so", -1, attemptInstall(ams, channel, shortPin));
        recordAbsent(channel, "P04", "PIN thieu byte");

        byte[] letterPin = Arrays.copyOf(pin, ADMIN_PIN_LENGTH);
        letterPin[3] = (byte) 'A';
        byte[] badDigit = payload(PROVISIONING_FORMAT, (byte) ADMIN_PIN_LENGTH, letterPin);
        record("P05", "PIN co ky tu ngoai 0-9", -1, attemptInstall(ams, channel, badDigit));
        recordAbsent(channel, "P05", "PIN co ky tu ngoai 0-9");

        byte[] shortBody = new byte[2 + ADMIN_PIN_LENGTH - 1];
        shortBody[0] = PROVISIONING_FORMAT;
        shortBody[1] = (byte) ADMIN_PIN_LENGTH;
        System.arraycopy(pin, 0, shortBody, 2, ADMIN_PIN_LENGTH - 1);
        record("P06", "khai bao 8 byte nhung chi co 7", -1, attemptInstall(ams, channel, shortBody));
        recordAbsent(channel, "P06", "thieu byte so voi khai bao");

        byte[] extraByte = new byte[2 + ADMIN_PIN_LENGTH + 1];
        extraByte[0] = PROVISIONING_FORMAT;
        extraByte[1] = (byte) ADMIN_PIN_LENGTH;
        System.arraycopy(pin, 0, extraByte, 2, ADMIN_PIN_LENGTH);
        extraByte[2 + ADMIN_PIN_LENGTH] = (byte) 0x00;
        record("P07", "payload co byte du", -1, attemptInstall(ams, channel, extraByte));
        recordAbsent(channel, "P07", "payload co byte du");

        Arrays.fill(valid, (byte) 0);
        Arrays.fill(badVersion, (byte) 0);
        Arrays.fill(shortPin, (byte) 0);
        Arrays.fill(letterPin, (byte) 0);
        Arrays.fill(badDigit, (byte) 0);
        Arrays.fill(shortBody, (byte) 0);
        Arrays.fill(extraByte, (byte) 0);
    }

    private static void loadPackage(String capPath, CardChannel channel) throws Exception {
        CAPFile capFile = CAPFile.from(capPath);
        AMService ams = openService(propsPath);
        AMSession session = ams.openSession(ISD_AID)
                .load("aid:" + PKG_HEX, capFile.getBytes())
                .close();
        boolean ok;
        try {
            session.run(channel, LOG_HEADER, r -> r.getSW() != SW_OK);
            ok = true;
        } catch (ScriptFailedException e) {
            ok = false;
        }
        log("    load package " + PKG_HEX + ": " + (ok ? "PASS" : "FAIL"));
    }

    private static int attemptInstall(AMService ams, CardChannel channel, byte[] payload) {
        final int[] lastSw = { 0 };
        Predicate<ResponseAPDU> stopOnError = r -> {
            lastSw[0] = r.getSW();
            log(String.format("    <- SW=%04X  dataLen=%d", r.getSW(), r.getData().length));
            return r.getSW() != SW_OK;
        };
        try {
            AMSession session = ams.openSession(ISD_AID)
                    .install("aid:" + PKG_HEX, "aid:" + APPLET_HEX, "aid:" + testInstanceHex,
                            payload)
                    .close();
            session.run(channel, LOG_HEADER, stopOnError);
            return 0;
        } catch (ScriptFailedException e) {
            return lastSw[0] == 0 ? -1 : lastSw[0];
        } catch (RuntimeException e) {
            return -2;
        }
    }

    private static void recordAbsent(CardChannel channel, String id, String label) throws Exception {
        boolean absent = testInstanceAbsent(channel);
        if (!absent) {
            failed++;
        }
        log(String.format("%s | %-46s | khong tao instance | %s", id, label,
                absent ? "PASS" : "FAIL"));
    }

    private static boolean testInstanceAbsent(CardChannel channel) throws Exception {
        ResponseAPDU select = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, hex(testInstanceHex), 256));
        log(String.format("    SELECT %s -> SW=%04X", testInstanceHex, select.getSW()));
        return select.getSW() != SW_OK;
    }

    private static void removeTestInstance(AMService ams, CardChannel channel) {
        Predicate<ResponseAPDU> stopUnlessOkOrMissing = r -> {
            log(String.format("    <- SW=%04X  dataLen=%d", r.getSW(), r.getData().length));
            return r.getSW() != SW_OK && r.getSW() != 0x6A88;
        };
        try {
            AMSession session = ams.openSession(ISD_AID)
                    .uninstall("aid:" + testInstanceHex).close();
            session.run(channel, LOG_HEADER, stopUnlessOkOrMissing);
        } catch (ScriptFailedException e) {
            log("    don instance thu: dung vi SW khac 9000/6A88");
        } catch (RuntimeException e) {
            log("    don instance thu: loi " + e.getClass().getSimpleName());
        }
    }

    private static byte[] payload(byte format, byte declaredLength, byte[] pin) {
        byte[] payload = new byte[2 + pin.length];
        payload[0] = format;
        payload[1] = declaredLength;
        System.arraycopy(pin, 0, payload, 2, pin.length);
        return payload;
    }

    private static byte[] readAdminPin() throws Exception {
        byte[] pin = new byte[ADMIN_PIN_LENGTH];
        int count = 0;
        int value = -1;
        while (count < ADMIN_PIN_LENGTH && (value = System.in.read()) != -1) {
            if (value == 10 || value == 13) {
                break;
            }
            pin[count] = (byte) value;
            count++;
        }
        if (count != ADMIN_PIN_LENGTH) {
            Arrays.fill(pin, (byte) 0);
            throw new IllegalStateException("Admin PIN phai co dung 8 chu so");
        }
        for (int index = 0; index < ADMIN_PIN_LENGTH; index++) {
            if (pin[index] < 48 || pin[index] > 57) {
                Arrays.fill(pin, (byte) 0);
                throw new IllegalStateException("Admin PIN chi duoc chua chu so 0-9");
            }
        }
        return pin;
    }

    private static AMService openService(String propsPath) throws Exception {
        AMService ams = AMServiceFactory.getInstance("GP2.2");
        if (propsPath != null) {
            Properties props = new Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(propsPath)) {
                props.load(in);
            }
            ams.setProperties(props);
        }
        return ams;
    }

    private static Card connect() throws Exception {
        CardTerminal terminal = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress("127.0.0.1", 9025)),
                "SocketCardTerminalProvider").terminals().list().get(0);
        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new IllegalStateException("Khong co the");
        }
        return terminal.connect("*");
    }

    private static void record(String id, String label, int expected, int actual) {
        boolean ok = expected == 0 ? actual == 0 : actual > 0;
        if (!ok) {
            failed++;
        }
        log(String.format("%s | %-46s | expected=%s actual=%s | %s", id, label,
                expected == 0 ? "cai dat thanh cong" : "bi tu choi",
                actual == 0 ? "cai dat thanh cong" : String.format("SW=%04X", actual),
                ok ? "PASS" : "FAIL"));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void log(String s) {
        System.out.println(s);
    }
}
