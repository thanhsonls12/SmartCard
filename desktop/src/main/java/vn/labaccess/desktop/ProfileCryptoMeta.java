package vn.labaccess.desktop;

record ProfileCryptoMeta(int recordVersion, int algorithmId, int schemaVersion, int ciphertextLength,
        String nonceHex, String tagHex, String ciphertextHashHex) {

    static final int ALGORITHM_AES_128_GCM = 0x01;
    static final int NONCE_LENGTH = 12;
    static final int TAG_LENGTH = 16;
    static final int HASH_LENGTH = 32;
    static final int SHORT_HEX_BYTES = 4;

    String algorithmText() {
        return algorithmId == ALGORITHM_AES_128_GCM
                ? "AES-128-GCM"
                : String.format("%02X KHÔNG RÕ", algorithmId);
    }

    String evidence() {
        return String.format(
                "record=%02X alg=%02X %s schema=%02X ciphertextLen=%d | nonce=%s tag=%s hashSHA256=%s",
                recordVersion, algorithmId, algorithmText(), schemaVersion, ciphertextLength,
                shortHex(nonceHex), shortHex(tagHex), shortHex(ciphertextHashHex));
    }

    private static String shortHex(String hex) {
        int keep = SHORT_HEX_BYTES * 2;
        return hex.length() <= keep ? hex : hex.substring(0, keep) + "…";
    }
}
