import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

public final class ProfileV2Cases {

    private static final int SW_OK = 0x9000;
    private static final int SW_LENGTH = 0x6700;
    private static final int SW_DATA = 0x6A80;
    private static final int SW_CONDITIONS = 0x6985;
    private static final int PUBLIC_KEY_LENGTH = 65;
    private static final int SIGNATURE_MIN_LENGTH = 8;
    private static final int SIGNATURE_MAX_LENGTH = 72;
    private static final byte[] CARD_ID = hex("A1B2C3D411223344");
    private final CardChannel channel;
    private final byte[] adminPin;
    private final byte[] studentPin;
    private int checks;

    @FunctionalInterface
    public interface FreshInstance {
        void run() throws Exception;
    }

    private ProfileV2Cases(CardChannel channel, byte[] adminPin, byte[] studentPin) {
        this.channel = channel;
        this.adminPin = adminPin;
        this.studentPin = studentPin;
    }

    public static int run(CardChannel channel, byte[] adminPin, byte[] studentPin,
            FreshInstance fresh) throws Exception {
        ProfileV2Cases cases = new ProfileV2Cases(channel, adminPin, studentPin);
        cases.invalidPersonalizationDoesNotIssueCard();
        cases.legacyUpgradesAndPreservesFields();
        fresh.run();
        cases.personalizationRoundTripsMaximumExtension();
        System.out.println("PROFILE-V2 checks=" + cases.checks + " failed=0");
        return 0;
    }

    private void invalidPersonalizationDoesNotIssueCard() throws Exception {
        byte[] base = personalizePayload("CT2026001", "Sinh viên thử", new byte[0]);
        for (InvalidExtension invalid : invalidExtensions()) {
            expect("personalize-invalid-" + invalid.label(), 0x30, concat(base, invalid.bytes()),
                    invalid.sw(), 256);
            ResponseAPDU status = send(0x10, null, 256);
            require(status.getSW() == SW_OK && status.getData()[status.getData().length - 9] == 0,
                    "invalid-personalize-keeps-unissued");
            require(send(0x61, null, 65).getSW() == SW_CONDITIONS,
                    "invalid-personalize-keeps-keys-unavailable");
        }
        Arrays.fill(base, (byte) 0);
    }

