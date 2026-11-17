import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

final class ApduErrorCases {
    private static final byte[] EMPTY = new byte[0];
    private CardChannel channel;
    private final byte[] adminPin;
    private final byte[] studentPin;
    private final byte[] cardId;
    private final CheckedCase reinstall;
    private final byte[] nonce = sequence(32, 17);
    private final byte[] visitId = sequence(16, 33);
    private final byte[] photo = sequence(4096, 57);
    private String caseId = "SETUP";
    private int failed;
    private int cases;
    private boolean supplements;

    ApduErrorCases(CardChannel channel, byte[] adminPin, byte[] studentPin, byte[] cardId,
            CheckedCase reinstall) {
        this.channel = channel;
        this.adminPin = adminPin;
        this.studentPin = studentPin;
        this.cardId = cardId;
        this.reinstall = reinstall;
    }

    int run(byte[] payload, String groups) throws Exception {
        if ("qa02".equals(groups)) {
            return pinLockoutCases(payload);
        }
        if ("qa03".equals(groups)) {
            return quotaCeilingCase(payload);
        }
        supplements = !"g1".equals(groups);
        if (supplements) {
            personalizePriorityCases(payload);
            personalizeRetryCases(payload);
        }
        if (!"g1".equals(groups)) {
            personalizeErrors(payload);
            personalizeMaximumPayload();
        } else {
            expect(command(0x30, 0, payload, 256), 0x9000);
        }
        byte[] status = expect(command(0x10, 0, EMPTY, 256), 0x9000);
        require(Arrays.equals(cardId, Arrays.copyOfRange(status, 19, 27)), "cardId mismatch");
        System.out.println("CARD cardId=" + hex(cardId) + " aid=F04C414202EE01");
        if (supplements) {
            check("G8-RECEIPT-NOT-STORED", () -> {
                verify();
                expect(command(0x60, 0, EMPTY, 256), 0x6985);
                expect(command(0x31, 0, EMPTY, 256), 0x6982);
                require(Arrays.equals(status, expect(command(0x10, 0, EMPTY, 256), 0x9000)),
                        "empty receipt read changed public state");
            });
        }
        expect(command(0x50, 0, grantData(adminPin), 6), 0x9000);
        signCases();
        if (supplements) {
            remainingProtocolCases(payload);
            beginWithoutUploadCases();
        }
        uploadCases();
        if (supplements) {
            uploadBoundaryCases();
            selectUploadCases();
        }
        readCases();
        if (supplements) {
            readBoundaryCases();
            selectIssuedCases();
        }
        if (!"g1".equals(groups)) {
            transactionCases();
            pinCases();
            readHeaderCases();
            authenticatedRetryCases();
            issuedPriorityCases(payload);
            extraDataCases();
            observerDetectsCommit();
        }
        System.out.println("APDU_ERRORS cases=" + cases + " failed=" + failed + " skipped=0");
        return failed;
    }

    private int pinLockoutCases(byte[] payload) throws Exception {
        byte[] newPin = different(studentPin);
        byte[] wrongAdmin = different(adminPin);
        try {
            caseId = "QA02-SETUP";
            expect(command(0x30, 0, payload, 256), 0x9000);
            expect(command(0x50, 0, grantData(adminPin), 6), 0x9000);
            Snapshot baseline = snapshot();
            require(Arrays.equals(cardId, Arrays.copyOfRange(baseline.status, 19, 27)), "cardId mismatch");
            require(baseline.status[27] == 0 && baseline.status[28] == 5
                    && counter(baseline.status, 29) == 1 && baseline.status[33] == 0, "baseline state");
            System.out.println("CARD cardId=" + hex(cardId) + " aid=F04C414202EE01");
            snapshotNegativeControls(baseline);
            byte[] publicBefore = expect(command(0x10, 0, EMPTY, 256), 0x9000);
            caseId = "QA02-STUDENT-LOCK-UNBLOCK";
            cases++;
            for (int sw : new int[] { 0x63C2, 0x63C1, 0x6983 }) {
                expect(command(0x20, 0, newPin, 0), sw);
            }
            expect(command(0x20, 0, studentPin, 0), 0x6983);
            expect(command(0x31, 0, EMPTY, 256), 0x6982);
            require(Arrays.equals(publicBefore, expect(command(0x10, 0, EMPTY, 256), 0x9000)),
                    "locked student changed public state");
            expect(command(0x23, 0, join(adminPin, newPin), 0), 0x9000);
            expect(command(0x20, 0, studentPin, 0), 0x63C2);
            System.out.println(caseId + " oldPin=REJECT retryAfterUnblock=3");
            same(baseline, snapshot(newPin));
            System.out.println(caseId + " newPin=ACCEPT | PASS");

            caseId = "QA02-ADMIN-LOCK-LAST";
            cases++;
            int attempt = 0;
            for (int sw : new int[] { 0x63C4, 0x63C3, 0x63C2, 0x63C1, 0x6983 }) {
                System.out.println(caseId + " wrongAdminAttempt=" + (++attempt));
                expect(command(0x50, 0, grantData(wrongAdmin), 6), sw);
                same(baseline, snapshot(newPin));
            }
            System.out.println(caseId + " correctAdminAfterLock operation=GRANT");
            expect(command(0x50, 0, grantData(adminPin), 6), 0x6983);
            same(baseline, snapshot(newPin));
            System.out.println(caseId + " correctAdminAfterLock operation=UNBLOCK");
            expect(command(0x23, 0, join(adminPin, studentPin), 0), 0x6983);
            same(baseline, snapshot(newPin));
            System.out.println(caseId + " balance=5 counter=1 fullReceipt=EXACT studentPin=UNCHANGED | PASS");
        } catch (AssertionError failure) {
            failed++;
            System.out.println(caseId + " | FAIL | " + failure.getMessage());
        } finally {
            Arrays.fill(newPin, (byte) 0);
            Arrays.fill(wrongAdmin, (byte) 0);
        }
        System.out.println("APDU_ERRORS cases=" + cases + " failed=" + failed + " skipped=" + (2 - cases));
        return failed;
    }

