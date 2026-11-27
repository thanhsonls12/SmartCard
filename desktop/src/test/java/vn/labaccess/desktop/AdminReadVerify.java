package vn.labaccess.desktop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Label;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.card.CardAdminCommands;
import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.CardStatusCodec;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptCodec;
import vn.labaccess.desktop.card.ReceiptReadRecovery;
import vn.labaccess.desktop.card.ReceiptVerifier;
import vn.labaccess.desktop.card.Reconciliation;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.ui.shared.CardOpRunner;

import vn.labaccess.desktop.ui.receipt.ReceiptPanel;

public final class AdminReadVerify {

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String OTHER_CARD_ID = "1122334455667788";
    private static final byte[] AID = HexFormat.of().parseHex("F04C4142010101");
    private static int checks;

    public static void main(String[] args) throws Exception {
        identityMismatchInvalidatesSession();
        adminReceiptDoesNotPopulateStudentCache();
        statusAndReceiptCommands();
        statusValidation();
        malformedStatusPrivacy();
        errorResponses();
        rejectedInputs();
        receiptTrust();
        sourceIsolation();
        transportFailures();
        receiptPanelIsolation();
        System.out.println("ADMIN READ PASS: " + checks + " checks");
    }

    private static void identityMismatchInvalidatesSession() throws Exception {
        for (boolean wrongAid : new boolean[] {true, false}) {
            try (Fixture fixture = new Fixture()) {
                CardGateway gateway = fixture.gateway();
                check("seed receipt caches before identity mismatch wrongAid=" + wrongAid,
                        fixture.readReceipt(gateway).ok()
                                && gateway.verifyAndReadReceipt(null, fixture.studentPin).ok()
                                && gateway.lastReceipt() != null && gateway.lastReceiptSignatureOk());
                gateway.markTransactionUnknown("fixture pending before mismatch");
                fixture.commands.clear();
                byte[] good = fixture.status.clone();
                fixture.status = wrongAid ? changed(good, 11, 0xE0) : statusBytes(AID, OTHER_CARD_ID);
                check("identity mismatch rejected wrongAid=" + wrongAid,
                        !gateway.readAdminStatus(fixture.pin).ok() && fixture.commands.equals(List.of(0x11)));
                check("identity mismatch removes readiness and old identity wrongAid=" + wrongAid,
                        !gateway.isReady() && gateway.status() == null && gateway.cardPublicKey() == null
                                && gateway.lastCardPublicKeyBytes() == null && gateway.enrollmentBlockReason() != null
                                && gateway.lastReceipt() == null && !gateway.lastReceiptSignatureChecked()
                                && !gateway.lastReceiptSignatureOk() && gateway.lastAdminReceiptRead() == null);
                check("identity invalidation preserves UNKNOWN and response SW wrongAid=" + wrongAid,
                        gateway.reconciliation().state() == Reconciliation.State.UNKNOWN
                                && gateway.lastResponseSw() == Sw.OK);
                int before = fixture.commands.size();
                check("both Admin reads blocked before APDU after identity mismatch wrongAid=" + wrongAid,
                        !gateway.readAdminStatus(fixture.pin).ok() && !fixture.readReceipt(gateway).ok()
                                && fixture.commands.size() == before);
                fixture.status = good;
                gateway.checkStatus(gateway.transmitStatus(), AID, CardStatus.StateSource.PUBLIC);
                before = fixture.commands.size();
                check("GET_STATUS alone cannot restore readiness wrongAid=" + wrongAid,
                        !gateway.isReady() && !gateway.readAdminStatus(fixture.pin).ok()
                                && !fixture.readReceipt(gateway).ok() && fixture.commands.size() == before);
                fixture.selectSw = Sw.CONDITIONS_NOT_SATISFIED;
                check("failed SELECT cannot restore readiness wrongAid=" + wrongAid,
                        !gateway.identifyWith(AID).ok() && !gateway.isReady() && gateway.status() == null);
                fixture.selectSw = Sw.OK;
                fixture.publicStatusSw = Sw.WRONG_LENGTH;
                check("SELECT without successful GET_STATUS cannot restore readiness wrongAid=" + wrongAid,
                        !gateway.identifyWith(AID).ok() && !gateway.isReady() && gateway.status() == null);
                before = fixture.commands.size();
                check("Admin remains blocked after incomplete identify wrongAid=" + wrongAid,
                        !gateway.readAdminStatus(fixture.pin).ok() && !fixture.readReceipt(gateway).ok()
                                && fixture.commands.size() == before);
                fixture.publicStatusSw = Sw.OK;
                before = fixture.commands.size();
                check("SELECT and GET_STATUS restore public identity wrongAid=" + wrongAid,
                        gateway.identifyWith(AID).ok() && gateway.isReady()
                                && gateway.status().stateSource() == CardStatus.StateSource.PUBLIC
                                && fixture.commands.subList(before, fixture.commands.size()).equals(List.of(0xA4, 0x10)));
                check("Admin read works only after reidentify wrongAid=" + wrongAid,
                        gateway.readAdminStatus(fixture.pin).ok() && fixture.readReceipt(gateway).ok());
            }
        }
    }

