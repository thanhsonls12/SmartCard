import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

/** Run on a fresh, selected test instance with the matching post-mutation fault CAP. */
public final class ProfileRollbackCases {
    private static final int SW_OK = 0x9000;
    private static final int SW_INTERNAL = 0x6F00;
    private static final int META_LENGTH = 65;
    private static final byte[] CARD_ID = {
        (byte) 0xA1, (byte) 0xB2, (byte) 0xC3, (byte) 0xD4, 0x11, 0x22, 0x33, 0x44
    };
    private final CardChannel channel;
    private final byte[] adminPin;
    private final byte[] studentPin;
    private int checks;

    private ProfileRollbackCases(CardChannel channel, byte[] adminPin, byte[] studentPin) {
        this.channel = channel;
        this.adminPin = adminPin;
        this.studentPin = studentPin;
    }

    /** Returns zero on success; throws on the first failed assertion. */
    public static int run(CardChannel channel, byte[] adminPin, byte[] studentPin,
            boolean identityFault) throws Exception {
        ProfileRollbackCases cases = new ProfileRollbackCases(channel, adminPin, studentPin);
        cases.v2Rollback(identityFault);
        System.out.println("PROFILE-ROLLBACK mode=" + (identityFault ? "identity-v2" : "rename-v2")
                + " checks=" + cases.checks + " failed=0");
        return 0;
    }

    /** Call only with the identity fault CAP on a second fresh, selected instance. */
    public static int runLegacyUpgrade(CardChannel channel, byte[] adminPin, byte[] studentPin)
            throws Exception {
        ProfileRollbackCases cases = new ProfileRollbackCases(channel, adminPin, studentPin);
        cases.legacyUpgradeRollback();
        System.out.println("PROFILE-ROLLBACK mode=identity-upgrade checks=" + cases.checks + " failed=0");
        return 0;
    }

    private void v2Rollback(boolean identityFault) throws Exception {
        String originalId = "CT2026001";
        String originalName = "Nguyễn Văn Á";
        byte[] originalExtension = extension("CNTT1", "Công nghệ thông tin");
        byte[] original = plaintext(originalId, originalName, originalExtension);
        personalize(originalId, originalName, originalExtension);
        require(Arrays.equals(profile(), original), "initial-v2-entire-profile");
        byte[] before = metadata();
        requireMetadata(before, original);

        String targetId = identityFault ? "AT2026002" : originalId;
        byte[] targetExtension = identityFault
                ? extension("AT20ABC", "An toàn thông tin") : originalExtension;
        String grow = "~" + "Á".repeat(45);
        String shrink = "~Ú";
        for (int attempt = 0; attempt < 2; attempt++) {
            String attemptedName = attempt == 0 ? grow : shrink;
            byte[] attempted = plaintext(targetId, attemptedName, targetExtension);
            require(attempt == 0 ? attempted.length > original.length : attempted.length < original.length,
                    "fault-" + attempt + "-changes-record-length");
            expect("fault-" + attempt + "-post-mutation", identityFault ? 0x42 : 0x41,
                    identityFault ? identityPayload(before, targetId, attemptedName, targetExtension)
                            : renamePayload(attemptedName), SW_INTERNAL, 0);
            unchanged(original, before, "fault-" + attempt);
        }

        String finalName = "Đỗ Ánh 🌟";
        expect("success-retry", identityFault ? 0x42 : 0x41,
                identityFault ? identityPayload(before, targetId, finalName, targetExtension)
                        : renamePayload(finalName), SW_OK, 0);
        byte[] expected = plaintext(targetId, finalName, targetExtension);
        require(Arrays.equals(profile(), expected), "success-entire-v2-profile");
        byte[] after = metadata();
        requireMetadata(after, expected);
        requireAdvancedThree(before, after);
    }

    private void legacyUpgradeRollback() throws Exception {
        String originalId = "CT2026003";
        String originalName = "Á".repeat(45);
        byte[] original = plaintext(originalId, originalName, null);
        personalize(originalId, originalName, null);
        require(Arrays.equals(profile(), original), "initial-legacy-entire-profile");
        byte[] before = metadata();
        requireMetadata(before, original);

        String targetId = "AT2026004";
        byte[] targetExtension = extension("AT19A", "An toàn thông tin");
        String[] names = {"~" + "Á".repeat(45), "~Ú"};
        for (int attempt = 0; attempt < names.length; attempt++) {
            byte[] attempted = plaintext(targetId, names[attempt], targetExtension);
            require(attempt == 0 ? attempted.length > original.length : attempted.length < original.length,
                    "upgrade-fault-" + attempt + "-changes-record-length");
            expect("upgrade-fault-" + attempt + "-post-mutation", 0x42,
                    identityPayload(before, targetId, names[attempt], targetExtension), SW_INTERNAL, 0);
            unchanged(original, before, "upgrade-fault-" + attempt);
        }

        String finalName = "Trần Hải 🌟";
        expect("upgrade-success-retry", 0x42,
                identityPayload(before, targetId, finalName, targetExtension), SW_OK, 0);
        byte[] expected = plaintext(targetId, finalName, targetExtension);
        require(Arrays.equals(profile(), expected), "upgrade-success-entire-v2-profile");
        byte[] after = metadata();
        requireMetadata(after, expected);
        requireAdvancedThree(before, after);
    }