    private int quotaCeilingCase(byte[] payload) throws Exception {
        caseId = "QA03-SETUP";
        expect(command(0x30, 0, payload, 256), 0x9000);
        expect(command(0x50, 0, grantData(adminPin), 6), 0x9000);
        System.out.println("CARD cardId=" + hex(cardId) + " aid=F04C414202EE01");
        check("QA03-GRANT-OVER-LIMIT", () -> {
            Snapshot before = snapshot();
            require(Arrays.equals(cardId, Arrays.copyOfRange(before.status, 19, 27)), "cardId mismatch");
            require(before.status[27] == 0 && before.status[28] == 5
                    && counter(before.status, 29) == 1, "quota baseline");
            snapshotNegativeControls(before);
            System.out.println(caseId + " balance=5 delta=9995 sum=10000 expected=6A80");
            verify();
            expect(command(0x50, 0, join(adminPin, new byte[] { 0x27, 0x0B }), 6), 0x6A80);
            expect(command(0x31, 0, EMPTY, 256), 0x6982);
            same(before, snapshot());
            System.out.println(caseId + " boundaryControl balance=5 delta=9994 sum=9999");
            byte[] result = expect(command(0x50, 0, join(adminPin, new byte[] { 0x27, 0x0A }), 6), 0x9000);
            require(Arrays.equals(result, new byte[] { 0x27, 0x0F, 0, 0, 0, 2 }), "ceiling grant response");
            Snapshot ceiling = snapshot();
            require(Arrays.equals(result, Arrays.copyOfRange(ceiling.status, 27, 33)), "ceiling state");
            require(counter(ceiling.receipt, 9) == 2 && ceiling.receipt[13] == 1
                    && ceiling.receipt[14] == 0x27 && ceiling.receipt[15] == 0x0A
                    && ceiling.receipt[16] == 0x27 && ceiling.receipt[17] == 0x0F, "ceiling receipt");
            System.out.println(caseId + " balance=9999 delta=1 sum=10000 expected=6A80");
            expect(command(0x50, 0, join(adminPin, new byte[] { 0, 1 }), 6), 0x6A80);
            same(ceiling, snapshot());
        });
        System.out.println("APDU_ERRORS cases=" + cases + " failed=" + failed + " skipped=0");
        return failed;
    }

    private void snapshotNegativeControls(Snapshot baseline) {
        for (boolean mutateReceipt : new boolean[] { false, true }) {
            byte[] status = baseline.status.clone();
            byte[] receipt = baseline.receipt.clone();
            if (mutateReceipt) {
                receipt[receipt.length - 1] ^= 1;
            } else {
                status[28] ^= 1;
            }
            boolean rejected = false;
            try {
                same(baseline, new Snapshot(status, receipt, baseline.profile, baseline.publicKey));
            } catch (AssertionError expected) {
                rejected = true;
            }
            require(rejected, "snapshot observer accepted mutated " + (mutateReceipt ? "receipt" : "balance"));
            System.out.println(caseId + " NEGATIVE_CONTROL changed="
                    + (mutateReceipt ? "receipt-DER" : "balance") + " assertion=REJECT");
        }
    }

    private void personalizeRetryCases(byte[] payload) throws Exception {
        for (int p : new int[] { 0x100, 1 }) {
            personalizeRetry("P-" + p, payload, command(0x30, p, payload, 256), 0x6A86);
        }
        for (int length : new int[] { 0, 24, 25, 131 }) {
            personalizeRetry("LC-" + length, payload,
                    command(0x30, 0, Arrays.copyOf(payload, length), 256), 0x6700);
        }
        for (int le : new int[] { 0, 1, 255 }) {
            personalizeRetry("LE-" + le, payload, command(0x30, 0, payload, le), 0x6700);
        }
        for (int offset : new int[] { 1, 18, 18 + (payload[17] & 255) }) {
            byte[] malformed = payload.clone();
            malformed[offset] = (byte) '!';
            personalizeRetry("FORMAT-" + offset, payload, command(0x30, 0, malformed, 256), 0x6A80);
            Arrays.fill(malformed, (byte) 0);
        }
        byte[] zeroCardId = payload.clone();
        Arrays.fill(zeroCardId, 9, 17, (byte) 0);
        personalizeRetry("ZERO-CARD-ID", payload, command(0x30, 0, zeroCardId, 256), 0x6A80);
        Arrays.fill(zeroCardId, (byte) 0);
        byte[] badAdminLength = payload.clone();
        badAdminLength[0] = 7;
        personalizeRetry("ADMIN-LENGTH", payload, command(0x30, 0, badAdminLength, 256), 0x6A80);
        Arrays.fill(badAdminLength, (byte) 0);
        for (String studentId : new String[] { "SV24001", "SV202400001" }) {
            byte[] data = personalizePayload(studentId.getBytes(StandardCharsets.US_ASCII), new byte[] { 'A' });
            personalizeRetry("STUDENT-ID-LENGTH-" + studentId.length(), payload,
                    command(0x30, 0, data, 256), 0x6A80);
            Arrays.fill(data, (byte) 0);
        }
        for (int length : new int[] { 0, 97 }) {
            byte[] name = new byte[length];
            Arrays.fill(name, (byte) 'A');
            byte[] data = personalizePayload("SV2024001".getBytes(StandardCharsets.US_ASCII), name);
            personalizeRetry("NAME-LENGTH-" + length, payload, command(0x30, 0, data, 256), 0x6A80);
            Arrays.fill(data, (byte) 0);
        }
        reinstall.run();
    }

    private void personalizeRetry(String name, byte[] valid, CommandAPDU malformed, int sw)
            throws Exception {
        reinstall.run();
        caseId = (name.startsWith("G6-") ? name : "G4-PERSONALIZE-" + name) + "-ADMIN-RETRY";
        cases++;
        byte[] wrong = valid.clone();
        System.arraycopy(different(adminPin), 0, wrong, 1, adminPin.length);
        try {
            byte[] before = expect(command(0x10, 0, EMPTY, 256), 0x9000);
            expect(command(0x30, 0, wrong, 256), 0x63C4);
            expect(malformed, sw);
            expect(command(0x30, 0, wrong, 256), 0x63C3);
            require(Arrays.equals(before, expect(command(0x10, 0, EMPTY, 256), 0x9000)),
                    "unissued state changed");
            expect(command(0x20, 0, studentPin, 0), 0x6985);
            expect(command(0x61, 0, EMPTY, 65), 0x6985);
            System.out.println(caseId + " UNCHANGED unissued-state,student-unassigned,keys-unavailable");
            System.out.println(caseId + " | PASS");
        } catch (AssertionError failure) {
            failed++;
            System.out.println(caseId + " | FAIL | " + failure.getMessage());
        } finally {
            Arrays.fill(wrong, (byte) 0);
        }
    }

    private void personalizePriorityCases(byte[] payload) throws Exception {
        for (int offset : new int[] { 0, 1, 18 }) {
            for (int delta : new int[] { -1, 1 }) {
                byte[] data = Arrays.copyOf(payload, payload.length + delta);
                data[offset] = (byte) '!';
                personalizeRetry("G6-PERSONALIZE-FORMAT-" + offset + "-LENGTH-" + delta,
                        payload, command(0x30, 0, data, 256), 0x6700);
                Arrays.fill(data, (byte) 0);
            }
        }
        for (int field : new int[] { 17, 24 + (payload[17] & 255) }) {
            byte[] data = payload.clone();
            data[field] = (byte) 255;
            personalizeRetry("G6-PERSONALIZE-LENGTH-FIELD-" + field,
                    payload, command(0x30, 0, data, 256), 0x6700);
            Arrays.fill(data, (byte) 0);
        }
        reinstall.run();
    }

