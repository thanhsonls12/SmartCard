package vn.labaccess.desktop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

public final class CardGatewayVerify {

    private static final byte[] AID = HexFormat.of().parseHex("F04C4142010101");
    private static final byte[] WRONG_AID = HexFormat.of().parseHex("F04C4142010199");
    private static final byte[] HELLOWORLD_AID = HexFormat.of().parseHex("A00000006203010C0101");

    private static final int SW_OK = 0x9000;

    private static final String HOST = System.getProperty("labaccess.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("labaccess.port", 9025);

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String OTHER_CARD_ID = "0011223344556677";
    private static final String VISIT_ID = "7172737475767778797A7B7C7D7E7F80";
    private static final String OTHER_VISIT_ID = "808182838485868788898A8B8C8D8E8F";
    private static final String NO_VISIT_ID = "00000000000000000000000000000000";
    private static final String PROFILE_NAME = "Nguy\u1EC5n V\u0103n \u00C1nh";

    private static int failed;
    private static int skipped;
    private static String expectedStudentId;
    private static String expectedDisplayName;

    public static void main(String[] args) throws Exception {
        if (System.getProperty("labaccess.twoClient.mode") != null) {
            runTwoClientChild();
            return;
        }
        checkLifecycleRules();
        checkReceiptCodec();
        checkReceiptExpectation();
        checkReconciliation();
        checkRealCardReceipts();
        checkReceiptVerifier();
        checkReceiptSignatureGate();
        checkPendingJournal();
        checkVerifyMapping();
        checkTransactionGate();
        checkPersonalizeOffline();
        checkPersonalizeMapping();
        checkPublicKeyOffline();
        checkPinOffline();
        checkPinState();
        checkProfileOffline();
        checkPhotoDraftOffline();
        checkPhotoOffline();

        if (Boolean.getBoolean("labaccess.offlineOnly")) {
            System.out.println();
            System.out.println("KET LUAN OFFLINE: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")"));
            if (failed != 0) {
                System.exit(1);
            }
            return;
        }

        CardGateway gateway = new CardGateway(HOST, PORT);
        try {
            checkIdentifyWithRealCard(gateway);
            checkPublicKeyBeforePersonalize(gateway);
            checkPersonalizeOnCard(gateway);
            checkPublicKeyAfterPersonalize(gateway);
            checkLastReceiptOnCard(gateway);
            checkVerifyAndTransactionOnCard(gateway);
            checkFaultAfterCommitOnCard(gateway);
            checkPinStateOnCard(gateway);
            checkProfileOnCard(gateway);
            checkPhotoSessionLossOnCard(gateway);
            checkPhotoOnCard(gateway);
            checkPinOnCard(gateway);
        } finally {
            clearLiveSecrets();
            if (gateway.isConnected()) {
                gateway.disconnect();
            }
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("KET LUAN: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")")
                + " | skipped=" + skipped);
        System.out.println("========================================");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void checkLifecycleRules() {
        System.out.println("--- Nhom V: kiem lifecycle/cardId bang response tong hop ---");

        CardGateway offline = new CardGateway(HOST, PORT);
        record("V03", "lifecycle 00 + cardId toan 00 chap nhan",
                offline.checkStatus(statusApdu(0x00, "0000000000000000"), AID).ok(), true);
        record("V04", "lifecycle 02 chua dinh nghia bi tu choi",
                offline.checkStatus(statusApdu(0x02, "0000000000000000"), AID).ok(), false);
        record("V05", "lifecycle 00 nhung cardId da gan bi tu choi",
                offline.checkStatus(statusApdu(0x00, "0123456789ABCDEF"), AID).ok(), false);
        record("V06", "lifecycle 01 ISSUED nhung cardId toan 00 bi tu choi",
                offline.checkStatus(statusApdu(0x01, "0000000000000000"), AID).ok(), false);
        record("V07", "lifecycle 01 ISSUED + cardId da gan duoc chap nhan",
                offline.checkStatus(statusApdu(0x01, "0123456789ABCDEF"), AID).ok(), true);
        record("V08", "AID tra ve khac AID yeu cau bi tu choi",
                offline.checkStatus(statusApdu(0x00, "0000000000000000"), WRONG_AID).ok(), false);

        CardGateway stateGateway = new CardGateway(HOST, PORT);
        record("V10", "GET_STATUS public chi chap nhan schema 27 byte",
                stateGateway.checkStatus(statusApdu(0x01, CARD_ID), AID).ok()
                        && stateGateway.status() != null
                        && !stateGateway.status().studentStateAvailable(), true);
        record("V11", "GET_STATUS authenticated parse balance/counter/active visit ID",
                stateGateway.checkStatus(authenticatedStatusApdu(5, 2, true, VISIT_ID), AID).ok()
                        && stateGateway.status().studentStateAvailable()
                        && stateGateway.status().balance() == 5
                        && stateGateway.status().counter() == 2
                        && stateGateway.status().hasActiveVisit()
                        && stateGateway.status().activeVisitId().equalsIgnoreCase(VISIT_ID), true);
        record("V12", "GET_STATUS closed visit phai co ID zero",
                stateGateway.checkStatus(authenticatedStatusApdu(5, 2, false, VISIT_ID), AID).ok(), false);
        record("V13", "GET_STATUS open visit phai co ID khac zero",
                stateGateway.checkStatus(authenticatedStatusApdu(5, 2, true, NO_VISIT_ID), AID).ok(), false);
        checkCardPublicKeyCacheReset();
    }

    private static void checkCardPublicKeyCacheReset() {
        TestKey firstKey = newTestKey();
        TestKey secondKey = newTestKey();
        byte[][] selectedAid = { AID.clone() };
        int[] keyReads = { 0 };
        CardGateway gateway = new CardGateway(scriptedChannel(command -> {
            int ins = command.getINS() & 0xFF;
            if (ins == 0xA4) {
                selectedAid[0] = command.getData();
                return response(Sw.OK);
            }
            if (ins == 0x10) {
                return statusApdu(0x01, CARD_ID, selectedAid[0]);
            }
            if (ins == 0x61) {
                keyReads[0]++;
                TestKey key = Arrays.equals(selectedAid[0], AID) ? firstKey : secondKey;
                return responseWithData(key.encoded(), Sw.OK);
            }
            return response(Sw.INS_NOT_SUPPORTED);
        }));
        try {
            gateway.identifyWith(AID);
            CardGateway.Result first = gateway.prepareReceiptVerification();
            boolean firstKeyLoaded = first.ok() && Arrays.equals(
                    ReceiptVerifier.encodePoint(firstKey.publicKey()),
                    ReceiptVerifier.encodePoint(gateway.cardPublicKey()));
            gateway.identifyWith(WRONG_AID);
            CardGateway.Result second = gateway.prepareReceiptVerification();
            record("V14", "public key cache duoc nap lai sau khi doi instance AID",
                    firstKeyLoaded && second.ok() && keyReads[0] == 2
                            && Arrays.equals(ReceiptVerifier.encodePoint(secondKey.publicKey()),
                                    ReceiptVerifier.encodePoint(gateway.cardPublicKey())), true);
        } catch (CardException exception) {
            record("V14", "public key cache duoc nap lai sau khi doi instance AID", false, true);
        }
    }

    private static void checkReceiptCodec() {
        System.out.println();
        System.out.println("--- Nhom C: ReceiptCodec tren response tong hop ---");

        ReceiptCodec.Parse grant = ReceiptCodec.parse(
                receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 70));
        record("C01", "GRANT hop le duoc parse", grant.ok(), true);
        record("C02", "GRANT doc dung counter/operation/delta/balance",
                grant.ok() && grant.receipt().counterAfter() == 1
                        && grant.receipt().operation() == 1
                        && grant.receipt().delta() == 5
                        && grant.receipt().balanceAfter() == 5,
                true);

        ReceiptCodec.Parse start = ReceiptCodec.parse(
                receiptResponse(2, -1, 2, 4, CARD_ID, VISIT_ID, 71));
        record("C03", "START hop le duoc parse", start.ok(), true);
        record("C04", "START doc dung delta -1 va visitId",
                start.ok() && start.receipt().delta() == -1
                        && HexFormat.of().formatHex(start.receipt().visitId())
                                .equalsIgnoreCase(VISIT_ID),
                true);

        ReceiptCodec.Parse end = ReceiptCodec.parse(
                receiptResponse(3, 0, 3, 4, CARD_ID, VISIT_ID, 72));
        record("C05", "END hop le duoc parse", end.ok(), true);

        byte[] raw = receiptResponse(2, -1, 7, 4, CARD_ID, VISIT_ID, 71);
        byte[] payloadPart = Arrays.copyOfRange(raw, 0, 34);
        byte[] signaturePart = Arrays.copyOfRange(raw, 35, raw.length);
        ReceiptCodec.Parse kept = ReceiptCodec.parse(raw);
        record("C06", "payload 34B va signature giu nguyen byte",
                kept.ok() && Arrays.equals(kept.receipt().payload(), payloadPart)
                        && Arrays.equals(kept.receipt().signature(), signaturePart),
                true);

        record("C07", "version 02 bi tu choi",
                ReceiptCodec.parse(withPayloadByte(
                        receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 70), 0, 0x02)).ok(),
                false);
        record("C08", "operation 04 bi tu choi",
                ReceiptCodec.parse(withPayloadByte(
                        receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 70), 13, 0x04)).ok(),
                false);
        record("C09", "GRANT delta 0 bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, 0, 1, 5, CARD_ID, NO_VISIT_ID, 70)).ok(),
                false);
        record("C10", "GRANT delta am bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, -2, 1, 5, CARD_ID, NO_VISIT_ID, 70)).ok(),
                false);
        record("C11", "START delta 0 bi tu choi",
                ReceiptCodec.parse(receiptResponse(2, 0, 2, 4, CARD_ID, VISIT_ID, 71)).ok(),
                false);
        record("C12", "END delta -1 bi tu choi",
                ReceiptCodec.parse(receiptResponse(3, -1, 3, 4, CARD_ID, VISIT_ID, 72)).ok(),
                false);
        record("C13", "balanceAfter 10000 bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, 5, 1, 10000, CARD_ID, NO_VISIT_ID, 70)).ok(),
                false);
        record("C14", "GRANT co visitId khac 00 bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, 5, 1, 5, CARD_ID, VISIT_ID, 70)).ok(),
                false);
        record("C15", "signatureLength 69 duoc parse o wire envelope",
                ReceiptCodec.parse(receiptResponse(1, 5, 1, 5, CARD_ID,
                        NO_VISIT_ID, 69)).ok(), true);
        record("C16", "signatureLength 73 bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, 5, 1, 5, CARD_ID,
                        NO_VISIT_ID, 73)).ok(), false);
        record("C17", "response bi cat ngan bi tu choi",
                ReceiptCodec.parse(Arrays.copyOf(
                        receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 70), 100)).ok(),
                false);
        record("C18", "response dai hon signatureLength bi tu choi",
                ReceiptCodec.parse(Arrays.copyOf(
                        receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 70), 108)).ok(),
                false);
        record("C19", "response rong bi tu choi", ReceiptCodec.parse(new byte[0]).ok(), false);
    }

    private static void checkReceiptExpectation() {
        System.out.println();
        System.out.println("--- Nhom D: doi chieu receipt voi ky vong giao dich ---");

        Receipt grant = receipt(1, 5, 1, 5, CARD_ID, NO_VISIT_ID);
        Receipt start = receipt(2, -1, 2, 4, CARD_ID, VISIT_ID);

        ReceiptExpectation grantExpectation = new ReceiptExpectation(
                HexFormat.of().parseHex(CARD_ID), 1, 1, null);
        record("D01", "GRANT khop cardId/operation, khong can visitId",
                grantExpectation.cardProblem(grant) == null
                        && grantExpectation.transactionProblems(grant).isEmpty(),
                true);
        record("D02", "cardId khac the dang nhan dien bi tu choi",
                new ReceiptExpectation(HexFormat.of().parseHex(OTHER_CARD_ID), 1, 1, null)
                        .cardProblem(grant) != null,
                true);

        ReceiptExpectation startExpectation = new ReceiptExpectation(
                HexFormat.of().parseHex(CARD_ID), 2, 2, HexFormat.of().parseHex(VISIT_ID));
        record("D03", "START khop visit context duoc chap nhan",
                startExpectation.cardProblem(start) == null
                        && startExpectation.transactionProblems(start).isEmpty(),
                true);
        record("D04", "START sai visitId bi tu choi",
                !new ReceiptExpectation(HexFormat.of().parseHex(CARD_ID), 2, 2,
                        HexFormat.of().parseHex(OTHER_VISIT_ID))
                        .transactionProblems(start).isEmpty(),
                true);
        record("D05", "START thieu visitId ky vong bi tu choi",
                !new ReceiptExpectation(HexFormat.of().parseHex(CARD_ID), 2, 2, null)
                        .transactionProblems(start).isEmpty(),
                true);
        record("D06", "operation khac ky vong bi tu choi",
                !new ReceiptExpectation(HexFormat.of().parseHex(CARD_ID), 2, 3,
                        HexFormat.of().parseHex(VISIT_ID)).transactionProblems(start).isEmpty(),
                true);
    }

    private static void checkReconciliation() {
        System.out.println();
        System.out.println("--- Nhom E: quyet dinh KNOWN/UNKNOWN sau mat response ---");

        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(CARD_ID), 2, 1, null);

        Reconciliation fresh = new Reconciliation();
        record("E01", "khoi tao o trang thai KNOWN va cho phep giao dich",
                fresh.state() == Reconciliation.State.KNOWN && fresh.transactionAllowed(), true);

        Reconciliation lostResponse = new Reconciliation();
        lostResponse.markUnknown("timeout sau GRANT_QUOTA");
        record("E02", "mat response -> UNKNOWN va chan giao dich moi",
                lostResponse.state() == Reconciliation.State.UNKNOWN
                        && !lostResponse.transactionAllowed(),
                true);

        Receipt notExecuted = receipt(1, 5, 1, 5, CARD_ID, NO_VISIT_ID);
        Reconciliation beforeCommit = new Reconciliation();
        beforeCommit.markUnknown("timeout truoc commit");
        record("E03", "receipt cu (counter truoc do) -> NOT_EXECUTED, mo khoa KNOWN",
                beforeCommit.resolve(notExecuted, expectation)
                        == Reconciliation.Verdict.NOT_EXECUTED
                        && beforeCommit.state() == Reconciliation.State.KNOWN,
                true);
        System.out.println("    quyet dinh: receipt counter=1, expected counter=2"
                + " -> NOT_EXECUTED | state=" + beforeCommit.state());

        Receipt executed = receipt(1, 8888, 2, 8893, CARD_ID, NO_VISIT_ID);
        Reconciliation afterCommit = new Reconciliation();
        afterCommit.markUnknown("timeout sau commit");
        record("E04", "receipt moi (dung counter) -> EXECUTED, mo khoa KNOWN",
                afterCommit.resolve(executed, expectation)
                        == Reconciliation.Verdict.EXECUTED
                        && afterCommit.state() == Reconciliation.State.KNOWN,
                true);
        System.out.println("    quyet dinh: receipt counter=2, expected counter=2"
                + " -> EXECUTED | state=" + afterCommit.state());

        Receipt gap = receipt(1, 5, 4, 9, CARD_ID, NO_VISIT_ID);
        Reconciliation gapState = new Reconciliation();
        gapState.markUnknown("timeout");
        record("E05", "counter lech 2 buoc -> MISMATCH va van khoa UNKNOWN",
                gapState.resolve(gap, expectation) == Reconciliation.Verdict.MISMATCH
                        && gapState.state() == Reconciliation.State.UNKNOWN
                        && !gapState.transactionAllowed(),
                true);

        byte[] payloadBefore = executed.payload().clone();
        byte[] signatureBefore = executed.signature().clone();
        gapState.resolve(executed, new ReceiptExpectation(
                HexFormat.of().parseHex(CARD_ID), 2, 1, null));
        record("E06", "doi soat lai khong sua payload/signature da doc",
                Arrays.equals(payloadBefore, executed.payload())
                        && Arrays.equals(signatureBefore, executed.signature()),
                true);

        Reconciliation wrongOperation = new Reconciliation();
        wrongOperation.markUnknown("timeout");
        record("E07", "dung counter nhung sai operation -> MISMATCH, van UNKNOWN",
                wrongOperation.resolve(executed, new ReceiptExpectation(
                        HexFormat.of().parseHex(CARD_ID), 2, 3,
                        HexFormat.of().parseHex(VISIT_ID)))
                        == Reconciliation.Verdict.MISMATCH
                        && wrongOperation.state() == Reconciliation.State.UNKNOWN,
                true);
    }

    private record TestKey(java.security.PrivateKey privateKey, java.security.PublicKey publicKey,
            byte[] encoded) {
    }

    private static TestKey newTestKey() {
        try {
            java.security.KeyPairGenerator generator =
                    java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair pair = generator.generateKeyPair();
            return new TestKey(pair.getPrivate(), pair.getPublic(),
                    ReceiptVerifier.encodePoint(pair.getPublic()));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("khong tao duoc keypair P-256", ex);
        }
    }

    private static byte[] sign(TestKey key, byte[] payload) {
        return sign(key, ReceiptVerifier.DOMAIN, payload);
    }

    private static byte[] sign(TestKey key, byte[] domain, byte[] payload) {
        try {
            java.security.Signature signer =
                    java.security.Signature.getInstance(ReceiptVerifier.ALGORITHM);
            signer.initSign(key.privateKey());
            signer.update(domain);
            signer.update(payload);
            return signer.sign();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("khong ky duoc payload", ex);
        }
    }

    private static byte[] signedReceipt(TestKey key, int operation, int delta, long counter,
            int balance, String cardIdHex, String visitIdHex) {
        byte[] payload = receiptPayload(operation, delta, counter, balance, cardIdHex, visitIdHex);
        byte[] signature = sign(key, payload);
        byte[] out = new byte[35 + signature.length];
        System.arraycopy(payload, 0, out, 0, 34);
        out[34] = (byte) signature.length;
        System.arraycopy(signature, 0, out, 35, signature.length);
        return out;
    }

    private static CardChannel signedCard(TestKey key, byte[] receiptResponse) {
        return issuedChannel(command -> {
            int ins = command.getINS() & 0xFF;
            if (ins == 0x61) {
                return responseWithData(key.encoded(), Sw.OK);
            }
            if (ins == 0x60) {
                return responseWithData(receiptResponse, Sw.OK);
            }
            return new ResponseAPDU(new byte[] {0, 5, 0, 0, 0, 1, (byte) 0x90, 0});
        });
    }

    private static void checkReceiptVerifier() {
        System.out.println();
        System.out.println("--- Nhom S: xac minh chu ky ECDSA receipt trong Java ---");

        TestKey card = newTestKey();
        byte[] payload = receiptPayload(1, 5, 1, 5, CARD_ID, NO_VISIT_ID);
        byte[] signature = sign(card, payload);

        record("S01", "receipt GRANT ky that -> verify PASS",
                ReceiptVerifier.verify(card.publicKey(), payload, signature).ok(), true);

        byte[] tamperedPayload = payload.clone();
        tamperedPayload[ReceiptCodec.OFFSET_BALANCE] ^= 0x01;
        record("S02", "sua 1 byte payload -> verify FAIL",
                !ReceiptVerifier.verify(card.publicKey(), tamperedPayload, signature).ok(), true);

        byte[] tamperedSignature = signature.clone();
        tamperedSignature[tamperedSignature.length - 1] ^= 0x01;
        record("S03", "sua 1 byte chu ky -> verify FAIL",
                !ReceiptVerifier.verify(card.publicKey(), payload, tamperedSignature).ok(), true);

        TestKey otherCard = newTestKey();
        record("S04", "public key khac -> verify FAIL",
                !ReceiptVerifier.verify(otherCard.publicKey(), payload, signature).ok(), true);

        byte[] otherDomain = "LABACCESS-RECEIPT-v2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        record("S05", "domain khac -> verify FAIL",
                !ReceiptVerifier.verify(card.publicKey(), otherDomain, payload, signature).ok(),
                true);

        byte[] signature69 = null;
        for (int attempt = 0; attempt < 8192 && signature69 == null; attempt++) {
            byte[] candidate = sign(card, payload);
            if (candidate.length == 69
                    && ReceiptVerifier.verify(card.publicKey(), payload, candidate).ok()) {
                signature69 = candidate;
            }
        }
        byte[] der73 = Arrays.copyOf(signature, 73);
        record("S06", "chu ky ECDSA DER hop le 69 byte duoc chap nhan, 73 byte bi tu choi",
                signature69 != null
                        && ReceiptVerifier.verify(card.publicKey(), payload, signature69).ok()
                        && !ReceiptVerifier.verify(card.publicKey(), payload, der73).ok(), true);

        ReceiptVerifier.ParsedKey parsed = ReceiptVerifier.parsePublicKey(card.encoded());
        boolean roundTrip = parsed.ok()
                && Arrays.equals(ReceiptVerifier.encodePoint(parsed.key()), card.encoded());
        byte[] wrongPrefix = card.encoded().clone();
        wrongPrefix[0] = 0x02;
        byte[] wrongLength = Arrays.copyOf(card.encoded(), 64);
        byte[] offCurve = card.encoded().clone();
        offCurve[64] ^= 0x01;
        record("S07", "doc 65B 04||X||Y, tu choi prefix/dai/diem ngoai duong cong",
                roundTrip
                        && !ReceiptVerifier.parsePublicKey(wrongPrefix).ok()
                        && !ReceiptVerifier.parsePublicKey(wrongLength).ok()
                        && !ReceiptVerifier.parsePublicKey(offCurve).ok(),
                true);

        byte[] asnOneInteger = Arrays.copyOf(signature, signature.length);
        int rLength = signature[3] & 0xFF;
        asnOneInteger[4 + rLength] = 0x03;
        record("S08", "DER thieu INTEGER cua S -> FAIL truoc khi verify",
                !ReceiptVerifier.verify(card.publicKey(), payload, asnOneInteger).ok(), true);
    }

    private static void checkReceiptSignatureGate() throws CardException {
        System.out.println();
        System.out.println("--- Nhom R: chu ky receipt quyet dinh xoa hay giu journal ---");

        TestKey card = newTestKey();
        byte[] goodReceipt = signedReceipt(card, 1, 5, 1, 5, CARD_ID, NO_VISIT_ID);

        CardGateway readGateway = readyGateway(signedCard(card, goodReceipt));
        CardGateway.Result read = readGateway.readLastReceipt(null);
        record("R01", "gateway doc public key INS 61 + verify -> ECDSA PASS",
                read.ok() && read.text().contains("ECDSA=PASS"), true);

        try {
            Path dir = Files.createTempDirectory("labaccess-signature");
            TestKey key = newTestKey();
            byte[] brokenSignature = signedReceipt(key, 1, 5, 1, 5, CARD_ID, NO_VISIT_ID);
            brokenSignature[35] ^= 0xFF;
            PendingJournal badJournal = new PendingJournal(dir.resolve("bad-signature.txt"));
            CardGateway badGateway = readyGateway(signedCard(key, brokenSignature));
            CardGateway.Result bad = badGateway.runStateTransaction(badJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> new ResponseAPDU(new byte[] {0, 5, 0, 0, 0, 1, (byte) 0x90, 0}), null);
            record("R03", "fields dung nhung chu ky sai -> khong xoa journal",
                    !bad.ok() && badJournal.load().present()
                            && badJournal.load().status() == PendingJournal.Status.PENDING
                            && badGateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                    true);

            TestKey oldKey = newTestKey();
            PendingJournal oldJournal = new PendingJournal(dir.resolve("old-receipt.txt"));
            PendingJournal.Entry oldEntry = new PendingJournal.Entry(CARD_ID, 2,
                    Receipt.OPERATION_GRANT, 5, 10, null, System.currentTimeMillis());
            oldJournal.writePending(oldEntry);
            CardGateway oldGateway = readyGateway(
                    signedCard(oldKey, signedReceipt(oldKey, 1, 5, 1, 5, CARD_ID, NO_VISIT_ID)));
            char[] pin = {'1', '2', '3', '4', '5', '6'};
            CardGateway.Result oldResult = oldGateway.reconcilePending(oldJournal, pin);
            record("R04", "receipt cu verify PASS nhung counter lui -> NOT_EXECUTED, xoa journal",
                    oldResult.ok() && !oldJournal.load().present(), true);

            TestKey newKey = newTestKey();
            PendingJournal newJournal = new PendingJournal(dir.resolve("new-receipt.txt"));
            PendingJournal.Entry newEntry = new PendingJournal.Entry(CARD_ID, 2,
                    Receipt.OPERATION_GRANT, 5, 10, null, System.currentTimeMillis());
            newJournal.writePending(newEntry);
            CardGateway newGateway = readyGateway(
                    signedCard(newKey, signedReceipt(newKey, 1, 5, 2, 10, CARD_ID, NO_VISIT_ID)));
            char[] newPin = {'1', '2', '3', '4', '5', '6'};
            CardGateway.Result newResult = newGateway.reconcilePending(newJournal, newPin);
            record("R05", "receipt moi verify PASS + counter dung -> EXECUTED, xoa journal",
                    newResult.ok() && !newJournal.load().present(), true);

            TestKey cardKey = newTestKey();
            TestKey foreignKey = newTestKey();
            PendingJournal foreignJournal = new PendingJournal(dir.resolve("foreign-key.txt"));
            foreignJournal.writePending(new PendingJournal.Entry(CARD_ID, 2,
                    Receipt.OPERATION_GRANT, 5, 10, null, System.currentTimeMillis()));
            CardGateway foreignGateway = readyGateway(signedCard(cardKey,
                    signedReceipt(foreignKey, 1, 5, 2, 10, CARD_ID, NO_VISIT_ID)));
            char[] foreignPin = {'1', '2', '3', '4', '5', '6'};
            CardGateway.Result foreign = foreignGateway.reconcilePending(foreignJournal, foreignPin);
            record("R06", "cardId khop payload nhung key khong thuoc the -> giu journal",
                    !foreign.ok() && foreignJournal.load().present()
                            && foreignGateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                    true);

            deleteRecursively(dir);
        } catch (Exception ex) {
            record("R00", "nhom R loi khong mong doi: " + ex, false, true);
        }
    }

    private static void checkIdentifyWithRealCard(CardGateway gateway) {
        System.out.println();
        System.out.println("--- Nhom B: nhan dien tren the that qua CardGateway ---");

        try {
            gateway.connect();
        } catch (Exception ex) {
            skip("V01", "identify() thanh cong va dat ready", "khong ket noi duoc Simulator: " + ex);
            skip("V02", "AID sai: that bai, ready=false, status=null", "khong ket noi duoc Simulator");
            skip("V09", "nhan dien lai sau ca sai phuc hoi duoc", "khong ket noi duoc Simulator");
            return;
        }

        CardGateway.Result positive;
        try {
            positive = gateway.identify();
        } catch (Exception ex) {
            positive = CardGateway.Result.fail("identify() loi: " + ex);
        }
        System.out.println("    identify() -> " + positive.text());
        record("V01", "identify() thanh cong va dat ready",
                positive.ok() && gateway.isReady() && gateway.status() != null, true);
        record("V01a", "identify() SW=9000 cho ca SELECT va GET_STATUS",
                positive.ok() && positive.text().contains("SELECT AID=")
                        && positive.text().contains("SW=9000")
                        && positive.text().contains("GET_STATUS SW=9000"), true);
        if (gateway.status() != null) {
            System.out.println(String.format("    status: protocol=%02X lifecycle=%02X cardId=%s",
                    gateway.status().protocolVersion(), gateway.status().lifecycle(),
                    gateway.status().cardId()));
        }

        CardGateway.Result negative;
        try {
            negative = gateway.identifyWith(WRONG_AID);
        } catch (Exception ex) {
            negative = CardGateway.Result.fail("identifyWith() loi: " + ex);
        }
        System.out.println("    identifyWith(AID sai) -> " + negative.text());
        record("V02", "AID sai: that bai, ready=false, status=null",
                !negative.ok() && !gateway.isReady() && gateway.status() == null, true);
        record("V02a", "AID sai: SW hien ro va ket luan CHUA SAN SANG",
                !negative.ok() && negative.text().contains("SW=")
                        && negative.text().contains("CHƯA SẴN SÀNG"), true);

        CardGateway.Result again;
        try {
            again = gateway.identify();
        } catch (Exception ex) {
            again = CardGateway.Result.fail("identify() loi: " + ex);
        }
        System.out.println("    identify() lai -> " + again.text());
        record("V09", "nhan dien lai sau ca sai phuc hoi duoc", again.ok() && gateway.isReady(), true);
    }

    private static void checkLastReceiptOnCard(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom F: GET_LAST_RECEIPT tren the that ---");

        if (!gateway.isReady() || gateway.status() == null) {
            skip("F01", "GET_LAST_RECEIPT khi chua VERIFY tra 6982/6985",
                    "chua SELECT + GET_STATUS thanh cong");
            skip("F02", "doc duoc receipt that tren the", "chua SELECT + GET_STATUS thanh cong");
            return;
        }

        CardGateway.Result read;
        try {
            read = gateway.readLastReceipt(null);
        } catch (Exception ex) {
            read = CardGateway.Result.fail("readLastReceipt loi: " + ex);
        }
        System.out.println("    readLastReceipt(null) -> " + read.text());
        int sw = gateway.lastResponseSw();
        int lifecycle = gateway.status().lifecycle();
        if (lifecycle == 0x01) {
            record("F01", "the ISSUED, chua VERIFY -> 6982",
                    sw == Sw.SECURITY_NOT_SATISFIED, true);
        } else {
            record("F01", "the chua ISSUED -> 6985",
                    sw == Sw.CONDITIONS_NOT_SATISFIED, true);
        }

        if (lifecycle != 0x01) {
            skip("F02", "doc duoc receipt that tren the", "the chua ISSUED nen khong co receipt");
            skip("F03", "Le thieu tra 6700", "the chua ISSUED nen khong kiem duoc Le");
            return;
        }
        liveSecret(true);
        char[] studentPin = liveSecret(false);
        if (studentPin == null) {
            skip("F02", "doc duoc receipt that tren the", "khong doc duoc student PIN");
            skip("F03", "Le thieu tra 6700", "khong doc duoc student PIN");
            return;
        }
        CardGateway.Result verify = gateway.verifyStudentPin(studentPin);
        if (!verify.ok()) {
            skip("F02", "doc duoc receipt that tren the", "VERIFY that bai: " + verify.text());
            skip("F03", "Le thieu tra 6700", "VERIFY that bai");
            return;
        }
        try {
            CardGateway.Result first = gateway.readLastReceipt(null, 256);
            if (first.ok()) {
                record("F02", "doc duoc receipt that tren the sau VERIFY", true, true);
            } else {
                record("F02", "the ISSUED nhung chua co receipt nao",
                        gateway.lastResponseSw() == Sw.CONDITIONS_NOT_SATISFIED, true);
            }
            gateway.verifyStudentPin(studentPin);
            CardGateway.Result shortLe = gateway.readLastReceipt(null, 34);
            record("F03", "Le thieu tra 6700", shortLe.ok(), false);
            System.out.println("    Le=34 -> " + shortLe.text());
        } catch (CardException ex) {
            record("F02", "doc duoc receipt that tren the", false, true);
            System.out.println("    exception: " + ex);
        }
    }

    private static void checkPendingJournal() {
        System.out.println();
        System.out.println("--- Nhom J: PendingJournal ghi doc atomic replace ---");

        try {
            Path dir = Files.createTempDirectory("labaccess-journal-test");
            Path file = dir.resolve("pending-journal.txt");
            PendingJournal journal = new PendingJournal(file);
            record("J01", "khong co file -> khong pending",
                    !journal.load().present() && journal.load().valid(), true);

            PendingJournal.Entry entry = new PendingJournal.Entry(
                    CARD_ID, 2, 1, 5, 5, null, 1000L);
            record("J02", "ghi pending thanh cong", journal.writePending(entry), true);
            record("J03", "doc lai giu dung 7 truong",
                    journal.load().present() && journal.load().valid()
                            && journal.load().entry().cardId().equalsIgnoreCase(CARD_ID)
                            && journal.load().entry().expectedCounter() == 2
                            && journal.load().entry().operation() == 1
                            && journal.load().entry().delta() == 5
                            && journal.load().entry().expectedBalance() == 5
                            && journal.load().status() == PendingJournal.Status.PENDING,
                    true);

            byte[] pinNeedle = "123456".getBytes();
            byte[] fileBytes = Files.readAllBytes(file);
            boolean noPin = indexOf(fileBytes, pinNeedle) < 0;
            record("J04", "file journal khong chua PIN", noPin, true);

            Files.write(file, "v1|PENDING|A1B2C3D411223344|2|02|-1|4||1000\n".getBytes());
            record("J05", "START thieu visitId -> journal khong hop le",
                    journal.load().present() && !journal.load().valid(), true);

            Files.write(file, "v1|PENDING|A1B2C3D411223344|2|01|5|5|00000000000000000000000000000000|1000\n".getBytes());
            record("J06", "GRANT co visitId -> journal khong hop le",
                    journal.load().present() && !journal.load().valid(), true);

            Files.write(file, "v1|PENDING|A1B2C3D411223344|2|02|-1|4|00000000000000000000000000000000|1000\n".getBytes());
            record("J07", "START visitId toan 00 bi tu choi",
                    journal.load().present() && !journal.load().valid(), true);

            Files.write(file, "v2|PENDING|A1B2C3D411223344|2|01|5|5||1000\n".getBytes());
            record("J08", "schema khac v1 -> khong parse duoc",
                    journal.load().present() && !journal.load().valid(), true);

            Files.write(file, "khong phai journal\n".getBytes());
            record("J09", "file rac -> khong parse duoc, bao doi soat thu cong",
                    journal.load().present() && !journal.load().valid(), true);

            PendingJournal.Entry resolved = new PendingJournal.Entry(
                    CARD_ID, 2, 1, 5, 5, null, 1000L);
            record("J10", "ghi RESOLVED thanh cong", journal.writeResolved(resolved), true);
            record("J11", "doc lai trang thai RESOLVED",
                    journal.load().present() && journal.load().valid()
                            && journal.load().status() == PendingJournal.Status.RESOLVED,
                    true);

            journal.remove();
            record("J12", "xoa journal thanh cong",
                    !Files.exists(file) && !journal.load().present(), true);

            deleteRecursively(dir);
        } catch (Exception ex) {
            record("J00", "PendingJournal loi khong mong doi", false, true);
            System.out.println("    exception: " + ex);
        }
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static void checkVerifyMapping() throws CardException {
        System.out.println();
        System.out.println("--- Nhom P: anh xa SW VERIFY_STUDENT_PIN ---");

        record("P01", "9000 -> ok", verifyText(Sw.OK).contains("SW=9000"), true);
        record("P02", "63C2 -> con 2 lan thu", verifyText(0x63C2).contains("còn 2 lần thử"), true);
        record("P03", "6983 -> PIN bi khoa", verifyText(Sw.AUTH_BLOCKED).contains("khóa"), true);
        record("P04", "6985 -> chua personalize",
                verifyText(Sw.CONDITIONS_NOT_SATISFIED).contains("chưa personalize"), true);
        record("P05", "6A80 -> dinh dang sai",
                verifyText(Sw.WRONG_DATA).contains("định dạng PIN sai"), true);
        record("P06", "6700 -> Lc khong khop",
                verifyText(Sw.WRONG_LENGTH).contains("Lc không khớp"), true);
        record("P07", "6A86 -> P1P2 sai",
                verifyText(Sw.WRONG_P1P2).contains("P1/P2 phải 0000"), true);

        java.util.concurrent.atomic.AtomicBoolean shape = new java.util.concurrent.atomic.AtomicBoolean();
        CardGateway gateway = readyGateway(issuedChannel(command -> {
            byte[] data = command.getData();
            shape.set(command.getCLA() == 0x80 && command.getINS() == 0x20
                    && command.getP1() == 0 && command.getP2() == 0
                    && command.getNc() == 6 && command.getNe() == 0
                    && Arrays.equals(data, "654321".getBytes()));
            return response(Sw.OK);
        }));
        CardGateway.Result verify = gateway.verifyStudentPin("654321".toCharArray());
        record("P08", "VERIFY gui Case 3S 80 20 00 00 Lc=6 khong Le",
                verify.ok() && shape.get(), true);
    }

    private static String verifyText(int sw) throws CardException {
        return readyGateway(issuedChannel(command -> response(sw)))
                .verifyStudentPin("654321".toCharArray()).text();
    }

    private static ResponseAPDU responseWithData(byte[] data, int sw) {
        byte[] raw = Arrays.copyOf(data, data.length + 2);
        raw[data.length] = (byte) (sw >> 8);
        raw[data.length + 1] = (byte) sw;
        return new ResponseAPDU(raw);
    }

    private static ResponseAPDU response(int sw) {
        return new ResponseAPDU(new byte[] {(byte) (sw >> 8), (byte) sw});
    }

    private static CardChannel scriptedChannel(java.util.function.Function<CommandAPDU,
            ResponseAPDU> handler) {
        return new CardChannel() {
            @Override
            public Card getCard() {
                return null;
            }

            @Override
            public int getChannelNumber() {
                return 0;
            }

            @Override
            public ResponseAPDU transmit(CommandAPDU command) throws CardException {
                return handler.apply(command);
            }

            @Override
            public int transmit(ByteBuffer command, ByteBuffer response) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
    }

    private static void checkTransactionGate() {
        System.out.println();
        System.out.println("--- Nhom T: khoa giao dich khi pending/UNKNOWN ---");

        try {
            Path dir = Files.createTempDirectory("labaccess-gate-test");
            Path file = dir.resolve("pending-journal.txt");
            PendingJournal journal = new PendingJournal(file);
            CardGateway gate1 = readyGateway(issuedChannel(command -> response(Sw.OK)));
            gate1.markTransactionUnknown("mat response sau GRANT");
            record("T01", "UNKNOWN -> khong cho giao dich",
                    !gate1.reconciliation().transactionAllowed(), true);

            CardGateway.Result blockedByUnknown = gate1.runStateTransaction(journal,
                    new CardGateway.TransactionPlan(new ReceiptExpectation(null, 1, 1, null),
                            5, 5),
                    () -> {
                        throw new AssertionError("khong duoc gui APDU khi UNKNOWN");
                    }, null);
            record("T02", "runStateTransaction tu choi khi UNKNOWN",
                    !blockedByUnknown.ok() && blockedByUnknown.text().contains("bị khóa"), true);

            PendingJournal.Entry pending = new PendingJournal.Entry(
                    CARD_ID, 2, 1, 5, 5, null, System.currentTimeMillis());
            journal.writePending(pending);
            CardGateway gate2 = readyGateway(issuedChannel(command -> response(Sw.OK)));
            CardGateway.Result blockedByPending = gate2.runStateTransaction(journal,
                    new CardGateway.TransactionPlan(new ReceiptExpectation(null, 1, 1, null),
                            5, 5),
                    () -> {
                        throw new AssertionError("khong duoc gui APDU khi con pending");
                    }, null);
            record("T03", "runStateTransaction tu choi khi con pending journal",
                    !blockedByPending.ok() && blockedByPending.text().contains("pending journal"),
                    true);

            record("T04", "journal van con pending, APDU khong duoc gui",
                    Files.exists(file) && gate2.reconciliation().transactionAllowed(), true);

            PendingJournal blockedJournal = new PendingJournal(dir.resolve("blocked").resolve("journal"));
            Files.createDirectories(dir.resolve("blocked").resolve("journal"));
            java.util.concurrent.atomic.AtomicInteger blockedCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            CardGateway blockedGateway = readyGateway(issuedChannel(command -> response(Sw.OK)));
            CardGateway.Result blockedWrite = blockedGateway.runStateTransaction(blockedJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> {
                        blockedCalls.incrementAndGet();
                        return response(Sw.OK);
                    }, null);
            record("T05", "journal write failure -> no state APDU",
                    !blockedWrite.ok() && blockedCalls.get() == 0, true);

            PendingJournal lostJournal = new PendingJournal(dir.resolve("lost-journal.txt"));
            java.util.concurrent.atomic.AtomicInteger lostCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            CardGateway lostGateway = readyGateway(issuedChannel(command -> response(Sw.OK)));
            CardGateway.Result lost = lostGateway.runStateTransaction(lostJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> {
                        lostCalls.incrementAndGet();
                        throw new CardException("scripted lost response");
                    }, null);
            record("T06", "lost response -> one transmit, PENDING + UNKNOWN",
                    !lost.ok() && lostCalls.get() == 1
                            && lostGateway.reconciliation().state() == Reconciliation.State.UNKNOWN
                            && lostJournal.load().present() && lostJournal.load().valid()
                            && lostJournal.load().status() == PendingJournal.Status.PENDING,
                    true);
            CardGateway.Result noRetry = lostGateway.runStateTransaction(lostJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> {
                        lostCalls.incrementAndGet();
                        return response(Sw.OK);
                    }, null);
            record("T07", "UNKNOWN/pending -> lan goi sau bi chan, khong retry",
                    !noRetry.ok() && lostCalls.get() == 1, true);

            PendingJournal successJournal = new PendingJournal(dir.resolve("success-journal.txt"));
            java.util.concurrent.atomic.AtomicInteger successCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            TestKey successKey = newTestKey();
            CardGateway successGateway = readyGateway(signedCard(successKey,
                    signedReceipt(successKey, 1, 5, 1, 5, CARD_ID, NO_VISIT_ID)));
            CardGateway.Result successful = successGateway.runStateTransaction(successJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> {
                        successCalls.incrementAndGet();
                        return new ResponseAPDU(new byte[] {0, 5, 0, 0, 0, 1,
                                (byte) 0x90, 0});
                    }, null);
            record("T08", "receipt EXECUTED + values khop -> RESOLVED va xoa",
                    successful.ok() && !successJournal.load().present()
                            && successGateway.reconciliation().state() == Reconciliation.State.KNOWN,
                    true);

            PendingJournal mismatchJournal = new PendingJournal(dir.resolve("mismatch-journal.txt"));
            TestKey mismatchKey = newTestKey();
            CardGateway mismatchGateway = readyGateway(signedCard(mismatchKey,
                    signedReceipt(mismatchKey, 1, 4, 1, 4, CARD_ID, NO_VISIT_ID)));
            CardGateway.Result mismatch = mismatchGateway.runStateTransaction(mismatchJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> new ResponseAPDU(new byte[] {0, 5, 0, 0, 0, 1,
                            (byte) 0x90, 0}), null);
            record("T09", "receipt delta/balance mismatch -> UNKNOWN + PENDING retained",
                    !mismatch.ok() && mismatchJournal.load().present()
                            && mismatchJournal.load().status() == PendingJournal.Status.PENDING
                            && mismatchGateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                    true);

            PendingJournal staleJournal = new PendingJournal(dir.resolve("stale-journal.txt"));
            TestKey staleKey = newTestKey();
            CardGateway staleGateway = readyGateway(signedCard(staleKey,
                    signedReceipt(staleKey, 1, 0, 0, 0, CARD_ID, NO_VISIT_ID)));
            CardGateway.Result stale = staleGateway.runStateTransaction(staleJournal,
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> new ResponseAPDU(new byte[] {0, 5, 0, 0, 0, 1,
                            (byte) 0x90, 0}), null);
            record("T10", "state APDU 9000 + counter receipt cu -> UNKNOWN/PENDING",
                    !stale.ok() && staleJournal.load().present()
                            && staleJournal.load().status() == PendingJournal.Status.PENDING
                            && staleGateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                    true);

            CardGateway unknownGateway = new CardGateway(scriptedChannel(command -> response(Sw.OK)));
            java.util.concurrent.atomic.AtomicInteger unknownCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            CardGateway.Result unknownResult = unknownGateway.runStateTransaction(
                    new PendingJournal(dir.resolve("unknown-journal.txt")),
                    new CardGateway.TransactionPlan(
                            new ReceiptExpectation(null, 1, Receipt.OPERATION_GRANT, null), 5, 5),
                    () -> {
                        unknownCalls.incrementAndGet();
                        return response(Sw.OK);
                    }, null);
            record("T11", "chua SELECT/GET_STATUS ISSUED -> tu choi, khong gui APDU",
                    !unknownResult.ok() && unknownCalls.get() == 0
                            && unknownResult.text().contains("ISSUED"), true);

            journal.remove();
            blockedJournal.remove();
            lostJournal.remove();
            successJournal.remove();
            mismatchJournal.remove();
            staleJournal.remove();
            deleteRecursively(dir);
        } catch (Exception ex) {
            record("T00", "Transaction gate loi khong mong doi", false, true);
            System.out.println("    exception: " + ex);
        }
    }

    private static char[] liveAdminPinChars;
    private static char[] liveStudentPinChars;

    private static char[] liveSecret(boolean admin) {
        if (admin) {
            if (liveAdminPinChars == null) {
                liveAdminPinChars = readSecret(System.console(), "Admin PIN (8 digits): ", 8);
            }
            return liveAdminPinChars;
        }
        if (liveStudentPinChars == null) {
            liveStudentPinChars = readSecret(System.console(), "Student PIN (6 digits): ", 6);
        }
        return liveStudentPinChars;
    }

    private static void clearLiveSecrets() {
        if (liveAdminPinChars != null) {
            java.util.Arrays.fill(liveAdminPinChars, '\0');
            liveAdminPinChars = null;
        }
        if (liveStudentPinChars != null) {
            java.util.Arrays.fill(liveStudentPinChars, '\0');
            liveStudentPinChars = null;
        }
    }

    private static void checkPersonalizeOffline() {
        System.out.println();
        System.out.println("--- Nhom K: PERSONALIZE payload, byte length, chan input sai ---");

        char[] adminPin = sampleDigits(8);
        char[] studentPin = sampleDigits(6);
        String cardId = CARD_ID;
        String studentId = "SV2024-001";
        String name = "Nguyễn Văn Ánh";
        try {
            record("K01", "input hop le -> khong co loi dinh dang",
                    PersonalizeRequest.problem(adminPin, cardId, studentId, studentPin, name) == null, true);

            record("K02", "admin PIN 7 chu so bi chan",
                    PersonalizeRequest.problem(
                            sampleDigits(7), cardId, studentId, studentPin, name) != null, true);
            char[] adminLetters = "abcdefgh".toCharArray();
            record("K03", "admin PIN co chu cai bi chan",
                    PersonalizeRequest.problem(
                            adminLetters, cardId, studentId, studentPin, name) != null, true);
            record("K04", "cardId toan 00 bi chan",
                    PersonalizeRequest.problem(
                            adminPin, "0000000000000000", studentId, studentPin, name) != null, true);
            record("K05", "cardId sai do dai hex bi chan",
                    PersonalizeRequest.problem(
                            adminPin, "A1B2C3", studentId, studentPin, name) != null, true);
            record("K06", "studentId rong bi chan",
                    PersonalizeRequest.problem(
                            adminPin, cardId, "", studentPin, name) != null, true);
            record("K07", "studentId 33 byte bi chan",
                    PersonalizeRequest.problem(
                            adminPin, cardId, "A".repeat(33), studentPin, name) != null, true);
            record("K08", "studentId chu thuong bi chan",
                    PersonalizeRequest.problem(
                            adminPin, cardId, "sv2024", studentPin, name) != null, true);
            record("K09", "student PIN 5 chu so bi chan",
                    PersonalizeRequest.problem(
                            adminPin, cardId, studentId, sampleDigits(5), name) != null, true);
            record("K10", "ten hien thi rong bi chan",
                    PersonalizeRequest.problem(
                            adminPin, cardId, studentId, studentPin, "") != null, true);
            record("K11", "ten hien thi 97 byte bi chan",
                    PersonalizeRequest.problem(
                            adminPin, cardId, studentId, studentPin, "a".repeat(97)) != null, true);

            byte[] ascii = PersonalizeRequest.payload(
                    adminPin, cardId, studentId, studentPin, "Nguyen Van A");
            record("K12", "Lc = 25 + studentId + ten (ASCII)",
                    ascii.length, 25 + studentId.length() + "Nguyen Van A".length());

            byte[] multibyte = PersonalizeRequest.payload(
                    adminPin, cardId, studentId, studentPin, name);
            int nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            record("K13", "Lc tinh theo byte UTF-8, khong theo ky tu",
                    multibyte.length, 25 + studentId.length() + nameBytes);
            record("K14", "byte UTF-8 nhieu hon so ky tu",
                    nameBytes > name.length(), true);

            record("K15", "adminPinLength tai offset 0 bang 08", ascii[0], 0x08);
            record("K16", "cardId tai offset 9",
                    HexFormat.of().formatHex(Arrays.copyOfRange(ascii, 9, 17))
                            .equalsIgnoreCase(cardId), true);
            record("K17", "studentIdLength tai offset 17", ascii[17] & 0xFF, studentId.length());
            record("K18", "studentId tai offset 18",
                    new String(Arrays.copyOfRange(ascii, 18, 18 + studentId.length()),
                            java.nio.charset.StandardCharsets.US_ASCII).equals(studentId), true);
            int pinOffset = 18 + studentId.length();
            record("K19", "initialStudentPIN ngay sau studentId",
                    matchesDigitsAt(ascii, pinOffset, studentPin), true);
            record("K20", "displayNameLength tai offset sau student PIN",
                    ascii[pinOffset + 6] & 0xFF, "Nguyen Van A".length());

            byte[] maxSized = PersonalizeRequest.payload(
                    adminPin, cardId, "A".repeat(32), studentPin, "a".repeat(96));
            record("K21", "Lc toi da 153 voi studentId 32 + ten 96", maxSized.length,
                    PersonalizeRequest.MAX_PAYLOAD_LENGTH);

            Arrays.fill(ascii, (byte) 0);
            Arrays.fill(multibyte, (byte) 0);
            Arrays.fill(maxSized, (byte) 0);
        } finally {
            Arrays.fill(adminPin, '\0');
            Arrays.fill(studentPin, '\0');
        }
    }

    private static void checkPersonalizeMapping() throws CardException {
        System.out.println();
        System.out.println("--- Nhom Q: anh xa SW cua PERSONALIZE ---");

        char[] adminPin = sampleDigits(8);
        char[] studentPin = sampleDigits(6);
        String[] ids = { "Q01", "Q02", "Q03", "Q04", "Q05", "Q06", "Q07", "Q08" };
        int[] sws = { Sw.OK, Sw.CONDITIONS_NOT_SATISFIED, 0x63C2, Sw.AUTH_BLOCKED,
                Sw.WRONG_DATA, Sw.WRONG_LENGTH, Sw.WRONG_P1P2, Sw.INTERNAL_ERROR };
        String[] expects = { "SW=9000", "đã ISSUED", "còn 2 lượt thử", "khóa",
                "payload bị từ chối", "Lc không khớp", "P1/P2", "UNPERSONALIZED" };
        boolean[] wantOk = { true, false, false, false, false, false, false, false };
        try {
            for (int index = 0; index < sws.length; index++) {
                final int scriptedSw = sws[index];
                CommandAPDU[] captured = new CommandAPDU[1];
                CardGateway gateway = readyGateway(issuedChannel(command -> {
                    if ((command.getINS() & 0xFF) == 0x30) {
                        captured[0] = command;
                        return response(scriptedSw);
                    }
                    return response(Sw.INS_NOT_SUPPORTED);
                }));
                CardGateway.Result result = gateway.personalize(
                        adminPin, CARD_ID, "SV2024-001", studentPin, "Nguyen Van A");
                boolean okText = result.ok() == wantOk[index]
                        && result.text().contains(expects[index])
                        && !mentionsDigits(result.text(), adminPin)
                        && !mentionsDigits(result.text(), studentPin);
                record(ids[index], "SW " + String.format("%04X", sws[index]) + " -> " + expects[index],
                        okText, true);
                if (index == 0) {
                    record("Q09", "gui Case 4S: Lc dung byte, Le=00",
                            captured[0] != null
                            && captured[0].getNc() == 25 + "SV2024-001".length()
                                    + "Nguyen Van A".length()
                            && captured[0].getNe() == 256, true);
                    record("Q10", "P1/P2 = 0000",
                            captured[0] != null && captured[0].getP1() == 0
                            && captured[0].getP2() == 0, true);
                }
            }
        } finally {
            Arrays.fill(adminPin, '\0');
            Arrays.fill(studentPin, '\0');
        }
    }

    private static char[] sampleDigits(int length) {
        char[] value = new char[length];
        for (int index = 0; index < length; index++) {
            value[index] = (char) ('1' + index % 9);
        }
        return value;
    }

    private static boolean matchesDigitsAt(byte[] data, int offset, char[] digits) {
        for (int index = 0; index < digits.length; index++) {
            if (data[offset + index] != (byte) digits[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean mentionsDigits(String text, char[] digits) {
        for (int start = 0; start + digits.length <= text.length(); start++) {
            boolean match = true;
            for (int index = 0; index < digits.length; index++) {
                if (text.charAt(start + index) != digits[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static void checkPublicKeyOffline() throws Exception {
        System.out.println();
        System.out.println("--- Nhom N: GET_CARD_PUBLIC_KEY (INS 61) offline ---");

        byte[] validPoint = p256Point();

        CardGateway unissued = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x61) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            return response(Sw.INS_NOT_SUPPORTED);
        }));
        CardGateway.Result notIssued = unissued.readCardPublicKey();
        record("N01", "the chua ISSUED -> 6985, khong giu key",
                !notIssued.ok() && notIssued.text().contains("6985")
                && notIssued.text().contains("chưa ISSUED")
                && unissued.cardPublicKey() == null, true);

        record("N02", "Le thieu -> 6700 duoc anh xa",
                publicKeyFailure(Sw.WRONG_LENGTH).contains("Le nhỏ hơn 65 byte"), true);
        record("N03", "P1/P2 sai -> 6A86 duoc anh xa",
                publicKeyFailure(Sw.WRONG_P1P2).contains("P1/P2 phải 0000"), true);

        byte[] shortPoint = Arrays.copyOf(validPoint, 64);
        record("N04", "public key 64 byte bi tu choi",
                publicKeyProblem(shortPoint).contains("phải đúng 65 byte"), true);

        byte[] wrongPrefix = validPoint.clone();
        wrongPrefix[0] = 0x05;
        record("N05", "prefix khac 04 bi tu choi",
                publicKeyProblem(wrongPrefix).contains("phải là 04"), true);

        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        record("N06", "diem khong nam tren P-256 bi tu choi",
                publicKeyProblem(offCurve).contains("không dựng được")
                || publicKeyProblem(offCurve).contains("key=null"), true);

        CardGateway issued = readyGateway(issuedChannel(command ->
                (command.getINS() & 0xFF) == 0x61
                        ? dataApdu(validPoint)
                        : response(Sw.INS_NOT_SUPPORTED)));
        CardGateway.Result first = issued.readCardPublicKey();
        String firstHex = hexPoint(issued);
        record("N07", "65 byte hop le -> 9000, giu PublicKey",
                first.ok() && first.text().contains("SW=9000")
                && first.text().contains("dataLen=65")
                && first.text().contains("cardId=" + CARD_ID)
                && issued.cardPublicKey() != null, true);
        record("N08", "encode lai dung 65 byte", firstHex.length() / 2, 65);
        record("N09", "byte dau la 04", firstHex.startsWith("04"), true);

        CardGateway.Result second = issued.readCardPublicKey();
        record("N10", "doc lai cung the -> cung public key",
                second.ok() && hexPoint(issued).equals(firstHex), true);

        Arrays.fill(shortPoint, (byte) 0);
        Arrays.fill(wrongPrefix, (byte) 0);
    }

    private static String publicKeyFailure(int sw) {
        CardGateway gateway = readyGateway(issuedChannel(command ->
                (command.getINS() & 0xFF) == 0x61
                        ? response(sw)
                        : response(Sw.INS_NOT_SUPPORTED)));
        try {
            return gateway.readCardPublicKey().text();
        } catch (CardException ex) {
            return "loi " + ex.getClass().getSimpleName();
        }
    }

    private static String publicKeyProblem(byte[] point) {
        CardGateway gateway = readyGateway(issuedChannel(command ->
                (command.getINS() & 0xFF) == 0x61
                        ? dataApdu(point)
                        : response(Sw.INS_NOT_SUPPORTED)));
        try {
            CardGateway.Result result = gateway.readCardPublicKey();
            return result.text() + " | " + (gateway.cardPublicKey() == null ? "key=null" : "key=GIU");
        } catch (CardException ex) {
            return "loi " + ex.getClass().getSimpleName();
        }
    }

    private static byte[] p256Point() throws Exception {
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("EC");
        java.security.AlgorithmParameters parameters =
                java.security.AlgorithmParameters.getInstance("EC");
        parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        generator.initialize(parameters.getParameterSpec(
                java.security.spec.ECParameterSpec.class));
        return ReceiptVerifier.encodePoint(generator.generateKeyPair().getPublic());
    }

    private static String hexPoint(CardGateway gateway) {
        java.security.PublicKey key = gateway.cardPublicKey();
        return key == null ? "" : HexFormat.of().formatHex(ReceiptVerifier.encodePoint(key));
    }

    private static ResponseAPDU dataApdu(byte[] data) {
        byte[] raw = new byte[data.length + 2];
        System.arraycopy(data, 0, raw, 0, data.length);
        raw[data.length] = (byte) (SW_OK >> 8);
        raw[data.length + 1] = (byte) SW_OK;
        return new ResponseAPDU(raw);
    }

    private static void checkPublicKeyBeforePersonalize(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom N: GET_CARD_PUBLIC_KEY tren the chua phat hanh ---");

        CardGateway.Status current = gateway.status();
        if (current != null && current.lifecycle() == 0x01) {
            skip("N11", "the chua ISSUED -> 6985 tren the that", "the da ISSUED truoc phien chay");
            return;
        }
        CardGateway.Result result = gateway.readCardPublicKey();
        record("N11", "the chua ISSUED -> 6985 tren the that",
                !result.ok() && result.text().contains("6985")
                && result.text().contains("chưa ISSUED")
                && gateway.cardPublicKey() == null, true);
    }

    private static void checkPublicKeyAfterPersonalize(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom N: GET_CARD_PUBLIC_KEY tren the da ISSUED ---");

        if (gateway.status() == null || gateway.status().lifecycle() != 0x01) {
            skip("N12", "the ISSUED doc public key 65 byte", "the chua ISSUED");
            return;
        }
        CardGateway.Result first = gateway.readCardPublicKey();
        String firstHex = hexPoint(gateway);
        record("N12", "the ISSUED -> 9000 + 65 byte + PublicKey",
                first.ok() && first.text().contains("SW=9000")
                && first.text().contains("dataLen=65")
                && gateway.cardPublicKey() != null, true);
        record("N13", "public key hien cung cardId dang nhan dien",
                first.text().contains("cardId=" + gateway.status().cardId()), true);
        record("N14", "diem uncompressed 04||X||Y dung 65 byte", firstHex.length() / 2, 65);
        record("N15", "header 04", firstHex.startsWith("04"), true);

        CardGateway.Result second = gateway.readCardPublicKey();
        record("N16", "doc lai cung the -> cung public key",
                second.ok() && hexPoint(gateway).equals(firstHex), true);
    }

    private static void checkPinOffline() throws CardException {
        System.out.println();
        System.out.println("--- Nhom M: VERIFY / CHANGE / UNBLOCK offline ---");

        char[] pin = "654321".toCharArray();
        char[] admin = "12345678".toCharArray();
        try {
            record("M01", "VERIFY 9000 -> quyen student mo",
                    pinText("verify", Sw.OK).contains("quyền student đã mở"), true);
            record("M02", "VERIFY 63C2 -> con 2 lan thu",
                    pinText("verify", 0x63C2).contains("còn 2 lần thử"), true);
            record("M03", "VERIFY 6983 -> PIN bi khoa",
                    pinText("verify", Sw.AUTH_BLOCKED).contains("khóa"), true);
            record("M04", "VERIFY 6985 -> chua personalize",
                    pinText("verify", Sw.CONDITIONS_NOT_SATISFIED).contains("chưa personalize"), true);
            record("M05", "VERIFY 6A80 -> dinh dang sai",
                    pinText("verify", Sw.WRONG_DATA).contains("định dạng PIN sai"), true);
            record("M06", "VERIFY 6700 -> Lc khong khop",
                    pinText("verify", Sw.WRONG_LENGTH).contains("Lc không khớp"), true);
            record("M07", "VERIFY 6A86 -> P1P2 sai",
                    pinText("verify", Sw.WRONG_P1P2).contains("P1/P2 phải 0000"), true);
            record("M08", "CHANGE 9000 -> bao da doi PIN",
                    pinText("change", Sw.OK).contains("PIN đã đổi"), true);
            record("M09", "CHANGE 63C1 -> con 1 lan thu",
                    pinText("change", 0x63C1).contains("còn 1 lần thử"), true);
            record("M10", "UNBLOCK 9000 -> bao da mo khoa",
                    pinText("unblock", Sw.OK).contains("đã mở khóa"), true);
            record("M11", "UNBLOCK 6983 -> admin bi khoa",
                    pinText("unblock", Sw.AUTH_BLOCKED).contains("khóa"), true);

            java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<byte[]> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<int[]> shape =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CardGateway counter = readyGateway(issuedChannel(command -> {
                sent.incrementAndGet();
                seen.set(command.getData());
                shape.set(new int[] {command.getINS() & 0xFF, command.getP1(), command.getP2(),
                    command.getNc(), command.getNe(), command.getBytes().length});
                return response(Sw.OK);
            }));

            counter.verifyStudentPin(pin);
            record("M12", "VERIFY chi gui dung 1 APDU", sent.get(), 1);

            counter.changeStudentPin(pin, "112233".toCharArray());
            record("M13", "CHANGE chi gui dung 1 APDU", sent.get(), 2);
            record("M14", "CHANGE Case 3S: INS 21 Lc=12, khong Le",
                    shape.get()[0] == 0x21 && shape.get()[3] == 12 && shape.get()[4] == 0
                    && shape.get()[5] == 17, true);
            record("M15", "CHANGE data = old 6 byte + new 6 byte",
                    Arrays.equals(seen.get(), "654321112233".getBytes()), true);

            counter.unblockStudentPin(admin, "445566".toCharArray());
            record("M16", "UNBLOCK chi gui dung 1 APDU", sent.get(), 3);
            record("M17", "UNBLOCK Case 3S: INS 23 Lc=14, khong Le",
                    shape.get()[0] == 0x23 && shape.get()[3] == 14 && shape.get()[4] == 0
                    && shape.get()[5] == 19, true);
            record("M18", "UNBLOCK data = admin 8 byte + new 6 byte",
                    Arrays.equals(seen.get(), "12345678445566".getBytes()), true);

            record("M19", "PIN sai van chi 1 APDU, khong retry",
                    retryCount(pin), 1);

            java.util.concurrent.atomic.AtomicInteger guarded = new java.util.concurrent.atomic.AtomicInteger();
            CardGateway unissued = readyGateway(issuedChannel(command -> {
                guarded.incrementAndGet();
                return response(Sw.OK);
            }, 0x00));
            record("M20", "the chua ISSUED bi chan truoc khi gui",
                    !unissued.verifyStudentPin(pin).ok() && guarded.get() == 0, true);

            CardGateway notIdentified = new CardGateway(scriptedChannel(command -> {
                guarded.incrementAndGet();
                return response(Sw.OK);
            }));
            record("M21", "chua SELECT + GET_STATUS bi chan truoc khi gui",
                    !notIdentified.verifyStudentPin(pin).ok() && guarded.get() == 0, true);

            record("M22", "PIN 5 chu so bi chan tai client",
                    counter.verifyStudentPin("12345".toCharArray()).text().contains("6 chữ số"), true);
            record("M23", "PIN co chu cai bi chan tai client",
                    counter.verifyStudentPin("12345a".toCharArray()).text().contains("chữ số 0-9"), true);
            record("M24", "admin PIN 7 chu so bi chan tai client",
                    counter.unblockStudentPin("1234567".toCharArray(),
                            "445566".toCharArray()).text().contains("8 chữ số"), true);
            record("M25", "chan tai client thi khong gui APDU", sent.get(), 3);
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(admin, '\0');
        }
    }

    private static String pinText(String op, int sw) throws CardException {
        CardGateway gateway = readyGateway(issuedChannel(command -> response(sw)));
        char[] pin = "654321".toCharArray();
        char[] admin = "12345678".toCharArray();
        try {
            if (op.equals("verify")) {
                return gateway.verifyStudentPin(pin).text();
            }
            if (op.equals("change")) {
                return gateway.changeStudentPin(pin, "112233".toCharArray()).text();
            }
            return gateway.unblockStudentPin(admin, "445566".toCharArray()).text();
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(admin, '\0');
        }
    }

    private static int retryCount(char[] pin) throws CardException {
        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        CardGateway gateway = readyGateway(issuedChannel(command -> {
            sent.incrementAndGet();
            return response(0x63C2);
        }));
        gateway.verifyStudentPin(pin);
        return sent.get();
    }

    private static void checkPinState() throws CardException {
        System.out.println();
        System.out.println("--- Nhom H: trang thai PIN hien thi theo SW ---");

        record("H01", "VERIFY 9000 -> da xac thuc, quyen cho lenh ke tiep",
                PinState.of(PinState.Operation.VERIFY, true, Sw.OK).text()
                        .contains("Đã xác thực")
                && PinState.of(PinState.Operation.VERIFY, true, Sw.OK).text()
                        .contains("lệnh nhạy cảm kế tiếp"), true);
        record("H02", "VERIFY 63C2 -> con 2 luot",
                PinState.of(PinState.Operation.VERIFY, false, 0x63C2).text()
                        .contains("còn 2 lượt thử"), true);
        record("H03", "VERIFY 63C1 -> con 1 luot",
                PinState.of(PinState.Operation.VERIFY, false, 0x63C1).text()
                        .contains("còn 1 lượt thử"), true);
        record("H04", "VERIFY 6983 -> PIN dang khoa",
                PinState.of(PinState.Operation.VERIFY, false, Sw.AUTH_BLOCKED).text()
                        .contains("PIN đang khóa"), true);
        record("H05", "CHANGE 9000 -> da doi, retry reset",
                PinState.of(PinState.Operation.CHANGE, true, Sw.OK).text()
                        .contains("PIN đã đổi")
                && PinState.of(PinState.Operation.CHANGE, true, Sw.OK).text()
                        .contains("retry reset về 3"), true);
        record("H06", "CHANGE 63C2 -> sai PIN cu",
                PinState.of(PinState.Operation.CHANGE, false, 0x63C2).text()
                        .contains("Sai PIN cũ"), true);
        record("H07", "UNBLOCK 9000 -> da mo khoa, retry reset",
                PinState.of(PinState.Operation.UNBLOCK, true, Sw.OK).text()
                        .contains("PIN đã mở khóa")
                && PinState.of(PinState.Operation.UNBLOCK, true, Sw.OK).text()
                        .contains("retry reset về 3"), true);
        record("H08", "UNBLOCK 63C4 -> sai admin PIN, con 4 luot",
                PinState.of(PinState.Operation.UNBLOCK, false, 0x63C4).text()
                        .contains("Sai admin PIN")
                && PinState.of(PinState.Operation.UNBLOCK, false, 0x63C4).text()
                        .contains("còn 4 lượt thử"), true);
        record("H09", "UNBLOCK 6983 -> admin PIN khoa, khong co duong mo",
                PinState.of(PinState.Operation.UNBLOCK, false, Sw.AUTH_BLOCKED).text()
                        .contains("admin PIN đang khóa"), true);
        record("H10", "6985 -> PIN chua dung duoc",
                PinState.of(PinState.Operation.VERIFY, false, Sw.CONDITIONS_NOT_SATISFIED)
                        .text().contains("chưa ISSUED"), true);
        record("H11", "khong gui APDU -> chua gui lenh PIN",
                PinState.of(PinState.Operation.VERIFY, false, -1).text()
                        .contains("Chưa gửi lệnh PIN"), true);
        record("H12", "trang thai chua biet -> huong dan doc lai",
                PinState.unknown().text().contains("Chưa biết trạng thái PIN")
                && PinState.unknown().text().contains("VERIFY"), true);
        record("H13", "SW la khong suy ra duoc trang thai",
                PinState.of(PinState.Operation.VERIFY, false, 0x6F00).text()
                        .contains("6F00"), true);

        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        CardGateway gateway = readyGateway(issuedChannel(command -> {
            sent.incrementAndGet();
            return response(0x63C2);
        }));
        record("H14", "gateway moi chua co ket qua PIN", gateway.lastPinSw() == -1, true);
        gateway.verifyStudentPin("12345".toCharArray());
        record("H15", "chan tai client -> lastPinSw van -1, khong gui APDU",
                gateway.lastPinSw() == -1 && sent.get() == 0, true);
        gateway.verifyStudentPin("654321".toCharArray());
        record("H16", "sau khi gui -> lastPinSw la SW that", gateway.lastPinSw(), 0x63C2);
        gateway.identify();
        record("H17", "identify lai -> lastPinSw ve -1", gateway.lastPinSw() == -1, true);
    }

    private static void checkProfileOffline() throws CardException {
        System.out.println();
        System.out.println("--- Nhom L: parser ho so + metadata + SW (offline) ---");

        ProfileCodec.Parse valid = ProfileCodec.parse(profileResponse("SV2024-001", PROFILE_NAME));
        record("L01", "ho so schema v1 hop le -> parse PASS",
                valid.ok() && "SV2024-001".equals(valid.profile().studentId()), true);
        record("L02", "displayName tieng Viet giu nguyen so byte UTF-8",
                valid.ok() && valid.profile().displayNameBytes() == 18
                && valid.profile().displayNameCodePoints() == 14, true);
        record("L03", "wireLength tinh dung tu schema",
                valid.ok() && valid.profile().wireLength() == 3 + 10 + 18, true);

        record("L04", "schemaVersion 02 bi tu choi",
                !ProfileCodec.parse(withByte(profileResponse("SV2024-001", "A"), 0, 0x02)).ok(), true);
        record("L05", "studentIdLength 0 bi tu choi",
                !ProfileCodec.parse(withByte(profileResponse("SV2024-001", "A"), 1, 0x00)).ok(), true);
        record("L06", "studentId chu thuong bi tu choi",
                !ProfileCodec.parse(profileResponse("sv2024", "A")).ok(), true);
        record("L07", "displayNameLength 0 bi tu choi",
                !ProfileCodec.parse(withByte(profileResponse("SV2024-001", "A"), 12, 0x00)).ok(), true);
        record("L08", "response thieu byte bi tu choi",
                !ProfileCodec.parse(Arrays.copyOf(profileResponse("SV2024-001", "Ánh"), 15)).ok(), true);
        byte[] extra = Arrays.copyOf(profileResponse("SV2024-001", "Ánh"),
                profileResponse("SV2024-001", "Ánh").length + 1);
        record("L09", "response thua byte bi tu choi", !ProfileCodec.parse(extra).ok(), true);

        byte[] loneContinuation = profileResponse("SV2024-001", "A");
        loneContinuation[loneContinuation.length - 1] = (byte) 0x80;
        record("L10", "UTF-8 khong hop le (byte noi don le) bi tu choi",
                !ProfileCodec.parse(loneContinuation).ok(), true);
        record("L11", "UTF-8 byte đơn 0xC0 bị từ chối",
                !ProfileCodec.parse(profileResponse("SV2024-001", "\u00C0".getBytes(
                        java.nio.charset.StandardCharsets.ISO_8859_1))).ok(), true);

        byte[] emoji = profileResponse("SV2024-001", "Anh 🎓");
        ProfileCodec.Parse emojiParse = ProfileCodec.parse(emoji);
        record("L12", "UTF-8 4 byte hop le -> 1 code point",
                emojiParse.ok() && emojiParse.profile().displayNameCodePoints() == 5, true);

        ProfileMetaCodec.Parse meta = ProfileMetaCodec.parse(metaResponse(1, 1, 1, 20));
        record("L13", "metadata 65 byte hop le -> parse PASS",
                meta.ok() && meta.meta().ciphertextLength() == 20
                && meta.meta().nonceHex().length() == 24
                && meta.meta().tagHex().length() == 32
                && meta.meta().ciphertextHashHex().length() == 64, true);
        record("L14", "metadata 64 byte bi tu choi",
                !ProfileMetaCodec.parse(Arrays.copyOf(metaResponse(1, 1, 1, 20), 64)).ok(), true);
        record("L15", "recordVersion 02 bi tu choi",
                !ProfileMetaCodec.parse(metaResponse(2, 1, 1, 20)).ok(), true);
        record("L16", "algorithmId 02 bi tu choi",
                !ProfileMetaCodec.parse(metaResponse(1, 2, 1, 20)).ok(), true);
        record("L17", "profileSchemaVersion 02 bi tu choi",
                !ProfileMetaCodec.parse(metaResponse(1, 1, 2, 20)).ok(), true);
        record("L18", "ciphertextLength 0 bi tu choi",
                !ProfileMetaCodec.parse(metaResponse(1, 1, 1, 0)).ok(), true);

        record("L19", "GET_PROFILE 6982 -> bao thieu quyen student",
                profileText(response(Sw.SECURITY_NOT_SATISFIED)).contains("thiếu quyền student"), true);
        record("L20", "GET_PROFILE 6A86 -> bao P1/P2 phai 0000",
                profileText(response(Sw.WRONG_P1P2)).contains("P1/P2 phải 0000"), true);
        record("L21", "GET_PROFILE 6F00 -> bao record hong",
                profileText(response(Sw.INTERNAL_ERROR)).contains("record hồ sơ hỏng"), true);
        record("L22", "metadata 6982 -> bao thieu quyen student",
                metaText(response(Sw.SECURITY_NOT_SATISFIED)).contains("thiếu quyền student"), true);

        CardGateway ok = readyGateway(issuedChannel(command -> responseWithData(
                profileResponse("SV2024-001", "Nguyễn Văn Ánh"), Sw.OK)));
        CardGateway.Result read = ok.readProfile(0x00, 0x00);
        record("L23", "GET_PROFILE 9000 -> parser PASS + lastProfile co du lieu",
                read.ok() && read.text().contains("parser=PASS")
                && ok.lastProfile() != null, true);
        record("L24", "log ho so khong chua displayName",
                !read.text().contains("Nguyễn"), true);
        record("L25", "GET_PROFILE dung Case 2S Le=00",
                read.text().contains("P1P2=0000 Le=00"), true);

        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
        CardGateway flaky = readyGateway(issuedChannel(command -> round.incrementAndGet() == 1
                ? responseWithData(profileResponse("SV2024-001", "Nguyễn Văn Ánh"), Sw.OK)
                : responseWithData(profileResponse("SV2024-001", "Nguyễn Văn Ánh"), Sw.INTERNAL_ERROR)));
        flaky.readProfile(0x00, 0x00);
        CardGateway.Result afterFailure = flaky.readProfile(0x00, 0x00);
        record("L26", "doc loi xoa ket qua cu khoi gateway",
                !afterFailure.ok() && flaky.lastProfile() == null, true);
        record("L27", "doc thanh cong nap metadata rong khi chua doc meta",
                flaky.lastProfileMeta() == null, true);

        CardGateway noApdu = readyGateway(issuedChannel(command -> {
            throw new AssertionError("khong duoc gui APDU khi VERIFY sai dinh dang");
        }));
        CardGateway.Result shortPin = noApdu.readProfileWithPin("12".toCharArray());
        record("L28", "PIN sai dinh dang -> khong gui APDU doc ho so",
                !shortPin.ok() && shortPin.text().contains("6 chữ số"), true);
    }

    private static String profileText(ResponseAPDU apdu) throws CardException {
        return readyGateway(issuedChannel(command -> apdu)).readProfile(0x00, 0x00).text();
    }

    private static String metaText(ResponseAPDU apdu) throws CardException {
        return readyGateway(issuedChannel(command -> apdu))
                .readProfileCryptoMeta(0x00, 0x00).text();
    }

    private static byte[] profileResponse(String studentId, String displayName) {
        return profileResponse(studentId,
                displayName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] profileResponse(String studentId, byte[] displayName) {
        byte[] data = new byte[3 + studentId.length() + displayName.length];
        data[0] = 0x01;
        data[1] = (byte) studentId.length();
        System.arraycopy(studentId.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0,
                data, 2, studentId.length());
        data[2 + studentId.length()] = (byte) displayName.length;
        System.arraycopy(displayName, 0, data, 3 + studentId.length(), displayName.length);
        return data;
    }

    private static byte[] metaResponse(int recordVersion, int algorithmId, int schemaVersion,
            int ciphertextLength) {
        byte[] data = new byte[65];
        data[0] = (byte) recordVersion;
        data[1] = (byte) algorithmId;
        data[2] = (byte) schemaVersion;
        data[3] = (byte) (ciphertextLength >> 8);
        data[4] = (byte) ciphertextLength;
        for (int index = 5; index < data.length; index++) {
            data[index] = (byte) (index + 1);
        }
        return data;
    }

    private static byte[] withByte(byte[] data, int offset, int value) {
        byte[] copy = data.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    private static void checkPinStateOnCard(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom H: trang thai PIN tren the that ---");

        if (gateway.status() == null || gateway.status().lifecycle() != 0x01) {
            skip("H20", "trang thai PIN bam theo SW that", "the chua ISSUED");
            return;
        }
        char[] pin = liveSecret(false).clone();
        char[] wrongPin = shiftDigits(pin, 5);
        try {
            gateway.verifyStudentPin(wrongPin);
            record("H20", "VERIFY sai -> hien so luot con lai",
                    PinState.of(PinState.Operation.VERIFY, false, gateway.lastPinSw()).text()
                            .contains("lượt thử"), true);
            gateway.verifyStudentPin(pin);
            record("H21", "VERIFY dung -> hien da xac thuc",
                    PinState.of(PinState.Operation.VERIFY, true, gateway.lastPinSw()).text()
                            .contains("Đã xác thực"), true);
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(wrongPin, '\0');
        }
    }

    private static void checkProfileOnCard(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom L: doc ho so tren the that ---");

        if (gateway.status() == null || gateway.status().lifecycle() != 0x01) {
            skip("L30", "doc ho so tren the ISSUED", "the chua ISSUED");
            return;
        }
        char[] source = liveSecret(false);
        if (source == null) {
            skip("L30", "doc ho so tren the ISSUED", "khong doc duoc PIN dung do dai");
            return;
        }
        char[] pin = source.clone();
        try {
            String statusBefore = statusFingerprint(gateway);

            CardGateway.Result read = gateway.readProfileWithPin(pin);
            Profile profile = gateway.lastProfile();
            record("L30", "VERIFY + GET_PROFILE -> 9000, parser PASS",
                    read.ok() && read.text().contains("parser=PASS") && profile != null, true);
            record("L31", "ho so dung schema v1",
                    profile != null && profile.schemaVersion() == 0x01, true);
            if (expectedDisplayName == null) {
                skip("L32", "displayName khop du lieu luc PERSONALIZE",
                        "the da ISSUED truoc phien chay");
                skip("L33", "studentId khop du lieu luc PERSONALIZE",
                        "the da ISSUED truoc phien chay");
            } else {
                record("L32", "displayName khop du lieu luc PERSONALIZE",
                        profile != null && expectedDisplayName.equals(profile.displayName()), true);
                record("L33", "studentId khop du lieu luc PERSONALIZE",
                        profile != null && expectedStudentId.equals(profile.studentId()), true);
            }
            record("L34", "displayName giu nguyen so byte UTF-8 da gui",
                    profile != null && profile.displayNameBytes() == profile.displayName()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length, true);
            boolean nameHidden = profile == null || !read.text().contains(profile.displayName());
            boolean idHidden = profile == null || !read.text().contains(profile.studentId());
            record("L35", "log ho so khong chua noi dung ho so", nameHidden && idHidden, true);
            System.out.println("    " + read.text());
            String firstEvidence = profile == null ? "" : profile.evidence();

            CardGateway.Result second = gateway.readProfile(0x00, 0x00);
            record("L36", "doc lai khong VERIFY -> 6982 (quyen da tieu thu)",
                    !second.ok() && gateway.lastResponseSw() == Sw.SECURITY_NOT_SATISFIED, true);

            gateway.verifyStudentPin(pin);
            CardGateway.Result wrongP1 = gateway.readProfile(0x01, 0x00);
            record("L37", "GET_PROFILE sai P1 -> 6A86",
                    !wrongP1.ok() && gateway.lastResponseSw() == Sw.WRONG_P1P2, true);
            CardGateway.Result afterError = gateway.readProfile(0x00, 0x00);
            record("L38", "loi 6A86 xoa quyen -> doc dung tra 6982",
                    !afterError.ok() && gateway.lastResponseSw() == Sw.SECURITY_NOT_SATISFIED, true);

            gateway.verifyStudentPin(pin);
            CardGateway.Result metaRead = gateway.readProfileCryptoMetaWithPin(pin);
            ProfileCryptoMeta meta = gateway.lastProfileMeta();
            record("L39", "VERIFY + GET_PROFILE_CRYPTO_META -> 9000, 65 byte",
                    metaRead.ok() && metaRead.text().contains("dataLen=65") && meta != null, true);
            record("L40", "metadata: nonce 12B, tag 16B, hash 32B",
                    meta != null && meta.nonceHex().length() == 24 && meta.tagHex().length() == 32
                    && meta.ciphertextHashHex().length() == 64, true);
            record("L41", "ciphertextLength khop do dai ho so",
                    meta != null && profile != null
                    && meta.ciphertextLength() == profile.wireLength(), true);
            record("L42", "hash ciphertext khac 0",
                    meta != null && !meta.ciphertextHashHex().matches("0+"), true);
            System.out.println("    " + metaRead.text());

            CardGateway.Result metaAgain = gateway.readProfileCryptoMeta(0x00, 0x00);
            record("L43", "doc lai metadata khong VERIFY -> 6982",
                    !metaAgain.ok() && gateway.lastResponseSw() == Sw.SECURITY_NOT_SATISFIED, true);

            gateway.verifyStudentPin(pin);
            CardGateway.Result metaWrongP2 = gateway.readProfileCryptoMeta(0x00, 0x01);
            record("L44", "metadata sai P2 -> 6A86",
                    !metaWrongP2.ok() && gateway.lastResponseSw() == Sw.WRONG_P1P2, true);
            CardGateway.Result metaAfterError = gateway.readProfileCryptoMeta(0x00, 0x00);
            record("L45", "loi 6A86 xoa quyen -> doc metadata tra 6982",
                    !metaAfterError.ok() && gateway.lastResponseSw() == Sw.SECURITY_NOT_SATISFIED, true);

            CardGateway.Result profileAgain = gateway.readProfileWithPin(pin);
            record("L46", "doc lai ho so cho cung fingerprint",
                    profileAgain.ok() && gateway.lastProfile() != null
                    && gateway.lastProfile().evidence().equals(firstEvidence), true);
            record("L47", "doc ho so khong doi lifecycle/cardId",
                    statusBefore.equals(statusFingerprint(gateway)), true);
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    private static void checkPinOnCard(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom M: PIN runtime tren the that ---");

        if (gateway.status() == null || gateway.status().lifecycle() != 0x01) {
            skip("M30", "PIN runtime tren the ISSUED", "the chua ISSUED");
            return;
        }
        char[] source = liveSecret(false);
        char[] adminSource = liveSecret(true);
        if (source == null || adminSource == null) {
            skip("M30", "PIN runtime tren the ISSUED", "khong doc duoc PIN dung do dai");
            return;
        }
        char[] pin = source.clone();
        char[] admin = adminSource.clone();
        char[] changed = shiftDigits(pin, 1);
        char[] unblocked = shiftDigits(pin, 2);
        char[] wrongPin = shiftDigits(pin, 5);
        char[] wrongAdmin = shiftDigits(admin, 1);
        try {
            CardGateway.Result verify = gateway.verifyStudentPin(pin);
            record("M30", "VERIFY PIN dung tren the that -> 9000", verify.ok(), true);

            CardGateway.Result wrong = gateway.verifyStudentPin(wrongPin);
            record("M31", "VERIFY PIN sai -> 63Cx", gateway.lastResponseSw() == 0x63C2, true);
            gateway.verifyStudentPin(pin);

            String statusBefore = statusFingerprint(gateway);
            String receiptBefore = receiptFingerprint(gateway, pin);
            boolean receiptReal = receiptBefore.startsWith("GRANT")
                    || receiptBefore.contains("counter=");
            System.out.println("    fingerprint truoc CHANGE: status=" + statusBefore
                    + " | receipt=" + receiptBefore);

            CardGateway.Result change = gateway.changeStudentPin(pin, changed);
            record("M32", "CHANGE PIN dung -> 9000", change.ok(), true);

            gateway.verifyStudentPin(pin);
            record("M33", "PIN cu bi vo hieu sau CHANGE -> 63Cx",
                    gateway.lastResponseSw() == 0x63C2, true);

            gateway.verifyStudentPin(changed);
            record("M34", "PIN moi dung duoc sau CHANGE", gateway.lastResponseSw() == Sw.OK, true);
            record("M35", "CHANGE khong doi lifecycle/cardId",
                    statusFingerprint(gateway).equals(statusBefore), true);
            if (receiptReal) {
                record("M36", "CHANGE khong doi balance/counter/receipt",
                        receiptFingerprint(gateway, changed).equals(receiptBefore), true);
            } else {
                skip("M36", "CHANGE khong doi balance/counter/receipt",
                        "the chua co receipt doc duoc: " + receiptBefore);
            }

            gateway.verifyStudentPin(wrongPin);
            record("M37", "sai lan 1 -> 63C2", gateway.lastResponseSw() == 0x63C2, true);
            gateway.verifyStudentPin(wrongPin);
            record("M38", "sai lan 2 -> 63C1", gateway.lastResponseSw() == 0x63C1, true);
            gateway.verifyStudentPin(wrongPin);
            record("M39", "sai lan 3 -> 6983 khoa PIN",
                    gateway.lastResponseSw() == Sw.AUTH_BLOCKED, true);
            gateway.verifyStudentPin(changed);
            record("M40", "PIN dang khoa thi VERIFY dung cung 6983",
                    gateway.lastResponseSw() == Sw.AUTH_BLOCKED, true);

            String lockedStatus = statusFingerprint(gateway);
            CardGateway.Result wrongUnblock = gateway.unblockStudentPin(wrongAdmin, unblocked);
            record("M41", "UNBLOCK admin PIN sai bi tu choi",
                    !wrongUnblock.ok()
                    && ((gateway.lastResponseSw() & 0xFF00) == 0x6300
                        || gateway.lastResponseSw() == Sw.AUTH_BLOCKED), true);
            gateway.verifyStudentPin(unblocked);
            record("M42", "admin PIN sai khong mo khoa student PIN",
                    gateway.lastResponseSw() == Sw.AUTH_BLOCKED, true);
            record("M43", "admin PIN sai khong doi lifecycle/cardId",
                    statusFingerprint(gateway).equals(lockedStatus), true);

            CardGateway.Result unblock = gateway.unblockStudentPin(admin, unblocked);
            record("M44", "UNBLOCK admin PIN dung -> 9000", unblock.ok(), true);
            record("M45", "PIN moi dung duoc sau UNBLOCK",
                    gateway.verifyStudentPin(unblocked).ok(), true);
            String receiptAfter = receiptFingerprint(gateway, unblocked);
            System.out.println("    fingerprint sau UNBLOCK: receipt=" + receiptAfter);
            if (receiptReal) {
                record("M46", "sau UNBLOCK balance/counter/receipt khong doi",
                        receiptAfter.equals(receiptBefore), true);
            } else {
                skip("M46", "sau UNBLOCK balance/counter/receipt khong doi",
                        "the chua co receipt doc duoc: " + receiptBefore);
            }
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(admin, '\0');
            Arrays.fill(changed, '\0');
            Arrays.fill(unblocked, '\0');
            Arrays.fill(wrongPin, '\0');
            Arrays.fill(wrongAdmin, '\0');
        }
    }

    private static String statusFingerprint(CardGateway gateway) {
        CardGateway.Status current = gateway.status();
        return current == null ? "no-status"
                : current.lifecycle() + "/" + current.cardId();
    }

    private static String receiptFingerprint(CardGateway gateway, char[] pin)
            throws CardException {
        CardGateway.Result verify = gateway.verifyStudentPin(pin);
        if (!verify.ok()) {
            return "verify-loi:" + gateway.lastResponseSw();
        }
        CardGateway.Result receipt = gateway.readLastReceipt(null);
        return receipt.ok() ? receipt.text() : "khong-receipt:" + gateway.lastResponseSw();
    }

    private static char[] shiftDigits(char[] pin, int delta) {
        char[] shifted = new char[pin.length];
        for (int index = 0; index < pin.length; index++) {
            shifted[index] = (char) ('0' + (pin[index] - '0' + delta) % 10);
        }
        return shifted;
    }

    private static void checkPersonalizeOnCard(CardGateway gateway) throws CardException {
        System.out.println();
        System.out.println("--- Nhom W: PERSONALIZE tren the that qua CardGateway ---");

        char[] adminSource = liveSecret(true);
        char[] studentSource = liveSecret(false);
        if (adminSource == null || studentSource == null) {
            skip("W01", "PERSONALIZE the moi", "khong doc duoc PIN dung do dai");
            return;
        }
        char[] adminPin = adminSource.clone();
        char[] studentPin = studentSource.clone();
        String studentId = "SV2024-001";
        String displayName = "Nguyễn Văn Ánh";
        char[] wrongAdmin = shiftedDigits(adminPin);
        try {
            CardGateway.Status before = gateway.status();
            boolean alreadyIssued = before != null && before.lifecycle() == 0x01;
            if (alreadyIssued) {
                skip("W01", "PERSONALIZE the moi -> 9000 + ISSUED + cardId",
                        "the da ISSUED truoc phien chay");
                skip("W03", "admin PIN sai -> 63Cx khong lo PIN", "the da ISSUED truoc phien chay");
                skip("W04", "admin PIN sai khong doi lifecycle", "the da ISSUED truoc phien chay");
            } else if (!Boolean.getBoolean("labaccess.personalize")) {
                skip("W01", "PERSONALIZE the moi -> 9000 + ISSUED + cardId",
                        "dat -Dlabaccess.personalize=true de tao the test moi");
                skip("W03", "admin PIN sai -> 63Cx khong lo PIN", "chua bat -Dlabaccess.personalize");
                skip("W04", "admin PIN sai khong doi lifecycle", "chua bat -Dlabaccess.personalize");
            } else {
                CardGateway.Result wrong = gateway.personalize(
                        wrongAdmin, CARD_ID, studentId, studentPin, displayName);
                record("W03", "admin PIN sai -> 63Cx, khong lo PIN",
                        !wrong.ok() && wrong.text().contains("admin PIN sai")
                        && wrong.text().contains("63C")
                        && !mentionsDigits(wrong.text(), adminPin)
                        && !mentionsDigits(wrong.text(), wrongAdmin), true);

                CardGateway.Result afterWrong = gateway.identify();
                record("W04", "admin PIN sai khong doi lifecycle va cardId",
                        afterWrong.ok() && gateway.status() != null
                        && gateway.status().lifecycle() == 0x00
                        && gateway.status().cardId().equals("0000000000000000"), true);

                CardGateway.Result first = gateway.personalize(
                        adminPin, CARD_ID, studentId, studentPin, displayName);
                System.out.println("    " + first.text());
                record("W01", "PERSONALIZE the moi -> 9000 + ISSUED + cardId",
                        first.ok() && first.text().contains("SW=9000"), true);
                if (first.ok()) {
                    expectedStudentId = studentId;
                    expectedDisplayName = displayName;
                }
                record("W02", "sau PERSONALIZE lifecycle ISSUED + cardId dung",
                        gateway.status() != null && gateway.status().lifecycle() == 0x01
                        && CARD_ID.equalsIgnoreCase(gateway.status().cardId()), true);
            }

            CardGateway.Result second = gateway.personalize(
                    adminPin, CARD_ID, studentId, studentPin, displayName);
            record("W05", "PERSONALIZE lan hai bi tu choi 6985",
                    !second.ok() && second.text().contains("6985")
                    && !mentionsDigits(second.text(), adminPin), true);
        } finally {
            Arrays.fill(adminPin, '\0');
            Arrays.fill(studentPin, '\0');
            Arrays.fill(wrongAdmin, '\0');
        }
    }

    private static char[] shiftedDigits(char[] source) {
        char[] shifted = new char[source.length];
        for (int index = 0; index < source.length; index++) {
            shifted[index] = (char) ('0' + (source[index] - '0' + 1) % 10);
        }
        return shifted;
    }
    private static void checkVerifyAndTransactionOnCard(CardGateway gateway) {
        System.out.println();
        System.out.println("--- Nhom X: VERIFY + GRANT + journal tren the that ---");

        if (!gateway.isReady() || gateway.status() == null
                || gateway.status().lifecycle() != 0x01) {
            skip("X01", "VERIFY_STUDENT_PIN tren the ISSUED that", "the chua ISSUED tren Simulator");
            return;
        }

        char[] adminPinChars = liveSecret(true);
        char[] studentPin = liveSecret(false);
        if (adminPinChars == null || studentPin == null) {
            skip("X01", "VERIFY_STUDENT_PIN tren the ISSUED that",
                    "khong doc duoc PIN dung do dai tu Console/stdin; khong gui APDU");
            return;
        }
        byte[] adminPin = new byte[adminPinChars.length];
        for (int i = 0; i < adminPinChars.length; i++) {
            adminPin[i] = (byte) adminPinChars[i];
        }
        try {
            Path dir = Files.createTempDirectory("labaccess-live-journal");
            Path file = dir.resolve("pending-journal.txt");
            PendingJournal journal = new PendingJournal(file);

            CardGateway.Result publicKey = gateway.readCardPublicKey();
            System.out.println("    GET_CARD_PUBLIC_KEY -> " + publicKey.text());
            record("X02", "doc public key that INS 61 tu the",
                    publicKey.ok() && gateway.cardPublicKey() != null
                            && ReceiptVerifier.encodePoint(gateway.cardPublicKey()) != null,
                    true);

            CardGateway.Result verify = gateway.verifyStudentPin(studentPin);
            System.out.println("    VERIFY -> " + verify.text());
            record("X01", "VERIFY dung PIN tren the ISSUED -> 9000", verify.ok(), true);

            long counterBefore = 0;
            int balanceBefore = 0;
            CardGateway.Result baseline = gateway.readLastReceipt(null);
            System.out.println("    baseline receipt -> " + baseline.text());
            if (baseline.ok() && gateway.lastReceipt() != null) {
                counterBefore = gateway.lastReceipt().counterAfter();
                balanceBefore = gateway.lastReceipt().balanceAfter();
            }
            System.out.println("    moc truoc GRANT: counter=" + counterBefore
                    + " balance=" + balanceBefore);

            ReceiptExpectation expectation = new ReceiptExpectation(
                    HexFormat.of().parseHex(gateway.status().cardId()), counterBefore + 1,
                    Receipt.OPERATION_GRANT, null);
            CardGateway.Result grant = gateway.runStateTransaction(journal,
                    new CardGateway.TransactionPlan(expectation, 5, balanceBefore + 5),
                    gateway.grantCommand(adminPin, 5), studentPin);
            System.out.println("    GRANT+receipt -> " + grant.text());
            record("X03", "GRANT 9000 + receipt ECDSA PASS + EXECUTED + journal xoa",
                    grant.ok() && grant.text().contains("ECDSA=PASS") && !Files.exists(file), true);
            record("X04", "doi soat sau GRANT o KNOWN",
                    gateway.reconciliation().state() == Reconciliation.State.KNOWN, true);
            record("X05", "receipt that verify lai doc lap bang public key vua doc",
                    gateway.lastReceipt() != null && ReceiptVerifier
                            .verifyReceipt(gateway.cardPublicKey(), gateway.lastReceipt()).ok(),
                    true);

            deleteRecursively(dir);
        } catch (Exception ex) {
            record("X00", "live VERIFY/GRANT loi khong mong doi", false, true);
            System.out.println("    exception: " + ex);
        } finally {
            java.util.Arrays.fill(adminPin, (byte) 0);
        }
    }

    private static void checkRealCardReceipts() {
        System.out.println();
        System.out.println("--- Nhom G: byte that cua lan chay SIGN-04 qua parser desktop ---");

        Path path = Path.of(System.getProperty("labaccess.receipts",
                "applet/build/receipt-artifacts.jsonl"));
        if (!Files.isRegularFile(path)) {
            skip("G00", "parse receipt that tu artifact SIGN-04", "khong thay " + path);
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException ex) {
            skip("G00", "parse receipt that tu artifact SIGN-04", "doc loi: " + ex.getMessage());
            return;
        }

        Map<String, byte[]> payloads = new HashMap<>();
        Map<String, Long> counters = new HashMap<>();
        int index = 0;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            index++;
            String tag = text(line, "\"tag\":\"([^\"]+)\"");
            byte[] payload = HexFormat.of().parseHex(text(line, "\"payloadHex\":\"([0-9A-Fa-f]+)\""));
            byte[] signature = HexFormat.of()
                    .parseHex(text(line, "\"signatureDerHex\":\"([0-9A-Fa-f]+)\""));
            String expectCardId = text(line, "\"cardIdHex\":\"([0-9A-Fa-f]+)\"");
            String expectVisitId = text(line, "\"visitIdHex\":\"([0-9A-Fa-f]+)\"");
            int expectCounter = Integer.parseInt(text(line, "\"counter\":(\\d+)"));
            int expectOperation = Integer.parseInt(text(line, "\"operation\":(\\d+)"));
            int expectDelta = Integer.parseInt(text(line, "\"delta\":(-?\\d+)"));
            int expectBalance = Integer.parseInt(text(line, "\"balance\":(\\d+)"));

            byte[] response = new byte[35 + signature.length];
            System.arraycopy(payload, 0, response, 0, 34);
            response[34] = (byte) signature.length;
            System.arraycopy(signature, 0, response, 35, signature.length);

            ReceiptCodec.Parse parse = ReceiptCodec.parse(response);
            boolean fieldsOk = parse.ok()
                    && parse.receipt().version() == 1
                    && parse.receipt().cardIdHex().equalsIgnoreCase(expectCardId)
                    && parse.receipt().counterAfter() == expectCounter
                    && parse.receipt().operation() == expectOperation
                    && parse.receipt().delta() == expectDelta
                    && parse.receipt().balanceAfter() == expectBalance
                    && parse.receipt().visitIdHex().equalsIgnoreCase(expectVisitId);

            Reconciliation reconciliation = new Reconciliation();
            reconciliation.markUnknown("doc lai receipt sau khi mat response");
            boolean executed = parse.ok()
                    && reconciliation.resolve(parse.receipt(), new ReceiptExpectation(
                            HexFormat.of().parseHex(expectCardId), expectCounter,
                            expectOperation, HexFormat.of().parseHex(expectVisitId)))
                            == Reconciliation.Verdict.EXECUTED;

            record(String.format("G%02d", index),
                    tag + " parse dung 7 truong + EXECUTED", fieldsOk && executed, true);

            byte[] publicKeyHex = HexFormat.of()
                    .parseHex(text(line, "\"publicKeyHex\":\"([0-9A-Fa-f]+)\""));
            ReceiptVerifier.ParsedKey cardKey = ReceiptVerifier.parsePublicKey(publicKeyHex);
            boolean signatureOk = parse.ok() && cardKey.ok()
                    && ReceiptVerifier.verifyReceipt(cardKey.key(), parse.receipt()).ok();
            record(String.format("G%02d-SIG", index),
                    tag + " chu ky that tren the -> ECDSA PASS", signatureOk, true);
            if (!signatureOk) {
                System.out.println("    ECDSA chi tiet: publicKey="
                        + (cardKey.ok() ? "PASS" : "FAIL (" + cardKey.problem() + ")")
                        + " verify=" + (parse.ok() && cardKey.ok()
                                ? ReceiptVerifier.verifyReceipt(cardKey.key(), parse.receipt())
                                        .problem()
                                : "chua chay"));
            }
            System.out.println(String.format(
                    "    expected counter=%d operation=%02X delta=%d balance=%d visitId=%s"
                            + " | response=%d byte payload=34 sigLen=%d | parser=%s | doi soat=%s",
                    expectCounter, expectOperation, expectDelta, expectBalance, expectVisitId,
                    response.length, signature.length,
                    parse.ok() ? "PASS" : "FAIL (" + parse.problem() + ")",
                    parse.ok() ? reconciliation.state() + " " + Reconciliation.Verdict.EXECUTED
                            : reconciliation.state()));
            payloads.put(tag, payload);
            counters.put(tag, (long) expectCounter);
        }

        byte[] beforeCommit = payloads.get("before-commit-A");
        byte[] kept = payloads.get("before-commit-A-kept");
        record("G91", "receipt cu sau fault truoc commit giu nguyen byte",
                beforeCommit != null && kept != null && Arrays.equals(beforeCommit, kept), true);

        byte[] afterCommitA = payloads.get("after-commit-A");
        byte[] afterCommitNew = payloads.get("after-commit-new");
        boolean newDiffers = afterCommitA != null && afterCommitNew != null
                && !Arrays.equals(afterCommitA, afterCommitNew);
        boolean newIsNext = counters.containsKey("after-commit-A")
                && counters.containsKey("after-commit-new")
                && counters.get("after-commit-new") == counters.get("after-commit-A") + 1;
        record("G92", "receipt moi sau fault sau commit khac receipt cu va counter +1",
                newDiffers && newIsNext, true);
    }

    private static String text(String line, String pattern) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(line);
        if (!matcher.find()) {
            throw new IllegalArgumentException("khong tim thay " + pattern);
        }
        return matcher.group(1);
    }

    private static byte[] receiptResponse(int operation, int delta, long counter, int balance,
            String cardIdHex, String visitIdHex, int signatureLength) {
        byte[] payload = receiptPayload(operation, delta, counter, balance, cardIdHex, visitIdHex);
        byte[] out = new byte[35 + signatureLength];
        System.arraycopy(payload, 0, out, 0, 34);
        out[34] = (byte) signatureLength;
        if (out.length > 35) {
            out[35] = 0x30;
            for (int i = 36; i < out.length; i++) {
                out[i] = (byte) 0xA5;
            }
        }
        return out;
    }

    private static byte[] receiptPayload(int operation, int delta, long counter, int balance,
            String cardIdHex, String visitIdHex) {
        byte[] payload = new byte[34];
        payload[0] = 0x01;
        System.arraycopy(HexFormat.of().parseHex(cardIdHex), 0, payload, 1, 8);
        payload[9] = (byte) (counter >>> 24);
        payload[10] = (byte) (counter >>> 16);
        payload[11] = (byte) (counter >>> 8);
        payload[12] = (byte) counter;
        payload[13] = (byte) operation;
        payload[14] = (byte) (delta >> 8);
        payload[15] = (byte) delta;
        payload[16] = (byte) (balance >> 8);
        payload[17] = (byte) balance;
        System.arraycopy(HexFormat.of().parseHex(visitIdHex), 0, payload, 18, 16);
        return payload;
    }

    private static Receipt receipt(int operation, int delta, long counter, int balance,
            String cardIdHex, String visitIdHex) {
        return ReceiptCodec.parse(receiptResponse(operation, delta, counter, balance, cardIdHex,
                visitIdHex, 71)).receipt();
    }

    private static byte[] withPayloadByte(byte[] response, int payloadOffset, int value) {
        byte[] copy = response.clone();
        copy[payloadOffset] = (byte) value;
        return copy;
    }

    private static final int PHOTO_LENGTH = 4096;
    private static final int PHOTO_WIDTH = 64;
    private static final int PHOTO_CHUNK = 128;
    private static final int PHOTO_BEGIN_LENGTH = 13;
    private static final String ADMIN_PIN_TEXT = "12345678";

    private interface Pixel {
        int rgb(int x, int y);
    }

    private static java.awt.image.BufferedImage sampleImage(int width, int height, Pixel pixel) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, pixel.rgb(x, y));
            }
        }
        return image;
    }

    private static int gray(byte[] photo, int column, int row) {
        return photo[row * PHOTO_WIDTH + column] & 0xFF;
    }

    private static int grey(int value) {
        return 0xFF000000 | (value << 16) | (value << 8) | value;
    }

    private static void checkPhotoDraftOffline() {
        byte[] quadrants = PhotoDraft.fromImage(sampleImage(256, 128, (x, y) -> {
            if (x < 128) {
                return grey(y < 64 ? 0 : 200);
            }
            return grey(y < 64 ? 128 : 255);
        }));
        record("PH49", "ảnh bốn góc: cột trái tối hơn cột phải ở hàng trên",
                gray(quadrants, 8, 8) < gray(quadrants, 55, 8), true);
        record("PH50", "ảnh bốn góc: hàng dưới sáng hơn hàng trên ở cột trái",
                gray(quadrants, 8, 55) > gray(quadrants, 8, 8), true);
        record("PH51", "ảnh bốn góc: thứ tự góc trên-trái < trên-phải < dưới-trái < dưới-phải",
                gray(quadrants, 8, 8) < gray(quadrants, 55, 8)
                        && gray(quadrants, 55, 8) < gray(quadrants, 8, 55)
                        && gray(quadrants, 8, 55) < gray(quadrants, 55, 55), true);

        byte[] transparent = PhotoDraft.fromImage(sampleImage(64, 64, (x, y) -> 0x00000000));
        record("PH52", "alpha trong suốt ghép lên nền trắng nên ra 255",
                transparent[0] == (byte) 0xFF && transparent[PHOTO_LENGTH - 1] == (byte) 0xFF, true);

        byte[] uniform = PhotoDraft.fromImage(sampleImage(64, 64, (x, y) -> grey(77)));
        boolean allSame = true;
        for (byte value : uniform) {
            if ((value & 0xFF) != 77) {
                allSame = false;
                break;
            }
        }
        record("PH53", "ảnh xám đều 64×64 giữ nguyên 77 ở mọi byte (không lệch grayscale)",
                allSame && uniform.length == PHOTO_LENGTH, true);

        byte[] croppedSides = PhotoDraft.fromImage(sampleImage(128, 32, (x, y) -> {
            if (x < 8 || x >= 120) {
                return grey(0);
            }
            return grey(255);
        }));
        record("PH54", "ảnh dẹt 128×32: cắt hai bên nên dải đen ở mép biến mất",
                gray(croppedSides, 0, 32) == 255 && gray(croppedSides, 63, 32) == 255, true);

        byte[] croppedTopBottom = PhotoDraft.fromImage(sampleImage(32, 128, (x, y) -> {
            if (y < 8 || y >= 120) {
                return grey(0);
            }
            return grey(255);
        }));
        record("PH55", "ảnh dọc 32×128: cắt trên/dưới nên dải đen ở mép biến mất",
                gray(croppedTopBottom, 32, 0) == 255
                        && gray(croppedTopBottom, 32, 63) == 255, true);
    }

    private static byte[] syntheticPhoto() {
        byte[] image = new byte[PHOTO_LENGTH];
        for (int row = 0; row < 64; row++) {
            for (int column = 0; column < 64; column++) {
                int index = row * 64 + column;
                int value = ((row / 8) % 2 == 0) ? (column * 4) : (255 - row * 4);
                image[index] = (byte) ((row % 8 == 0) ? 0xFF : value & 0xFF);
            }
        }
        return image;
    }

    private static String firstMismatch(byte[] expected, byte[] actual) {
        if (actual == null) {
            return "không có dữ liệu đọc lại";
        }
        if (expected.length != actual.length) {
            return String.format("độ dài lệch: nguồn=%d đọc lại=%d", expected.length, actual.length);
        }
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != actual[index]) {
                return String.format("lệch tại byte %d: nguồn=%02X đọc lại=%02X",
                        index, expected[index] & 0xFF, actual[index] & 0xFF);
            }
        }
        return null;
    }

    private static final class PhotoCard implements java.util.function.Function<CommandAPDU, ResponseAPDU> {

        private final byte[] photo = new byte[PHOTO_LENGTH];
        private boolean ready;
        private boolean sessionOpen;
        private int sessionOffset;
        private boolean authorized;
        private int readOffset;
        private int adminTries = 5;
        private int chunksAccepted;
        private int finishCount;
        private int receivedBytes;
        private int readAttempts;

        byte[] photo() {
            return photo.clone();
        }

        int chunksAccepted() {
            return chunksAccepted;
        }

        int finishCount() {
            return finishCount;
        }

        boolean ready() {
            return ready;
        }

        int adminTries() {
            return adminTries;
        }

        int receivedBytes() {
            return receivedBytes;
        }

        int readAttempts() {
            return readAttempts;
        }

        @Override
        public ResponseAPDU apply(CommandAPDU command) {
            int ins = command.getINS() & 0xFF;
            if (ins != 0x32 && ins != 0x33 && ins != 0x35) {
                sessionOpen = false;
            }
            if (ins == 0x32) {
                return begin(command);
            }
            if (ins == 0x33) {
                return write(command);
            }
            if (ins == 0x35) {
                return finish(command);
            }
            if (ins == 0x34) {
                return read(command);
            }
            return response(Sw.INS_NOT_SUPPORTED);
        }

        private ResponseAPDU begin(CommandAPDU command) {
            byte[] data = command.getData();
            if (data == null || data.length != PHOTO_BEGIN_LENGTH) {
                return response(Sw.WRONG_LENGTH);
            }
            if (ready || sessionOpen) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (data[8] != 0x01 || data[9] != 64 || data[10] != 64
                    || ((data[11] & 0xFF) << 8 | (data[12] & 0xFF)) != PHOTO_LENGTH) {
                return response(Sw.WRONG_DATA);
            }
            for (int index = 0; index < 8; index++) {
                if (data[index] < '0' || data[index] > '9') {
                    return response(Sw.WRONG_DATA);
                }
            }
            boolean matches = true;
            for (int index = 0; index < 8; index++) {
                if (data[index] != (byte) ADMIN_PIN_TEXT.charAt(index)) {
                    matches = false;
                }
            }
            if (!matches) {
                adminTries--;
                return response(adminTries == 0 ? Sw.AUTH_BLOCKED : 0x63C0 | adminTries);
            }
            sessionOpen = true;
            sessionOffset = 0;
            return response(Sw.OK);
        }

        private ResponseAPDU write(CommandAPDU command) {
            ResponseAPDU result = writeChunk(command);
            if (result.getSW() != Sw.OK) {
                sessionOpen = false;
            }
            return result;
        }

        private ResponseAPDU writeChunk(CommandAPDU command) {
            byte[] data = command.getData();
            int offset = (command.getP1() << 8) | command.getP2();
            if (!sessionOpen) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (data == null || data.length == 0 || data.length > PHOTO_CHUNK) {
                return response(Sw.WRONG_LENGTH);
            }
            if (offset != sessionOffset) {
                return response(Sw.WRONG_P1P2);
            }
            if (offset + data.length > PHOTO_LENGTH) {
                return response(Sw.WRONG_P1P2);
            }
            System.arraycopy(data, 0, photo, offset, data.length);
            sessionOffset += data.length;
            receivedBytes += data.length;
            chunksAccepted++;
            return response(Sw.OK);
        }

        private ResponseAPDU finish(CommandAPDU command) {
            ResponseAPDU result = finishPhoto(command);
            sessionOpen = false;
            return result;
        }

        private ResponseAPDU finishPhoto(CommandAPDU command) {
            if (command.getData() != null && command.getData().length > 0) {
                return response(Sw.WRONG_LENGTH);
            }
            finishCount++;
            if (!sessionOpen) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (ready) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (sessionOffset < PHOTO_LENGTH) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            ready = true;
            return response(Sw.OK);
        }

        private ResponseAPDU read(CommandAPDU command) {
            readAttempts++;
            int offset = (command.getP1() << 8) | command.getP2();
            int ne = command.getNe();
            if (ne <= 0 || ne > PHOTO_CHUNK) {
                return response(Sw.WRONG_LENGTH);
            }
            if (!ready) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!authorized) {
                return response(Sw.SECURITY_NOT_SATISFIED);
            }
            if (offset > PHOTO_LENGTH) {
                return response(Sw.WRONG_P1P2);
            }
            if (offset == PHOTO_LENGTH) {
                return response(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (offset != readOffset) {
                return response(Sw.WRONG_P1P2);
            }
            int length = Math.min(ne, PHOTO_LENGTH - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(photo, offset, chunk, 0, length);
            readOffset = offset + length;
            if (readOffset == PHOTO_LENGTH) {
                readOffset = 0;
                authorized = false;
            }
            return responseWithData(chunk, Sw.OK);
        }

        ResponseAPDU authorize() {
            authorized = true;
            return response(Sw.OK);
        }
    }

    private static void checkPhotoOffline() throws CardException {
        PhotoCard card = new PhotoCard();
        CardGateway gateway = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x20) {
                return card.authorize();
            }
            return card.apply(command);
        }));

        byte[] image = syntheticPhoto();
        CardGateway.Result upload = gateway.uploadPhoto(image, ADMIN_PIN_TEXT.toCharArray());
        record("PH01", "uploadPhoto đủ 4096 byte trả 9000 và FINISH đúng một lần",
                upload.ok() && card.finishCount() == 1, true);
        record("PH02", "số byte thẻ nhận đúng 4096", card.receivedBytes(), PHOTO_LENGTH);
        record("PH03", "số chunk thẻ nhận đúng 32", card.chunksAccepted(), 32);
        record("PH04", "ảnh trên thẻ khớp từng byte với nguồn",
                firstMismatch(image, card.photo()) == null, true);

        CardGateway lengthGateway = readyGateway(issuedChannel(command -> card.apply(command)));
        int before = card.chunksAccepted();
        CardGateway.Result shortImage = lengthGateway.uploadPhoto(new byte[1024], ADMIN_PIN_TEXT.toCharArray());
        record("PH05", "uploadPhoto từ chối 1024 byte trước khi gửi APDU",
                !shortImage.ok() && card.chunksAccepted() == before, true);

        PhotoCard fresh = new PhotoCard();
        CardGateway offline = readyGateway(issuedChannel(command -> fresh.apply(command)));
        CardGateway.Result badPin = offline.uploadPhoto(image, "12ab5678".toCharArray());
        record("PH06", "admin PIN không phải ASCII số trả 6A80",
                !badPin.ok() && badPin.text().contains("6A80"), true);
        record("PH07", "admin PIN sai định dạng không tiêu lượt thử", fresh.adminTries(), 5);

        PhotoCard ready = new PhotoCard();
        CardGateway readyCard = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x20) {
                return ready.authorize();
            }
            return ready.apply(command);
        }));
        readyCard.uploadPhoto(image, ADMIN_PIN_TEXT.toCharArray());
        CardGateway.Result second = readyCard.uploadPhoto(image, ADMIN_PIN_TEXT.toCharArray());
        record("PH08", "uploadPhoto lần hai khi photoReady=true trả 6985",
                !second.ok() && second.text().contains("6985"), true);

        PhotoCard notReadyCard = new PhotoCard();
        CardGateway notReadyGateway = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x20) {
                return notReadyCard.authorize();
            }
            return notReadyCard.apply(command);
        }));
        CardGateway.Result notReady = notReadyGateway.readPhoto("654321".toCharArray());
        record("PH09", "readPhoto khi photoReady=false trả 6985",
                !notReady.ok() && notReady.text().contains("6985"), true);

        PhotoCard refusedCard = new PhotoCard();
        CardGateway refusedGateway = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x20) {
                return response(0x63C2);
            }
            return refusedCard.apply(command);
        }));
        CardGateway.Result refused = refusedGateway.readPhoto("654321".toCharArray());
        record("PH10", "VERIFY thất bại thì không gửi chunk đọc nào",
                !refused.ok() && refusedCard.readAttempts() == 0, true);

        PhotoCard readCard = new PhotoCard();
        CardGateway reader = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x20) {
                return readCard.authorize();
            }
            return readCard.apply(command);
        }));
        reader.uploadPhoto(image, ADMIN_PIN_TEXT.toCharArray());

        CardGateway.Result readBack = reader.readPhoto("654321".toCharArray());
        record("PH11", "readPhoto đọc lại đủ 4096 byte", readBack.ok(), true);
        record("PH12", "ảnh đọc lại khớp từng byte với nguồn",
                firstMismatch(image, reader.lastPhoto()) == null, true);

        record("PH13", "sau khi hết chuỗi, READ_CHUNK offset 0 không có VERIFY trả 6982",
                reader.transmitReadChunk(0, PHOTO_CHUNK).getSW() == Sw.SECURITY_NOT_SATISFIED, true);

        PhotoCard formatCard = new PhotoCard();
        CardGateway formatGateway = readyGateway(issuedChannel(command -> formatCard.apply(command)));
        byte[] badFormat = new byte[PHOTO_BEGIN_LENGTH];
        System.arraycopy(ADMIN_PIN_TEXT.getBytes(), 0, badFormat, 0, 8);
        badFormat[8] = 0x02;
        badFormat[9] = 64;
        badFormat[10] = 64;
        badFormat[11] = (byte) (PHOTO_LENGTH >> 8);
        badFormat[12] = (byte) PHOTO_LENGTH;
        record("PH14", "BEGIN với format=02 trả 6A80 và không mở phiên",
                formatGateway.transmitPhotoBegin(badFormat).getSW() == Sw.WRONG_DATA
                        && formatGateway.transmitPhotoWriteChunk(0, new byte[4]).getSW()
                                == Sw.CONDITIONS_NOT_SATISFIED, true);

        PhotoCard skewCard = new PhotoCard();
        CardGateway skewGateway = readyGateway(issuedChannel(command -> skewCard.apply(command)));
        byte[] openData = new byte[PHOTO_BEGIN_LENGTH];
        System.arraycopy(ADMIN_PIN_TEXT.getBytes(), 0, openData, 0, 8);
        openData[8] = 0x01;
        openData[9] = 64;
        openData[10] = 64;
        openData[11] = (byte) (PHOTO_LENGTH >> 8);
        openData[12] = (byte) PHOTO_LENGTH;
        skewGateway.transmitPhotoBegin(openData);
        byte[] chunkZero = new byte[PHOTO_CHUNK];
        System.arraycopy(image, 0, chunkZero, 0, PHOTO_CHUNK);
        int acceptedFirst = skewGateway.transmitPhotoWriteChunk(0, chunkZero).getSW();
        record("PH15", "WRITE lặp offset 0 khi phiên đang ở 128 trả 6A86 và không nhận thêm byte",
                acceptedFirst == Sw.OK
                        && skewGateway.transmitPhotoWriteChunk(0, chunkZero).getSW()
                                == Sw.WRONG_P1P2
                        && skewCard.chunksAccepted() == 1, true);

        PhotoCard earlyCard = new PhotoCard();
        CardGateway earlyGateway = readyGateway(issuedChannel(command -> earlyCard.apply(command)));
        byte[] goodBegin = new byte[PHOTO_BEGIN_LENGTH];
        System.arraycopy(ADMIN_PIN_TEXT.getBytes(), 0, goodBegin, 0, 8);
        goodBegin[8] = 0x01;
        goodBegin[9] = 64;
        goodBegin[10] = 64;
        goodBegin[11] = (byte) (PHOTO_LENGTH >> 8);
        goodBegin[12] = (byte) PHOTO_LENGTH;
        earlyGateway.transmitPhotoBegin(goodBegin);
        byte[] firstChunk = new byte[PHOTO_CHUNK];
        System.arraycopy(image, 0, firstChunk, 0, PHOTO_CHUNK);
        earlyGateway.transmitPhotoWriteChunk(0, firstChunk);
        record("PH16", "FINISH khi mới có 128/4096 byte trả 6985 và ảnh vẫn chưa ready",
                earlyGateway.transmitPhotoFinish().getSW() == Sw.CONDITIONS_NOT_SATISFIED
                        && earlyGateway.transmitReadChunk(0, PHOTO_CHUNK).getSW()
                                == Sw.CONDITIONS_NOT_SATISFIED, true);

        PhotoCard interleavedCard = new PhotoCard();
        CardGateway interleaved = readyGateway(issuedChannel(command -> {
            if ((command.getINS() & 0xFF) == 0x20) {
                return interleavedCard.authorize();
            }
            return interleavedCard.apply(command);
        }));
        interleaved.uploadPhoto(image, ADMIN_PIN_TEXT.toCharArray());
        interleaved.readPhoto("654321".toCharArray());
        interleaved.identify();
        record("PH17", "lệnh xen giữa chuỗi đọc (SELECT/GET_STATUS) hủy quyền đọc ảnh",
                interleaved.transmitReadChunk(0, PHOTO_CHUNK).getSW()
                        == Sw.SECURITY_NOT_SATISFIED, true);

        record("PH18", "READ_CHUNK với Le=256 (Le=00) bị từ chối 6700",
                reader.transmitReadChunk(0, 256).getSW() == Sw.WRONG_LENGTH, true);

        PhotoCard failingCard = new PhotoCard();
        int[] chunkCount = { 0 };
        CardChannel failingChannel = new CardChannel() {
            @Override
            public Card getCard() {
                return null;
            }

            @Override
            public int getChannelNumber() {
                return 0;
            }

            @Override
            public ResponseAPDU transmit(CommandAPDU command) throws CardException {
                int ins = command.getINS() & 0xFF;
                if (ins == 0xA4) {
                    return response(Sw.OK);
                }
                if (ins == 0x10) {
                    return statusApdu(0x01, CARD_ID);
                }
                if (ins == 0x33 && ++chunkCount[0] == 3) {
                    throw new CardException("mat ket noi gia lap giua upload");
                }
                return failingCard.apply(command);
            }

            @Override
            public int transmit(ByteBuffer command, ByteBuffer response) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        CardGateway failing = readyGateway(failingChannel);
        boolean threw = false;
        try {
            failing.uploadPhoto(image, ADMIN_PIN_TEXT.toCharArray());
        } catch (CardException expected) {
            threw = true;
        }
        record("PH46", "lỗi truyền giữa upload nổi lên thành CardException, không bị nuốt",
                threw, true);
        record("PH47", "lỗi truyền giữa upload không gửi FINISH và ảnh không thành ready",
                failingCard.finishCount() == 0 && !failingCard.ready(), true);
        record("PH48", "lỗi truyền dừng đúng sau chunk thứ hai",
                failingCard.chunksAccepted() == 2, true);
    }

    private static void checkPhotoOnCard(CardGateway gateway) throws CardException {
        byte[] image = syntheticPhoto();
        char[] admin = liveSecret(true);
        char[] student = liveSecret(false);

        byte[] beginData = new byte[PHOTO_BEGIN_LENGTH];
        System.arraycopy(ADMIN_PIN_TEXT.getBytes(), 0, beginData, 0, 8);
        for (int index = 0; index < 8; index++) {
            beginData[index] = (byte) admin[index];
        }
        beginData[8] = 0x01;
        beginData[9] = 64;
        beginData[10] = 64;
        beginData[11] = (byte) (PHOTO_LENGTH >> 8);
        beginData[12] = (byte) PHOTO_LENGTH;

        record("PH26", "thẻ thật: BEGIN đúng hợp đồng trả 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);

        byte[] firstChunk = new byte[PHOTO_CHUNK];
        System.arraycopy(image, 0, firstChunk, 0, PHOTO_CHUNK);
        record("PH27", "thẻ thật: chunk đầu tại offset 0 trả 9000",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW() == Sw.OK, true);
        record("PH28", "thẻ thật: lặp offset 0 khi phiên đang ở 128 trả 6A86",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW() == Sw.WRONG_P1P2, true);
        record("PH33", "thẻ thật: sau chunk lỗi offset, phiên đã đóng nên WRITE đúng offset trả 6985",
                gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);
        record("PH34", "thẻ thật: sau chunk lỗi, BEGIN mới mở lại được phiên từ đầu",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);

        byte[] oversized = new byte[PHOTO_CHUNK + 1];
        System.arraycopy(image, 0, oversized, 0, PHOTO_CHUNK + 1);
        record("PH35", "thẻ thật: chunk đầu sau khi mở lại trả 9000",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW() == Sw.OK, true);
        record("PH36", "thẻ thật: chunk Lc=129 bị từ chối 6700",
                gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, oversized).getSW()
                        == Sw.WRONG_LENGTH, true);
        record("PH37", "thẻ thật: sau chunk sai độ dài, phiên đã đóng (WRITE trả 6985)",
                gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);

        record("PH38", "thẻ thật: BEGIN lại sau lỗi Lc trả 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH39", "thẻ thật: chunk 0 sau khi mở lại trả 9000",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW() == Sw.OK, true);
        CardGateway.Result interleavedVerify = gateway.verifyStudentPin(student);
        record("PH40", "thẻ thật: VERIFY xen giữa upload vẫn trả 9000",
                interleavedVerify.ok(), true);
        record("PH41", "thẻ thật: lệnh xen giữa đóng phiên upload (WRITE trả 6985)",
                gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);

        record("PH42", "thẻ thật: BEGIN sau lệnh xen giữa trả 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH43", "thẻ thật: FINISH khi thiếu byte trả 6985 (đường hủy chính thức)",
                gateway.transmitPhotoFinish().getSW() == Sw.CONDITIONS_NOT_SATISFIED, true);
        record("PH30", "thẻ thật: sau FINISH lỗi, WRITE không còn phiên nên trả 6985",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);
        record("PH44", "thẻ thật: sau khi hủy bằng FINISH, BEGIN mở lại được",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH45", "thẻ thật: offset vượt tổng ảnh trả 6A86 trong phiên mới",
                gateway.transmitPhotoWriteChunk(PHOTO_LENGTH, firstChunk).getSW()
                        == Sw.WRONG_P1P2, true);
        record("PH31", "thẻ thật: sau 6A86 vượt tổng, phiên đã đóng (WRITE trả 6985)",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);

        CardGateway.Result noPhoto = gateway.readPhoto(student);
        record("PH32", "thẻ thật: ảnh dở dang không đọc được (6985)",
                !noPhoto.ok() && noPhoto.text().contains("6985"), true);

        CardGateway.Result upload = gateway.uploadPhoto(image, admin);
        record("PH20", "thẻ thật: upload 4096 byte qua 32 chunk + FINISH trả 9000", upload.ok(), true);
        if (!upload.ok()) {
            System.out.println("      chi tiet: " + upload.text());
        }

        CardGateway.Result secondBegin = gateway.uploadPhoto(image, admin);
        record("PH21", "thẻ thật: upload lần hai bị từ chối vì photoReady=true",
                !secondBegin.ok() && secondBegin.text().contains("6985"), true);

        CardGateway.Result read = gateway.readPhoto(student);
        record("PH22", "thẻ thật: VERIFY một lần + 32 chunk đọc trả 9000", read.ok(), true);
        if (!read.ok()) {
            System.out.println("      chi tiet: " + read.text());
        }
        record("PH23", "thẻ thật: ảnh đọc lại khớp từng byte với nguồn",
                firstMismatch(image, gateway.lastPhoto()) == null, true);

        CardGateway.Result again = gateway.readPhoto(student);
        record("PH24", "thẻ thật: đọc lại cần VERIFY mới (quyền đã tiêu thụ)",
                again.ok(), true);
        record("PH25", "thẻ thật: sau khi hết chuỗi, READ_CHUNK offset 0 trả 6982",
                gateway.transmitReadChunk(0, PHOTO_CHUNK).getSW()
                        == Sw.SECURITY_NOT_SATISFIED, true);
    }

    private static void checkPhotoSessionLossOnCard(CardGateway gateway) throws Exception {
        System.out.println("--- Nhom PH56+: mat phien upload khi deselect / reset / hai tien trinh ---");
        byte[] image = syntheticPhoto();
        char[] admin = liveSecret(true);
        char[] student = liveSecret(false);

        byte[] beginData = new byte[PHOTO_BEGIN_LENGTH];
        for (int index = 0; index < 8; index++) {
            beginData[index] = (byte) admin[index];
        }
        beginData[8] = 0x01;
        beginData[9] = 64;
        beginData[10] = 64;
        beginData[11] = (byte) (PHOTO_LENGTH >> 8);
        beginData[12] = (byte) PHOTO_LENGTH;

        byte[] firstChunk = new byte[PHOTO_CHUNK];
        System.arraycopy(image, 0, firstChunk, 0, PHOTO_CHUNK);

        record("PH56", "deselect: BEGIN tra 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH57", "deselect: WRITE offset 0 tra 9000",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW() == Sw.OK, true);
        record("PH58", "deselect: SELECT applet khac (helloworld) tra 9000",
                gateway.selectInstance(HELLOWORLD_AID), Sw.OK);
        record("PH59", "deselect: SELECT LabAccess lai tra 9000",
                gateway.selectInstance(AID), Sw.OK);
        record("PH60", "deselect: WRITE offset 128 sau deselect tra 6985",
                gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);
        record("PH61", "deselect: BEGIN moi sau deselect tra 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH62", "deselect: khong FINISH nao xay ra (FINISH khi chua du byte tra 6985)",
                gateway.transmitPhotoFinish().getSW() == Sw.CONDITIONS_NOT_SATISFIED, true);
        CardGateway.Result deselectedRead = gateway.readPhoto(student);
        record("PH63", "deselect: anh dở dang van khong doc duoc (6985)",
                !deselectedRead.ok() && deselectedRead.text().contains("6985"), true);

        record("PH64", "reset: BEGIN lai sau buoc doc tra 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH65", "reset: WRITE chunk 0 tra 9000",
                gateway.transmitPhotoWriteChunk(0, firstChunk).getSW() == Sw.OK, true);
        gateway.disconnect();
        gateway.connect();
        gateway.identify();
        record("PH66", "reset: SELECT + GET_STATUS sau disconnect(reset)+connect tra 9000",
                gateway.isReady() && gateway.status() != null, true);
        record("PH67", "reset: WRITE offset 128 sau reset tra 6985",
                gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, firstChunk).getSW()
                        == Sw.CONDITIONS_NOT_SATISFIED, true);
        record("PH68", "reset: BEGIN moi sau reset tra 9000",
                gateway.transmitPhotoBegin(beginData).getSW() == Sw.OK, true);
        record("PH69", "reset: khong FINISH nao xay ra (FINISH khi chua du byte tra 6985)",
                gateway.transmitPhotoFinish().getSW() == Sw.CONDITIONS_NOT_SATISFIED, true);
        CardGateway.Result resetRead = gateway.readPhoto(student);
        record("PH70", "reset: anh dở dang van khong doc duoc (6985)",
                !resetRead.ok() && resetRead.text().contains("6985"), true);

        java.util.Arrays.fill(beginData, (byte) 0);
        runTwoClientCase(gateway, image);
        System.out.println("--- Ket noi lai de nhom tiep theo chay binh thuong ---");
        if (!gateway.isConnected()) {
            gateway.connect();
            gateway.identify();
        }
    }

    private static void runTwoClientCase(CardGateway gateway, byte[] image) throws Exception {
        Path logDir = Path.of("logs");
        Files.createDirectories(logDir);
        Path clientLog = logDir.resolve("photo-two-client-B.log");
        Path handshake = logDir.resolve("photo-two-client-handshake.flag");
        Files.deleteIfExists(clientLog);
        Files.deleteIfExists(handshake);
        String separator = System.getProperty("file.separator");
        String parentModulePath = System.getProperty("jdk.module.path", "");
        List<String> command = List.of(
                System.getProperty("java.home") + separator + "bin" + separator + "java",
                "--module-path", parentModulePath,
                "-Dlabaccess.port=" + PORT,
                "-Dlabaccess.twoClient.mode=interleave",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                "-m", "vn.labaccess.desktop/vn.labaccess.desktop.CardGatewayVerify");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(clientLog.toFile()).redirectErrorStream(true);
        Process clientB = builder.start();
        try {
            long pidB = clientB.pid();
            long pidA = ProcessHandle.current().pid();
            byte[] beginData = new byte[PHOTO_BEGIN_LENGTH];
            byte[] adminPin = ADMIN_PIN_TEXT.getBytes();
            for (int index = 0; index < 8; index++) {
                beginData[index] = (byte) adminPin[index];
            }
            beginData[8] = 0x01;
            beginData[9] = 64;
            beginData[10] = 64;
            beginData[11] = (byte) (PHOTO_LENGTH >> 8);
            beginData[12] = (byte) PHOTO_LENGTH;
            byte[] firstChunk = new byte[PHOTO_CHUNK];
            System.arraycopy(image, 0, firstChunk, 0, PHOTO_CHUNK);

            int beginSw = gateway.transmitPhotoBegin(beginData).getSW();
            System.out.println("    A(PID=" + pidA + ") PHOTO_BEGIN SW=" + String.format("%04X", beginSw));
            int write0Sw = gateway.transmitPhotoWriteChunk(0, firstChunk).getSW();
            System.out.println("    A(PID=" + pidA + ") WRITE offset=0 SW=" + String.format("%04X", write0Sw));

            boolean handshakeSeen = false;
            boolean exited = false;
            for (int second = 0; second < 60 && !handshakeSeen; second++) {
                if (!clientB.isAlive() && !Files.exists(handshake)) {
                    break;
                }
                handshakeSeen = Files.exists(handshake);
                if (!handshakeSeen) {
                    Thread.sleep(1000);
                }
            }
            boolean serializedBridge = false;
            if (!handshakeSeen && clientB.isAlive()) {
                serializedBridge = true;
                System.out.println("    B(PID=" + pidB + ") bi chan o connect() trong khi A con giu ket noi — bridge jcsw tuan tu hoa client");
                gateway.disconnect();
                exited = clientB.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            }
            record("PH71", "hai client: tien trinh B (PID=" + pidB + ") da khoi dong", clientB.isAlive() || exited, true);
            String bLog = Files.exists(clientLog) ? Files.readString(clientLog) : "(khong co log B)";
            System.out.println("    Log tien trinh B: " + bLog.replace(System.lineSeparator(), " | "));

            int write128Sw = serializedBridge ? -1 : gateway.transmitPhotoWriteChunk(PHOTO_CHUNK, firstChunk).getSW();
            if (!serializedBridge) {
                System.out.println("    A(PID=" + pidA + ") WRITE offset=128 sau lenh xen ngang cua B SW="
                        + String.format("%04X", write128Sw));
            }

            boolean exitedFinal = exited || clientB.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            int exitCode = exitedFinal ? clientB.exitValue() : -1;

            record("PH72", "hai client: A BEGIN + WRITE offset 0 truoc khi B xen tra 9000",
                    beginSw == Sw.OK && write0Sw == Sw.OK, true);
            if (serializedBridge) {
                skip("PH73", "hai client: B xen lenh trong khi A dang giu phien",
                        "bridge jcsw chi cho mot client: connect() cua B bi chan toi khi A ngat — khong thuc hien duoc tranh phien that tren runtime nay");
                skip("PH74", "hai client: WRITE cua A bi chan 6985 sau lenh xen cua B",
                        "phu thuoc PH73");
            } else {
                record("PH73", "hai client: B da gui lenh xen ngang (GET_STATUS) truoc khi A ghi tiep",
                        handshakeSeen && bLog.contains("GET_STATUS"), true);
                record("PH74", "hai client: WRITE offset 128 cua A bi chan 6985 sau lenh xen cua B",
                        write128Sw == Sw.CONDITIONS_NOT_SATISFIED, true);
            }
            if (serializedBridge) {
                gateway.connect();
                gateway.identify();
            }
            int finishSw = gateway.transmitPhotoFinish().getSW();
            record("PH75", "hai client: khong FINISH nao xay ra (FINISH tra 6985)",
                    finishSw == Sw.CONDITIONS_NOT_SATISFIED, true);
            CardGateway.Result contendedRead = gateway.readPhoto(liveSecret(false));
            record("PH76", "hai client: anh dở dang van khong doc duoc (6985)",
                    !contendedRead.ok() && contendedRead.text().contains("6985"), true);
            record("PH77", "hai client: B ket thuc trong han (exited=" + exitedFinal + " exit=" + exitCode + ")",
                    exitedFinal && exitCode == 0, true);
        } finally {
            try {
                if (clientB.isAlive()) {
                    clientB.destroy();
                    if (!clientB.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        clientB.destroyForcibly();
                        if (!clientB.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Khong the dung tien trinh B PID=" + clientB.pid());
                        }
                    }
                }
            } finally {
                try {
                    Files.deleteIfExists(handshake);
                } finally {
                    Files.deleteIfExists(clientLog);
                }
            }
        }
    }

    private static void runTwoClientChild() {
        String mode = System.getProperty("labaccess.twoClient.mode", "interleave");
        try {
            CardGateway gateway = new CardGateway(HOST, PORT);
            gateway.connect();
            gateway.identify();
            long pid = ProcessHandle.current().pid();
            System.out.println("B(PID=" + pid + ") SELECT SW=9000");
            System.out.flush();
            if (mode.equals("hold")) {
                Thread.sleep(15_000);
                gateway.disconnect();
                System.out.println("B(PID=" + pid + ") hold xong");
                System.out.flush();
                System.exit(0);
            }
            int statusSw = gateway.transmitStatus().getSW();
            System.out.println("B(PID=" + pid + ") GET_STATUS SW=" + String.format("%04X", statusSw)
                    + " (lenh xen ngang)");
            System.out.flush();
            Files.writeString(Path.of("logs", "photo-two-client-handshake.flag"),
                    "B interleaved GET_STATUS SW=" + String.format("%04X", statusSw));
            System.exit(0);
        } catch (Exception ex) {
            System.out.println("B(PID=" + ProcessHandle.current().pid() + ") LOI: " + ex);
            System.out.flush();
            System.exit(1);
        }
    }

    private static CardGateway readyGateway(CardChannel channel) {
        CardGateway gateway = new CardGateway(channel);
        try {
            CardGateway.Result result = gateway.identify();
            if (!result.ok()) {
                throw new IllegalStateException("fixture identify loi: " + result.text());
            }
            return gateway;
        } catch (CardException ex) {
            throw new IllegalStateException("fixture identify loi", ex);
        }
    }

    private static CardChannel issuedChannel(
            java.util.function.Function<CommandAPDU, ResponseAPDU> afterStatus) {
        return issuedChannel(afterStatus, 0x01);
    }

    private static CardChannel issuedChannel(
            java.util.function.Function<CommandAPDU, ResponseAPDU> afterStatus, int lifecycle) {
        String cardId = lifecycle == 0x01 ? CARD_ID : "0000000000000000";
        return scriptedChannel(command -> {
            int ins = command.getINS() & 0xFF;
            if (ins == 0xA4) {
                return response(Sw.OK);
            }
            if (ins == 0x10) {
                return statusApdu(lifecycle, cardId);
            }
            return afterStatus.apply(command);
        });
    }

    private static ResponseAPDU authenticatedStatusApdu(int balance, long counter,
            boolean activeVisit, String visitIdHex) {
        byte[] publicData = statusApdu(0x01, CARD_ID).getData();
        byte[] visitId = HexFormat.of().parseHex(visitIdHex);
        byte[] data = Arrays.copyOf(publicData, publicData.length + 23);
        int offset = publicData.length;
        data[offset] = (byte) (balance >> 8);
        data[offset + 1] = (byte) balance;
        for (int index = 0; index < 4; index++) {
            data[offset + 5 - index] = (byte) (counter >> (index * 8));
        }
        data[offset + 6] = (byte) (activeVisit ? 1 : 0);
        System.arraycopy(visitId, 0, data, offset + 7, visitId.length);
        byte[] raw = Arrays.copyOf(data, data.length + 2);
        raw[data.length] = (byte) (SW_OK >> 8);
        raw[data.length + 1] = (byte) SW_OK;
        return new ResponseAPDU(raw);
    }

    private static ResponseAPDU statusApdu(int lifecycle, String cardIdHex) {
        return statusApdu(lifecycle, cardIdHex, AID);
    }

    private static ResponseAPDU statusApdu(int lifecycle, String cardIdHex, byte[] aid) {
        byte[] cardId = HexFormat.of().parseHex(cardIdHex);
        byte[] data = new byte[11 + aid.length + 1 + cardId.length];
        byte[] magic = { 'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S' };
        System.arraycopy(magic, 0, data, 0, magic.length);
        data[9] = 0x01;
        data[10] = (byte) aid.length;
        System.arraycopy(aid, 0, data, 11, aid.length);
        data[11 + aid.length] = (byte) lifecycle;
        System.arraycopy(cardId, 0, data, 12 + aid.length, cardId.length);
        byte[] raw = new byte[data.length + 2];
        System.arraycopy(data, 0, raw, 0, data.length);
        raw[data.length] = (byte) (SW_OK >> 8);
        raw[data.length + 1] = (byte) SW_OK;
        return new ResponseAPDU(raw);
    }

    private static char[] readSecret(java.io.Console console, String prompt, int length) {
        if (console != null) {
            char[] typed = console.readPassword(prompt);
            if (typed == null) {
                return null;
            }
            if (typed.length == length) {
                return typed;
            }
            java.util.Arrays.fill(typed, '\0');
            return null;
        }
        System.out.print(prompt);
        System.out.flush();
        String line = readStdinLine();
        if (line == null) {
            return null;
        }
        char[] chars = line.toCharArray();
        line = null;
        if (chars.length == length) {
            return chars;
        }
        java.util.Arrays.fill(chars, '\0');
        return null;
    }

    private static java.io.BufferedReader stdinReader;

    private static String readStdinLine() {
        try {
            if (stdinReader == null) {
                stdinReader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        System.in, java.nio.charset.StandardCharsets.US_ASCII));
            }
            String line = stdinReader.readLine();
            return line == null ? null : line.strip();
        } catch (IOException ex) {
            return null;
        }
    }

    private static void checkFaultAfterCommitOnCard(CardGateway gateway) {
        System.out.println();
        System.out.println("--- Nhom Z: fault-after-commit that + reconnect + doi soat ---");

        if (!gateway.isConnected() || !gateway.isReady() || gateway.status() == null
                || gateway.status().lifecycle() != 0x01) {
            skip("Z01", "fault-after-commit that tren Simulator", "the chua ISSUED");
            return;
        }
        if (!Boolean.getBoolean("labaccess.faultCard")) {
            skip("Z01", "fault-after-commit that tren Simulator",
                    "can cai CAP variant quota-fault-after-commit va dat -Dlabaccess.faultCard=true");
            return;
        }

        char[] adminPinChars = liveSecret(true);
        char[] studentPin = liveSecret(false);
        if (adminPinChars == null || studentPin == null) {
            skip("Z01", "fault-after-commit that tren Simulator",
                    "khong doc duoc PIN dung do dai; khong gui APDU");
            return;
        }
        byte[] adminPin = new byte[8];
        for (int i = 0; i < 8; i++) {
            adminPin[i] = (byte) adminPinChars[i];
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory("labaccess-fault-commit");
            Path file = dir.resolve("pending-journal.txt");
            PendingJournal journal = new PendingJournal(file);

            Receipt before = gateway.lastReceipt();
            long counterBefore;
            int balanceBefore;
            if (before != null) {
                counterBefore = before.counterAfter();
                balanceBefore = before.balanceAfter();
            } else {
                CardGateway.Result read = gateway.readLastReceipt(null);
                if (!read.ok() || gateway.lastReceipt() == null) {
                    record("Z00", "khong doc duoc receipt baseline truoc fault", false, true);
                    System.out.println("    baseline: " + read.text());
                    return;
                }
                counterBefore = gateway.lastReceipt().counterAfter();
                balanceBefore = gateway.lastReceipt().balanceAfter();
            }

            int faultDelta = Integer.getInteger("labaccess.faultDelta", 8888);
            if (balanceBefore + faultDelta > Receipt.MAX_BALANCE) {
                skip("Z01", "fault-after-commit that tren Simulator",
                        "balance=" + balanceBefore + " + " + faultDelta
                                + " vuot MAX_BALANCE nen applet se tu choi 6A80 truoc khi toi fault;"
                                + " cai lai CAP de personalize the moi");
                return;
            }

            ReceiptExpectation expectation = new ReceiptExpectation(
                    HexFormat.of().parseHex(gateway.status().cardId()), counterBefore + 1,
                    Receipt.OPERATION_GRANT, null);
            CardGateway.Result fault = gateway.runStateTransaction(journal,
                    new CardGateway.TransactionPlan(expectation, faultDelta,
                            balanceBefore + faultDelta),
                    gateway.grantCommand(adminPin, faultDelta), studentPin);
            System.out.println("    GRANT " + faultDelta + " (fault sau commit) -> " + fault.text());
            record("Z01", "fault-after-commit -> PENDING + UNKNOWN, khong retry",
                    !fault.ok() && journal.load().present()
                            && journal.load().status() == PendingJournal.Status.PENDING
                            && gateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                    true);

            CardGateway.Result blocked = gateway.runStateTransaction(journal,
                    new CardGateway.TransactionPlan(expectation, 1, balanceBefore + 1),
                    gateway.grantCommand(adminPin, 1), studentPin);
            record("Z02", "sau fault, giao dich moi bi chan truoc khi gui APDU",
                    !blocked.ok(), true);

            gateway.disconnect();
            gateway.connect();
            gateway.identify();
            record("Z03", "reconnect + nhan dien lai the ISSUED",
                    gateway.isReady() && gateway.status() != null
                            && gateway.status().lifecycle() == 0x01, true);

            PendingJournal.Loaded reloaded = journal.load();
            record("Z04", "journal PENDING van con sau reconnect",
                    reloaded.present() && reloaded.valid()
                            && reloaded.status() == PendingJournal.Status.PENDING, true);

            CardGateway.Result reconciled = gateway.reconcilePending(journal, studentPin);
            System.out.println("    RECONCILE -> " + reconciled.text());
            record("Z05", "doi soat EXECUTED + ECDSA PASS + journal xoa",
                    reconciled.ok() && reconciled.text().contains("ECDSA=PASS")
                            && !journal.load().present()
                            && gateway.reconciliation().state() == Reconciliation.State.KNOWN,
                    true);

            Receipt after = gateway.lastReceipt();
            record("Z06", "receipt sau fault la cua giao dich da commit (counter +1)",
                    after != null && after.counterAfter() == counterBefore + 1
                            && ReceiptVerifier.verifyReceipt(gateway.cardPublicKey(), after).ok(),
                    true);
        } catch (Exception ex) {
            record("Z00", "nhom Z loi khong mong doi: " + ex, false, true);
        } finally {
            java.util.Arrays.fill(adminPin, (byte) 0);
            clearLiveSecrets();
            if (dir != null) {
                try {
                    deleteRecursively(dir);
                } catch (IOException ignored) {
                    dir = null;
                }
            }
        }
    }

    private static void record(String id, String name, boolean actual, boolean expected) {
        boolean ok = actual == expected;
        if (!ok) {
            failed++;
        }
        System.out.println(String.format("%s | %-56s | expected=%s actual=%s | %s",
                id, name, expected, actual, ok ? "PASS" : "FAIL"));
    }

    private static void record(String id, String name, int actual, int expected) {
        boolean ok = actual == expected;
        if (!ok) {
            failed++;
        }
        System.out.println(String.format("%s | %-56s | expected=%04X actual=%04X | %s",
                id, name, expected, actual, ok ? "PASS" : "FAIL"));
    }

    private static void skip(String id, String name, String reason) {
        skipped++;
        System.out.println(String.format("%s | %-56s | SKIP: %s", id, name, reason));
    }
}
