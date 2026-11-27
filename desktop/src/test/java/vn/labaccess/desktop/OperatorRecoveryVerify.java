package vn.labaccess.desktop;

import java.nio.file.Files;
import java.util.Arrays;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptCodec;
import vn.labaccess.desktop.card.ReceiptExpectation;
import vn.labaccess.desktop.card.Reconciliation;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.ConfirmedOperationRefresh;

public final class OperatorRecoveryVerify {
    private static int checks;

    public static void main(String[] args) throws Exception {
        receiptRecovery();
        confirmedRefresh();
        brokenTransport();
        receiptBackendAccess();
        System.out.println("OPERATOR RECOVERY PASS: " + checks + " checks");
    }

    private static void receiptRecovery() throws Exception {
        for (int mode : new int[] {1, 2, 3}) {
            RecoveryCardFixture card = new RecoveryCardFixture();
            CardGateway gateway = card.gateway();
            char[] pin = RecoveryCardFixture.pin(6);
            try {
                card.receiptMode = mode;
                check("bad receipt blocks transactions " + mode, !gateway.readOperatorCard(pin).ok()
                        && !gateway.reconciliation().transactionAllowed()
                        && gateway.reconciliation().receiptUnverified()
                        && !gateway.status().studentStateAvailable());
                var gates = SessionGates.of(new SessionGates.State(false, true, true, false,
                        false, false, false, true, null, gateway.status()));
                check("read remains open without pending " + mode,
                        gates.readReceipt() == null && gates.grant() != null && gates.reconcile() != null);
                check("reidentify does not erase verification lock " + mode,
                        gateway.identify().ok() && !gateway.reconciliation().transactionAllowed());
                card.receiptMode = 0;
                card.ledgerAvailable = false;
                check("backend outage cannot unlock " + mode, !gateway.readOperatorCard(pin).ok()
                        && !gateway.reconciliation().transactionAllowed());
                card.ledgerAvailable = true;
                card.ledgerOffset = 1;
                check("counter mismatch cannot unlock " + mode, !gateway.readOperatorCard(pin).ok()
                        && !gateway.reconciliation().transactionAllowed());
                card.ledgerOffset = 0;
                card.ledgerVisit = "112233445566778899AABBCCDDEEFF00";
                check("visit mismatch cannot unlock " + mode, !gateway.readOperatorCard(pin).ok());
                card.ledgerVisit = null;
                check("verified read restores ready transaction state " + mode, gateway.readOperatorCard(pin).ok()
                        && gateway.reconciliation().transactionAllowed() && card.stateCommands == 0);
                gateway.markTransactionUnknown("pending transaction");
                card.receiptMode = mode;
                gateway.readLastReceipt(null);
                check("receipt failure never downgrades pending UNKNOWN " + mode,
                        gateway.reconciliation().state() == Reconciliation.State.UNKNOWN);
                card.receiptMode = 0;
                gateway.readOperatorCard(pin);
                check("normal read never clears pending UNKNOWN " + mode,
                        gateway.reconciliation().state() == Reconciliation.State.UNKNOWN);
            } finally {
                Arrays.fill(pin, '\0');
            }
        }
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        char[] pin = RecoveryCardFixture.pin(6);
        try {
            card.receiptMode = 1;
            gateway.readOperatorCard(pin);
            card.receiptMode = 4;
            check("missing receipt at nonzero counter stays locked", !gateway.readOperatorCard(pin).ok()
                    && !gateway.reconciliation().transactionAllowed());
            card.counter = 0;
            card.balance = 0;
            check("authenticated zero baseline and matching ledger can recover", gateway.readOperatorCard(pin).ok()
                    && gateway.reconciliation().transactionAllowed());
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    private static void confirmedRefresh() throws Exception {
        var directory = Files.createTempDirectory("labaccess-confirmed-refresh");
        PendingJournal journal = new PendingJournal(directory.resolve("pending.txt"));
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        char[] pin = RecoveryCardFixture.pin(6);
        try {
            CardOperationResult result = gateway.runStateTransaction(journal,
                    new CardGateway.TransactionPlan(new ReceiptExpectation(null, 2, Receipt.OPERATION_GRANT, null),
                            5, 10), () -> card.transmit(new CommandAPDU(0x80, 0x50, 0, 0)), pin);
            check("transaction completed before refresh", result.ok() && !journal.load().present()
                    && gateway.reconciliation().transactionAllowed() && card.stateCommands == 1);
            for (int mode : new int[] {0, 1, 2, 3}) {
                card.pinSw = mode == 1 ? 0x63C2 : Sw.OK;
                card.statusSw = mode == 2 ? Sw.INTERNAL_ERROR : Sw.OK;
                card.failTransport = mode == 3;
                CardOperationResult refreshed = ConfirmedOperationRefresh.read(gateway, result, pin);
                check("confirmed result remains successful after refresh " + mode,
                        refreshed.ok() && card.stateCommands == 1 && !journal.load().present());
                check("refresh warning accurately describes state " + mode,
                        mode == 0 ? refreshed.warning() == null && gateway.status().studentStateAvailable()
                                : refreshed.warning() != null && !gateway.status().studentStateAvailable());
            }
            check("transport failure uses reconnect guidance", gateway.needsReconnect()
                    && !gateway.isReady() && !gateway.isConnected());
            check("failed operation cannot be promoted", !ConfirmedOperationRefresh.read(gateway,
                    CardOperationResult.fail("unconfirmed"), pin).ok());
        } finally {
            Arrays.fill(pin, '\0');
            Files.deleteIfExists(journal.file());
            Files.delete(directory);
        }
    }

    private static void brokenTransport() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            RecoveryCardFixture card = new RecoveryCardFixture();
            CardGateway gateway = card.gateway();
            card.failTransport = !timeout;
            card.hold = timeout;
            try {
                gateway.transmitStatus();
                throw new AssertionError("expected failure");
            } catch (CardException expected) {
                check("failure invalidates readiness timeout=" + timeout,
                        !gateway.isConnected() && !gateway.isReady() && gateway.needsReconnect());
            } finally {
                card.release.countDown();
            }
            try {
                gateway.transmitStatus();
                throw new AssertionError("broken channel reused");
            } catch (IllegalStateException expected) {
                check("reuse blocked before APDU timeout=" + timeout, expected.getMessage().contains("Kết nối lại"));
            }
            gateway.disconnect();
            check("disconnect clears recovery requirement timeout=" + timeout, !gateway.needsReconnect());
        }
    }