    private void issuedPriorityCases(byte[] payload) throws Exception {
        for (int length : new int[] { 0, 24, 25, 131, 132, 153, 154 }) {
            protocolError("G6-PERSONALIZE-ISSUED-LC-" + length,
                    command(0x30, 0, Arrays.copyOf(payload, length), 256), 0x6700);
        }
        for (int le : new int[] { 0, 1, 255 }) {
            protocolError("G6-PERSONALIZE-ISSUED-LE-" + le,
                    command(0x30, 0, payload, le), 0x6700);
        }
        byte[] malformed = payload.clone();
        malformed[0] = 7;
        protocolError("G6-PERSONALIZE-ISSUED-FORMAT", command(0x30, 0, malformed, 256), 0x6985);
        protocolError("G6-PERSONALIZE-ISSUED-FORMAT-LENGTH",
                command(0x30, 0, Arrays.copyOf(malformed, malformed.length - 1), 256), 0x6700);
        Arrays.fill(malformed, (byte) 0);
        protocolError("G6-ORDER-CLA-INS", new CommandAPDU(0x90, 0x7F, 1, 1), 0x6E00);
        protocolError("G6-ORDER-INS-P", command(0x7F, 0x101, new byte[1], 0), 0x6D00);
        for (int ins : new int[] { 0x10, 0x20, 0x21, 0x23, 0x30, 0x31, 0x32,
                0x35, 0x40, 0x50, 0x51, 0x52, 0x60, 0x61, 0x62 }) {
            protocolError("G6-ORDER-P-LENGTH-" + Integer.toHexString(ins),
                    command(ins, 0x101, EMPTY, 0), 0x6A86);
        }
    }

    private void extraDataCases() throws Exception {
        for (int ins : new int[] { 0x40, 0x60, 0x61 }) {
            for (int length : new int[] { 1, 17, 131 }) {
                byte[] data = sequence(length, 91);
                String id = "G6-DATA-" + Integer.toHexString(ins) + "-LC-" + length;
                check(id + "-RESPONSE-AUTH-STATE", () -> {
                    Snapshot before = snapshot();
                    verify();
                    byte[] expected = expect(command(ins, 0, EMPTY, 256), 0x9000);
                    verify();
                    require(Arrays.equals(expected, expect(command(ins, 0, data, 256), 0x9000)),
                            "extra data changed read response");
                    expect(command(0x31, 0, EMPTY, 256), 0x6982);
                    same(before, snapshot());
                });
                check(id + "-ADMIN-RETRY", () -> {
                    resetPins();
                    Snapshot before = snapshot();
                    expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
                    verify();
                    expect(command(ins, 0, data, 256), 0x9000);
                    expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
                    expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
                    same(before, snapshot());
                    resetPins();
                });
            }
        }
        for (int length : new int[] { 1, 17, 131 }) {
            byte[] data = sequence(length, 91);
            check("G6-DATA-34-LC-" + length + "-STREAM", () -> {
                resetPins();
                Snapshot before = snapshot();
                expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
                openReadStream();
                require(Arrays.equals(Arrays.copyOfRange(photo, 128, 256),
                        expect(command(0x34, 128, data, 128), 0x9000)), "extra data changed chunk");
                require(Arrays.equals(Arrays.copyOfRange(photo, 256, 384),
                        expect(command(0x34, 256, EMPTY, 128), 0x9000)), "extra data broke stream");
                expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
                expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
                assertPhotoUnchanged();
                same(before, snapshot());
                resetPins();
            });
        }
        for (int ins : new int[] { 0x40, 0x60, 0x34 }) {
            retryError("G6-DATA-NO-AUTH-" + Integer.toHexString(ins),
                    command(ins, 0, new byte[] { 1 }, ins == 0x34 ? 128 : 256), 0x6982);
        }
        protocolError("G6-DATA-KEY-NO-AUTH", command(0x61, 0, new byte[] { 1 }, 65), 0x9000);
        for (int ins : new int[] { 0x40, 0x60, 0x61, 0x34 }) {
            protocolError("G6-DATA-NO-LE-" + Integer.toHexString(ins),
                    command(ins, 0, new byte[] { 1 }, 0), 0x6700, ins != 0x60);
        }
    }

    private void remainingProtocolCases(byte[] payload) throws Exception {
        for (int ins : new int[] { 0x10, 0x20, 0x21, 0x23, 0x30, 0x31, 0x40,
                0x50, 0x51, 0x52, 0x60, 0x61, 0x62 }) {
            byte[] data = switch (ins) {
                case 0x20 -> studentPin;
                case 0x21 -> join(studentPin, different(studentPin));
                case 0x23 -> join(adminPin, different(studentPin));
                case 0x30 -> payload;
                case 0x50 -> grantData(adminPin);
                case 0x51, 0x52 -> visitId;
                case 0x62 -> nonce;
                default -> EMPTY;
            };
            int le = ins == 0x20 || ins == 0x21 || ins == 0x23 ? 0 : 256;
            protocolError("G4-CLA-" + Integer.toHexString(ins),
                    new CommandAPDU(0x90, ins, 0, 0, data, le), 0x6E00);
        }
        for (int ins : new int[] { 0x7F, 0x41 }) {
            protocolError("G4-INS-" + Integer.toHexString(ins), command(ins, 0, EMPTY, 0), 0x6D00);
        }
        protocolError("G4-PERSONALIZE-ISSUED", command(0x30, 0, payload, 256), 0x6985);
        retryError("G4-STATUS-PUBLIC-LE-26", command(0x10, 0, EMPTY, 26), 0x6700);
        protocolError("G4-VERIFY-FORMAT", command(0x20, 0, nonDigit(studentPin), 0), 0x6A80);
        protocolError("G4-CHANGE-OLD-FORMAT",
                command(0x21, 0, join(nonDigit(studentPin), different(studentPin)), 0), 0x6A80);
        protocolError("G4-CHANGE-NEW-FORMAT",
                command(0x21, 0, join(studentPin, nonDigit(studentPin)), 0), 0x6A80);
        for (int p : new int[] { 0x100, 1 }) {
            authorizationError("G4-UNBLOCK-P-" + p,
                    command(0x23, p, join(adminPin, different(studentPin)), 0), 0x6A86);
        }
        for (int length : new int[] { 0, 13, 15 }) {
            authorizationError("G4-UNBLOCK-LC-" + length,
                    command(0x23, 0, Arrays.copyOf(join(adminPin, different(studentPin)), length), 0), 0x6700);
        }
        for (int le : new int[] { 1, 256 }) {
            authorizationError("G4-UNBLOCK-LE-" + le,
                    command(0x23, 0, join(adminPin, different(studentPin)), le), 0x6700);
        }
        for (boolean admin : new boolean[] { true, false }) {
            byte[] data = admin ? join(nonDigit(adminPin), different(studentPin))
                    : join(adminPin, nonDigit(studentPin));
            String id = "G4-UNBLOCK-" + (admin ? "ADMIN" : "NEW") + "-FORMAT";
            protocolError(id, command(0x23, 0, data, 0), 0x6A80);
            unblockError(id + "-BLOCKED", command(0x23, 0, data, 0), 0x6A80);
        }
        protocolError("G4-GRANT-PIN-FORMAT", command(0x50, 0, grantData(nonDigit(adminPin)), 6), 0x6A80);
        check("G4-OBSERVER-DETECTS-RETRY-CHANGE", () -> {
            resetPins();
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            boolean rejected = false;
            try {
                expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            } catch (AssertionError expected) {
                rejected = true;
            }
            require(rejected, "retry observer accepted a consumed retry");
            System.out.println(caseId + " NEGATIVE_CONTROL unchanged-retry expectation=REJECT");
            resetPins();
        });
    }

