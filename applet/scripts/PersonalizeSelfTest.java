import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.IOException;
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

public final class PersonalizeSelfTest {

    private static final String ISD_AID = "aid:A000000151000000";
    private static final String PACKAGE_AID = "F04C414201";
    private static final String APPLET_AID = "F04C4142010101";
    private static final String TEST_AID_HEX = "F04C414201EE01";
    private static final byte[] TEST_AID = hex(TEST_AID_HEX);
    private static final int SW_OK = 0x9000;
    private static final int SW_WRONG_LENGTH = 0x6700;
    private static final int SW_WRONG_DATA = 0x6A80;
    private static final int SW_CONDITIONS = 0x6985;
    private static final int SW_RETRY_BASE = 0x63C0;
    private static final int SW_DELETE_NOT_FOUND = 0x6A88;
    private static final int LIFECYCLE_UNPERSONALIZED = 0x00;
    private static final int LIFECYCLE_ISSUED = 0x01;

    private static final byte CLA = (byte) 0x80;
    private static final byte INS_PERSONALIZE = (byte) 0x30;
    private static final byte INS_GET_STATUS = (byte) 0x10;
    private static final byte INS_GET_PUBLIC_KEY = (byte) 0x61;

    private static final byte[] CARD_ID = hex("A1B2C3D411223344");
    private static final byte[] STUDENT_ID = ascii("SV2024-001");
    private static final byte[] STUDENT_PIN = ascii("654321");
    private static final byte[] DISPLAY_NAME = "Nguyen Van A".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static int failed;

