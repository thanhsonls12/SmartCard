package vn.labaccess.desktop;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;

final class ReceiptVerifier {

    record Result(boolean ok, String problem) {
        static Result pass() {
            return new Result(true, null);
        }

        static Result fail(String problem) {
            return new Result(false, problem);
        }
    }

    record ParsedKey(boolean ok, PublicKey key, String problem) {
        static ParsedKey pass(PublicKey key) {
            return new ParsedKey(true, key, null);
        }

        static ParsedKey fail(String problem) {
            return new ParsedKey(false, null, problem);
        }
    }

    static final byte[] DOMAIN = "LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII);
    static final int PUBLIC_KEY_LENGTH = 65;
    static final byte PUBLIC_POINT_PREFIX = 0x04;
    static final int SIGNATURE_MIN_LENGTH = 70;
    static final int SIGNATURE_MAX_LENGTH = 72;
    static final int COORDINATE_LENGTH = 32;
    static final String ALGORITHM = "SHA256withECDSA";
    static final String CURVE = "secp256r1";

    private static final byte DER_SEQUENCE = 0x30;
    private static final byte DER_INTEGER = 0x02;
    private static final int MAX_INTEGER_LENGTH = 33;

    private ReceiptVerifier() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }

    static ParsedKey parsePublicKey(byte[] encoded) {
        if (encoded == null) {
            return ParsedKey.fail("không có public key");
        }
        if (encoded.length != PUBLIC_KEY_LENGTH) {
            return ParsedKey.fail(String.format("public key %d byte, phải đúng %d byte",
                    encoded.length, PUBLIC_KEY_LENGTH));
        }
        if (encoded[0] != PUBLIC_POINT_PREFIX) {
            return ParsedKey.fail(String.format("public key bắt đầu %02X, phải là 04 (uncompressed)",
                    encoded[0] & 0xFF));
        }

        byte[] xBytes = new byte[COORDINATE_LENGTH];
        byte[] yBytes = new byte[COORDINATE_LENGTH];
        System.arraycopy(encoded, 1, xBytes, 0, COORDINATE_LENGTH);
        System.arraycopy(encoded, 1 + COORDINATE_LENGTH, yBytes, 0, COORDINATE_LENGTH);
        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);

        ECParameterSpec parameters;
        try {
            parameters = p256Parameters();
        } catch (GeneralSecurityException ex) {
            return ParsedKey.fail("không lấy được tham số P-256: " + ex.getMessage());
        }
        String curveProblem = checkPoint(x, y, parameters.getCurve());
        if (curveProblem != null) {
            return ParsedKey.fail(curveProblem);
        }

        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            return ParsedKey.pass(factory.generatePublic(
                    new ECPublicKeySpec(new ECPoint(x, y), parameters)));
        } catch (GeneralSecurityException ex) {
            return ParsedKey.fail("không dựng được ECPublicKey: " + ex.getMessage());
        }
    }

    static Result verify(PublicKey key, byte[] payload, byte[] signature) {
        return verify(key, DOMAIN, payload, signature);
    }

    static Result verify(PublicKey key, byte[] domain, byte[] payload, byte[] signature) {
        if (key == null) {
            return Result.fail("chưa có public key của thẻ");
        }
        if (domain == null || domain.length == 0) {
            return Result.fail("domain rỗng");
        }
        if (payload == null || payload.length != ReceiptCodec.PAYLOAD_LENGTH) {
            return Result.fail(String.format("payload %s byte, phải đúng %d byte",
                    payload == null ? "không có" : Integer.toString(payload.length),
                    ReceiptCodec.PAYLOAD_LENGTH));
        }
        Result derProblem = checkDer(signature);
        if (!derProblem.ok()) {
            return derProblem;
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(domain);
            verifier.update(payload);
            return verifier.verify(signature)
                    ? Result.pass()
                    : Result.fail("chữ ký ECDSA không khớp domain/payload");
        } catch (GeneralSecurityException ex) {
            return Result.fail("lỗi xác minh chữ ký: " + ex.getMessage());
        }
    }

    static Result verifyReceipt(PublicKey key, Receipt receipt) {
        if (receipt == null) {
            return Result.fail("không có receipt");
        }
        return verify(key, receipt.payload(), receipt.signature());
    }

    private static Result checkDer(byte[] signature) {
        if (signature == null) {
            return Result.fail("không có chữ ký");
        }
        int length = signature.length;
        if (length < SIGNATURE_MIN_LENGTH || length > SIGNATURE_MAX_LENGTH) {
            return Result.fail(String.format("độ dài DER=%d ngoài khoảng %d..%d",
                    length, SIGNATURE_MIN_LENGTH, SIGNATURE_MAX_LENGTH));
        }
        if (signature[0] != DER_SEQUENCE) {
            return Result.fail(String.format("byte đầu DER=%02X, phải là 30",
                    signature[0] & 0xFF));
        }
        if ((signature[1] & 0xFF) != length - 2) {
            return Result.fail(String.format("độ dài DER khai báo %d nhưng có %d",
                    signature[1] & 0xFF, length - 2));
        }
        if (signature[2] != DER_INTEGER) {
            return Result.fail("thiếu INTEGER của R");
        }
        int rLength = signature[3] & 0xFF;
        if (rLength < 1 || rLength > MAX_INTEGER_LENGTH) {
            return Result.fail("độ dài R không hợp lệ: " + rLength);
        }
        int rOffset = 4;
        if (rOffset + rLength > length) {
            return Result.fail("R vượt quá độ dài DER");
        }
        String rProblem = checkInteger(signature, rOffset, rLength, "R");
        if (rProblem != null) {
            return Result.fail(rProblem);
        }
        int sLengthOffset = rOffset + rLength;
        if (sLengthOffset + 2 > length) {
            return Result.fail("thiếu header INTEGER của S");
        }
        if (signature[sLengthOffset] != DER_INTEGER) {
            return Result.fail("thiếu INTEGER của S");
        }
        int sLength = signature[sLengthOffset + 1] & 0xFF;
        if (sLength < 1 || sLength > MAX_INTEGER_LENGTH) {
            return Result.fail("độ dài S không hợp lệ: " + sLength);
        }
        int sOffset = sLengthOffset + 2;
        if (sOffset + sLength != length) {
            return Result.fail(String.format("S chiếm %d byte nhưng DER còn %d byte",
                    sLength, length - sOffset));
        }
        String sProblem = checkInteger(signature, sOffset, sLength, "S");
        return sProblem == null ? Result.pass() : Result.fail(sProblem);
    }

    private static String checkInteger(byte[] signature, int offset, int length, String name) {
        boolean negative = (signature[offset] & 0x80) != 0;
        if (negative) {
            return name + " là số âm";
        }
        boolean padded = signature[offset] == 0x00 && length > 1
                && (signature[offset + 1] & 0x80) == 0;
        if (padded) {
            return name + " có byte 00 thừa";
        }
        return null;
    }

    private static String checkPoint(BigInteger x, BigInteger y, EllipticCurve curve) {
        if (!(curve.getField() instanceof ECFieldFp field)) {
            return "đường cong không dùng trường nguyên tố";
        }
        BigInteger p = field.getP();
        if (x.signum() < 0 || x.compareTo(p) >= 0) {
            return "toạ độ X ngoài trường P-256";
        }
        if (y.signum() < 0 || y.compareTo(p) >= 0) {
            return "toạ độ Y ngoài trường P-256";
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.multiply(x).multiply(x)
                .add(curve.getA().multiply(x))
                .add(curve.getB())
                .mod(p);
        return left.equals(right) ? null : "điểm public key không nằm trên đường cong P-256";
    }

    private static ECParameterSpec p256Parameters() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    static byte[] encodePoint(PublicKey key) {
        if (!(key instanceof java.security.interfaces.ECPublicKey ecKey)) {
            return null;
        }
        byte[] encoded = new byte[PUBLIC_KEY_LENGTH];
        encoded[0] = PUBLIC_POINT_PREFIX;
        copyCoordinate(ecKey.getW().getAffineX(), encoded, 1);
        copyCoordinate(ecKey.getW().getAffineY(), encoded, 1 + COORDINATE_LENGTH);
        return encoded;
    }

    private static void copyCoordinate(BigInteger value, byte[] target, int offset) {
        byte[] raw = value.toByteArray();
        int length = Math.min(raw.length, COORDINATE_LENGTH);
        int sourceOffset = raw.length - length;
        System.arraycopy(raw, sourceOffset, target, offset + COORDINATE_LENGTH - length, length);
    }
}