    private void beginWithoutUploadCases() throws Exception {
        protocolError("G4-BEGIN-CLA", new CommandAPDU(0x90, 0x32, 0, 0, photoBeginData()), 0x6E00);
        for (int p : new int[] { 0x100, 1 }) {
            protocolError("G4-BEGIN-P-" + p, command(0x32, p, photoBeginData(), 0), 0x6A86);
        }
        for (int length : new int[] { 0, 12, 14 }) {
            protocolError("G4-BEGIN-LC-" + length,
                    command(0x32, 0, Arrays.copyOf(photoBeginData(), length), 0), 0x6700);
        }
        for (int le : new int[] { 1, 256 }) {
            protocolError("G4-BEGIN-LE-" + le, command(0x32, 0, photoBeginData(), le), 0x6700);
        }
        for (int offset : new int[] { 0, 8, 9, 10, 11, 12 }) {
            byte[] data = photoBeginData();
            data[offset] = offset == 0 ? (byte) '!' : (byte) (data[offset] ^ 1);
            protocolError("G4-BEGIN-FORMAT-" + offset, command(0x32, 0, data, 0), 0x6A80);
        }
    }

    private void personalizeErrors(byte[] payload) throws Exception {
        caseId = "G3-PERSONALIZE-RETRY-SEED";
        byte[] before = expect(command(0x10, 0, EMPTY, 256), 0x9000);
        byte[] wrong = payload.clone();
        System.arraycopy(different(adminPin), 0, wrong, 1, adminPin.length);
        expect(command(0x30, 0, wrong, 256), 0x63C4);
        for (int p : new int[] { 0x100, 1 }) {
            unissuedError("G3-PERSONALIZE-P-" + p, command(0x30, p, payload, 256), 0x6A86, before);
        }
        for (int le : new int[] { 0, 1, 255 }) {
            unissuedError("G3-PERSONALIZE-LE-" + le, command(0x30, 0, payload, le), 0x6700, before);
        }
        int expectedRetry = 0x63C3;
        for (int length : new int[] { 0, 24, 25, 131, 132, 153, 154 }) {
            unissuedError("G3-PERSONALIZE-LC-" + length,
                    command(0x30, 0, Arrays.copyOf(payload, length), 256), 0x6700, before);
            if (length > 131) {
                caseId = "G3-PERSONALIZE-LC-" + length + "-RETRY";
                expect(command(0x30, 0, wrong, 256), expectedRetry--);
                cases++;
                System.out.println(caseId + " | PASS");
            }
        }
        Arrays.fill(wrong, (byte) 0);
    }

