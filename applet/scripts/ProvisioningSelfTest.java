
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

public final class ProvisioningSelfTest {

    private static final String ISD_AID = "aid:A000000151000000";
    private static final String PKG_HEX = "F04C414201";
    private static final String APPLET_HEX = "F04C4142010101";
    private static final String TEST_INSTANCE_HEX = "F04C414201EE01";

    private static boolean negativeOnly;
    private static String propsPath;

    private static final int SW_OK = 0x9000;
    private static final int SW_WRONG_DATA = 0x6A80;
    private static final int SW_INSTANCE_NOT_FOUND = 0x6A82;
    private static final int SW_DELETE_NOT_FOUND = 0x6A88;
    private static final int ADMIN_PIN_LENGTH = 8;
    private static final byte PROVISIONING_FORMAT = 0x01;

    private static final ScriptVisitor<CommandAPDU> LOG_HEADER = ProvisioningSelfTest::logCommand;

    private static int failed;

    public static void main(String[] args) {
        propsPath = args.length > 0 ? args[0] : null;
        negativeOnly = args.length > 1 && args[1].equals("negative");

        byte[] pin = null;
        Card card = null;
        try {
            pin = readAdminPin();
            card = connect();
            CardChannel channel = card.getBasicChannel();
            AMService ams = openService(propsPath);
            runCases(ams, channel, pin);
        } catch (Exception e) {
            failed++;
            log("SUITE DUNG SOM: " + e.getClass().getSimpleName());
        } finally {
            if (pin != null) {
                Arrays.fill(pin, (byte) 0);
            }
            if (card != null) {
                try {
                    card.disconnect(true);
                } catch (Exception e) {
                    failed++;
                    log("DONG KET NOI THAT BAI: " + e.getClass().getSimpleName());
                }
            }
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
        log("--- Provisioning install parameters (test instance " + TEST_INSTANCE_HEX + ") ---");
        byte[] valid = null;
        byte[] badVersion = null;
        byte[] shortPin = null;
        byte[] letterPin = null;
        byte[] badDigit = null;
        byte[] shortBody = null;
        byte[] extraByte = null;
        try {
            valid = payload(PROVISIONING_FORMAT, (byte) ADMIN_PIN_LENGTH, pin);
            if (!removeTestInstance(ams, channel)) {
                failed++;
                log("SUITE DUNG: khong don duoc test instance truoc tien kiem");
                return;
            }

            int swValid = attemptInstall(ams, channel, valid);
            record(negativeOnly ? "PRECHECK" : "P01",
                    "package/app registration san sang tren test AID", SW_OK, swValid);
            if (swValid != SW_OK) {
                log("SUITE DUNG: tien kiem INSTALL tren test AID khong thanh cong; kiem tra package da duoc chuan bi ben ngoai va AID applet");
                if (!removeTestInstance(ams, channel)) {
                    failed++;
                    log("SUITE DUNG: cleanup test instance that bai");
                }
                return;
            }
            if (!removeTestInstance(ams, channel)) {
                failed++;
                log("SUITE DUNG: cleanup test instance sau tien kiem that bai");
                return;
            }

            badVersion = payload((byte) 0x02, (byte) ADMIN_PIN_LENGTH, pin);
            if (!recordNegative(ams, channel, "P02", "thieu application data", new byte[0])) {
                return;
            }

            if (!recordNegative(ams, channel, "P03", "version khong ho tro (02)", badVersion)) {
                return;
            }

            shortPin = payload(PROVISIONING_FORMAT, (byte) 7, pin);
            if (!recordNegative(ams, channel, "P04", "khai bao PIN 7 chu so", shortPin)) {
                return;
            }

            letterPin = Arrays.copyOf(pin, ADMIN_PIN_LENGTH);
            letterPin[3] = (byte) 'A';
            badDigit = payload(PROVISIONING_FORMAT, (byte) ADMIN_PIN_LENGTH, letterPin);
            if (!recordNegative(ams, channel, "P05", "PIN co ky tu ngoai 0-9", badDigit)) {
                return;
            }

            shortBody = new byte[2 + ADMIN_PIN_LENGTH - 1];
            shortBody[0] = PROVISIONING_FORMAT;
            shortBody[1] = (byte) ADMIN_PIN_LENGTH;
            System.arraycopy(pin, 0, shortBody, 2, ADMIN_PIN_LENGTH - 1);
            if (!recordNegative(ams, channel, "P06", "khai bao 8 byte nhung chi co 7", shortBody)) {
                return;
            }

            extraByte = new byte[2 + ADMIN_PIN_LENGTH + 1];
            extraByte[0] = PROVISIONING_FORMAT;
            extraByte[1] = (byte) ADMIN_PIN_LENGTH;
            System.arraycopy(pin, 0, extraByte, 2, ADMIN_PIN_LENGTH);
            extraByte[2 + ADMIN_PIN_LENGTH] = (byte) 0x00;
            recordNegative(ams, channel, "P07", "payload co byte du", extraByte);
        } finally {
            clear(valid);
            clear(badVersion);
            clear(shortPin);
            clear(letterPin);
            clear(badDigit);
            clear(shortBody);
            clear(extraByte);
        }
    }

    private static boolean recordNegative(AMService ams, CardChannel channel, String id,
            String label, byte[] payload) throws Exception {
        int sw = attemptInstall(ams, channel, payload);
        record(id, label, SW_WRONG_DATA, sw);
        boolean absent = false;
        boolean cleaned;
        try {
            if (sw == SW_WRONG_DATA) {
                absent = recordAbsent(channel, id, label + " (khong tao instance)");
            } else {
                log(id + " | dung suite sau SW khong mong doi: " + String.format("%04X", sw));
            }
        } finally {
            cleaned = removeTestInstance(ams, channel);
            if (!cleaned) {
                failed++;
                log(id + " | cleanup test instance that bai; suite dung");
            }
        }
        return sw == SW_WRONG_DATA && absent && cleaned;
    }

    private static boolean removeTestInstance(AMService ams, CardChannel channel) {
        boolean[] deleteTargetsTestAid = { false };
        ScriptVisitor<CommandAPDU> visitor = command -> {
            logCommand(command);
            deleteTargetsTestAid[0] = isDeleteForAid(command, TEST_INSTANCE_HEX);
            return command;
        };
        Predicate<ResponseAPDU> stopOnUnexpectedStatus = response -> {
            log(String.format("    <- SW=%04X  dataLen=%d", response.getSW(), response.getData().length));
            return response.getSW() != SW_OK
                    && !(response.getSW() == SW_DELETE_NOT_FOUND && deleteTargetsTestAid[0]);
        };
        try {
            AMSession session = ams.openSession(ISD_AID)
                    .uninstall("aid:" + TEST_INSTANCE_HEX).close();
            List<ResponseAPDU> responses = session.run(channel, visitor, stopOnUnexpectedStatus);
            log("    cleanup test instance: " + responses.size() + " response; 6A88 chi duoc phep tren DELETE dung AID");
            return true;
        } catch (ScriptFailedException e) {
            log("    cleanup test instance: DUNG vi SW khong duoc phep");
            return false;
        } catch (RuntimeException e) {
            log("    cleanup test instance: loi " + e.getClass().getSimpleName());
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

    private static CommandAPDU logCommand(CommandAPDU command) {
        byte[] bytes = command.getBytes();
        log(String.format("    -> %02X %02X %02X %02X  (%d byte)",
                bytes[0], bytes[1], bytes[2], bytes[3], bytes.length));
        return command;
    }

    private static void clear(byte[] buffer) {
        if (buffer != null) {
            Arrays.fill(buffer, (byte) 0);
        }
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
                    .install("aid:" + PKG_HEX, "aid:" + APPLET_HEX, "aid:" + TEST_INSTANCE_HEX,
                            payload)
                    .close();
            session.run(channel, LOG_HEADER, stopOnError);
            return SW_OK;
        } catch (ScriptFailedException e) {
            return lastSw[0] == 0 ? -1 : lastSw[0];
        } catch (RuntimeException e) {
            return -2;
        }
    }

    private static boolean recordAbsent(CardChannel channel, String id, String label) throws Exception {
        ResponseAPDU select = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, hex(TEST_INSTANCE_HEX), 256));
        log(String.format("    SELECT %s -> SW=%04X", TEST_INSTANCE_HEX, select.getSW()));
        boolean absent = select.getSW() == SW_INSTANCE_NOT_FOUND;
        if (!absent) {
            failed++;
        }
        log(String.format("%s | %-46s | SELECT=%04X, doi 6A82 | %s", id, label,
                select.getSW(), absent ? "PASS" : "FAIL"));
        return absent;
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
        boolean accepted = false;
        try {
            int count = 0;
            int value;
            while ((value = System.in.read()) != -1) {
                if (value == 10 || value == 13) {
                    break;
                }
                if (count == ADMIN_PIN_LENGTH) {
                    throw new IllegalStateException("Admin PIN phai co dung 8 chu so");
                }
                pin[count++] = (byte) value;
            }
            if (count != ADMIN_PIN_LENGTH) {
                throw new IllegalStateException("Admin PIN phai co dung 8 chu so");
            }
            for (int index = 0; index < ADMIN_PIN_LENGTH; index++) {
                if (pin[index] < 48 || pin[index] > 57) {
                    throw new IllegalStateException("Admin PIN chi duoc chua chu so 0-9");
                }
            }
            accepted = true;
            return pin;
        } finally {
            if (!accepted) {
                Arrays.fill(pin, (byte) 0);
            }
        }
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
        boolean ok = actual == expected;
        if (!ok) {
            failed++;
        }
        String actualText = actual < 0 ? "khong co response" : String.format("SW=%04X", actual);
        log(String.format("%s | %-46s | expected=SW=%04X actual=%s | %s", id, label,
                expected, actualText, ok ? "PASS" : "FAIL"));
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