    private void legacyUpgradesAndPreservesFields() throws Exception {
        expect("legacy-personalize", 0x30,
                personalizePayload("CT2026001", "Nguyễn Văn Á", new byte[0]), SW_OK, 256);
        byte[] legacy = plaintext("CT2026001", "Nguyễn Văn Á", null);
        require(Arrays.equals(profile(), legacy), "legacy-plaintext-roundtrip");
        require(metadata()[2] == 1, "legacy-metadata-schema");
        verifySignature(legacy);
        expect("legacy-identity-keeps-v1", 0x42,
                identityPayload("AT2026002", "Lê Bình", new byte[0]), SW_OK, 0);
        require(Arrays.equals(profile(), plaintext("AT2026002", "Lê Bình", null)),
                "legacy-identity-roundtrip");
        expect("legacy-rename", 0x41, renamePayload("Tên cũ"), SW_OK, 0);
        require(Arrays.equals(profile(), plaintext("AT2026002", "Tên cũ", null)),
                "legacy-rename-roundtrip");
        byte[] extension = extension("AT19A", "An toàn thông tin");
        expect("upgrade-v1-to-v2", 0x42,
                identityPayload("AT2026003", "Trần Hải 🌟", extension), SW_OK, 0);
        byte[] upgraded = plaintext("AT2026003", "Trần Hải 🌟", extension);
        require(Arrays.equals(profile(), upgraded), "v2-unicode-roundtrip");
        require(metadata()[2] == 2, "v2-metadata-schema");
        verifySignature(upgraded);
        byte[] before = metadata();
        expect("v2-rejects-legacy-identity", 0x42,
                identityPayload("CT2026004", "Field loss", new byte[0]), SW_CONDITIONS, 0);
        unchanged(upgraded, before);
        for (InvalidExtension invalid : invalidExtensions()) {
            before = metadata();
            expect("identity-invalid-" + invalid.label(), 0x42,
                    identityPayload("DT2026005", "Tên mới", invalid.bytes()), invalid.sw(), 0);
            unchanged(upgraded, before);
        }
        String longName = "Á".repeat(48);
        expect("rename-grows-to-96-utf8-bytes", 0x41, renamePayload(longName), SW_OK, 0);
        byte[] renamed = plaintext("AT2026003", longName, extension);
        require(Arrays.equals(profile(), renamed), "rename-growing-preserves-extension");
        require(metadata()[2] == 2, "rename-keeps-schema-v2");
        verifySignature(renamed);
        expect("rename-shrinks", 0x41, renamePayload("Ú"), SW_OK, 0);
        require(Arrays.equals(profile(), plaintext("AT2026003", "Ú", extension)),
                "rename-shrinking-preserves-extension");
        extension = extension("DT20A1B", "Điện tử viễn thông");
        expect("replace-v2-identity", 0x42,
                identityPayload("DT2026006", "Đỗ Ánh", extension), SW_OK, 0);
        byte[] replaced = plaintext("DT2026006", "Đỗ Ánh", extension);
        require(Arrays.equals(profile(), replaced), "v2-identity-replaces-all-fields");
        verifySignature(replaced);
        byte[] emptyMajor = extension("ZZ20A", "");
        expect("empty-major-for-unknown-prefix", 0x42,
                identityPayload("ZZ2026007", "Unknown", emptyMajor), SW_OK, 0);
        require(Arrays.equals(profile(), plaintext("ZZ2026007", "Unknown", emptyMajor)),
                "zero-byte-major-roundtrip");
        byte[] version = metadata();
        byte[] stale = identityPayload("AT2026008", "Stale", extension);
        stale[8] ^= 1;
        expect("stale-version", 0x42, stale, SW_CONDITIONS, 0);
        unchanged(plaintext("ZZ2026007", "Unknown", emptyMajor), version);
        byte[] canonical = plaintext("CT2026001", "Nguyễn Văn An",
                extension("CNTT1", "Công nghệ thông tin"));
        require(canonical.length == 58 && Arrays.equals(canonical,
                hex("0209435432303236303031104e677579e1bb856e2056c4836e20416e05434e545431"
                        + "1743c3b46e67206e6768e1bb87207468c3b46e672074696e")),
                "shared-canonical-v2-58-byte-vector");
        require(Arrays.equals(sha256(canonical),
                hex("17db24ee2fe1d98a4814c8fba92708fda95eff3e2fe5a3fe0089b7611ccbccd7")),
                "shared-canonical-v2-sha256");
        expect("shared-canonical-v2-identity", 0x42,
                identityPayload("CT2026001", "Nguyễn Văn An",
                        extension("CNTT1", "Công nghệ thông tin")), SW_OK, 0);
        require(Arrays.equals(profile(), canonical), "shared-canonical-v2-card-roundtrip");
        verifySignature(canonical);
    }

    private void personalizationRoundTripsMaximumExtension() throws Exception {
        String name = "Á".repeat(48);
        byte[] extension = extension("CT20ABC", "ế".repeat(20) + "😀");
        byte[] payload = personalizePayload("CT20260001", name, extension);
        require(payload.length == 204, "personalize-maximum-204-byte-payload");
        expect("personalize-maximum-v2", 0x30, payload, SW_OK, 256);
        byte[] expected = plaintext("CT20260001", name, extension);
        require(Arrays.equals(profile(), expected), "maximum-v2-roundtrip");
        byte[] metadata = metadata();
        require(metadata[2] == 2 && ((metadata[3] & 255) * 256 + (metadata[4] & 255)) == expected.length,
                "maximum-v2-metadata-schema-and-length");
        verifySignature(expected);
        byte[] replacement = extension("AT20ABC", "é".repeat(32));
        expect("identity-maximum-valid-fields", 0x42,
                identityPayload("AT20260001", name, replacement), SW_OK, 0);
        require(Arrays.equals(profile(), plaintext("AT20260001", name, replacement)),
                "maximum-identity-roundtrip");
    }