    private void personalizeMaximumPayload() throws Exception {
        caseId = "G3-PERSONALIZE-VALID-LC-131";
        byte[] studentId = "SV20240001".getBytes(StandardCharsets.US_ASCII);
        byte[] displayName = "\u00C1".repeat(48).getBytes(StandardCharsets.UTF_8);
        byte[] payload = personalizePayload(studentId, displayName);
        byte[] profile = join(new byte[] { 1, 10 }, join(studentId,
                join(new byte[] { 96 }, displayName)));
        try {
            require(payload.length == 131, "maximum PERSONALIZE payload length");
            require(displayName.length == 96, "maximum displayName byte length");
            expect(command(0x30, 0, payload, 256), 0x9000);
            byte[] status = expect(command(0x10, 0, EMPTY, 256), 0x9000);
            require(status[18] == 1, "maximum payload did not issue card");
            require(Arrays.equals(cardId, Arrays.copyOfRange(status, 19, 27)), "cardId mismatch");
            verify();
            byte[] actualProfile = expect(command(0x31, 0, EMPTY, 256), 0x9000);
            require(Arrays.equals(profile, actualProfile), "maximum profile roundtrip mismatch");
            byte[] point = expect(command(0x61, 0, EMPTY, 65), 0x9000);
            require(point.length == 65 && point[0] == 4, "public key encoding");
            verifySignature(point, expect(command(0x62, 0, nonce, 72), 0x9000));
            cases++;
            System.out.println(caseId + " studentIdBytes=10 displayNameBytes=96 profileBytes="
                    + actualProfile.length + " profile=EXACT | PASS");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private void unissuedError(String id, CommandAPDU malformed, int sw, byte[] before)
            throws Exception {
        caseId = id;
        cases++;
        boolean matched = true;
        try {
            expect(malformed, sw);
        } catch (AssertionError failure) {
            matched = false;
            failed++;
            System.out.println(id + " | FAIL | " + failure.getMessage());
        }
        require(Arrays.equals(before, expect(command(0x10, 0, EMPTY, 256), 0x9000)),
                "unissued state changed");
        expect(command(0x20, 0, studentPin, 0), 0x6985);
        expect(command(0x61, 0, EMPTY, 65), 0x6985);
        System.out.println(id + " UNCHANGED unissued-state,student-unassigned,keys-unavailable");
        if (matched) {
            System.out.println(id + " | PASS");
        }
    }

    private void signCases() throws Exception {
        int[] lengths = { 0, 31, 33 };
        for (int length : lengths) {
            protocolError("G1-SIGN-LC-" + length,
                    command(0x62, 0, Arrays.copyOf(nonce, length), 256), 0x6700);
        }
        for (int le : new int[] { 0, 1, 71 }) {
            protocolError("G1-SIGN-LE-" + le, command(0x62, 0, nonce, le), 0x6700);
        }
        for (int p : new int[] { 0x100, 1 }) {
            protocolError("G1-SIGN-P-" + p, command(0x62, p, nonce, 256), 0x6A86);
        }
        for (int le : new int[] { 72, 256 }) {
            check("G1-SIGN-KEEP-AUTH-" + le, () -> {
                Snapshot before = snapshot();
                verify();
                byte[] signature = expect(command(0x62, 0, nonce, le), 0x9000);
                expect(command(0x31, 0, EMPTY, 256), 0x9000);
                verifySignature(before.publicKey, signature);
                same(before, snapshot());
            });
        }
        check("G1-SIGN-NO-AUTH-GRANTED", () -> {
            Snapshot before = snapshot();
            byte[] signature = expect(command(0x62, 0, nonce, 256), 0x9000);
            expect(command(0x31, 0, EMPTY, 256), 0x6982);
            verifySignature(before.publicKey, signature);
            same(before, snapshot());
        });
        check("G1-SIGN-VALID-RETRY", () -> {
            resetPins();
            Snapshot before = snapshot();
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
            expect(command(0x62, 0, nonce, 256), 0x9000);
            expect(command(0x20, 0, different(studentPin), 0), 0x63C1);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
            same(before, snapshot());
            resetPins();
        });
    }

    private void uploadCases() throws Exception {
        for (int ins : new int[] { 0x32, 0x33, 0x35 }) {
            byte[] data = ins == 0x32 ? photoBeginData() : ins == 0x33 ? new byte[1] : EMPTY;
            uploadError("G1-UPLOAD-CLA-" + ins,
                    new CommandAPDU(0x90, ins, 0, 0, data), 0x6E00, false);
        }
        uploadError("G1-BEGIN-OPEN", command(0x32, 0, photoBeginData(), 0), 0x6985, false);
        for (int p : new int[] { 0x100, 1 }) {
            uploadError("G1-BEGIN-P-" + p, command(0x32, p, photoBeginData(), 0), 0x6A86, false);
        }
        for (int length : new int[] { 0, 12, 14 }) {
            uploadError("G1-BEGIN-LC-" + length,
                    command(0x32, 0, Arrays.copyOf(photoBeginData(), length), 0), 0x6700, false);
        }
        for (int le : new int[] { 1, 256 }) {
            uploadError("G1-BEGIN-LE-" + le, command(0x32, 0, photoBeginData(), le), 0x6700, false);
        }
        for (int length : new int[] { 0, 129 }) {
            uploadError("G3-WRITE-LC-" + length, command(0x33, 128, new byte[length], 0), 0x6700, false);
        }
        for (int le : new int[] { 1, 256 }) {
            uploadError("G3-WRITE-LE-" + le, command(0x33, 128, new byte[1], le), 0x6700, false);
        }
        for (int p : new int[] { 0x100, 1 }) {
            uploadError("G3-FINISH-P-" + p, command(0x35, p, EMPTY, 0), 0x6A86, true);
        }
        uploadError("G3-FINISH-DATA", command(0x35, 0, new byte[1], 0), 0x6700, true);
        uploadError("G3-FINISH-DATA-LE", command(0x35, 0, new byte[1], 256), 0x6700, true);
        for (int le : new int[] { 1, 256 }) {
            uploadError("G3-FINISH-LE-" + le, command(0x35, 0, EMPTY, le), 0x6700, true);
        }
    }

    private void uploadError(String id, CommandAPDU malformed, int sw, boolean complete)
            throws Exception {
        check(id, () -> {
            Snapshot before = snapshot();
            expect(command(0x32, 0, photoBeginData(), 0), 0x9000);
            int length = complete ? photo.length : 128;
            upload(length);
            expect(malformed, sw);
            expect(command(0x33, length, new byte[1], 0), 0x6985);
            verify();
            expect(command(0x34, 0, EMPTY, 128), 0x6985);
            same(before, snapshot());
        });
        if (supplements) {
            uploadRetryError(id.replaceFirst("^G[13]", "G4") + "-OPEN-STUDENT-RETRY",
                    malformed, sw, complete ? photo.length : 128);
        }
    }

    private void uploadRetryError(String id, CommandAPDU malformed, int sw, int length)
            throws Exception {
        check(id, () -> {
            resetPins();
            Snapshot before = snapshot();
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            expect(command(0x32, 0, photoBeginData(), 0), 0x9000);
            upload(length);
            System.out.println(caseId + " PRECONDITION uploadedBytes=" + length
                    + " studentRetry=2 adminRetry=5 studentAuthorization=absent");
            expect(malformed, sw);
            expect(command(0x33, length, new byte[1], 0), 0x6985);
            expect(command(0x20, 0, different(studentPin), 0), 0x63C1);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
            verify();
            expect(command(0x34, 0, EMPTY, 128), 0x6985);
            same(before, snapshot());
            resetPins();
        });
    }

    private void uploadBoundaryCases() throws Exception {
        check("G5-WRITE-END-OVERFLOW", () -> {
            Snapshot before = snapshot();
            expect(command(0x32, 0, photoBeginData(), 0), 0x9000);
            upload(4095);
            System.out.println(caseId + " PRECONDITION uploadedBytes=4095 nextOffset=4095 Lc=2");
            expect(command(0x33, 4095, new byte[] { (byte) 0xA5, (byte) 0x5A }, 0), 0x6A86);
            expect(command(0x33, 4095, new byte[1], 0), 0x6985);
            verify();
            expect(command(0x34, 0, EMPTY, 128), 0x6985);
            same(before, snapshot());
            expect(command(0x32, 0, photoBeginData(), 0), 0x9000);
            upload(4095);
            expect(command(0x33, 4095, Arrays.copyOfRange(photo, 4095, 4096), 0), 0x9000);
            expect(command(0x10, 0, EMPTY, 256), 0x9000);
        });
        uploadRetryError("G4-WRITE-END-OVERFLOW-OPEN-STUDENT-RETRY",
                command(0x33, 4095, new byte[2], 0), 0x6A86, 4095);
        uploadRetryError("G4-WRITE-OFFSET-OPEN-STUDENT-RETRY",
                command(0x33, 0, new byte[1], 0), 0x6A86, 128);
        uploadRetryError("G4-FINISH-INCOMPLETE-OPEN-STUDENT-RETRY",
                command(0x35, 0, EMPTY, 0), 0x6985, 128);
        for (int ins : new int[] { 0x7F, 0x41 }) {
            uploadRetryError("G4-INS-" + Integer.toHexString(ins) + "-OPEN-STUDENT-RETRY",
                    command(ins, 0, EMPTY, 0), 0x6D00, 128);
        }
    }

    private void selectUploadCases() throws Exception {
        for (String transition : new String[] { "RESELECT", "DESELECT", "RESET" }) {
            check("G8-" + transition + "-UPLOAD", () -> {
                resetPins();
                Snapshot before = snapshot();
                expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
                expect(command(0x32, 0, photoBeginData(), 0), 0x9000);
                upload(128);
                selectAgain(transition);
                expect(command(0x33, 128, new byte[1], 0), 0x6985);
                expect(command(0x20, 0, different(studentPin), 0), 0x63C1);
                expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
                verify();
                expect(command(0x34, 0, EMPTY, 128), 0x6985);
                same(before, snapshot());
                resetPins();
            });
        }
    }

    private void selectIssuedCases() throws Exception {
        for (String transition : new String[] { "RESELECT", "DESELECT", "RESET" }) {
            String name = "G8-" + transition;
            check(name + "-RETRY-STATE", () -> {
                resetPins();
                Snapshot before = snapshot();
                expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
                expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
                selectAgain(transition);
                expect(command(0x20, 0, different(studentPin), 0), 0x63C1);
                expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
                same(before, snapshot());
                resetPins();
            });
            check(name + "-AUTH-READ-STREAM", () -> {
                Snapshot before = snapshot();
                openReadStream();
                selectAgain(transition);
                expect(command(0x34, 128, EMPTY, 128), 0x6982);
                verify();
                expect(command(0x34, 128, EMPTY, 128), 0x6A86);
                assertPhotoUnchanged();
                same(before, snapshot());
            });
        }
    }

    CardChannel channel() {
        return channel;
    }

    private void selectAgain(String transition) throws Exception {
        if (transition.equals("RESET")) {
            channel.getCard().disconnect(true);
            channel = TerminalFactory.getInstance("SocketCardTerminalFactoryType",
                    List.of(new InetSocketAddress("127.0.0.1",
                            Integer.getInteger("labaccess.simulator.port", 9025))),
                    "SocketCardTerminalProvider").terminals().list().get(0).connect("*").getBasicChannel();
            System.out.println(caseId + " RESET disconnect(true),reconnect");
        } else if (transition.equals("DESELECT")) {
            expect(new CommandAPDU(0, 0xA4, 4, 0, HexFormat.of().parseHex("A000000151000000"), 256), 0x9000);
        }
        expect(new CommandAPDU(0, 0xA4, 4, 0, HexFormat.of().parseHex("F04C414202EE01"), 256), 0x9000);
    }

    private void readBoundaryCases() throws Exception {
        for (int offset : new int[] { 4096, 4097, 127, 0, 0x8000, 0xFFFF }) {
            readBoundary("OFFSET-" + offset, command(0x34, offset, EMPTY, 128),
                    offset == 4096 ? 0x6985 : 0x6A86);
        }
        for (int le : new int[] { 0, 129, 256 }) {
            readBoundary("LE-" + le, command(0x34, 128, EMPTY, le), 0x6700);
        }
        readStreamRetry("G4-READ-CLA-STREAM-ADMIN-RETRY",
                new CommandAPDU(0x90, 0x34, 0, 128, 128), 0x6E00);
        for (int ins : new int[] { 0x7F, 0x41 }) {
            readStreamRetry("G4-INS-" + Integer.toHexString(ins) + "-STREAM-ADMIN-RETRY",
                    command(ins, 0, EMPTY, 0), 0x6D00);
        }
        check("G5-READ-LAST-SHORT-CHUNK", () -> {
            Snapshot before = snapshot();
            verify();
            for (int offset = 0; offset < 3968; offset += 128) {
                require(Arrays.equals(Arrays.copyOfRange(photo, offset, offset + 128),
                        expect(command(0x34, offset, EMPTY, 128), 0x9000)), "photo prefix changed");
            }
            require(Arrays.equals(Arrays.copyOfRange(photo, 3968, 4095),
                    expect(command(0x34, 3968, EMPTY, 127), 0x9000)), "photo penultimate chunk changed");
            require(Arrays.equals(Arrays.copyOfRange(photo, 4095, 4096),
                    expect(command(0x34, 4095, EMPTY, 128), 0x9000)), "final byte mismatch");
            expect(command(0x34, 0, EMPTY, 128), 0x6982);
            assertPhotoUnchanged();
            same(before, snapshot());
        });
        protocolError("G4-BEGIN-PHOTO-READY", command(0x32, 0, photoBeginData(), 0), 0x6985);
        protocolError("G4-WRITE-NO-UPLOAD", command(0x33, 0, new byte[1], 0), 0x6985);
        protocolError("G4-FINISH-NO-UPLOAD", command(0x35, 0, EMPTY, 0), 0x6985);
        check("G5-OBSERVER-DETECTS-PHOTO-CHANGE", () -> {
            byte[] actual = readPhoto();
            actual[4095] ^= 1;
            boolean rejected = false;
            try {
                requirePhotoMatches(actual);
            } catch (AssertionError expected) {
                rejected = true;
            }
            require(rejected, "photo observer accepted a changed final byte");
            System.out.println(caseId + " NEGATIVE_CONTROL unchanged-photo expectation=REJECT");
            assertPhotoUnchanged();
        });
    }

    private void readBoundary(String name, CommandAPDU malformed, int sw) throws Exception {
        check("G5-READ-" + name + "-STREAM-AUTH-STATE", () -> {
            Snapshot before = snapshot();
            openReadStream();
            expect(malformed, sw);
            expect(command(0x34, 128, EMPTY, 128), 0x6982);
            verify();
            expect(command(0x34, 128, EMPTY, 128), 0x6A86);
            assertPhotoUnchanged();
            same(before, snapshot());
        });
        readStreamRetry("G4-READ-" + name + "-STREAM-ADMIN-RETRY", malformed, sw);
    }

    private void readStreamRetry(String id, CommandAPDU malformed, int sw) throws Exception {
        check(id, () -> {
            resetPins();
            Snapshot before = snapshot();
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
            openReadStream();
            expect(malformed, sw);
            expect(command(0x34, 128, EMPTY, 128), 0x6982);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            assertPhotoUnchanged();
            same(before, snapshot());
            resetPins();
        });
    }

    private void openReadStream() throws Exception {
        verify();
        require(Arrays.equals(Arrays.copyOfRange(photo, 0, 128),
                expect(command(0x34, 0, EMPTY, 128), 0x9000)), "first photo chunk mismatch");
        System.out.println(caseId + " PRECONDITION readOffset=128 studentRetry=3 studentAuthorization=present");
    }

    private byte[] readPhoto() throws Exception {
        verify();
        byte[] actual = new byte[4096];
        for (int offset = 0; offset < actual.length; offset += 128) {
            byte[] chunk = expect(command(0x34, offset, EMPTY, 128), 0x9000);
            require(chunk.length == 128, "photo chunk length");
            System.arraycopy(chunk, 0, actual, offset, chunk.length);
        }
        expect(command(0x34, 0, EMPTY, 128), 0x6982);
        return actual;
    }

    private void requirePhotoMatches(byte[] actual) {
        require(Arrays.equals(photo, actual), "full photo bytes changed");
    }

    private void assertPhotoUnchanged() throws Exception {
        byte[] actual = readPhoto();
        requirePhotoMatches(actual);
        System.out.println(caseId + " UNCHANGED photoBytes=4096 photoSha256=" + digest(actual));
    }

    private void authenticatedRetryCases() throws Exception {
        Snapshot before = snapshot();
        authenticatedRetry("G4-STATUS-AUTH-49-ADMIN-RETRY", command(0x10, 0, EMPTY, 49));
        authenticatedRetry("G4-RECEIPT-NO-LE-ADMIN-RETRY", command(0x60, 0, EMPTY, 0));
        authenticatedRetry("G4-RECEIPT-SHORT-LE-ADMIN-RETRY",
                command(0x60, 0, EMPTY, before.receipt.length - 1));
    }

    private void authenticatedRetry(String id, CommandAPDU malformed) throws Exception {
        check(id, () -> {
            resetPins();
            Snapshot before = snapshot();
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
            verify();
            expect(malformed, 0x6700);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            same(before, snapshot());
            resetPins();
        });
    }

    private void readCases() throws Exception {
        caseId = "PHOTO-SETUP";
        expect(command(0x10, 0, EMPTY, 256), 0x9000);
        expect(command(0x32, 0, photoBeginData(), 0), 0x9000);
        upload(photo.length);
        expect(command(0x35, 0, EMPTY, 0), 0x9000);
        check("G1-READ-CLA-CLOSES-STREAM", () -> {
            Snapshot before = snapshot();
            verify();
            expect(command(0x34, 0, EMPTY, 128), 0x9000);
            expect(new CommandAPDU(0x90, 0x34, 0, 128, 128), 0x6E00);
            expect(command(0x34, 128, EMPTY, 128), 0x6982);
            same(before, snapshot());
        });
        check("G1-SIGN-INTERRUPTS-READ", () -> {
            verify();
            expect(command(0x34, 0, EMPTY, 128), 0x9000);
            expect(command(0x62, 0, nonce, 256), 0x9000);
            expect(command(0x34, 128, EMPTY, 128), 0x6982);
        });
        check("G1-PHOTO-ROUNDTRIP", () -> {
            verify();
            for (int offset = 0; offset < photo.length; offset += 128) {
                byte[] actual = expect(command(0x34, offset, EMPTY, 128), 0x9000);
                require(Arrays.equals(Arrays.copyOfRange(photo, offset, offset + 128), actual),
                        "photo bytes changed at " + offset);
            }
            expect(command(0x34, 0, EMPTY, 128), 0x6982);
        });
        for (int le : new int[] { 0, 129, 256 }) {
            protocolError("G3-PHOTO-READ-LE-" + le, command(0x34, 0, EMPTY, le), 0x6700);
        }
    }

    private void transactionCases() throws Exception {
        transactionErrors(0x51, visitId, "START");
        caseId = "OPEN-VISIT";
        verify();
        expect(command(0x51, 0, visitId, 6), 0x9000);
        transactionErrors(0x50, grantData(adminPin), "GRANT-ACTIVE");
        transactionErrors(0x52, visitId, "END");
        check("G3-END-AFTER-ERRORS-ONCE", () -> {
            Snapshot before = snapshot();
            verify();
            byte[] result = expect(command(0x52, 0, visitId, 6), 0x9000);
            Snapshot after = snapshot();
            require(Arrays.equals(Arrays.copyOfRange(before.status, 27, 29),
                    Arrays.copyOfRange(after.status, 27, 29)), "END changed balance");
            require(counter(after.status, 29) == counter(before.status, 29) + 1,
                    "END counter did not advance once");
            require(after.status[33] == 0, "END did not close visit");
            require(Arrays.equals(result, Arrays.copyOfRange(after.status, 27, 33)), "END response");
            verify();
            expect(command(0x52, 0, visitId, 6), 0x6985);
            verify();
            expect(command(0x51, 0, visitId, 6), 0x6985);
            same(after, snapshot());
        });
        transactionErrors(0x50, grantData(adminPin), "GRANT-IDLE");
    }

    private void transactionErrors(int ins, byte[] data, String name) throws Exception {
        for (int le : new int[] { 0, 1, 5 }) {
            protocolError("G2-" + name + "-LE-" + le, command(ins, 0, data, le), 0x6700);
        }
        for (int p : new int[] { 0x100, 1 }) {
            protocolError("G3-" + name + "-P-" + p, command(ins, p, data, 6), 0x6A86);
        }
        for (int length : new int[] { 0, data.length - 1, data.length + 1 }) {
            protocolError("G3-" + name + "-LC-" + length,
                    command(ins, 0, Arrays.copyOf(data, length), 6), 0x6700);
        }
    }

    private void pinCases() throws Exception {
        for (int ins : new int[] { 0x20, 0x21 }) {
            byte[] data = ins == 0x20 ? studentPin.clone() : join(studentPin, different(studentPin));
            for (int p : new int[] { 0x100, 1 }) {
                protocolError("G3-PIN-" + ins + "-P-" + p, command(ins, p, data, 0), 0x6A86);
            }
            for (int length : new int[] { 0, data.length - 1, data.length + 1, 15 }) {
                protocolError("G3-PIN-" + ins + "-LC-" + length,
                        command(ins, 0, Arrays.copyOf(data, length), 0), 0x6700);
            }
            for (int le : new int[] { 1, 256 }) {
                protocolError("G3-PIN-" + ins + "-LE-" + le, command(ins, 0, data, le), 0x6700);
            }
            Arrays.fill(data, (byte) 0);
        }
        byte[] unblock = join(adminPin, different(studentPin));
        for (int p : new int[] { 0x100, 1 }) {
            unblockError("G3-UNBLOCK-P-" + p, command(0x23, p, unblock, 0), 0x6A86);
        }
        for (int length : new int[] { 0, 13, 15 }) {
            unblockError("G3-UNBLOCK-LC-" + length,
                    command(0x23, 0, Arrays.copyOf(unblock, length), 0), 0x6700);
        }
        for (int le : new int[] { 1, 256 }) {
            unblockError("G3-UNBLOCK-LE-" + le, command(0x23, 0, unblock, le), 0x6700);
        }
        Arrays.fill(unblock, (byte) 0);
    }

    private void readHeaderCases() throws Exception {
        for (int ins : new int[] { 0x10, 0x31, 0x40, 0x60, 0x61 }) {
            for (int p : new int[] { 0x100, 1 }) {
                protocolError("G3-READ-" + ins + "-P-" + p,
                        command(ins, p, EMPTY, 256), 0x6A86);
            }
            protocolError("G3-READ-" + ins + "-NO-LE", command(ins, 0, EMPTY, 0), 0x6700,
                    ins != 0x60);
        }
        Snapshot baseline = snapshot();
        for (int ins : new int[] { 0x31, 0x40, 0x60, 0x61 }) {
            int length = ins == 0x31 ? baseline.profile.length : ins == 0x60 ? baseline.receipt.length : 65;
            protocolError("G3-READ-" + ins + "-SHORT-LE",
                    command(ins, 0, EMPTY, length - 1), 0x6700, ins != 0x60);
            check("G3-READ-" + ins + "-EXACT-LE", () -> {
                verify();
                require(expect(command(ins, 0, EMPTY, length), 0x9000).length == length,
                        "response length mismatch");
                same(baseline, snapshot());
            });
        }
        protocolError("G3-STATUS-AUTH-49", command(0x10, 0, EMPTY, 49), 0x6700, false);
        check("G3-STATUS-AUTH-50", () -> {
            verify();
            require(expect(command(0x10, 0, EMPTY, 50), 0x9000).length == 50, "status size");
            expect(command(0x31, 0, EMPTY, 256), 0x6982);
        });
        check("G3-STATUS-PUBLIC-EXTRA-DATA", () -> {
            byte[] before = expect(command(0x10, 0, EMPTY, 256), 0x9000);
            byte[] after = expect(command(0x10, 0, new byte[] { (byte) 0xFF }, 256), 0x9000);
            require(Arrays.equals(before, after), "public status changed with extra data");
        });
        for (int ins : new int[] { 0x10, 0x31 }) {
            check("G3-READ-AUTH-EXTRA-DATA-" + ins, () -> {
                verify();
                byte[] before = expect(command(ins, 0, EMPTY, 256), 0x9000);
                verify();
                byte[] after = expect(command(ins, 0, new byte[] { (byte) 0xFF }, 256), 0x9000);
                require(Arrays.equals(before, after), "response changed with extra data");
                expect(command(0x31, 0, EMPTY, 256), 0x6982);
            });
        }
    }

    private void protocolError(String id, CommandAPDU malformed, int sw) throws Exception {
        protocolError(id, malformed, sw, true);
    }

    private void observerDetectsCommit() throws Exception {
        check("G2-OBSERVER-DETECTS-COMMIT", () -> {
            Snapshot before = snapshot();
            expect(command(0x50, 0, grantData(adminPin), 6), 0x9000);
            Snapshot after = snapshot();
            require(!Arrays.equals(before.status, after.status), "observer missed state commit");
            require(!Arrays.equals(before.receipt, after.receipt), "observer missed receipt commit");
            boolean rejected = false;
            try {
                same(before, after);
            } catch (AssertionError expected) {
                rejected = true;
            }
            require(rejected, "unchanged-state assertion accepted a commit");
            System.out.println(caseId + " NEGATIVE_CONTROL unchanged-state expectation=REJECT");
        });
    }

    private void protocolError(String id, CommandAPDU malformed, int sw, boolean retry)
            throws Exception {
        authorizationError(id, malformed, sw);
        if (retry) {
            retryError(id, malformed, sw);
        }
    }

    private void authorizationError(String id, CommandAPDU malformed, int sw) throws Exception {
        check(id + "-AUTH-STATE", () -> {
            resetPins();
            Snapshot before = snapshot();
            verify();
            expect(malformed, sw);
            expect(command(0x31, 0, EMPTY, 256), 0x6982);
            same(before, snapshot());
        });
    }

    private void retryError(String id, CommandAPDU malformed, int sw) throws Exception {
        check(id + "-RETRY", () -> {
            resetPins();
            Snapshot before = snapshot();
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
            expect(malformed, sw);
            expect(command(0x20, 0, different(studentPin), 0), 0x63C1);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
            same(before, snapshot());
            resetPins();
        });
    }

    private void unblockError(String id, CommandAPDU malformed, int sw) throws Exception {
        check(id, () -> {
            resetPins();
            Snapshot before = snapshot();
            expect(command(0x20, 0, different(studentPin), 0), 0x63C2);
            expect(command(0x20, 0, different(studentPin), 0), 0x63C1);
            expect(command(0x20, 0, different(studentPin), 0), 0x6983);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C4);
            expect(malformed, sw);
            expect(command(0x50, 0, grantData(different(adminPin)), 6), 0x63C3);
            expect(command(0x20, 0, studentPin, 0), 0x6983);
            resetPins();
            same(before, snapshot());
        });
    }

    private Snapshot snapshot() throws Exception {
        return snapshot(studentPin);
    }

    private Snapshot snapshot(byte[] pin) throws Exception {
        expect(command(0x20, 0, pin, 0), 0x9000);
        byte[] status = expect(command(0x10, 0, EMPTY, 256), 0x9000);
        require(status.length == 50, "authenticated status length");
        expect(command(0x20, 0, pin, 0), 0x9000);
        byte[] receipt = expect(command(0x60, 0, EMPTY, 256), 0x9000);
        require(receipt.length == 35 + (receipt[34] & 255), "receipt framing");
        expect(command(0x20, 0, pin, 0), 0x9000);
        byte[] profile = expect(command(0x31, 0, EMPTY, 256), 0x9000);
        byte[] key = expect(command(0x61, 0, EMPTY, 256), 0x9000);
        System.out.println(caseId + " SNAPSHOT status=" + hex(status)
                + " receipt=" + hex(receipt) + " profileSha256=" + digest(profile)
                + " publicKeySha256=" + digest(key));
        return new Snapshot(status, receipt, profile, key);
    }

    private void same(Snapshot before, Snapshot after) {
        require(Arrays.equals(before.status, after.status), "state changed");
        require(Arrays.equals(before.receipt, after.receipt), "full receipt changed");
        require(Arrays.equals(before.profile, after.profile), "profile changed");
        require(Arrays.equals(before.publicKey, after.publicKey), "public key changed");
        System.out.println(caseId + " UNCHANGED status,receipt-payload-length-DER,profile,publicKey");
    }

    private void verifySignature(byte[] point, byte[] der) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECPoint coordinates = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
        var key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(coordinates,
                parameters.getParameterSpec(ECParameterSpec.class)));
        byte[] message = join("LABACCESS-ENROLLMENT-v1".getBytes(StandardCharsets.US_ASCII),
                join(cardId, join(MessageDigest.getInstance("SHA-256").digest(point), nonce)));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(message);
        require(verifier.verify(der), "enrollment signature invalid");
        message[message.length - 1] ^= 1;
        verifier.initVerify(key);
        verifier.update(message);
        require(!verifier.verify(der), "signature accepted changed nonce");
        System.out.println(caseId + " ECDSA valid=PASS changedNonce=REJECT");
    }

    private void resetPins() throws Exception {
        expect(command(0x23, 0, join(adminPin, studentPin), 0), 0x9000);
    }

    private void verify() throws Exception {
        expect(command(0x20, 0, studentPin, 0), 0x9000);
    }

    private void upload(int length) throws Exception {
        for (int offset = 0; offset < length; offset += 128) {
            expect(command(0x33, offset, Arrays.copyOfRange(photo, offset,
                    Math.min(offset + 128, length)), 0), 0x9000);
        }
    }

    private byte[] photoBeginData() {
        return join(adminPin, new byte[] { 1, 64, 64, 16, 0 });
    }

    private byte[] personalizePayload(byte[] studentId, byte[] displayName) {
        return join(new byte[] { 8 }, join(adminPin, join(cardId,
                join(new byte[] { (byte) studentId.length }, join(studentId, join(studentPin,
                        join(new byte[] { (byte) displayName.length }, displayName)))))));
    }

    private static byte[] grantData(byte[] pin) {
        return join(pin, new byte[] { 0, 5 });
    }

    private static CommandAPDU command(int ins, int p, byte[] data, int ne) {
        return new CommandAPDU(0x80, ins, p >>> 8, p & 255, data, ne);
    }

    private byte[] expect(CommandAPDU command, int sw) throws Exception {
        System.out.printf("%s -> %02X %02X %02X %02X Lc=%d Le=%s payload=<redacted>%n",
                caseId, command.getCLA(), command.getINS(), command.getP1(), command.getP2(),
                command.getNc(), command.getNe() == 0 ? "absent" : String.valueOf(command.getNe()));
        ResponseAPDU response = channel.transmit(command);
        System.out.printf("%s <- expected=%04X actual=%04X responseLength=%d%n",
                caseId, sw, response.getSW(), response.getNr());
        require(response.getSW() == sw, String.format("SW expected=%04X actual=%04X", sw, response.getSW()));
        require(sw == 0x9000 || response.getNr() == 0, "error response leaked data");
        return response.getData();
    }

    private void check(String id, CheckedCase test) throws Exception {
        caseId = id;
        cases++;
        try {
            test.run();
            System.out.println(id + " | PASS");
        } catch (AssertionError failure) {
            failed++;
            System.out.println(id + " | FAIL | " + failure.getMessage());
            resetPins();
            expect(command(0x10, 0, EMPTY, 256), 0x9000);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static long counter(byte[] data, int offset) {
        return ((long) (data[offset] & 255) << 24) | ((long) (data[offset + 1] & 255) << 16)
                | ((long) (data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }

    private static byte[] sequence(int length, int seed) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }

    private static byte[] different(byte[] pin) {
        byte[] result = pin.clone();
        result[0] = result[0] == '9' ? (byte) '0' : (byte) (result[0] + 1);
        return result;
    }

    private static byte[] nonDigit(byte[] pin) {
        byte[] result = pin.clone();
        result[0] = (byte) '!';
        return result;
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String digest(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] data) {
        return HexFormat.of().withUpperCase().formatHex(data);
    }

    private record Snapshot(byte[] status, byte[] receipt, byte[] profile, byte[] publicKey) { }

    @FunctionalInterface
    interface CheckedCase {
        void run() throws Exception;
    }
}
