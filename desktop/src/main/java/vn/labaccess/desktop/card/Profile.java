package vn.labaccess.desktop.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record Profile(int schemaVersion, String studentId, String displayName, int displayNameBytes,
        String classCode, String major) {

    static final int FINGERPRINT_BYTES = 4;

    public Profile(int schemaVersion, String studentId, String displayName, int displayNameBytes) {
        this(schemaVersion, studentId, displayName, displayNameBytes, null, null);
    }

    public int wireLength() {
        int length = 3 + studentId.length() + displayNameBytes;
        return schemaVersion == ProfileCodec.SCHEMA_VERSION
                ? length + 2 + classCode.length() + major.getBytes(StandardCharsets.UTF_8).length
                : length;
    }

    public int displayNameCodePoints() {
        return displayName.codePointCount(0, displayName.length());
    }

    String displayNameFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(displayName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(hash, 0, FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK không có SHA-256", ex);
        }
    }

    public String evidence() {
        return String.format(
                "schema=%02X studentIdLen=%d nameBytes=%d nameCodePoints=%d nameSha256=%s classCode=%s major=%s",
                schemaVersion, studentId.length(), displayNameBytes, displayNameCodePoints(),
                displayNameFingerprint(), classCode, major);
    }
}
