package vn.labaccess.desktop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.ui.shared.EnrollmentRunner;
import vn.labaccess.desktop.ui.shared.HistoryPanel;
import vn.labaccess.desktop.ui.shared.OperationLog;
import vn.labaccess.desktop.ui.shared.SystemConnectionPanel;

import vn.labaccess.desktop.ui.receipt.ReceiptPanel;

public final class OperatorBeforeAfterVerify {
    private static final String CARD = RecoveryCardFixture.CARD_ID;
    private static boolean baseline;

    public static void main(String[] args) throws Exception {
        baseline = args.length != 0 && args[0].equals("baseline");
        Platform.startup(() -> { });
        try {
            receiptReadGate();
            refreshResult();
            transportReady();
            fx(() -> { historyWarning(); logPreference(); pendingCardHint(); return null; });
            backendBadge();
            System.out.println("BEFORE_AFTER " + (baseline ? "BASELINE" : "FIXED") + " 7/7 expected outcomes");
        } finally {
            Platform.exit();
        }
    }

    private static void receiptReadGate() throws Exception {
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        card.receiptMode = 1;
        gateway.readLastReceipt(null);
        var gates = SessionGates.of(new SessionGates.State(false, true, true, false,
                gateway.reconciliation().transactionAllowed(), false, false, true, null, gateway.status()));
        outcome("1 recovery read remains available", gates.readReceipt() == null && gates.grant() != null);
    }

    private static void refreshResult() throws Exception {
        outcome("2 confirmed transaction preserves success", AdminGrantVerify.confirmedGrantSurvivesRefreshFailure());
    }

    private static void transportReady() throws Exception {
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        card.failTransport = true;
        try {
            gateway.transmitStatus();
            throw new AssertionError("expected transport failure");
        } catch (javax.smartcardio.CardException expected) {
            outcome("3 transport failure invalidates readiness", !gateway.isReady() && !gateway.isConnected());
        }
    }

    private static void historyWarning() {
        HistoryPanel panel = new HistoryPanel(new BackendClient("http://127.0.0.1:1"),
                new HistoryPanel.Runner() {
                    @Override public <T> void submit(String label, Callable<T> work,
                            java.util.function.Consumer<T> success, java.util.function.Consumer<Exception> failure) {
                        throw new AssertionError("no request expected");
                    }
                }, () -> CARD, ignored -> { });
        var entry = new BackendClient.HistoryEntry("fixture", 1, "GRANT", 5, 5, "verified", java.time.Instant.now());
        panel.show(new BackendClient.HistoryLoad(new BackendClient.TransactionHistory(CARD, java.util.List.of(entry)),
                new BackendClient.LedgerSnapshot(CARD, 1, 99, null)));
        String mismatch = ((Label) panel.node().lookup("#history-verdict")).getText();
        panel.showFailure(new IllegalStateException("fixture load failure"));
        outcome("4 failed reload preserves mismatch", ((Label) panel.node().lookup("#history-verdict"))
                .getText().equals(mismatch));
    }

    private static void logPreference() throws Exception {
        App app = new App();
        TitledPane pane;
        if (baseline) {
            pane = new TitledPane();
            pane.setExpanded(false);
            set(app, "logPane", pane);
            set(app, "logArea", new TextArea());
            Method log = App.class.getDeclaredMethod("showLogLine", String.class, String.class);
            log.setAccessible(true);
            log.invoke(app, "[FAIL] VERIFY_STUDENT_PIN: SW=63C2", "fixture PIN failure");
        } else {
            OperationLog log = (OperationLog) get(app, "operationLog");
            pane = log.createPane();
            pane.setExpanded(false);
            log.append("[FAIL] VERIFY_STUDENT_PIN: SW=63C2");
        }
        outcome("7 PIN failure preserves collapsed log", !pane.isExpanded());
        Object owner = baseline ? app : get(app, "taskRunner");
        ((java.util.concurrent.ExecutorService) get(owner, baseline ? "worker" : "executor")).shutdownNow();
    }