    private InvalidExtension[] invalidExtensions() {
        byte[] valid = extension("AT19A", "An toàn thông tin");
        byte[] missingMajorLength = concat(new byte[] {5}, ascii("AT19A"));
        return new InvalidExtension[] {
            new InvalidExtension("class-too-short", extension("AT19", ""), SW_DATA),
            new InvalidExtension("class-too-long", extension("AT19ABCD", ""), SW_DATA),
            new InvalidExtension("lowercase", extension("at19a", ""), SW_DATA),
            new InvalidExtension("punctuation", extension("AT-9A", ""), SW_DATA),
            new InvalidExtension("missing-class-bytes", new byte[] {5, 'A'}, SW_LENGTH),
            new InvalidExtension("missing-major-length", missingMajorLength, SW_LENGTH),
            new InvalidExtension("major-too-long", extension("AT19A", "A".repeat(65)), SW_DATA),
            new InvalidExtension("truncated-major", Arrays.copyOf(valid, valid.length - 1), SW_LENGTH),
            new InvalidExtension("extra-byte", concat(valid, new byte[] {0}), SW_LENGTH),
            new InvalidExtension("truncated-utf8", concat(missingMajorLength, new byte[] {1, (byte) 0xC3}), SW_DATA),
            new InvalidExtension("overlong-utf8", concat(missingMajorLength,
                    new byte[] {2, (byte) 0xC0, (byte) 0xAF}), SW_DATA),
            new InvalidExtension("surrogate-utf8", concat(missingMajorLength,
                    new byte[] {3, (byte) 0xED, (byte) 0xA0, (byte) 0x80}), SW_DATA)
        };
    }

    private void unchanged(byte[] expectedProfile, byte[] expectedMetadata) throws Exception {
        require(Arrays.equals(profile(), expectedProfile), "rejected-write-keeps-entire-profile");
        require(Arrays.equals(metadata(), expectedMetadata), "rejected-write-keeps-ciphertext-nonce-tag");
    }

