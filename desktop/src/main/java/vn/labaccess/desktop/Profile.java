package vn.labaccess.desktop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

record Profile(int schemaVersion, String studentId, String displayName, int displayNameBytes) {

    static final int FINGERPRINT_BYTES = 4;

    int wireLength() {
        return 3 + studentId.length() + displayNameBytes;
    }

    int displayNameCodePoints() {
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

    String evidence() {
        return String.format(
                "schema=%02X studentIdLen=%d nameBytes=%d nameCodePoints=%d nameSha256=%s",
                schemaVersion, studentId.length(), displayNameBytes, displayNameCodePoints(),
                displayNameFingerprint());
    }
}
