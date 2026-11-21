package vn.labaccess.desktop;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;

public final class KioskBoundaryVerify {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            if (args.length == 1 && args[0].equals("closure")) {
                journalClosureChecks(true);
                journalClosureChecks(false);
                System.out.println("KIOSK JOURNAL CLOSURE PASS: " + checks + " checks");
                return;
            }
            if (args.length == 1 && args[0].equals("review")) {
                receiptRetryChecks();
                adminAuditChecks();
                System.out.println("KIOSK REVIEW PASS: " + checks + " checks");
                return;
            }
            promptChecks();
            serviceChecks();
            workspaceChecks();
            System.out.println("KIOSK BOUNDARY PASS: " + checks + " checks");
        } finally {
            Platform.exit();
        }
    }

    private static void journalClosureChecks(boolean ledgerMatches) throws Exception {
        String label = ledgerMatches ? "CLOSED" : "PENDING";
        Path directory = Files.createTempDirectory("kiosk-closure-log-");
        String previousBackend = System.getProperty("labaccess.backend");
        CardTaskRunner tasks = new CardTaskRunner();
        RecoveryCardFixture card = new RecoveryCardFixture();
        AtomicInteger adminCommands = new AtomicInteger();
        String payload = ReceiptCodec.parse(card.receiptBytes()).receipt().payloadHex();
        try (RecoveryBackendFixture backend = new RecoveryBackendFixture((request, body) -> {
            boolean ledger = request.contains("/receipts/ledger/");
            String result = ledger ? "{\"cardId\":\"" + card.cardId + "\",\"lastCounter\":"
                    + (ledgerMatches ? 1 : 2) + ",\"lastBalance\":5,\"activeVisitId\":null}"
                    : "{\"cardId\":\"" + card.cardId + "\",\"counterAfter\":1,\"payload\":\"" + payload + "\"}";
            if (!request.startsWith("GET ")) throw new AssertionError("unexpected mutation HTTP");
            return new RecoveryBackendFixture.Reply(200, result);
        })) {
            System.setProperty("labaccess.backend", backend.url());
            CardSession session = new CardSession(directory.resolve("journal.txt"), ignored -> { });
            javax.smartcardio.CardChannel channel = new javax.smartcardio.CardChannel() {
                @Override public javax.smartcardio.ResponseAPDU transmit(javax.smartcardio.CommandAPDU command)
                        throws javax.smartcardio.CardException {
                    if (command.getINS() != 0x11) return card.transmit(command);
                    adminCommands.incrementAndGet();
                    return card.transmit(new javax.smartcardio.CommandAPDU(0x80, 0x10, 0, 0, 256));
                }
                @Override public javax.smartcardio.Card getCard() { return null; }
                @Override public int getChannelNumber() { return 0; }
                @Override public int transmit(java.nio.ByteBuffer command, java.nio.ByteBuffer response) {
                    throw new UnsupportedOperationException();
                }
                @Override public void close() { }
            };
            Object transport = field(session.gateway(), "transport");
            set(transport, "channel", channel);
            set(transport, "connectedFlag", true);
            check(label + " fixture identifies", session.gateway().identify().ok());
            PendingJournal journal = new PendingJournal(session.journalFile());
            byte[] visit = new byte[16];
            Arrays.fill(visit, (byte) 1);
            check(label + " pending fixture created", journal.writePending(new PendingJournal.Entry(
                    card.cardId, 1, Receipt.OPERATION_START, -1, 4, visit, 1)));
            String original = Files.readString(journal.file());
            char[] student = RecoveryCardFixture.pin(6);
            try { check(label + " Student state read", session.gateway().readAuthenticatedStatus(student).ok()); }
            finally { Arrays.fill(student, '\0'); }
            var proof = new CardOperationResult.SupersededProof(original, session.gateway().status(), 1, payload, payload);
            KioskOperations operations = new KioskOperations(session);
            KioskWorkspace workspace = fx(() -> new KioskWorkspace(session, operations, tasks,
                    (name, work, finished) -> { throw new AssertionError("unexpected UI task"); },
                    KioskPinPrompt.Request::close, (value, pin) -> { throw new AssertionError("unexpected dialog"); },
                    new EnrollmentRunner() {
                        @Override public <T> void run(String name, Callable<T> work,
                                java.util.function.Consumer<T> success, java.util.function.Consumer<Exception> failure) {
                            throw new AssertionError("unexpected UI HTTP");
                        }
                    }));
            char[] admin = RecoveryCardFixture.pin(8);
            SupersededRecovery.Confirmation result = operations.confirm(proof, admin);
            check(label + " authentication is one 11/9000 and PIN is wiped", adminCommands.get() == 1
                    && result.adminStatusWord().orElse(-1) == Sw.OK && allZero(admin));
            check(label + " result reflects closure, not merely SW", result.result().ok() == ledgerMatches);
            check(label + " file state matches result", ledgerMatches ? !Files.exists(journal.file())
                    : Files.readString(journal.file()).equals(original) && journal.load().status() == PendingJournal.Status.PENDING);
            fx(() -> {
                workspace.logOperatorResponse(proof, result);
                String logs = ((javafx.scene.control.TextArea) field(workspace, "logArea")).getText();
                check(label + " authentication audit remains", logs.contains("GET_ADMIN_STATUS SW=9000"));
                check(label + " conclusion only on successful closure", logs.contains("Đóng journal SUPERSEDED") == ledgerMatches);
                if (ledgerMatches) {
                    check(label + " closure log includes reason and cardId", logs.contains("lý do=Nhân viên xác nhận giao dịch khác đã chiếm counter")
                            && logs.contains("cardId=" + card.cardId));
                    check(label + " closure log includes expected counter and occupied canonical payload",
                            logs.contains("counterChờ=1") && logs.contains("payloadCanonicalChiếm=" + payload));
                } else {
                    check(label + " no closure evidence is claimed for PENDING", !logs.contains("payloadCanonicalChiếm="));
                }
                return null;
            });
        } finally {
            ((java.util.concurrent.ExecutorService) field(tasks, "executor")).shutdownNow();
            restore("labaccess.backend", previousBackend);
            Files.deleteIfExists(directory.resolve("journal.txt"));
            Files.delete(directory);
        }
    }

    private static void receiptRetryChecks() throws Exception {
        Path directory = Files.createTempDirectory("kiosk-receipt-retry-");
        String previousJournal = System.getProperty("labaccess.journal");
        String previousBackend = System.getProperty("labaccess.backend");
        KioskApp app = new KioskApp();
        javafx.stage.Stage[] stage = {null};
        java.util.concurrent.ExecutorService[] executor = {null};
        try (RecoveryBackendFixture backend = new RecoveryBackendFixture()) {
            System.setProperty("labaccess.journal", directory.resolve("journal.txt").toString());
            System.setProperty("labaccess.backend", backend.url());
            RecoveryCardFixture card = new RecoveryCardFixture();
            card.receiptMode = 3;
            KioskWorkspace workspace = fx(() -> {
                stage[0] = new javafx.stage.Stage();
                app.start(stage[0]);
                CardSession session = (CardSession) field(app, "session");
                Object transport = field(session.gateway(), "transport");
                set(transport, "channel", card);
                set(transport, "connectedFlag", true);
                check("receipt fixture identifies", session.gateway().identify().ok());
                executor[0] = (java.util.concurrent.ExecutorService) field(field(app, "tasks"), "executor");
                KioskWorkspace value = (KioskWorkspace) field(app, "workspace");
                value.synchronizeConnection();
                value.refresh();
                return value;
            });
            CardSession session = (CardSession) field(app, "session");
            char[] pin = RecoveryCardFixture.pin(6);
            try {
                infoClick(workspace, pin);
                idle(executor[0]);
                fx(() -> {
                    check("bad receipt becomes RECEIPT_UNVERIFIED",
                            session.gateway().reconciliation().receiptUnverified());
                    check("INFO remains enabled for receipt verification", !((Button) field(workspace, "info")).isDisabled());
                    check("START END CHANGE remain locked before verification", ((Button) field(workspace, "start")).isDisabled()
                            && ((Button) field(workspace, "end")).isDisabled() && ((Button) field(workspace, "change")).isDisabled());
                    return null;
                });
                card.receiptMode = 0;
                infoClick(workspace, pin);
                idle(executor[0]);
                fx(() -> {
                    check("second INFO verifies receipt and returns KNOWN", session.gateway().reconciliation().transactionAllowed()
                            && session.gateway().status().studentStateAvailable());
                    check("mutations unlock only after verification", !((Button) field(workspace, "start")).isDisabled()
                            && !((Button) field(workspace, "end")).isDisabled() && !((Button) field(workspace, "change")).isDisabled());
                    check("receipt retry sends zero mutation APDU", card.stateCommands == 0 && !Files.exists(session.journalFile()));
                    session.gateway().reconciliation().markUnknown("fixture UNKNOWN");
                    workspace.refresh();
                    check("UNKNOWN still blocks INFO", ((Button) field(workspace, "info")).isDisabled());
                    session.gateway().reconciliation().markReceiptUnverified("fixture must not downgrade UNKNOWN");
                    check("INFO worker also rejects UNKNOWN", !workspace.operations().execute(new KioskPinPrompt.Request(
                            KioskOperations.Action.INFO, pin.clone(), null)).result().ok());
                    return null;
                });
            } finally {
                Arrays.fill(pin, '\0');
            }
        } finally {
            if (executor[0] != null) {
                executor[0].submit(() -> {
                    try { ((CardSession) field(app, "session")).disconnect(); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }).get(10, TimeUnit.SECONDS);
                executor[0].shutdownNow();
            }
            if (stage[0] != null) fx(() -> { stage[0].hide(); return null; });
            restore("labaccess.journal", previousJournal);
            restore("labaccess.backend", previousBackend);
            Files.deleteIfExists(directory.resolve("journal.txt"));
            Files.delete(directory);
        }
    }

    private static void infoClick(KioskWorkspace workspace, char[] pin) throws Exception {
        fx(() -> {
            ((Button) field(workspace, "info")).fire();
            KioskPinPrompt prompt = (KioskPinPrompt) field(workspace, "prompt");
            if (!prompt.isOpen()) throw new AssertionError("INFO prompt did not open");
            ((PasswordField) field(prompt, "pin")).setText(new String(pin));
            ((Button) field(prompt, "submit")).fire();
            return null;
        });
    }

    private static void idle(java.util.concurrent.ExecutorService executor) throws Exception {
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        executor.submit(() -> Platform.runLater(completed::countDown));
        if (!completed.await(15, TimeUnit.SECONDS)) throw new AssertionError("worker/FX timeout");
    }

    private static void adminAuditChecks() throws Exception {
        Path directory = Files.createTempDirectory("kiosk-admin-audit-");
        CardTaskRunner tasks = new CardTaskRunner();
        try {
            CardSession session = new CardSession(directory.resolve("journal.txt"), ignored -> { });
            RecoveryCardFixture card = new RecoveryCardFixture();
            AtomicInteger adminSw = new AtomicInteger(0x63C4);
            AtomicInteger adminCommands = new AtomicInteger();
            javax.smartcardio.CardChannel channel = new javax.smartcardio.CardChannel() {
                @Override public javax.smartcardio.ResponseAPDU transmit(javax.smartcardio.CommandAPDU command)
                        throws javax.smartcardio.CardException {
                    if (command.getINS() != 0x11) return card.transmit(command);
                    adminCommands.incrementAndGet();
                    return RecoveryCardFixture.response(new byte[0], adminSw.get());
                }
                @Override public javax.smartcardio.Card getCard() { return null; }
                @Override public int getChannelNumber() { return 0; }
                @Override public int transmit(java.nio.ByteBuffer command, java.nio.ByteBuffer response) {
                    throw new UnsupportedOperationException();
                }
                @Override public void close() { }
            };
            Object transport = field(session.gateway(), "transport");
            set(transport, "channel", channel);
            set(transport, "connectedFlag", true);
            check("Admin fixture identifies", session.gateway().identify().ok());
            byte[] visit = new byte[16];
            Arrays.fill(visit, (byte) 1);
            PendingJournal journal = new PendingJournal(session.journalFile());
            check("Admin pending fixture created", journal.writePending(new PendingJournal.Entry(
                    RecoveryCardFixture.CARD_ID, 1, Receipt.OPERATION_START, -1, 4, visit, 1)));
            byte[] original = Files.readAllBytes(journal.file());
            KioskOperations operations = new KioskOperations(session);
            KioskWorkspace workspace = fx(() -> new KioskWorkspace(session, operations, tasks,
                    (label, work, finished) -> { throw new AssertionError("unexpected task"); },
                    KioskPinPrompt.Request::close, (proof, pin) -> { throw new AssertionError("unexpected dialog"); },
                    new EnrollmentRunner() {
                        @Override public <T> void run(String label, Callable<T> work,
                                java.util.function.Consumer<T> success, java.util.function.Consumer<Exception> failure) {
                            throw new AssertionError("unexpected HTTP");
                        }
                    }));
            fx(() -> { workspace.synchronizeConnection(); return null; });
            for (int response : new int[] {0x63C4, Sw.OK}) {
                char[] student = RecoveryCardFixture.pin(6);
                try { check("Admin proof uses authenticated Student state", session.gateway().readAuthenticatedStatus(student).ok()); }
                finally { Arrays.fill(student, '\0'); }
                var proof = new CardOperationResult.SupersededProof(Files.readString(journal.file()),
                        session.gateway().status(), 1, "fixture", "fixture");
                long revision = fx(() -> { workspace.synchronizeConnection(); return workspace.revision(); });
                char[] admin = RecoveryCardFixture.pin(8);
                adminSw.set(response);
                int before = adminCommands.get();
                SupersededRecovery.Confirmation confirmation = operations.confirm(proof, admin);
                fx(() -> {
                    workspace.logOperatorResponse(proof.state().cardId(), confirmation);
                    workspace.synchronizeConnection();
                    workspace.operatorCompleted(proof, revision, new CardTaskRunner.Outcome<>(confirmation, null));
                    String logs = ((javafx.scene.control.TextArea) field(workspace, "logArea")).getText();
                    String line = logs.lines().filter(value -> value.contains(String.format("SW=%04X", response)))
                            .reduce((left, right) -> right).orElseThrow();
                    java.time.Instant.parse(line.substring(0, line.indexOf(" | ")));
                    check(String.format("Admin %04X audit includes time cardId SW remaining", response),
                            line.contains("cardId=" + proof.state().cardId()) && line.contains("lượt còn lại="
                                    + (response == 0x63C4 ? "4" : "chưa biết")));
                    check("Admin response sends exactly one command and wipes PIN", adminCommands.get() == before + 1 && allZero(admin));
                    check("Admin failure keeps journal byte-for-byte", Arrays.equals(original, Files.readAllBytes(journal.file())));
                    if (response == 0x63C4) {
                        check("same-session PIN failure updates four known tries", (int) field(workspace, "adminTries") == 4);
                    } else {
                        check("malformed 9000 loses identity but still logs SW", !session.gateway().isReady()
                                && session.gateway().status() == null);
                        check("lost identity cannot update retry UI", (int) field(workspace, "adminTries") == -1);
                    }
                    return null;
                });
            }
        } finally {
            ((java.util.concurrent.ExecutorService) field(tasks, "executor")).shutdownNow();
            Files.deleteIfExists(directory.resolve("journal.txt"));
            Files.delete(directory);
        }
    }

    private static void restore(String property, String previous) {
        if (previous == null) System.clearProperty(property);
        else System.setProperty(property, previous);
    }

    private static void promptChecks() throws Exception {
        AtomicReference<KioskPinPrompt.Request> submitted = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        char[] pin = RecoveryCardFixture.pin(6);
        char[] next = RecoveryCardFixture.pin(6);
        try {
            fx(() -> {
                KioskPinPrompt prompt = new KioskPinPrompt(request -> {
                    count.incrementAndGet();
                    submitted.set(request);
                }, () -> { }, ignored -> { });
                prompt.open(KioskOperations.Action.START);
                ((PasswordField) field(prompt, "pin")).setText(new String(pin));
                ((Button) field(prompt, "submit")).fire();
                check("one Student form submits exactly one operation", count.get() == 1
                        && submitted.get().action() == KioskOperations.Action.START);
                check("submission clears and hides all input fields", !prompt.isOpen()
                        && inputs(prompt.node()).stream().allMatch(value -> value.getText().isEmpty()));
                char[] captured = submitted.get().pin();
                check("request carries the entered PIN only until completion", Arrays.equals(captured, pin));
                submitted.get().close();
                check("request close wipes Student copy", allZero(captured));
                prompt.open(KioskOperations.Action.CHANGE);
                ((PasswordField) field(prompt, "pin")).setText(new String(pin));
                ((PasswordField) field(prompt, "newPin")).setText(new String(next));
                ((PasswordField) field(prompt, "confirmation")).setText(new String(pin));
                ((Button) field(prompt, "submit")).fire();
                check("mismatched new PIN never submits", count.get() == 1 && prompt.isOpen());
                ((PasswordField) field(prompt, "confirmation")).setText(new String(next));
                prompt.setBusy(true);
                ((Button) field(prompt, "submit")).fire();
                check("busy locks every input and prevents submission", count.get() == 1
                        && inputs(prompt.node()).stream().allMatch(PasswordField::isDisabled));
                prompt.setBusy(false);
                ((Button) field(prompt, "submit")).fire();
                check("matching change submits current and new PIN once", count.get() == 2
                        && Arrays.equals(submitted.get().newPin(), next));
                KioskPinPrompt.Request change = submitted.get();
                change.close();
                check("change request closes both PIN copies", allZero(change.pin()) && allZero(change.newPin()));
                AtomicReference<char[]> rejected = new AtomicReference<>();
                KioskPinPrompt rejecting = new KioskPinPrompt(request -> {
                    rejected.set(request.pin());
                    throw new IllegalStateException("fixture rejected submission");
                }, () -> { }, ignored -> { });
                rejecting.open(KioskOperations.Action.INFO);
                ((PasswordField) field(rejecting, "pin")).setText(new String(pin));
                try {
                    ((Button) field(rejecting, "submit")).fire();
                    throw new AssertionError("expected rejection");
                } catch (IllegalStateException expected) {
                    check("callback rejection wipes captured input", allZero(rejected.get()) && !rejecting.isOpen());
                }
                return null;
            });
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(next, '\0');
        }
    }

    private static void workspaceChecks() throws Exception {
        Path directory = Files.createTempDirectory("kiosk-ui-boundary-");
        CardTaskRunner tasks = new CardTaskRunner();
        try {
            CardSession session = new CardSession(directory.resolve("journal.txt"), ignored -> { });
            RecoveryCardFixture card = new RecoveryCardFixture();
            Object transport = field(session.gateway(), "transport");
            set(transport, "channel", card);
            set(transport, "connectedFlag", true);
            check("UI fixture card identifies", session.gateway().identify().ok());
            KioskOperations operations = new KioskOperations(session);
            AtomicReference<KioskWorkspace> workspace = new AtomicReference<>();
            fx(() -> {
                EnrollmentRunner noBackend = new EnrollmentRunner() {
                    @Override
                    public <T> void run(String label, Callable<T> work,
                            java.util.function.Consumer<T> success, java.util.function.Consumer<Exception> failure) {
                        throw new AssertionError("unexpected Backend submission");
                    }
                };
                KioskWorkspace value = new KioskWorkspace(session, operations, tasks,
                        (label, work, finished) -> { throw new AssertionError("unexpected card submission"); },
                        KioskPinPrompt.Request::close,
                        (proof, pin) -> { throw new AssertionError("unexpected Admin dialog"); }, noBackend);
                workspace.set(value);
                PendingJournal pending = new PendingJournal(session.journalFile());
                byte[] visit = new byte[16];
                Arrays.fill(visit, (byte) 1);
                check("startup pending fixture created", pending.writePending(new PendingJournal.Entry(
                        RecoveryCardFixture.CARD_ID, 2, Receipt.OPERATION_START, -1, 4, visit, 1)));
                List<Integer> startupIns = new ArrayList<>();
                session.gateway().traceCommands(startupIns::add);
                value.initialize();
                check("startup PENDING locks every ordinary action before I/O", startupIns.isEmpty()
                        && session.gateway().reconciliation().state() == Reconciliation.State.UNKNOWN
                        && ((Button) field(value, "start")).isDisabled()
                        && ((Button) field(value, "end")).isDisabled()
                        && ((Button) field(value, "info")).isDisabled()
                        && ((Button) field(value, "change")).isDisabled());
                pending.remove();
                session.gateway().reconciliation().markKnown("fixture resolved before next UI case");
                value.synchronizeConnection();
                value.refresh();
                return null;
            });
            char[] pin = RecoveryCardFixture.pin(6);
            try {
                check("UI fixture has authenticated state", session.gateway().readAuthenticatedStatus(pin).ok());
                CardStatus authenticated = session.gateway().status();
                KioskOperations.Outcome outcome = new KioskOperations.Outcome(KioskOperations.Action.INFO,
                        CardOperationResult.ok("fixture information"), Sw.OK, authenticated,
                        new Profile(1, "KIOSK001", "Fixture Student", 15), null, true);
                fx(() -> {
                    KioskWorkspace value = workspace.get();
                    value.studentCompleted(new CardTaskRunner.Outcome<>(outcome, null));
                    check("authenticated data and history are visible", "Fixture Student".equals(
                            ((javafx.scene.control.Label) field(value, "name")).getText())
                            && ((HistoryPanel) field(value, "history")).node().isVisible());
                    ((Button) field(value, "done")).fire();
                    check("ending session clears user state and history", ((javafx.scene.control.Label) field(value, "name")).getText().isEmpty()
                            && !((HistoryPanel) field(value, "history")).node().isVisible()
                            && !session.gateway().status().studentStateAvailable());
                    ((Button) field(value, "info")).fire();
                    KioskPinPrompt prompt = (KioskPinPrompt) field(value, "prompt");
                    ((PasswordField) field(prompt, "pin")).setText(new String(pin));
                    set(session.gateway(), "ready", false);
                    value.synchronizeConnection();
                    value.refresh();
                    check("identity loss clears open PIN form and disables actions", !prompt.isOpen()
                            && inputs(prompt.node()).stream().allMatch(input -> input.getText().isEmpty())
                            && ((Button) field(value, "start")).isDisabled()
                            && ((Button) field(value, "info")).isDisabled());
                    value.sessionChanged(new CardTaskRunner.Outcome<>(outcome, null));
                    check("late result cannot restore prior card data", ((javafx.scene.control.Label) field(value, "name")).getText().isEmpty()
                            && !((HistoryPanel) field(value, "history")).node().isVisible());
                    set(session.gateway(), "ready", true);
                    value.synchronizeConnection();
                    value.refresh();
                    ((Button) field(value, "info")).fire();
                    ((PasswordField) field(prompt, "pin")).setText(new String(pin));
                    set(tasks, "closing", true);
                    value.closing();
                    check("closing clears PIN and locks Kiosk controls", !prompt.isOpen()
                            && inputs(prompt.node()).stream().allMatch(input -> input.getText().isEmpty() && input.isDisabled())
                            && ((Button) field(value, "start")).isDisabled()
                            && ((Button) field(value, "end")).isDisabled()
                            && ((Button) field(value, "change")).isDisabled()
                            && ((Button) field(value, "reconcile")).isDisabled());
                    return null;
                });
            } finally {
                Arrays.fill(pin, '\0');
            }
        } finally {
            ((java.util.concurrent.ExecutorService) field(tasks, "executor")).shutdownNow();
            Files.delete(directory);
        }
    }

    private static void serviceChecks() throws Exception {
        Path directory = Files.createTempDirectory("kiosk-boundary-");
        Path journal = directory.resolve("pending.txt");
        String original = System.getProperty("labaccess.backend");
        AtomicInteger ledgerReads = new AtomicInteger();
        try (RecoveryBackendFixture backend = new RecoveryBackendFixture((request, body) -> {
            ledgerReads.incrementAndGet();
            return new RecoveryBackendFixture.Reply(200,
                    "{\"cardId\":\"" + RecoveryCardFixture.CARD_ID
                            + "\",\"lastCounter\":2,\"lastBalance\":5,\"activeVisitId\":null}");
        })) {
            System.setProperty("labaccess.backend", backend.url());
            CardSession session = new CardSession(journal, ignored -> { });
            RecoveryCardFixture card = new RecoveryCardFixture();
            Object transport = field(session.gateway(), "transport");
            set(transport, "channel", card);
            set(transport, "connectedFlag", true);
            check("fixture card identifies", session.gateway().identify().ok());
            session.gateway().applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "fixture"));
            List<Integer> ins = new ArrayList<>();
            session.gateway().traceCommands(ins::add);
            KioskOperations operations = new KioskOperations(session);
            char[] pin = RecoveryCardFixture.pin(6);
            KioskOperations.Outcome mismatch = operations.execute(new KioskPinPrompt.Request(KioskOperations.Action.START, pin, null));
            check("ledger mismatch blocks START and journal", !mismatch.result().ok() && !Files.exists(journal)
                    && ledgerReads.get() == 1 && !ins.contains(0x51) && !ins.contains(0x52));
            check("mismatch request PIN is wiped", allZero(pin));
            for (int sw : new int[] {0x63C2, 0x63C1, Sw.AUTH_BLOCKED}) {
                card.pinSw = sw;
                ins.clear();
                char[] wrong = RecoveryCardFixture.pin(6);
                KioskOperations.Outcome result = operations.execute(new KioskPinPrompt.Request(KioskOperations.Action.START, wrong, null));
                check(String.format("SW %04X stops after exactly one VERIFY", sw), !result.result().ok()
                        && result.pinSw() == sw && ins.equals(List.of(0x20)) && allZero(wrong));
            }
            ins.clear();
            for (KioskOperations.Action action : KioskOperations.Action.values()) {
                char[] blocked = RecoveryCardFixture.pin(6);
                KioskOperations.Outcome result = operations.execute(new KioskPinPrompt.Request(action, blocked, null));
                check("blocked Student forbids " + action, !result.result().ok() && ins.isEmpty() && allZero(blocked));
            }
            operations.resetSession();
            card.pinSw = Sw.OK;
            PendingJournal pending = new PendingJournal(journal);
            check("pending fixture created", pending.writePending(new PendingJournal.Entry(RecoveryCardFixture.CARD_ID,
                    2, Receipt.OPERATION_GRANT, 5, 10, null, 1)));
            byte[] contents = Files.readAllBytes(journal);
            for (KioskOperations.Action action : KioskOperations.Action.values()) {
                char[] blocked = RecoveryCardFixture.pin(6);
                KioskOperations.Outcome result = operations.execute(new KioskPinPrompt.Request(action, blocked, null));
                check("PENDING forbids ordinary action " + action, !result.result().ok() && ins.isEmpty()
                        && allZero(blocked) && Arrays.equals(contents, Files.readAllBytes(journal)));
            }
            char[] admin = RecoveryCardFixture.pin(8);
            SupersededRecovery.Confirmation noProof = operations.confirm(null, admin);
            check("no proof cannot send Admin APDU or alter journal", !noProof.result().ok()
                    && noProof.adminStatusWord().isEmpty() && ins.isEmpty() && allZero(admin)
                    && Arrays.equals(contents, Files.readAllBytes(journal)));
            pending.remove();
            check("without PENDING there is no Admin confirmation", !operations.canConfirm(null));
        } finally {
            if (original == null) System.clearProperty("labaccess.backend");
            else System.setProperty("labaccess.backend", original);
            Files.deleteIfExists(journal);
            Files.delete(directory);
        }
    }

    private static List<PasswordField> inputs(Parent parent) {
        List<PasswordField> result = new ArrayList<>();
        for (var node : parent.getChildrenUnmodifiable()) {
            if (node instanceof PasswordField input) result.add(input);
            else if (node instanceof Parent nested) result.addAll(inputs(nested));
        }
        return result;
    }

    private static boolean allZero(char[] pin) {
        if (pin == null) return false;
        for (char digit : pin) if (digit != '\0') return false;
        return true;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void check(String text, boolean condition) {
        if (!condition) throw new AssertionError(text);
        checks++;
        System.out.println("PASS " + text);
    }
}
