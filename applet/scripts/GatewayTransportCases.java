package vn.labaccess.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import javax.smartcardio.ResponseAPDU;

public final class GatewayTransportCases {
    private GatewayTransportCases() {
    }

    public static int run(int port, byte[] admin, byte[] student, Path evidence) throws Exception {
        CardGateway gateway = new CardGateway("127.0.0.1", port);
        PendingJournal journal = new PendingJournal(Path.of("n2-pending.txt"));
        Receipt[] saved = new Receipt[1];
        int[] saves = { 0 };
        gateway.setReceiptSink(new CardGateway.ReceiptSink() {
            public CardGateway.ReceiptSave save(Receipt receipt) {
                saved[0] = receipt;
                saves[0]++;
                return new CardGateway.ReceiptSave(true, "test ledger accepted verified receipt");
            }

            public CardGateway.LedgerCheck ledger(String cardId) {
                Receipt receipt = saved[0];
                if (receipt == null) {
                    return new CardGateway.LedgerCheck(true, "isolated test ledger", cardId, 1, 5, null);
                }
                return new CardGateway.LedgerCheck(true, "isolated test ledger", cardId,
                        receipt.counterAfter(), receipt.balanceAfter(), receipt.visitIdHex());
            }
        });
        try {
            require(gateway.connect().ok(), "gateway connect");
            require(gateway.identifyWith(HexFormat.of().parseHex("F04C414202EE01")).ok(), "identify");
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "isolated test enrollment"));
            require(gateway.grantCommand(admin, 5).transmit().getSW() == 0x9000, "setup GRANT");
            require(gateway.readAuthenticatedStatus(chars(student)).ok(), "before state");
            require(gateway.status().balance() == 5 && gateway.status().counter() == 1, "before values");
            require(gateway.readCardPublicKey().ok(), "public key");
            byte[] visit = HexFormat.of().parseHex("39393939393939393939393939393939");
            CardGateway.TransactionPlan plan = new CardGateway.TransactionPlan(
                    new ReceiptExpectation(HexFormat.of().parseHex("A1B2C3D411223344"), 2,
                            Receipt.OPERATION_START, visit), -1, 4);
            require(gateway.verifyStudentPin(chars(student)).ok(), "authorize START");
            CardOperationResult result = gateway.runStateTransaction(journal, plan, () -> {
                require(journal.load().valid() && journal.load().status() == PendingJournal.Status.PENDING,
                        "journal must be PENDING before wire START");
                System.out.println("N2 BEFORE-START journal=PENDING");
                ResponseAPDU response = gateway.visitCommand((byte) 0x51, visit).transmit();
                System.out.printf("N2 CALLER expected=6C06 actual=%04X%n", response.getSW());
                return response;
            }, chars(student));
            require(!result.ok(), "unexpected successful START result");
            int callerSw = gateway.lastResponseSw();
            boolean preservedSw = callerSw == 0x6C06;
            require(gateway.reconciliation().state() == Reconciliation.State.UNKNOWN, "state must be UNKNOWN");
            require(journal.load().status() == PendingJournal.Status.PENDING && saves[0] == 0,
                    "journal stays pending without ledger write");
            byte[] pending = Files.readAllBytes(journal.file());
            Files.write(evidence.resolve("pending-journal.txt"), pending);
            System.out.printf("N2-GATEWAY-UNKNOWN | %s | SW=%04X journal=PENDING reconciliation=UNKNOWN saves=0%n",
                    preservedSw ? "PASS" : "FAIL", callerSw);
            int[] sentAgain = { 0 };
            CardOperationResult blocked = gateway.runStateTransaction(journal, plan, () -> {
                sentAgain[0]++;
                return gateway.visitCommand((byte) 0x51, visit).transmit();
            }, chars(student));
            require(!blocked.ok() && sentAgain[0] == 0
                    && Arrays.equals(pending, Files.readAllBytes(journal.file())), "pending transaction replayed");
            System.out.println("N2-GATEWAY-BLOCK-REPLAY | PASS | commandCalls=0 journalUnchanged=true");
            CardOperationResult recovery = gateway.reconcilePending(journal, chars(student));
            require(recovery.ok() && gateway.lastReceiptSignatureOk(), "receipt reconciliation: " + recovery.text());
            Receipt receipt = gateway.lastReceipt();
            require(receipt.counterAfter() == 2 && receipt.balanceAfter() == 4 && receipt.delta() == -1
                    && receipt.operation() == Receipt.OPERATION_START && Arrays.equals(visit, receipt.visitId()),
                    "reconciled receipt values");
            require(saves[0] == 1 && !journal.load().present()
                    && gateway.reconciliation().state() == Reconciliation.State.KNOWN, "journal completion");
            Files.writeString(evidence.resolve("reconciled-receipt.txt"),
                    "payload=" + receipt.payloadHex() + "\nsignature=" + receipt.signatureHex() + "\n");
            require(gateway.readAuthenticatedStatus(chars(student)).ok(), "after state");
            require(gateway.status().balance() == 4 && gateway.status().counter() == 2
                    && gateway.status().hasActiveVisit(), "state committed more than once");
            require(!gateway.reconcilePending(journal, chars(student)).ok() && saves[0] == 1, "duplicate recovery");
            System.out.println("N2-GATEWAY-RECONCILE | PASS | signature=valid counter=2 balance=4 active=true"
                    + " journal=absent reconciliation=KNOWN ledgerSaves=1");
            require(gateway.readProfileWithPin(chars(student)).ok(), "profile read over new transport");
            require(gateway.readProfileCryptoMetaWithPin(chars(student)).ok(), "metadata over new transport");
            byte[] photo = new byte[4096];
            for (int index = 0; index < photo.length; index++) photo[index] = (byte) (index + 57);
            require(gateway.uploadPhoto(photo, chars(admin)).ok(), "photo upload over new transport");
            require(gateway.readPhoto(chars(student)).ok() && Arrays.equals(photo, gateway.lastPhoto()),
                    "photo roundtrip over new transport");
            gateway.disconnect();
            require(gateway.connect().ok(), "reconnect");
            require(gateway.identifyWith(HexFormat.of().parseHex("F04C414202EE01")).ok(), "reidentify");
            require(gateway.readAuthenticatedStatus(chars(student)).ok()
                    && gateway.status().counter() == 2 && gateway.status().balance() == 4, "reconnect state");
            System.out.println("N2-GATEWAY-REGRESSION | PASS | profile=OK metadata=OK photoBytes=4096 reconnectState=2/4");
            System.out.println("N2 ledger and enrollment use isolated test adapters; card, gateway and journal are real");
            System.out.println("APDU_ERRORS cases=4 failed=" + (preservedSw ? 0 : 1) + " skipped=0");
            return preservedSw ? 0 : 1;
        } finally {
            gateway.disconnect();
            if (journal.load().present()) journal.remove();
        }
    }

    private static char[] chars(byte[] pin) {
        char[] chars = new char[pin.length];
        for (int index = 0; index < pin.length; index++) chars[index] = (char) pin[index];
        return chars;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
