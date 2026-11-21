package vn.labaccess.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

/**
 * ADGR-12 trên Simulator: lệnh đọc Admin không mở quyền Student.
 */
public final class Adgr12BoundaryVerify {

    private static final int SW_OK = 0x9000;
    private static final int SW_SECURITY = 0x6982;
    private static final int AUTH_STATE_LENGTH = 23;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("labaccess.host", "127.0.0.1");
        int port = Integer.getInteger("labaccess.port");
        Path evidence = Path.of(System.getProperty("labaccess.evidence", "logs"));
        Files.createDirectories(evidence);
        char[] admin = readPin(8);
        char[] student = readPin(6);
        String cardId = cardId();
        String studentId = "AD12" + cardId.substring(0, 4);
        System.out.println("ADGR-12 cardId=" + cardId + " studentId=" + studentId);
        System.out.println("CAP=" + System.getProperty("labaccess.cap", "khong-truyen"));
        System.out.println("CAP-SHA256=" + System.getProperty("labaccess.capHash", "khong-truyen"));
        CardGateway gateway = new CardGateway(host, port);
        try {
            require(gateway.connect().ok(), "ket noi");
            require(gateway.identify().ok(), "nhan dien");
            require(gateway.personalize(admin.clone(), cardId, studentId, student.clone(), "Pham Thi D").ok(),
                    "personalize");
            require(gateway.identify().ok(), "nhan dien sau phat hanh");
            ResponseAPDU granted = transmit(gateway, grant(admin, 5));
            require(granted.getSW() == SW_OK && granted.getData().length == 6, "GRANT tao receipt");
            require(gateway.uploadPhoto(new byte[PhotoImage.LENGTH], admin.clone()).ok(), "ghi anh");
            int publicLength = transmit(gateway, status()).getData().length;
            ResponseAPDU authorized = afterVerify(gateway, student);
            require(authorized.getSW() == SW_OK && authorized.getData().length == publicLength + AUTH_STATE_LENGTH,
                    "VERIFY mo state xac thuc");
            for (int ins : new int[] {0x11, 0x63}) {
                require(transmit(gateway, verify(student)).getSW() == SW_OK, "VERIFY truoc lenh admin");
                ResponseAPDU adminRead = transmit(gateway, adminCommand(ins, admin));
                require(adminRead.getSW() == SW_OK && adminRead.getData().length > publicLength,
                        "lenh admin " + hex(ins) + " doc duoc du lieu");
                ResponseAPDU after = transmit(gateway, status());
                require(after.getSW() == SW_OK && after.getData().length == publicLength,
                        "GET_STATUS sau admin " + hex(ins) + " chi con du lieu cong khai");
                requireRejected(gateway, ins);
            }
            require(transmit(gateway, verify(student)).getSW() == SW_OK, "VERIFY doi chung ho so");
            ResponseAPDU profile = transmit(gateway, new CommandAPDU(0x80, 0x31, 0x00, 0x00, 256));
            require(profile.getSW() == SW_OK && contains(profile.getData(), studentId),
                    "GET_PROFILE sau VERIFY doc duoc ho so");
            require(transmit(gateway, verify(student)).getSW() == SW_OK, "VERIFY doi chung meta");
            ResponseAPDU meta = transmit(gateway, new CommandAPDU(0x80, 0x40, 0x00, 0x00, 256));
            require(meta.getSW() == SW_OK && meta.getData().length == 65, "GET_PROFILE_CRYPTO_META sau VERIFY");
            require(transmit(gateway, verify(student)).getSW() == SW_OK, "VERIFY doi chung anh");
            ResponseAPDU photo = transmit(gateway, new CommandAPDU(0x80, 0x34, 0x00, 0x00, 128));
            require(photo.getSW() == SW_OK && photo.getData().length == 128, "PHOTO_READ_CHUNK sau VERIFY");
            System.out.println("publicLength=" + publicLength + " authorizedLength="
                    + (publicLength + AUTH_STATE_LENGTH));
            System.out.println("ADGR-12 PASS");
        } finally {
            java.util.Arrays.fill(admin, '\0');
            java.util.Arrays.fill(student, '\0');
            if (gateway.isConnected()) {
                gateway.disconnect();
            }
        }
    }

    private static void requireRejected(CardGateway gateway, int adminIns) throws Exception {
        ResponseAPDU profile = transmit(gateway, new CommandAPDU(0x80, 0x31, 0x00, 0x00, 256));
        ResponseAPDU meta = transmit(gateway, new CommandAPDU(0x80, 0x40, 0x00, 0x00, 256));
        ResponseAPDU photo = transmit(gateway, new CommandAPDU(0x80, 0x34, 0x00, 0x00, 128));
        require(profile.getSW() == SW_SECURITY && meta.getSW() == SW_SECURITY && photo.getSW() == SW_SECURITY,
                "sau admin " + hex(adminIns) + " ho so/meta/anh bi tu choi SW="
                        + hex(profile.getSW()) + "/" + hex(meta.getSW()) + "/" + hex(photo.getSW()));
        System.out.println("ADMIN " + hex(adminIns)
                + " -> GET_STATUS public; GET_PROFILE/META/PHOTO SW=6982");
    }

    private static ResponseAPDU afterVerify(CardGateway gateway, char[] student) throws Exception {
        require(transmit(gateway, verify(student)).getSW() == SW_OK, "VERIFY dau");
        return transmit(gateway, status());
    }

    private static CommandAPDU status() {
        return new CommandAPDU(0x80, 0x10, 0x00, 0x00, 256);
    }

    private static CommandAPDU verify(char[] student) {
        byte[] data = digits(student);
        return new CommandAPDU(0x80, 0x20, 0x00, 0x00, data);
    }

    private static CommandAPDU adminCommand(int ins, char[] admin) {
        return new CommandAPDU(0x80, ins, 0x00, 0x00, digits(admin), 256);
    }

    private static CommandAPDU grant(char[] admin, int delta) {
        byte[] pin = digits(admin);
        byte[] data = new byte[pin.length + 2];
        System.arraycopy(pin, 0, data, 0, pin.length);
        data[pin.length] = (byte) (delta >> 8);
        data[pin.length + 1] = (byte) delta;
        java.util.Arrays.fill(pin, (byte) 0);
        CommandAPDU command = new CommandAPDU(0x80, 0x50, 0x00, 0x00, data, 6);
        java.util.Arrays.fill(data, (byte) 0);
        return command;
    }

    private static byte[] digits(char[] pin) {
        byte[] data = new byte[pin.length];
        for (int index = 0; index < pin.length; index++) {
            data[index] = (byte) pin[index];
        }
        return data;
    }

    private static boolean contains(byte[] data, String text) {
        byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int start = 0; start + needle.length <= data.length; start++) {
            boolean same = true;
            for (int index = 0; index < needle.length; index++) {
                if (data[start + index] != needle[index]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    private static ResponseAPDU transmit(CardGateway gateway, CommandAPDU command) throws CardException {
        try {
            java.lang.reflect.Field field = CardGateway.class.getDeclaredField("transport");
            field.setAccessible(true);
            return ((CardTransport) field.get(gateway)).transmit(command);
        } catch (ReflectiveOperationException failure) {
            throw new CardException("khong gui duoc APDU", failure);
        }
    }

    private static String cardId() {
        byte[] value = new byte[8];
        RANDOM.nextBytes(value);
        value[0] = 1;
        return java.util.HexFormat.of().formatHex(value).toUpperCase();
    }

    private static String hex(int value) {
        return String.format("%02X", value);
    }

    private static java.io.BufferedReader pinReader;

    private static char[] readPin(int length) throws IOException {
        if (pinReader == null) {
            pinReader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                    java.nio.charset.StandardCharsets.US_ASCII));
        }
        String line = pinReader.readLine();
        if (line == null || line.strip().length() != length) {
            throw new IllegalStateException("PIN stdin sai do dai");
        }
        return line.strip().toCharArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