    private static void adminReceiptDoesNotPopulateStudentCache() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            check("Admin receipt succeeds before Student read", fixture.readReceipt(gateway).ok());
            check("Admin receipt never populates Student receipt or signature verdict",
                    gateway.lastReceipt() == null && !gateway.lastReceiptSignatureChecked()
                            && !gateway.lastReceiptSignatureOk());
            check("refresh Student snapshot succeeds after Admin receipt",
                    gateway.readAuthenticatedStatus(fixture.studentPin).ok()
                            && gateway.status().studentStateAvailable());
            check("refresh Student snapshot does not publish Admin receipt",
                    gateway.lastReceipt() == null && !gateway.lastReceiptSignatureChecked()
                            && !gateway.lastReceiptSignatureOk());
            check("Student receipt requires Student read path",
                    gateway.verifyStudentPin(fixture.studentPin).ok() && gateway.readLastReceipt(null).ok()
                            && gateway.lastReceipt() != null && gateway.lastReceiptSignatureChecked()
                            && gateway.lastReceiptSignatureOk() && fixture.commands.contains(0x60));
            check("Admin reread removes old Student receipt without publishing Admin result",
                    fixture.readReceipt(gateway).ok() && gateway.lastReceipt() == null
                            && !gateway.lastReceiptSignatureChecked() && !gateway.lastReceiptSignatureOk()
                            && gateway.lastAdminReceiptRead().result().ok());
            check("second Student refresh still has no receipt from Admin",
                    gateway.readAuthenticatedStatus(fixture.studentPin).ok() && gateway.lastReceipt() == null
                            && !gateway.lastReceiptSignatureChecked() && !gateway.lastReceiptSignatureOk());
        }
    }

    private static void statusAndReceiptCommands() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            CardOperationResult status = gateway.readAdminStatus(fixture.pin);
            check("status sends one Case 4S APDU with exact PIN bytes and Le=00",
                    status.ok() && fixture.commands.size() == 1 && fixture.shapeValid);
            check("Admin snapshot has authenticated fields without Student permission",
                    gateway.status().stateSource() == CardStatus.StateSource.ADMIN
                            && gateway.status().stateAvailable() && !gateway.status().studentStateAvailable()
                            && gateway.status().balance() == 5 && gateway.status().counter() == 1);
            byte[] receiptBefore = fixture.receipt.clone();
            CardOperationResult receipt = fixture.readReceipt(gateway);
            check("receipt sends exactly one additional INS=63 APDU",
                    receipt.ok() && fixture.commands.equals(List.of(0x11, 0x63)) && fixture.shapeValid);
            CardAdminCommands.ReceiptRead adminRead = gateway.lastAdminReceiptRead();
            check("receipt verified with enrolled fixture key and unchanged wire bytes",
                    adminRead.signatureChecked() && adminRead.result().ok()
                            && Arrays.equals(adminRead.receipt().payload(), Arrays.copyOf(receiptBefore, 34))
                            && Arrays.equals(adminRead.receipt().signature(),
                                    Arrays.copyOfRange(receiptBefore, 35, receiptBefore.length)));
            check("read does not save to ledger or change reconciliation state",
                    fixture.saves == 0 && gateway.reconciliation().state() == Reconciliation.State.KNOWN);
            check("results and diagnostics contain neither PIN nor PIN hex",
                    !fixture.leaks(status.text() + receipt.text() + fixture.diagnostics));
            check("reads do not fetch card key or verify Student PIN", fixture.keyReads == 0
                    && !fixture.commands.contains(0x20) && !fixture.commands.contains(0x61));
        }
    }

    private static void statusValidation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            byte[] good = fixture.status.clone();
            var malformed = new ArrayList<byte[]>();
            malformed.add(new byte[0]);
            malformed.add(Arrays.copyOf(good, good.length - 1));
            malformed.add(Arrays.copyOf(good, good.length + 1));
            malformed.add(Arrays.copyOf(good, 27));
            malformed.add(changed(good, 0, 'X'));
            malformed.add(changed(good, 9, 2));
            malformed.add(changed(good, 10, 16));
            malformed.add(changed(good, 11, 0xE0));
            malformed.add(changed(good, 18, 0));
            malformed.add(changed(good, 19, 0xB1));
            byte[] zeroCard = good.clone();
            Arrays.fill(zeroCard, 19, 27, (byte) 0);
            malformed.add(zeroCard);
            byte[] overflowBalance = good.clone();
            ByteBuffer.wrap(overflowBalance).putShort(27, (short) 10000);
            malformed.add(overflowBalance);
            malformed.add(changed(good, 33, 2));
            malformed.add(changed(good, 33, 1));
            malformed.add(changed(good, 34, 1));
            int index = 0;
            for (byte[] bad : malformed) {
                fixture.status = good;
                check("reidentify before malformed response " + index, gateway.identifyWith(AID).ok());
                check("valid Admin snapshot before malformed response " + index,
                        gateway.readAdminStatus(fixture.pin).ok());
                fixture.status = bad;
                int before = fixture.commands.size();
                CardOperationResult failed = gateway.readAdminStatus(fixture.pin);
                check("reject malformed status and remove previous state " + index++, !failed.ok()
                        && fixture.commands.size() == before + 1 && !gateway.isReady() && gateway.status() == null);
                before = fixture.commands.size();
                check("malformed status blocks next Admin APDU " + (index - 1),
                        !gateway.readAdminStatus(fixture.pin).ok() && !fixture.readReceipt(gateway).ok()
                                && fixture.commands.size() == before);
            }
            fixture.status = good.clone();
            ByteBuffer.wrap(fixture.status).putInt(29, -1);
            check("reidentify before uint32 status", gateway.identifyWith(AID).ok());
            check("counter decoded as uint32", gateway.readAdminStatus(fixture.pin).ok()
                    && gateway.status().counter() == 0xFFFFFFFFL);
            fixture.status = statusBytes(HexFormat.of().parseHex("F04C414201010102"), CARD_ID);
            CardGateway longerAid = new CardGateway(fixture);
            check("identify supports a different valid AID length",
                    longerAid.identifyWith(HexFormat.of().parseHex("F04C414201010102")).ok());
            check("Admin parser calculates offsets from aidLen",
                    longerAid.readAdminStatus(fixture.pin).ok() && longerAid.status().balance() == 5);
        }
    }

    private static void malformedStatusPrivacy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            byte[] good = fixture.status.clone();
            byte[] magicEcho = fixture.status.clone();
            byte[] aidEcho = statusBytes(new byte[8], CARD_ID);
            byte[] cardEcho = fixture.status.clone();
            cardEcho[18] = 0;
            for (int index = 0; index < fixture.pin.length; index++) {
                magicEcho[index] = (byte) fixture.pin[index];
                aidEcho[11 + index] = (byte) fixture.pin[index];
                cardEcho[19 + index] = (byte) fixture.pin[index];
            }
            int index = 0;
            for (byte[] malformed : new byte[][] {magicEcho, aidEcho, cardEcho}) {
                fixture.status = good;
                check("identify before privacy response " + index, gateway.identifyWith(AID).ok());
                fixture.status = malformed;
                int before = fixture.commands.size();
                CardOperationResult failed = gateway.readAdminStatus(fixture.pin);
                check("malformed success never echoes PIN through parser diagnostics " + index++,
                        !failed.ok() && fixture.commands.size() == before + 1
                                && !fixture.leaks(failed.text() + fixture.diagnostics));
            }
        }
    }

    private static void errorResponses() throws Exception {
        int[] errors = {0x63C4, 0x63C1, Sw.AUTH_BLOCKED, Sw.WRONG_LENGTH, Sw.WRONG_DATA,
            Sw.WRONG_P1P2, Sw.CONDITIONS_NOT_SATISFIED, Sw.INTERNAL_ERROR, 0x6C32, 0x6101};
        for (int sw : errors) {
            try (Fixture fixture = new Fixture()) {
                CardGateway gateway = fixture.gateway();
                fixture.adminSw = sw;
                CardOperationResult status = gateway.readAdminStatus(fixture.pin);
                CardOperationResult receipt = fixture.readReceipt(gateway);
                String expected = String.format("SW=%04X", sw);
                check("keep exact SW and no retry " + expected, !status.ok() && !receipt.ok()
                        && status.text().contains(expected) && receipt.text().contains(expected)
                        && gateway.lastResponseSw() == sw && fixture.commands.equals(List.of(0x11, 0x63)));
                check("error response data never exposes PIN or authenticated result " + expected,
                        !fixture.leaks(status.text() + receipt.text() + fixture.diagnostics)
                                && !gateway.status().stateAvailable() && gateway.lastReceipt() == null
                                && !gateway.lastReceiptSignatureOk() && !gateway.lastReceiptSignatureChecked()
                                && gateway.lastAdminReceiptRead().receipt() == null
                                && !gateway.lastAdminReceiptRead().signatureChecked());
                if ((sw & 0xFFF0) == 0x63C0) {
                    check("retry count remains visible " + expected,
                            receipt.text().contains("còn " + (sw & 0xF) + " lượt thử"));
                }
            }
        }
    }

    private static void rejectedInputs() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            char[] nonAscii = fixture.pin.clone();
            nonAscii[0] = '１';
            char[][] invalid = {null, new char[7], new char[9], nonAscii, "abcdefgh".toCharArray()};
            for (int i = 0; i < invalid.length; i++) {
                check("invalid PIN blocks both APDUs " + i,
                        !gateway.readAdminStatus(invalid[i]).ok()
                                && !gateway.readAdminLastReceipt(invalid[i], CARD_ID, fixture.enrolledKey()).ok()
                                && fixture.commands.isEmpty() && gateway.lastResponseSw() == -1);
            }
            CardGateway unidentified = new CardGateway(fixture);
            check("unidentified card blocks both Admin commands", !unidentified.readAdminStatus(fixture.pin).ok()
                    && !fixture.readReceipt(unidentified).ok() && fixture.commands.isEmpty());
            fixture.status = statusBytes(AID, "0000000000000000");
            fixture.status[18] = 0;
            gateway.identifyWith(AID);
            fixture.commands.clear();
            check("unissued card blocks both Admin commands", !gateway.readAdminStatus(fixture.pin).ok()
                    && !fixture.readReceipt(gateway).ok() && fixture.commands.isEmpty());
        }
    }

    private static void receiptTrust() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            check("missing enrolled key rejected before APDU",
                    !gateway.readAdminLastReceipt(fixture.pin, CARD_ID, null).ok() && fixture.commands.isEmpty());
            check("missing or wrong enrolled identity rejected before APDU",
                    !gateway.readAdminLastReceipt(fixture.pin, null, fixture.enrolledKey()).ok()
                            && !gateway.readAdminLastReceipt(fixture.pin, OTHER_CARD_ID, fixture.enrolledKey()).ok()
                            && fixture.commands.isEmpty());
            for (byte[] invalidKey : new byte[][] {new byte[0], new byte[64], new byte[65],
                changed(new byte[65], 0, 4)}) {
                check("malformed enrolled key rejected before APDU length=" + invalidKey.length,
                        !gateway.readAdminLastReceipt(fixture.pin, CARD_ID, invalidKey).ok()
                                && fixture.commands.isEmpty());
            }
            fixture.receipt = signedReceipt(fixture.cardKeys, OTHER_CARD_ID);
            check("receipt cardId checked before signature", !fixture.readReceipt(gateway).ok()
                    && !gateway.lastAdminReceiptRead().signatureChecked() && gateway.lastAdminReceiptRead().receipt() == null);
            byte[] good = signedReceipt(fixture.cardKeys, CARD_ID);
            fixture.receipt = good;
            check("successful read precedes failure", fixture.readReceipt(gateway).ok());
            fixture.cardKeys = keyPair();
            fixture.receipt = signedReceipt(fixture.cardKeys, CARD_ID);
            gateway.readCardPublicKey();
            int keysRead = fixture.keyReads;
            check("reject receipt even when cached card key agrees with untrusted signer",
                    !fixture.readReceipt(gateway).ok() && gateway.lastAdminReceiptRead().signatureChecked()
                            && !gateway.lastAdminReceiptRead().result().ok() && gateway.lastAdminReceiptRead().receipt() == null
                            && fixture.keyReads == keysRead);
            fixture.receipt = good.clone();
            fixture.receipt[fixture.receipt.length - 1] ^= 1;
            check("reject tampered signature", !fixture.readReceipt(gateway).ok()
                    && gateway.lastAdminReceiptRead().signatureChecked() && !gateway.lastAdminReceiptRead().result().ok());
            fixture.receipt = changed(good, ReceiptCodec.OFFSET_BALANCE + 1, 4);
            check("reject tampered signed payload", !fixture.readReceipt(gateway).ok()
                    && gateway.lastAdminReceiptRead().signatureChecked() && gateway.lastAdminReceiptRead().receipt() == null);
            for (byte[] invalid : new byte[][] {new byte[0], Arrays.copyOf(good, 35),
                Arrays.copyOf(good, good.length - 1), Arrays.copyOf(good, good.length + 1), changed(good, 0, 2)}) {
                fixture.receipt = invalid;
                check("reject malformed receipt without retaining old signature verdict length=" + invalid.length,
                        !fixture.readReceipt(gateway).ok() && gateway.lastAdminReceiptRead().receipt() == null
                                && !gateway.lastAdminReceiptRead().signatureChecked() && !gateway.lastAdminReceiptRead().result().ok());
            }
        }
    }

    private static void sourceIsolation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            CardStatusCodec.Parsed publicRead = CardStatusCodec.parse(
                    response(fixture.status, Sw.OK), AID, CardStatus.StateSource.PUBLIC);
            check("full response alone never grants Student state", publicRead.ok()
                    && !publicRead.status().stateAvailable() && publicRead.status().balance() == -1);
            check("Student VERIFY followed by status retains Student source",
                    gateway.readAuthenticatedStatus(fixture.studentPin).ok()
                            && gateway.status().studentStateAvailable());
            gateway.readAdminStatus(fixture.pin);
            SessionGates.Reasons gates = SessionGates.of(new SessionGates.State(false, true, true, true,
                    true, false, false, true, null, gateway.status()));
            check("Admin snapshot does not satisfy Student transaction gates",
                    gates.grant() == null && gates.start() != null && gates.end() != null);
            fixture.readReceipt(gateway);
            check("Admin state cannot resolve Student read recovery",
                    ReceiptReadRecovery.problem(gateway.status(), gateway.lastAdminReceiptRead().receipt(),
                            gateway.lastAdminReceiptRead().signatureChecked(), fixture) != null);
            gateway.readAuthenticatedStatus(fixture.studentPin);
            fixture.readReceipt(gateway);
            check("Admin receipt read clears previous Student snapshot", !gateway.status().studentStateAvailable());
            gateway.markTransactionUnknown("fixture pending");
            check("Admin read cannot clear UNKNOWN", gateway.readAdminStatus(fixture.pin).ok()
                    && fixture.readReceipt(gateway).ok()
                    && gateway.reconciliation().state() == Reconciliation.State.UNKNOWN);
            gateway.identifyWith(AID);
            check("reidentify restores public-only view", gateway.status().stateSource() == CardStatus.StateSource.PUBLIC
                    && gateway.lastAdminReceiptRead() == null);
            check("Admin receipt before disconnect", fixture.readReceipt(gateway).ok());
            gateway.disconnect();
            check("disconnect removes snapshot", gateway.status() == null && !gateway.isReady()
                    && gateway.lastAdminReceiptRead() == null);
        }
    }

    private static void transportFailures() throws Exception {
        for (boolean receipt : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                CardGateway gateway = fixture.gateway();
                gateway.readAdminStatus(fixture.pin);
                fixture.readReceipt(gateway);
                fixture.commands.clear();
                fixture.failTransport = true;
                try {
                    if (receipt) {
                        fixture.readReceipt(gateway);
                    } else {
                        gateway.readAdminStatus(fixture.pin);
                    }
                    throw new AssertionError("expected transport failure");
                } catch (CardException expected) {
                    check("transport failure never retries receipt=" + receipt,
                            fixture.commands.size() == 1 && !gateway.isReady() && gateway.needsReconnect()
                                    && gateway.lastResponseSw() == -1);
                    check("transport failure clears previous result receipt=" + receipt,
                            !gateway.status().stateAvailable()
                                    && gateway.lastAdminReceiptRead() == null
                                    && gateway.lastReceipt() == null && !gateway.lastReceiptSignatureOk());
                }
            }
        }
    }

    private static void receiptPanelIsolation() throws Exception {
        Platform.startup(() -> { });
        try (Fixture fixture = new Fixture()) {
            CardGateway gateway = fixture.gateway();
            check("Admin receipt before panel presentation", fixture.readReceipt(gateway).ok());
            FutureTask<ReceiptPanel> create = new FutureTask<>(() -> {
                CardOpRunner noIo = (operation, work, callback) -> {
                    throw new AssertionError("presentation must not start I/O");
                };
                return new ReceiptPanel(gateway, noIo, ignored -> { }, new ReceiptPanel.Session(
                        () -> true, PendingJournal.Loaded::absent,
                        java.nio.file.Path.of("fixture", "pending-journal.txt"),
                        () -> new char[0], () -> { },
                        pin -> CardOperationResult.fail("Không chạy đối soát trong ca trình bày"),
                        null,
                        () -> { }, () -> { }, () -> { }, () -> { },
                        ignored -> { }, ignored -> { }, () -> { }));
            });
            Platform.runLater(create);
            ReceiptPanel panel = create.get(10, TimeUnit.SECONDS);
            presentReceipt(panel, "ReceiptPanel hides Admin receipt without Student snapshot", false,
                    "Chưa đọc biên nhận từ thẻ");
            check("Student snapshot before panel refresh", gateway.readAuthenticatedStatus(fixture.studentPin).ok());
            presentReceipt(panel, "ReceiptPanel hides Admin receipt after Student snapshot refresh", false,
                    "Chưa có biên nhận");
            check("Student read before panel displays receipt",
                    gateway.verifyAndReadReceipt(null, fixture.studentPin).ok());
            presentReceipt(panel, "ReceiptPanel displays only Student-read receipt", true, "Chữ ký hợp lệ");
        } finally {
            Platform.exit();
        }
    }

    private static void presentReceipt(ReceiptPanel panel, String description, boolean receiptExpected,
            String expectedText) throws Exception {
        FutureTask<Void> present = new FutureTask<>(() -> {
            panel.showReceipt();
            check(description, hasText(panel.node(), "5 lượt") == receiptExpected
                    && hasText(panel.node(), "Chữ ký hợp lệ") == receiptExpected
                    && hasText(panel.node(), expectedText));
            return null;
        });
        Platform.runLater(present);
        present.get(10, TimeUnit.SECONDS);
    }

    private static boolean hasText(Parent parent, String expected) {
        for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Label label && expected.equals(label.getText())) {
                return true;
            }
            if (child instanceof Parent nested && hasText(nested, expected)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] changed(byte[] bytes, int offset, int value) {
        byte[] changed = bytes.clone();
        changed[offset] = (byte) value;
        return changed;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] signedReceipt(KeyPair key, String cardId) throws Exception {
        byte[] payload = ByteBuffer.allocate(34).put((byte) 1).put(HexFormat.of().parseHex(cardId))
                .putInt(1).put((byte) 1).putShort((short) 5).putShort((short) 5).put(new byte[16]).array();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
        signer.update(payload);
        byte[] signature = signer.sign();
        return ByteBuffer.allocate(35 + signature.length).put(payload).put((byte) signature.length)
                .put(signature).array();
    }

    private static byte[] statusBytes(byte[] aid, String cardId) {
        return ByteBuffer.allocate(43 + aid.length).put("LABACCESS".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1).put((byte) aid.length).put(aid).put((byte) 1)
                .put(HexFormat.of().parseHex(cardId)).putShort((short) 5).putInt(1)
                .put((byte) 0).put(new byte[16]).array();
    }

    private static ResponseAPDU response(byte[] data, int sw) {
        return new ResponseAPDU(ByteBuffer.allocate(data.length + 2).put(data).putShort((short) sw).array());
    }

    private static void check(String description, boolean passed) {
        if (!passed) {
            throw new AssertionError(description);
        }
        checks++;
        System.out.println("PASS " + description);
    }

    private static final class Fixture extends CardChannel implements AutoCloseable, CardGateway.ReceiptSink {
        private final char[] pin = RecoveryCardFixture.pin(8);
        private final char[] studentPin = RecoveryCardFixture.pin(6);
        private KeyPair cardKeys = keyPair();
        private final Map<String, byte[]> enrolledKeys = Map.of(CARD_ID, ReceiptVerifier.encodePoint(cardKeys.getPublic()));
        private byte[] status = statusBytes(AID, CARD_ID);
        private byte[] receipt = signedReceipt(cardKeys, CARD_ID);
        private final List<Integer> commands = new ArrayList<>();
        private final StringBuilder diagnostics = new StringBuilder();
        private int adminSw = Sw.OK;
        private int selectSw = Sw.OK;
        private int publicStatusSw = Sw.OK;
        private int keyReads;
        private int saves;
        private boolean shapeValid = true;
        private boolean studentAuthorized;
        private boolean failTransport;

        Fixture() throws Exception {
        }

        CardGateway gateway() throws Exception {
            CardGateway gateway = new CardGateway(this);
            gateway.setDiagnostics(diagnostics::append);
            if (!gateway.identifyWith(AID).ok()) {
                throw new AssertionError("fixture identify");
            }
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "fixture registry"));
            gateway.setReceiptSink(this);
            commands.clear();
            return gateway;
        }

        byte[] enrolledKey() {
            return enrolledKeys.get(CARD_ID).clone();
        }

        CardOperationResult readReceipt(CardGateway gateway) throws CardException {
            return gateway.readAdminLastReceipt(pin, CARD_ID, enrolledKey());
        }

        boolean leaks(String text) {
            byte[] bytes = new byte[pin.length];
            for (int i = 0; i < pin.length; i++) {
                bytes[i] = (byte) pin[i];
            }
            try {
                return text.contains(new String(pin)) || text.toUpperCase().contains(HexFormat.of().formatHex(bytes).toUpperCase());
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            int ins = command.getINS();
            commands.add(ins);
            if (ins == 0xA4) {
                studentAuthorized = false;
                return response(new byte[0], selectSw);
            }
            if (ins == 0x10) {
                byte[] data = studentAuthorized ? status : Arrays.copyOf(status, 20 + (status[10] & 0xFF));
                studentAuthorized = false;
                return response(data, publicStatusSw);
            }
            if (ins == 0x20) {
                studentAuthorized = true;
                return response(new byte[0], Sw.OK);
            }
            if (ins == 0x60) {
                boolean authorized = studentAuthorized;
                studentAuthorized = false;
                return response(authorized ? receipt : new byte[0],
                        authorized ? Sw.OK : Sw.SECURITY_NOT_SATISFIED);
            }
            if (ins == 0x61) {
                keyReads++;
                return response(ReceiptVerifier.encodePoint(cardKeys.getPublic()), Sw.OK);
            }
            if (ins != 0x11 && ins != 0x63) {
                throw new AssertionError("unexpected instruction " + ins);
            }
            byte[] expected = ByteBuffer.allocate(14).put((byte) 0x80).put((byte) ins)
                    .putShort((short) 0).put((byte) 8).array();
            for (int i = 0; i < pin.length; i++) {
                expected[5 + i] = (byte) pin[i];
            }
            byte[] actual = command.getBytes();
            shapeValid &= Arrays.equals(actual, expected) && command.getNe() == 256 && command.getNc() == 8;
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
            studentAuthorized = false;
            if (failTransport) {
                throw new CardException("fixture transport failure");
            }
            if (adminSw != Sw.OK) {
                byte[] echo = new byte[pin.length];
                for (int i = 0; i < pin.length; i++) {
                    echo[i] = (byte) pin[i];
                }
                try {
                    return response(echo, adminSw);
                } finally {
                    Arrays.fill(echo, (byte) 0);
                }
            }
            return response(ins == 0x11 ? status : receipt, adminSw);
        }

        @Override
        public CardGateway.ReceiptSave save(Receipt receipt) {
            saves++;
            return new CardGateway.ReceiptSave(true, "fixture");
        }

        @Override
        public CardGateway.LedgerCheck ledger(String cardId) {
            return new CardGateway.LedgerCheck(true, "fixture", cardId, 1, 5, null);
        }

        @Override
        public Card getCard() {
            return null;
        }

        @Override
        public int getChannelNumber() {
            return 0;
        }

        @Override
        public int transmit(ByteBuffer command, ByteBuffer response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            Arrays.fill(pin, '\0');
            Arrays.fill(studentPin, '\0');
        }
    }
}