    private static void pendingCardHint() throws Exception {
        CardGateway gateway = new RecoveryCardFixture().gateway();
        var entry = new PendingJournal.Entry("0011223344556677", 2, Receipt.OPERATION_GRANT, 5, 10, null, 1);
        var loaded = new PendingJournal.Loaded(true, true, entry, PendingJournal.Status.PENDING, null);
        ReceiptPanel panel = new ReceiptPanel(gateway, (label, work, callback) -> {
            throw new AssertionError("wrong card must not start work");
        }, ignored -> { }, pendingSession(loaded));
        panel.offerPendingReconciliation();
        panel.applyLock(false, "pending", null);
        boolean visible = panel.node().lookupAll(".label").stream().filter(node -> node instanceof Label)
                .map(node -> (Label) node).anyMatch(label -> label.isVisible()
                        && label.getText().contains(entry.cardId()) && label.getText().contains(CARD));
        outcome("6 wrong card shows expected and actual IDs", visible);
    }

    private static ReceiptPanel.Session pendingSession(PendingJournal.Loaded loaded) throws Exception {
        Runnable noOp = () -> { };
        java.util.function.Consumer<Boolean> finished = ok -> { };
        Object[] arguments = {
            (java.util.function.BooleanSupplier) () -> true,
            (java.util.function.Supplier<PendingJournal.Loaded>) () -> loaded,
            Path.of("fixture-unused"),
            (java.util.function.Supplier<char[]>) () -> { throw new AssertionError("wrong card must not read PIN"); },
            noOp,
            (ReceiptPanel.PendingReconcile) pin -> { throw new AssertionError("wrong card reconciled"); },
            noOp, noOp, noOp, noOp, finished, finished, noOp
        };
        var values = new java.util.ArrayList<>(java.util.Arrays.asList(arguments));
        if (baseline) {
            values.add(10, noOp);
        }
        var constructor = ReceiptPanel.Session.class.getDeclaredConstructors()[0];
        if (!baseline && constructor.getParameterCount() == arguments.length + 1) {
            values.add(6, null);
        }
        constructor.setAccessible(true);
        return (ReceiptPanel.Session) constructor.newInstance(values.toArray());
    }

    private static void backendBadge() throws Exception {
        try (RecoveryBackendFixture server = new RecoveryBackendFixture()) {
            BackendClient client = new BackendClient(server.url());
            SystemConnectionPanel panel = fx(() -> new SystemConnectionPanel(client,
                    new CardGateway("127.0.0.1", 1), new EnrollmentRunner() {
                        @Override public <T> void run(String label, Callable<T> work,
                                java.util.function.Consumer<T> success, java.util.function.Consumer<Exception> failure) {
                            throw new AssertionError("no check button expected");
                        }
                    }, (ok, message) -> { }));
            client.historyWithLedger(CARD);
            fx(() -> { refreshBadge(panel); return null; });
            server.close();
            try {
                client.historyWithLedger(CARD);
                throw new AssertionError("expected backend outage");
            } catch (BackendClient.Failure expected) {
                fx(() -> { refreshBadge(panel); return null; });
            }
            outcome("5 latest transport failure updates badge", fx(() -> ((Label) panel.statusNode())
                    .getText().startsWith("Mất kết nối")));
        }
    }

    private static void refreshBadge(SystemConnectionPanel panel) throws Exception {
        if (baseline) {
            Method method = panel.getClass().getDeclaredMethod("showEnrollmentStatus", String.class);
            method.setAccessible(true);
            method.invoke(panel, "da-dang-ky");
        } else {
            Method method = panel.getClass().getDeclaredMethod("showBackendAccess");
            method.setAccessible(true);
            method.invoke(panel);
        }
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void outcome(String name, boolean fixed) {
        System.out.println((fixed ? "FIXED " : "REPRODUCED ") + name);
        if (fixed == baseline) throw new AssertionError("unexpected " + name);
    }
}