    private static void receiptBackendAccess() throws Exception {
        RecoveryCardFixture card = new RecoveryCardFixture();
        Receipt receipt = ReceiptCodec.parse(card.receiptBytes()).receipt();
        try (RecoveryBackendFixture server = new RecoveryBackendFixture()) {
            BackendClient client = new BackendClient(server.url());
            client.checkDatabase();
            check("successful backend access records timestamp", client.lastAccess().available()
                    && client.lastAccess().checkedAt() != null);
            for (int status : new int[] {422, 503, 201}) {
                server.status = status;
                try {
                    client.verifyReceipt(receipt);
                    throw new AssertionError("fixture must reject or return invalid acknowledgement");
                } catch (BackendClient.Failure expected) {
                    check("receipt access updates HTTP status " + status, client.lastAccess().httpStatus() == status
                            && client.lastAccess().available() == (status < 500));
                    check("reachability never claims receipt persisted " + status,
                            expected.kind() == (status == 201 ? BackendClient.Kind.INVALID_RESPONSE
                                    : BackendClient.Kind.HTTP_REJECTED));
                }
            }
            server.close();
            try {
                client.verifyReceipt(receipt);
                throw new AssertionError("backend stopped");
            } catch (BackendClient.Failure expected) {
                check("receipt transport failure removes stale connectivity", !client.lastAccess().available()
                        && client.lastAccess().httpStatus() == 0 && expected.kind() == BackendClient.Kind.TRANSPORT);
            }
        }
    }

    static void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
}