    private void personalize(String id, String name, byte[] extension) throws Exception {
        require(adminPin.length == 8 && studentPin.length == 6, "pin-length-preconditions");
        expect("personalize", 0x30,
                concat(new byte[] {8}, adminPin, CARD_ID, field(ascii(id)), studentPin,
                        field(utf8(name)), extension == null ? new byte[0] : extension), SW_OK, 256);
        ResponseAPDU response = send(0x10, null, 256);
        require(response.getSW() == SW_OK, "personalized-status-command");
        byte[] status = response.getData();
        require(status.length >= 9 && status[status.length - 9] == 1,
                "personalized-status-issued");
        require(status.length >= 8 && Arrays.equals(CARD_ID,
                Arrays.copyOfRange(status, status.length - 8, status.length)),
                "personalized-status-card-id");
    }

    private void unchanged(byte[] expectedProfile, byte[] expectedMetadata, String label)
            throws Exception {
        require(Arrays.equals(profile(), expectedProfile), label + "-entire-profile-unchanged");
        require(Arrays.equals(metadata(), expectedMetadata), label + "-all-65-metadata-bytes-unchanged");
    }

    private void requireMetadata(byte[] metadata, byte[] expectedProfile) {
        require(metadata[0] == 1 && metadata[1] == 1 && metadata[2] == expectedProfile[0],
                "metadata-record-algorithm-schema");
        require(((metadata[3] & 255) * 256 + (metadata[4] & 255)) == expectedProfile.length,
                "metadata-ciphertext-length");
    }

    private void requireAdvancedThree(byte[] before, byte[] after) {
        BigInteger first = new BigInteger(1, Arrays.copyOfRange(before, 5, 17));
        BigInteger last = new BigInteger(1, Arrays.copyOfRange(after, 5, 17));
        require(last.equals(first.add(BigInteger.valueOf(3))),
                "retry-nonce-advances-three-without-reusing-two-failed-reservations");
    }

    private byte[] profile() throws Exception {
        expect("verify-before-profile", 0x20, studentPin.clone(), SW_OK, 0);
        ResponseAPDU response = send(0x31, null, 256);
        require(response.getSW() == SW_OK, "get-profile");
        return response.getData();
    }

    private byte[] metadata() throws Exception {
        expect("verify-before-metadata", 0x20, studentPin.clone(), SW_OK, 0);
        ResponseAPDU response = send(0x40, null, META_LENGTH);
        require(response.getSW() == SW_OK, "get-metadata");
        byte[] metadata = response.getData();
        require(metadata.length == META_LENGTH, "metadata-exactly-65-bytes");
        return metadata;
    }

    private byte[] identityPayload(byte[] metadata, String id, String name, byte[] extension) {
        return concat(adminPin, Arrays.copyOfRange(metadata, 5, 17),
                field(ascii(id)), field(utf8(name)), extension);
    }

    private byte[] renamePayload(String name) {
        return concat(adminPin, field(utf8(name)));
    }

    private static byte[] plaintext(String id, String name, byte[] extension) {
        return concat(new byte[] {(byte) (extension == null ? 1 : 2)},
                field(ascii(id)), field(utf8(name)), extension == null ? new byte[0] : extension);
    }

    private static byte[] extension(String classCode, String major) {
        return concat(field(ascii(classCode)), field(utf8(major)));
    }

    private static byte[] field(byte[] bytes) {
        return concat(new byte[] {(byte) bytes.length}, bytes);
    }

    private ResponseAPDU send(int ins, byte[] data, int le) throws Exception {
        if (data == null) {
            return channel.transmit(new CommandAPDU(0x80, ins, 0, 0, le));
        }
        return channel.transmit(le == 0 ? new CommandAPDU(0x80, ins, 0, 0, data)
                : new CommandAPDU(0x80, ins, 0, 0, data, le));
    }

    private void expect(String label, int ins, byte[] data, int expectedSw, int le) throws Exception {
        try {
            int actualSw = send(ins, data, le).getSW();
            require(actualSw == expectedSw, label + " expected=" + Integer.toHexString(expectedSw)
                    + " actual=" + Integer.toHexString(actualSw));
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private void require(boolean condition, String label) {
        checks++;
        System.out.println("PROFILE-ROLLBACK " + label + " | " + (condition ? "PASS" : "FAIL"));
        if (!condition) {
            throw new IllegalStateException("PROFILE-ROLLBACK " + label);
        }
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) {
            out.writeBytes(value);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