    private void verifySignature(byte[] expectedProfile) throws Exception {
        ResponseAPDU response = send(0x61, null, 65);
        require(response.getSW() == SW_OK, "signature-public-key");
        byte[] point = response.getData();
        require(point.length == PUBLIC_KEY_LENGTH && point[0] == 4,
                "signature-public-key-uncompressed-65-bytes");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                        new BigInteger(1, Arrays.copyOfRange(point, 33, 65))), curve));
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        byte[] challenge = concat(adminPin, nonce);
        ResponseAPDU signed;
        try {
            signed = send(0x64, challenge, SIGNATURE_MAX_LENGTH);
        } finally {
            Arrays.fill(challenge, (byte) 0);
        }
        require(signed.getSW() == SW_OK, "signature-command");
        byte[] signature = signed.getData();
        int tag = signature.length > 0 ? signature[0] & 255 : -1;
        int contentLength = signature.length > 1 ? signature[1] & 255 : -1;
        System.out.printf("PROFILE-V2 signature-response commandBytes=%d requestedLe=%d"
                + " responseBytes=%d derTag=%d derContentLength=%d%n",
                adminPin.length + nonce.length, SIGNATURE_MAX_LENGTH, signature.length, tag, contentLength);
        require(signature.length >= SIGNATURE_MIN_LENGTH && signature.length <= SIGNATURE_MAX_LENGTH
                && tag == 0x30 && contentLength == signature.length - 2,
                "signature-der-shape-and-length");
        byte[] prefix = concat(ascii("LABACCESS-PROFILE-v1"), CARD_ID, sha256(point), nonce);
        byte[] message = concat(prefix, sha256(expectedProfile));
        require(verify(publicKey, message, signature), "signature-binds-entire-plaintext");
        writeSignatureEvidence(point, nonce, expectedProfile, message, signature);
        byte[] changed = expectedProfile.clone();
        changed[changed.length - 1] ^= 1;
        require(!verify(publicKey, concat(prefix, sha256(changed)), signature),
                "signature-rejects-modified-major-or-name");
        if (expectedProfile[0] == 2) {
            int classLengthOffset = 3 + (expectedProfile[1] & 255)
                    + (expectedProfile[2 + (expectedProfile[1] & 255)] & 255);
            changed = expectedProfile.clone();
            changed[classLengthOffset + 1] ^= 1;
            require(!verify(publicKey, concat(prefix, sha256(changed)), signature),
                    "signature-rejects-modified-class");
            changed = Arrays.copyOf(expectedProfile, classLengthOffset);
            changed[0] = 1;
            require(!verify(publicKey, concat(prefix, sha256(changed)), signature),
                    "signature-rejects-v1-profile-without-extension");
        }
    }

    private static boolean verify(PublicKey key, byte[] message, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(message);
        try {
            return verifier.verify(signature);
        } catch (java.security.SignatureException exception) {
            System.out.println("PROFILE-V2 signature-verifier-exception messageBytes="
                    + message.length + " signatureBytes=" + signature.length);
            throw exception;
        }
    }

    private static void writeSignatureEvidence(byte[] point, byte[] nonce, byte[] plaintext,
            byte[] message, byte[] signature) throws Exception {
        String evidencePath = System.getenv("LABACCESS_PROFILE_V2_EVIDENCE");
        if (evidencePath == null || evidencePath.isBlank()) {
            return;
        }
        java.util.HexFormat format = java.util.HexFormat.of();
        String vector = String.format("{\"publicKeyHex\":\"%s\",\"cardIdHex\":\"%s\","
                + "\"nonceHex\":\"%s\",\"plaintextHex\":\"%s\",\"messageHex\":\"%s\","
                + "\"signatureDerHex\":\"%s\"}%n",
                format.formatHex(point), format.formatHex(CARD_ID), format.formatHex(nonce),
                format.formatHex(plaintext), format.formatHex(message), format.formatHex(signature));
        Files.writeString(Path.of(evidencePath), vector, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private byte[] profile() throws Exception {
        expect("verify-before-profile", 0x20, studentPin.clone(), SW_OK, 0);
        ResponseAPDU result = send(0x31, null, 256);
        require(result.getSW() == SW_OK, "get-profile");
        return result.getData();
    }

    private byte[] metadata() throws Exception {
        expect("verify-before-metadata", 0x20, studentPin.clone(), SW_OK, 0);
        ResponseAPDU result = send(0x40, null, 65);
        require(result.getSW() == SW_OK, "get-metadata");
        return result.getData();
    }

    private byte[] identityPayload(String studentId, String name, byte[] extension) throws Exception {
        byte[] version = Arrays.copyOfRange(metadata(), 5, 17);
        return concat(adminPin, version, field(ascii(studentId)), field(utf8(name)), extension);
    }

    private byte[] personalizePayload(String studentId, String name, byte[] extension) {
        return concat(new byte[] {8}, adminPin, CARD_ID, field(ascii(studentId)), studentPin,
                field(utf8(name)), extension);
    }

    private byte[] renamePayload(String name) {
        return concat(adminPin, field(utf8(name)));
    }

    private static byte[] plaintext(String studentId, String name, byte[] extension) {
        return concat(new byte[] {(byte) (extension == null ? 1 : 2)}, field(ascii(studentId)),
                field(utf8(name)), extension == null ? new byte[0] : extension);
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
            int actual = send(ins, data, le).getSW();
            require(actual == expectedSw, label + " expected=" + Integer.toHexString(expectedSw)
                    + " actual=" + Integer.toHexString(actual));
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private void require(boolean condition, String label) {
        checks++;
        System.out.println("PROFILE-V2 " + label + " | " + (condition ? "PASS" : "FAIL"));
        if (!condition) {
            throw new IllegalStateException(label);
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

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private record InvalidExtension(String label, byte[] bytes, int sw) {
    }
}