    public static void main(String[] args) throws Exception {
        byte[] adminPin = null;
        Card card = null;
        CardChannel channel = null;
        boolean testInstanceMayExist = false;
        String propertiesPath = args.length == 0 ? null : args[0];
        try {
            adminPin = readAdminPin();
            card = connect();
            channel = card.getBasicChannel();
            if (!removeTestInstance(propertiesPath, channel)) {
                throw new IllegalStateException("test AID cleanup failed");
            }
            testInstanceMayExist = true;
            if (!installTestInstance(propertiesPath, channel, adminPin)) {
                throw new IllegalStateException("test AID install failed");
            }
            selectTestInstance(channel);
            runCases(channel, adminPin);
        } catch (Exception exception) {
            failed++;
            log("SUITE FAILED: " + exception.getClass().getSimpleName());
        } finally {
            if (testInstanceMayExist && !removeTestInstance(propertiesPath, channel)) {
                failed++;
                log("test AID cleanup failed");
            }
            clear(adminPin);
            if (card != null) {
                card.disconnect(true);
            }
        }
        log("KET LUAN: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")"));
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static boolean installTestInstance(String propertiesPath, CardChannel channel,
            byte[] adminPin) throws Exception {
        byte[] installData = new byte[10];
        installData[0] = 1;
        installData[1] = 8;
        System.arraycopy(adminPin, 0, installData, 2, adminPin.length);
        try {
            AMService service = openService(propertiesPath);
            AMSession session = service.openSession(ISD_AID)
                    .install("aid:" + PACKAGE_AID, "aid:" + APPLET_AID,
                            "aid:" + TEST_AID_HEX, installData).close();
            final int[] lastStatus = { 0 };
            Predicate<ResponseAPDU> stopOnError = response -> {
                lastStatus[0] = response.getSW();
                log(String.format("INSTALL test AID <- SW=%04X", response.getSW()));
                return response.getSW() != SW_OK;
            };
            session.run(channel, command -> {
                log(String.format("INSTALL test AID -> %02X %02X %02X %02X",
                        command.getCLA(), command.getINS(), command.getP1(), command.getP2()));
                return command;
            }, stopOnError);
            return lastStatus[0] == SW_OK;
        } finally {
            clear(installData);
        }
    }

    private static boolean removeTestInstance(String propertiesPath, CardChannel channel) {
        if (channel == null) {
            return false;
        }
        boolean[] deleteTargetsTestAid = { false };
        Predicate<ResponseAPDU> stopOnUnexpectedStatus = response -> {
            boolean allowedAbsent = response.getSW() == SW_DELETE_NOT_FOUND
                    && deleteTargetsTestAid[0];
            log(String.format("DELETE test AID <- SW=%04X", response.getSW()));
            return response.getSW() != SW_OK && !allowedAbsent;
        };
        try {
            AMService service = openService(propertiesPath);
            AMSession session = service.openSession(ISD_AID)
                    .uninstall("aid:" + TEST_AID_HEX).close();
            List<ResponseAPDU> responses = session.run(channel, command -> {
                log(String.format("DELETE test AID -> %02X %02X %02X %02X",
                        command.getCLA(), command.getINS(), command.getP1(), command.getP2()));
                deleteTargetsTestAid[0] = isDeleteForTestAid(command);
                return command;
            }, stopOnUnexpectedStatus);
            return !responses.isEmpty();
        } catch (ScriptFailedException exception) {
            return false;
        } catch (Exception exception) {
            log("test AID cleanup error: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isDeleteForTestAid(CommandAPDU command) {
        if (command.getCLA() != 0x80 || command.getINS() != 0xE4
                || command.getP1() != 0 || command.getP2() != 0) {
            return false;
        }
        byte[] data = command.getData();
        if (data.length != TEST_AID.length + 2 || (data[0] & 0xFF) != 0x4F
                || (data[1] & 0xFF) != TEST_AID.length) {
            return false;
        }
        for (int index = 0; index < TEST_AID.length; index++) {
            if (data[index + 2] != TEST_AID[index]) {
                return false;
            }
        }
        return true;
    }

    private static AMService openService(String propertiesPath) throws Exception {
        AMService service = AMServiceFactory.getInstance("GP2.2");
        if (propertiesPath != null) {
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(propertiesPath)) {
                properties.load(input);
            }
            service.setProperties(properties);
        }
        return service;
    }


    private static void runCases(CardChannel channel, byte[] adminPin) throws Exception {
        checkStatus(channel, "T01", LIFECYCLE_UNPERSONALIZED, null);
        record("T02", "GET_PUBLIC_KEY truoc PERSONALIZE", SW_CONDITIONS,
                transmit(channel, publicKeyCommand()).getSW());

        byte[] payload = validPayload(adminPin);
        payload[0] = 7;
        record("T03", "adminPinLength sai", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin);
        Arrays.fill(payload, 9, 17, (byte) 0);
        record("T04", "cardId toan 00", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin);
        payload[19] = (byte) '@';
        record("T05", "studentId co ky tu khong hop le", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin);
        int studentPinOffset = 18 + (payload[17] & 0xFF);
        payload[studentPinOffset] = (byte) 'A';
        record("T06", "student PIN sai dinh dang", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin);
        int nameLengthOffset = studentPinOffset + 6;
        payload[nameLengthOffset] = 0;
        record("T07", "displayNameLength bang 0", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin);
        byte[] truncated = Arrays.copyOf(payload, payload.length - 1);
        record("T08", "payload thieu 1 byte", SW_WRONG_LENGTH,
                transmit(channel, personalizeCommand(truncated)).getSW());
        clear(payload);
        clear(truncated);
        checkStatus(channel, "T09", LIFECYCLE_UNPERSONALIZED, new byte[8]);

        byte[] wrongPin = adminPin.clone();
        wrongPin[0] = wrongPin[0] == (byte) '9' ? (byte) '8' : (byte) (wrongPin[0] + 1);
        payload = validPayload(wrongPin);
        clear(wrongPin);
        record("T10", "admin PIN sai lan 1", SW_RETRY_BASE | 4,
                transmit(channel, personalizeCommand(payload)).getSW());
        record("T11", "admin PIN sai lan 2", SW_RETRY_BASE | 3,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);
        checkStatus(channel, "T12", LIFECYCLE_UNPERSONALIZED, new byte[8]);

        payload = validPayload(adminPin);
        record("T13", "PERSONALIZE admin dung", SW_OK,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        checkStatus(channel, "T14", LIFECYCLE_ISSUED, CARD_ID);
        ResponseAPDU publicKeyResponse = transmit(channel, publicKeyCommand());
        record("T15", "GET_PUBLIC_KEY sau PERSONALIZE", SW_OK, publicKeyResponse.getSW());
        byte[] publicKey = publicKeyResponse.getData();
        boolean keyShape = publicKeyResponse.getSW() == SW_OK
                && publicKey.length == 65 && publicKey[0] == 0x04;
        recordBoolean("T16", "public key 65 byte uncompressed", keyShape);
        if (keyShape) {
            verifyP256Point(publicKey);
        }

        payload = validPayload(adminPin);
        record("T17", "PERSONALIZE lan hai", SW_CONDITIONS,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);
        ResponseAPDU secondKeyResponse = transmit(channel, publicKeyCommand());
        recordBoolean("T18", "public key khong thay doi",
                secondKeyResponse.getSW() == SW_OK
                        && Arrays.equals(publicKey, secondKeyResponse.getData()));
        clear(publicKey);
        clear(secondKeyResponse.getData());

        ResponseAPDU maxLengthResponse = transmit(channel,
                new CommandAPDU(CLA, INS_GET_PUBLIC_KEY, 0, 0, 256));
        byte[] maxLengthKey = maxLengthResponse.getData();
        recordBoolean("T19", "GET_PUBLIC_KEY Le=00 tra 65 byte",
                maxLengthResponse.getSW() == SW_OK && maxLengthKey.length == 65
                        && maxLengthKey[0] == 0x04);
        clear(maxLengthKey);

        record("T20", "GET_PUBLIC_KEY Le=64", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PUBLIC_KEY, 0, 0, 64)).getSW());
    }

    private static void selectTestInstance(CardChannel channel) throws Exception {
        ResponseAPDU response = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, TEST_AID, 256));
        if (response.getSW() != SW_OK) {
            throw new IllegalStateException("test instance SELECT failed");
        }
    }

    private static void checkStatus(CardChannel channel, String id, int expectedLifecycle,
            byte[] expectedCardId) throws Exception {
        ResponseAPDU response = transmit(channel,
                new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256));
        record(id, "GET_STATUS", SW_OK, response.getSW());
        byte[] data = response.getData();
        int lifecycleOffset = data.length > 10 ? 11 + (data[10] & 0xFF) : data.length;
        boolean valid = response.getSW() == SW_OK && data.length >= lifecycleOffset + 1
                && (data[lifecycleOffset] & 0xFF) == expectedLifecycle;
        if (expectedCardId != null) {
            valid &= data.length >= lifecycleOffset + 1 + expectedCardId.length;
            if (valid) {
                valid &= Arrays.equals(expectedCardId,
                        Arrays.copyOfRange(data, lifecycleOffset + 1,
                                lifecycleOffset + 1 + expectedCardId.length));
            }
        }
        recordBoolean(id + "-STATE", "lifecycle/cardId", valid);
        clear(data);
    }

    private static void verifyP256Point(byte[] point) throws Exception {
        log("    publicKey=" + encodeHex(point));
        if (point.length != 65 || point[0] != 0x04) {
            recordBoolean("T16-P256", "Node crypto chap nhan P-256", false);
            return;
        }
        String script = "const c=require('crypto');const p=Buffer.from(process.argv[1],'hex');"
                + "const k=c.createPublicKey({key:{kty:'EC',crv:'P-256',"
                + "x:p.subarray(1,33).toString('base64url'),"
                + "y:p.subarray(33,65).toString('base64url')},format:'jwk'});"
                + "if(k.asymmetricKeyType!=='ec'||"
                + "k.asymmetricKeyDetails.namedCurve!=='prime256v1')process.exit(1);";
        Process process = new ProcessBuilder("node", "-e", script, encodeHex(point))
                .inheritIO()
                .start();
        recordBoolean("T16-P256", "Node crypto chap nhan P-256", process.waitFor() == 0);
    }

    private static byte[] validPayload(byte[] adminPin) {
        byte[] payload = new byte[25 + STUDENT_ID.length + DISPLAY_NAME.length];
        int offset = 0;
        payload[offset++] = 8;
        System.arraycopy(adminPin, 0, payload, offset, 8);
        offset += 8;
        System.arraycopy(CARD_ID, 0, payload, offset, CARD_ID.length);
        offset += CARD_ID.length;
        payload[offset++] = (byte) STUDENT_ID.length;
        System.arraycopy(STUDENT_ID, 0, payload, offset, STUDENT_ID.length);
        offset += STUDENT_ID.length;
        System.arraycopy(STUDENT_PIN, 0, payload, offset, STUDENT_PIN.length);
        offset += STUDENT_PIN.length;
        payload[offset++] = (byte) DISPLAY_NAME.length;
        System.arraycopy(DISPLAY_NAME, 0, payload, offset, DISPLAY_NAME.length);
        return payload;
    }

    private static CommandAPDU personalizeCommand(byte[] data) {
        return new CommandAPDU(CLA, INS_PERSONALIZE, 0, 0, data, 256);
    }

    private static CommandAPDU publicKeyCommand() {
        return new CommandAPDU(CLA, INS_GET_PUBLIC_KEY, 0, 0, 65);
    }

    private static ResponseAPDU transmit(CardChannel channel, CommandAPDU command) throws Exception {
        log(String.format("    -> %02X %02X %02X %02X dataLen=%d", command.getCLA(),
                command.getINS(), command.getP1(), command.getP2(), command.getData().length));
        ResponseAPDU response = channel.transmit(command);
        log(String.format("    <- SW=%04X dataLen=%d", response.getSW(), response.getData().length));
        return response;
    }

    private static byte[] readAdminPin() throws Exception {
        byte[] pin = new byte[8];
        boolean valid = false;
        try {
            int count = 0;
            int value;
            while ((value = System.in.read()) != -1 && value != '\n' && value != '\r') {
                if (count >= pin.length) {
                    throw new IllegalArgumentException("admin PIN length invalid");
                }
                pin[count++] = (byte) value;
            }
            if (count != pin.length) {
                throw new IllegalArgumentException("admin PIN length invalid");
            }
            for (byte digit : pin) {
                if (digit < '0' || digit > '9') {
                    throw new IllegalArgumentException("admin PIN format invalid");
                }
            }
            valid = true;
            return pin;
        } finally {
            if (!valid) {
                clear(pin);
            }
        }
    }

    private static Card connect() throws Exception {
        CardTerminal terminal = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress("127.0.0.1", 9025)),
                "SocketCardTerminalProvider").terminals().list().get(0);
        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new IllegalStateException("no card present");
        }
        return terminal.connect("*");
    }

    private static void record(String id, String label, int expected, int actual) {
        recordBoolean(id, label + " expected=" + String.format("%04X", expected)
                + " actual=" + String.format("%04X", actual), expected == actual);
    }

    private static void recordBoolean(String id, String label, boolean passed) {
        if (!passed) {
            failed++;
        }
        log(id + " | " + label + " | " + (passed ? "PASS" : "FAIL"));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String encodeHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xFF));
        }
        return result.toString();
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
