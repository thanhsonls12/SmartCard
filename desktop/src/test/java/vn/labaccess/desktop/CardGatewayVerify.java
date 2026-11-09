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

    private static final int SW_OK = 0x9000;

    private static final String HOST = System.getProperty("labaccess.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("labaccess.port", 9025);

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String OTHER_CARD_ID = "0011223344556677";
    private static final String VISIT_ID = "7172737475767778797A7B7C7D7E7F80";
    private static final String OTHER_VISIT_ID = "808182838485868788898A8B8C8D8E8F";
    private static final String NO_VISIT_ID = "00000000000000000000000000000000";

    private static int failed;
    private static int skipped;

    public static void main(String[] args) throws Exception {
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
        preparePersonalizedCard();

        CardGateway gateway = new CardGateway(HOST, PORT);
        try {
            checkIdentifyWithRealCard(gateway);
            checkLastReceiptOnCard(gateway);
            checkVerifyAndTransactionOnCard(gateway);
            checkFaultAfterCommitOnCard(gateway);
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
        record("C15", "signatureLength 69 bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 69)).ok(),
                false);
        record("C16", "signatureLength 73 bi tu choi",
                ReceiptCodec.parse(receiptResponse(1, 5, 1, 5, CARD_ID, NO_VISIT_ID, 73)).ok(),
                false);
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

        byte[] der69 = Arrays.copyOf(signature, 69);
        byte[] der73 = Arrays.copyOf(signature, 73);
        record("S06", "DER 69/73 byte -> FAIL truoc khi verify",
                !ReceiptVerifier.verify(card.publicKey(), payload, der69).ok()
                        && !ReceiptVerifier.verify(card.publicKey(), payload, der73).ok(),
                true);

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

        CardGateway.Result again;
        try {
            again = gateway.identify();
        } catch (Exception ex) {
            again = CardGateway.Result.fail("identify() loi: " + ex);
        }
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

        record("P01", "9000 -> ok", verifyText(Sw.OK).contains("9000"), true);
        record("P02", "63C2 -> con 2 lan thu", verifyText(0x63C2).contains("còn 2 lần thử"), true);
        record("P03", "6983 -> PIN bi khoa", verifyText(Sw.AUTH_BLOCKED).contains("khóa"), true);
        record("P04", "6985 -> chua personalize",
                verifyText(Sw.CONDITIONS_NOT_SATISFIED).contains("chưa personalize"), true);
        record("P05", "6A80 -> dinh dang sai",
                verifyText(Sw.WRONG_DATA).contains("PIN sai"), true);
        record("P06", "6700 -> do dai sai",
                verifyText(Sw.WRONG_LENGTH).contains("6 byte"), true);
        record("P07", "6A86 -> P1P2 sai",
                verifyText(Sw.WRONG_P1P2).contains("P1/P2"), true);

        java.util.concurrent.atomic.AtomicBoolean shape = new java.util.concurrent.atomic.AtomicBoolean();
        CardGateway gateway = new CardGateway(scriptedChannel(command -> {
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
        return new CardGateway(scriptedChannel(command -> response(sw))).verifyStudentPin(
                "654321".toCharArray()).text();
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

    private static void preparePersonalizedCard() {
        System.out.println();
        System.out.println("--- Nhom W: PERSONALIZE the that truoc khi giao dich ---");

        int lifecycle;
        String cardId;
        Card card = null;
        try {
            javax.smartcardio.TerminalFactory factory = javax.smartcardio.TerminalFactory
                    .getInstance("SocketCardTerminalFactoryType",
                            java.util.List.of(new java.net.InetSocketAddress(HOST, PORT)),
                            "SocketCardTerminalProvider");
            javax.smartcardio.CardTerminal terminal = factory.terminals().list().get(0);
            card = terminal.connect("*");
            CardChannel channel = card.getBasicChannel();
            ResponseAPDU select = channel.transmit(
                    new CommandAPDU(0x00, 0xA4, 0x04, 0x00, AID, 256));
            if (select.getSW() != Sw.OK) {
                skip("W01", "PERSONALIZE the that", "SELECT AID that bai");
                return;
            }
            ResponseAPDU status = channel.transmit(
                    new CommandAPDU(0x80, 0x10, 0x00, 0x00, 256));
            byte[] data = status.getData();
            int aidLength = data[10] & 0xFF;
            lifecycle = data[11 + aidLength] & 0xFF;
            byte[] id = Arrays.copyOfRange(data, 12 + aidLength, 20 + aidLength);
            cardId = HexFormat.of().formatHex(id).toUpperCase();

            if (lifecycle == 0x01) {
                record("W01", "the da o lifecycle ISSUED, khong PERSONALIZE lai",
                        cardId.equals(CARD_ID), true);
                return;
            }
            if (!Boolean.getBoolean("labaccess.personalize")) {
                skip("W01", "PERSONALIZE the that",
                        "dat -Dlabaccess.personalize=true de tao the test moi");
                return;
            }

            char[] adminPinChars = liveSecret(true);
            char[] studentPin = liveSecret(false);
            if (adminPinChars == null || studentPin == null) {
                skip("W01", "PERSONALIZE the that", "khong doc duoc PIN dung do dai");
                return;
            }
            byte[] adminPin = new byte[8];
            byte[] studentPinBytes = new byte[6];
            try {
                for (int i = 0; i < 8; i++) {
                    adminPin[i] = (byte) adminPinChars[i];
                }
                for (int i = 0; i < 6; i++) {
                    studentPinBytes[i] = (byte) studentPin[i];
                }
                ResponseAPDU personalized = channel.transmit(
                        new CommandAPDU(0x80, 0x30, 0x00, 0x00,
                                personalizePayload(adminPin, studentPinBytes), 256));
                record("W01", "PERSONALIZE the moi -> 9000",
                        personalized.getSW() == Sw.OK, true);

                ResponseAPDU recheck = channel.transmit(
                        new CommandAPDU(0x80, 0x10, 0x00, 0x00, 256));
                byte[] recheckData = recheck.getData();
                int recheckAidLength = recheckData[10] & 0xFF;
                int recheckLifecycle = recheckData[11 + recheckAidLength] & 0xFF;
                String recheckCardId = HexFormat.of().formatHex(Arrays.copyOfRange(
                        recheckData, 12 + recheckAidLength, 20 + recheckAidLength)).toUpperCase();
                record("W02", "sau PERSONALIZE: lifecycle ISSUED + cardId dung",
                        recheckLifecycle == 0x01 && recheckCardId.equals(CARD_ID), true);
            } finally {
                java.util.Arrays.fill(adminPin, (byte) 0);
                java.util.Arrays.fill(studentPinBytes, (byte) 0);
            }
        } catch (Exception ex) {
            record("W00", "PERSONALIZE loi khong mong doi: " + ex, false, true);
        } finally {
            if (card != null) {
                try {
                    card.disconnect(false);
                } catch (CardException ignored) {
                    card = null;
                }
            }
        }
    }

    private static byte[] personalizePayload(byte[] adminPin, byte[] studentPin) {
        byte[] cardId = HexFormat.of().parseHex(CARD_ID);
        byte[] studentId = "SV2024-001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] displayName = "Nguyen Van A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + 8 + cardId.length + 1 + studentId.length
                + studentPin.length + 1 + displayName.length];
        int offset = 0;
        payload[offset++] = 8;
        System.arraycopy(adminPin, 0, payload, offset, 8);
        offset += 8;
        System.arraycopy(cardId, 0, payload, offset, cardId.length);
        offset += cardId.length;
        payload[offset++] = (byte) studentId.length;
        System.arraycopy(studentId, 0, payload, offset, studentId.length);
        offset += studentId.length;
        System.arraycopy(studentPin, 0, payload, offset, studentPin.length);
        offset += studentPin.length;
        payload[offset++] = (byte) displayName.length;
        System.arraycopy(displayName, 0, payload, offset, displayName.length);
        return payload;
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
        return scriptedChannel(command -> {
            int ins = command.getINS() & 0xFF;
            if (ins == 0xA4) {
                return response(Sw.OK);
            }
            if (ins == 0x10) {
                return statusApdu(0x01, CARD_ID);
            }
            return afterStatus.apply(command);
        });
    }

    private static ResponseAPDU statusApdu(int lifecycle, String cardIdHex) {
        byte[] cardId = HexFormat.of().parseHex(cardIdHex);
        byte[] data = new byte[11 + AID.length + 1 + cardId.length];
        byte[] magic = { 'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S' };
        System.arraycopy(magic, 0, data, 0, magic.length);
        data[9] = 0x01;
        data[10] = (byte) AID.length;
        System.arraycopy(AID, 0, data, 11, AID.length);
        data[11 + AID.length] = (byte) lifecycle;
        System.arraycopy(cardId, 0, data, 12 + AID.length, cardId.length);
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
