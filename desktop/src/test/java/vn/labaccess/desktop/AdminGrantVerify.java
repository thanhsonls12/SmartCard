package vn.labaccess.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

public final class AdminGrantVerify {

    private enum UiCase {
        REFRESH_OK, REFRESH_SW, REFRESH_WRONG_CARD, PIN_TIMEOUT, PIN_INTERRUPTED, CLEANUP_DELAYED,
        REOPEN_PENDING, RECONCILE_GRANT
    }

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String OTHER_CARD_ID = "1122334455667788";
    private static final byte[] AID = HexFormat.of().parseHex("F04C4142010101");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            uiGrant(new UiCase[] {UiCase.valueOf(args[0])});
            return;
        }
        happyPath();
        preconditions();
        ledgerAndBounds();
        ledgerUnavailable();
        preflightBeforePin();
        journalWriteFailure();
        pinFailures();
        grantFailures();
        receiptFailures();
        check("refresh failure still records confirmed GRANT", confirmedGrantSurvivesRefreshFailure());
        refreshVariants();
        backendFailures();
        enrolledKeyValidation();
        uiGrant(UiCase.values());
        System.out.println("ADMIN GRANT PASS: " + checks + " checks");
    }

    private static void happyPath() throws Exception {
        for (boolean active : new boolean[] {false, true}) {
            try (Fixture f = new Fixture()) {
                f.activeVisit = active ? "11".repeat(16) : null;
                f.ledgerVisit = f.activeVisit;
                CardOperationResult result = f.run();
                check("+5 succeeds with Admin only active=" + active, result.ok() && result.warning() == null
                        && f.commands.equals(List.of(0x11, 0x50, 0x63, 0x11)));
                check("PENDING persisted before the only GRANT active=" + active, f.pendingBeforeGrant && f.grants == 1);
                check("PIN taken once, after both GETs and before any APDU active=" + active,
                        f.pinTakes == 1 && f.eventsAtTake == 2 && f.commandsAtTake == 0);
                check("PIN wiped and status refreshed before POST active=" + active,
                        f.pinWipedBeforePost && f.refreshBeforePost && f.posts == 1);
                check("latest response SW remains visible after GRANT active=" + active,
                        f.gateway.lastResponseSw() == Sw.OK);
                check("ACK closes journal without publishing Student receipt active=" + active,
                        !f.journal.load().present() && f.gateway.reconciliation().transactionAllowed()
                                && f.gateway.lastReceipt() == null && f.gateway.lastAdminReceiptRead() == null
                                && f.gateway.status().stateSource() == CardStatus.StateSource.ADMIN
                                && f.gateway.status().balance() == 5 && f.gateway.status().counter() == 1);
                check("request order and trusted key source active=" + active,
                        f.events.equals(List.of("ENROLLED_KEY", "LEDGER", "11", "50", "63", "11", "POST"))
                                && f.keyReads == 1 && f.shapeValid);
                check("result, HTTP and journal never leak Admin PIN active=" + active, !f.leaks(result.text())
                        && !f.leaks(f.postBody) && allZero(f.pinInput));
                System.out.println("TRACE +5: 11 -> 50 -> 63 -> 11; VERIFY_STUDENT_PIN=0; GRANT=1;"
                        + " keySource=HTTP fixture enrollment registry; ACK=recorded; journal=closed; active=" + active);
            }
        }
        try (Fixture f = new Fixture()) {
            f.counter = 0xFFFFFFFEL;
            f.ledgerCounter = f.counter;
            f.balance = 9998;
            f.ledgerBalance = f.balance;
            CardOperationResult result = f.run(1);
            check("uint32 terminal counter and balance 9999 accepted", result.ok() && f.counter == 0xFFFFFFFFL
                    && f.balance == 9999 && !f.journal.load().present());
        }
    }

    private static void preconditions() throws Exception {
        for (int mode = 0; mode < 7; mode++) {
            try (Fixture f = new Fixture()) {
                if (mode == 0) f.gateway.disconnect();
                if (mode == 1) f.gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, false, "fixture"));
                if (mode == 2) f.gateway.markTransactionUnknown("fixture UNKNOWN");
                if (mode == 3) f.journal.writePending(f.entry());
                if (mode == 4) f.pinInput = new char[7];
                if (mode == 5) f.pinInput[0] = '１';
                CardOperationResult result = f.run(mode == 6 ? 0 : 5);
                if (mode < 4 || mode == 6) {
                    check("preflight rejects before APDU, HTTP and PIN mode=" + mode, !result.ok()
                            && f.commands.isEmpty() && f.events.isEmpty() && f.grants == 0 && f.pinTakes == 0);
                } else {
                    check("malformed PIN is taken only after both GETs and never reaches APDU mode=" + mode,
                            !result.ok() && f.commands.isEmpty() && f.grants == 0 && f.pinTakes == 1
                                    && f.eventsAtTake == 2 && f.events.equals(List.of("ENROLLED_KEY", "LEDGER"))
                                    && allZero(f.pinInput));
                }
            }
        }
    }

    private static void ledgerAndBounds() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            try (Fixture f = new Fixture()) {
                if (mode == 0) f.ledgerCounter = 1;
                if (mode == 1) f.ledgerBalance = 1;
                if (mode == 2) f.ledgerVisit = "11".repeat(16);
                if (mode == 3) { f.counter = Receipt.COUNTER_MASK; f.ledgerCounter = f.counter; }
                if (mode == 4) { f.balance = Receipt.MAX_BALANCE; f.ledgerBalance = f.balance; }
                CardOperationResult result = f.run();
                check("ledger or numeric boundary blocks GRANT mode=" + mode, !result.ok()
                        && f.commands.equals(List.of(0x11)) && f.grants == 0 && f.posts == 0
                        && !f.journal.load().present() && allZero(f.pinInput));
            }
        }
    }

    private static void ledgerUnavailable() throws Exception {
        for (int mode = 1; mode <= 4; mode++) {
            try (Fixture f = new Fixture()) {
                f.ledgerMode = mode;
                CardOperationResult result = f.run();
                check("invalid or unavailable ledger blocks before Admin PIN APDU mode=" + mode,
                        !result.ok() && f.commands.isEmpty() && f.grants == 0 && f.posts == 0
                                && !f.journal.load().present() && f.pinTakes == 0);
            }
        }
    }

    private static void preflightBeforePin() throws Exception {
        try (Fixture f = new Fixture()) {
            f.getDelayMs = 250;
            long start = System.nanoTime();
            CardOperationResult result = f.run();
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(f.takenAtNanos - start);
            check("slow GETs finish before PIN is taken or any APDU is sent", result.ok() && f.pinTakes == 1
                    && waitedMs >= 450 && f.eventsAtTake == 2 && f.commandsAtTake == 0);
            System.out.println("TRACE slow-GET: GET enrollment+ledger " + waitedMs
                    + "ms -> PIN taken (APDU=0, events=2) -> 11 -> 50 -> 63 -> 11 -> POST");
        }
        for (int mode = 1; mode <= 6; mode++) {
            try (Fixture f = new Fixture()) {
                f.getDelayMs = 50;
                f.enrollmentMode = mode;
                CardOperationResult result = f.run();
                check("failed enrollment GET never takes PIN or sends APDU mode=" + mode, !result.ok()
                        && f.pinTakes == 0 && f.commands.isEmpty() && f.posts == 0);
            }
        }
        for (int mode = 1; mode <= 4; mode++) {
            try (Fixture f = new Fixture()) {
                f.getDelayMs = 50;
                f.ledgerMode = mode;
                CardOperationResult result = f.run();
                check("failed ledger GET never takes PIN or sends APDU mode=" + mode, !result.ok()
                        && f.pinTakes == 0 && f.commands.isEmpty() && f.posts == 0 && !f.journal.load().present());
            }
        }
        try (Fixture f = new Fixture()) {
            f.onTake = () -> {
                try {
                    f.gateway.disconnect();
                } catch (CardException failure) {
                    throw new AssertionError(failure);
                }
            };
            CardOperationResult result = f.run();
            check("card session changed after preflight blocks before APDU", !result.ok() && f.pinTakes == 1
                    && f.commands.isEmpty() && f.grants == 0 && f.posts == 0 && allZero(f.pinInput)
                    && !f.journal.load().present());
        }
        System.out.println("TRACE failed-GET: PIN not taken; APDU=0; journal=none");
    }

    private static void journalWriteFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            Path blocker = f.directory.resolve("not-a-directory");
            Files.writeString(blocker, "fixture");
            f.journal = new PendingJournal(blocker.resolve("pending.txt"));
            CardOperationResult result = f.run();
            check("journal failure prevents GRANT after Admin pre-read", !result.ok()
                    && f.commands.equals(List.of(0x11)) && f.grants == 0 && f.posts == 0 && allZero(f.pinInput));
            System.out.println("TRACE journal-write-failure: 11; GRANT=0; POST=0");
        }
    }

    private static void pinFailures() throws Exception {
        for (int sw : new int[] {0x63C4, 0x63C1, Sw.AUTH_BLOCKED}) {
            try (Fixture f = new Fixture()) {
                f.firstStatusSw = sw;
                CardOperationResult result = f.run();
                check("first Admin PIN rejection stops immediately SW=" + Integer.toHexString(sw), !result.ok()
                        && result.text().contains(String.format("SW=%04X", sw))
                        && f.commands.equals(List.of(0x11)) && f.posts == 0 && !f.journal.load().present()
                        && !f.leaks(result.text()) && allZero(f.pinInput));
                System.out.printf("TRACE first-PIN-failure: 11; SW=%04X; GRANT=0; no retry%n", sw);
            }
        }
    }

    private static void grantFailures() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (Fixture f = new Fixture()) {
                if (mode == 0) f.failGrantTransport = true;
                if (mode == 1) f.grantSw = Sw.INTERNAL_ERROR;
                if (mode == 2) f.badGrantResponse = true;
                if (mode == 3) f.grantSw = 0x63C4;
                CardOperationResult result = f.run();
                pending("GRANT failure has no replay mode=" + mode, f, result, List.of(0x11, 0x50));
                check("GRANT sent once mode=" + mode, f.grants == 1 && f.posts == 0
                        && (mode == 0 || f.gateway.lastResponseSw() == f.grantSw));
            }
        }
    }

    private static void receiptFailures() throws Exception {
        for (int mode = 1; mode <= 9; mode++) {
            try (Fixture f = new Fixture()) {
                f.receiptMode = mode;
                CardOperationResult result = f.run();
                pending("insufficient receipt keeps intent mode=" + mode, f, result, List.of(0x11, 0x50, 0x63));
                check("receipt rejection cannot POST mode=" + mode, f.posts == 0);
            }
        }
    }

    static boolean confirmedGrantSurvivesRefreshFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.refreshStatusSw = Sw.WRONG_LENGTH;
            CardOperationResult result = f.run();
            check("refresh failure posts retained verified receipt", result.ok() && result.warning() != null
                    && f.commands.equals(List.of(0x11, 0x50, 0x63, 0x11)) && f.posts == 1
                    && f.pinWipedBeforePost && !f.journal.load().present());
            check("refresh warning cannot look like fresh state", !f.gateway.status().stateAvailable()
                    && result.warning().contains("cần đọc lại trạng thái") && f.grants == 1);
            System.out.println("TRACE refresh-failure: 11 -> 50 -> 63 -> 11(SW=6700) -> POST;"
                    + " keySource=HTTP fixture enrollment registry; PIN=cleared-before-POST; journal=closed; warning=read-again");
            return result.ok();
        }
    }

    private static void refreshVariants() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (Fixture f = new Fixture()) {
                f.refreshMode = mode + 1;
                CardOperationResult result = f.run();
                check("refresh failure still posts independently verified receipt mode=" + mode, result.ok()
                        && result.warning() != null && f.posts == 1 && f.grants == 1
                        && !f.journal.load().present() && f.pinWipedBeforePost
                        && (f.gateway.status() == null || !f.gateway.status().stateAvailable()));
            }
        }
    }

    private static void backendFailures() throws Exception {
        for (int mode = 1; mode <= 9; mode++) {
            try (Fixture f = new Fixture()) {
                f.ackMode = mode;
                CardOperationResult result = f.run();
                pending("missing or mismatched ACK retains intent mode=" + mode, f, result,
                        List.of(0x11, 0x50, 0x63, 0x11));
                check("POST attempted once without GRANT replay mode=" + mode, f.posts == 1
                        && f.grants == 1 && f.pinWipedBeforePost);
                int before = f.commands.size();
                f.pinInput = f.correctPin.clone();
                check("fresh PIN cannot replay a pending GRANT mode=" + mode,
                        !f.run().ok() && f.commands.size() == before && f.grants == 1 && f.posts == 1);
                System.out.println("TRACE Backend-unacked mode=" + mode + ": 11 -> 50 -> 63 -> 11 -> POST; PENDING/UNKNOWN; GRANT=1");
            }
        }
        try (Fixture f = new Fixture()) {
            f.ackMode = 10;
            check("matching duplicate ACK closes journal", f.run().ok() && !f.journal.load().present());
        }
    }

    private static void enrolledKeyValidation() throws Exception {
        for (int mode = 1; mode <= 6; mode++) {
            try (Fixture f = new Fixture()) {
                f.enrollmentMode = mode;
                CardOperationResult result = f.run();
                check("untrusted enrollment response blocks before APDU mode=" + mode, !result.ok()
                        && f.commands.isEmpty() && f.posts == 0 && !f.journal.load().present() && f.pinTakes == 0);
            }
        }
        try (Fixture f = new Fixture()) {
            f.enrollmentMode = 7;
            pending("valid but wrong enrolled key cannot be replaced by a card key", f, f.run(),
                    List.of(0x11, 0x50, 0x63));
            check("bad enrolled signer never POSTs", f.posts == 0);
        }
    }

    private static void uiGrant(UiCase[] cases) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            for (UiCase scenario : cases) {
                if (scenario == UiCase.REOPEN_PENDING) {
                    reopenPendingGrant(false);
                } else if (scenario == UiCase.RECONCILE_GRANT) {
                    recoveryCases();
                    reopenPendingGrant(true);
                } else {
                    uiGrantRun(scenario);
                }
            }
        } finally {
            Platform.exit();
        }
    }

    private static void reopenPendingGrant(boolean recover) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ackMode = 2;
            pending("GRANT without Backend ACK creates PENDING/UNKNOWN", f, f.run(),
                    List.of(0x11, 0x50, 0x63, 0x11));
            check("first GRANT committed once and attempted one POST", f.grants == 1 && f.posts == 1);

            Path file = f.journal.file();
            String saved = Files.readString(file);
            String priorJournal = System.setProperty("labaccess.journal", file.toString());
            String priorBackend = System.setProperty("labaccess.backend", "http://127.0.0.1:" + f.server.getLocalPort());
            String priorDemo = System.setProperty("labaccess.demo", "false");
            App reopened = new App();
            Stage stage = onFx(Stage::new);
            Object taskRunner = field(reopened, "taskRunner").get(reopened);
            ExecutorService worker = (ExecutorService) field(taskRunner, "executor").get(taskRunner);
            try {
                Object session = field(reopened, "session").get(reopened);
                CardGateway gateway = (CardGateway) field(session, "gateway").get(session);
                PendingJournal journal = (PendingJournal) field(session, "journal").get(session);
                check("fresh App owns a new Gateway and opens the original journal path",
                        gateway != f.gateway && journal != f.journal && journal.file().equals(file));
                int apduBeforeStartup = f.commands.size();
                int httpBeforeStartup = f.events.size();
                onFx(() -> { reopened.start(stage); return null; });
                PendingJournal.Loaded pending = journal.load();
                check("startup reloads GRANT PENDING/UNKNOWN without APDU or HTTP",
                        pending.present() && pending.valid() && pending.status() == PendingJournal.Status.PENDING
                                && pending.entry().operation() == Receipt.OPERATION_GRANT
                                && gateway.reconciliation().state() == Reconciliation.State.UNKNOWN
                                && f.commands.size() == apduBeforeStartup && f.events.size() == httpBeforeStartup);
                String expected = "v1|PENDING|" + CARD_ID + "|1|1|5|5||" + pending.entry().createdAtEpochMillis();
                check("pending file contains only the nine journal fields and no Admin PIN",
                        saved.strip().equals(expected) && !f.leaks(saved));

                Object transport = field(gateway, "transport").get(gateway);
                field(transport, "channel").set(transport, f);
                field(transport, "connectedFlag").setBoolean(transport, true);
                var sync = App.class.getDeclaredMethod("syncCardConnection");
                sync.setAccessible(true);
                onFx(() -> {
                    sync.invoke(reopened);
                    reopened.refreshButtons();
                    check("Identify button dispatches in the reopened App", reopened.pressIdentify());
                    return null;
                });
                worker.submit(() -> { }).get(15, TimeUnit.SECONDS);
                onFx(() -> null);
                check("same enrolled card is ready after only SELECT and GET_STATUS",
                        gateway.isReady() && CARD_ID.equals(gateway.status().cardId())
                                && gateway.enrollmentBlockReason() == null
                                && f.commands.subList(apduBeforeStartup, f.commands.size()).equals(List.of(0xA4, 0x10)));

                int apduBeforeClick = f.commands.size();
                int httpBeforeClick = f.events.size();
                char[] freshPin = f.correctPin.clone();
                try {
                    onFx(() -> {
                        reopened.showTab(App.TAB_OPERATIONS);
                        reopened.fillTransactionInputs(new char[0], freshPin, 5);
                        TransactionPanel panel = (TransactionPanel) field(reopened, "transactionPanel").get(reopened);
                        check("fresh Admin PIN is present and Grant is disabled in the ready, idle App",
                                !emptyPinFields(panel) && !reopened.isBusy() && !reopened.pressGrant());
                        panel.clearInputs();
                        return null;
                    });
                } finally {
                    Arrays.fill(freshPin, '\0');
                }
                worker.submit(() -> { }).get(15, TimeUnit.SECONDS);
                check("blocked Grant click emits zero APDU and zero HTTP and preserves PENDING/UNKNOWN",
                        f.commands.size() == apduBeforeClick && f.events.size() == httpBeforeClick
                                && f.grants == 1 && f.posts == 1 && saved.equals(Files.readString(file))
                                && gateway.reconciliation().state() == Reconciliation.State.UNKNOWN);
                System.out.println("TRACE reopened GRANT: first=11 -> 50 -> 63 -> 11 -> POST(no ACK);"
                        + " restart=same journal, PENDING/UNKNOWN, PIN absent; identify=A4 -> 10;"
                        + " Grant click with newly entered PIN=blocked, APDU=0, HTTP=0; totals GRANT=1 POST=1");
                if (recover) {
                    reconcileFromUi(f, reopened, worker, gateway, journal);
                }
            } finally {
                worker.shutdownNow();
                try {
                    onFx(() -> { stage.hide(); return null; });
                } finally {
                    restoreProperty("labaccess.journal", priorJournal);
                    restoreProperty("labaccess.backend", priorBackend);
                    restoreProperty("labaccess.demo", priorDemo);
                }
            }
        }
    }

    private static void recoveryCases() throws Exception {
        List<String> afterAck = List.of("ENROLLED_KEY", "63", "11", "POST", "LEDGER");
        recoveryCase("UNKNOWN duplicate ACK with ledger counter mismatch", 1, 1, f -> {
            committedRecoveryLedger(f);
            f.ackMode = 10;
            f.ledgerCounter++;
        }, "UNKNOWN", afterAck);
        recoveryCase("UNKNOWN duplicate ACK with ledger balance mismatch", 1, 1, f -> {
            committedRecoveryLedger(f);
            f.ackMode = 10;
            f.ledgerBalance++;
        }, "UNKNOWN", afterAck);
        recoveryCase("UNKNOWN duplicate ACK with ledger visit mismatch", 1, 1, f -> {
            committedRecoveryLedger(f);
            f.ackMode = 10;
            f.activeVisit = "11".repeat(16);
            f.ledgerVisit = "22".repeat(16);
        }, "UNKNOWN", afterAck);
        recoveryCase("UNKNOWN duplicate ACK with ledger card mismatch", 1, 1, f -> {
            committedRecoveryLedger(f);
            f.ackMode = 10;
            f.ledgerMode = 4;
        }, "UNKNOWN", afterAck);
        recoveryCase("UNKNOWN duplicate ACK with ledger HTTP failure", 1, 1, f -> {
            committedRecoveryLedger(f);
            f.ackMode = 10;
            f.ledgerMode = 2;
        }, "UNKNOWN", afterAck);
        recoveryCase("EXECUTED", 1, 1, AdminGrantVerify::committedRecoveryLedger, "EXECUTED", afterAck);
        recoveryCase("NOT_EXECUTED no receipt", 1, 0, f -> f.adminReceiptSw = Sw.CONDITIONS_NOT_SATISFIED,
                "NOT_EXECUTED", List.of("ENROLLED_KEY", "63", "11", "LEDGER"));
        recoveryCase("NOT_EXECUTED previous receipt", 2, 1, f -> { },
                "NOT_EXECUTED", List.of("ENROLLED_KEY", "63", "11", "LEDGER"));
        recoveryCase("UNKNOWN wrong receipt signature", 1, 1, f -> f.receiptMode = 7,
                "UNKNOWN", List.of("ENROLLED_KEY", "63"));
        recoveryCase("UNKNOWN wrong Admin PIN", 1, 1, f -> f.adminReceiptSw = 0x63C4,
                "UNKNOWN", List.of("ENROLLED_KEY", "63"));
        recoveryCase("UNKNOWN no receipt despite previous counter", 2, 1,
                f -> f.adminReceiptSw = Sw.CONDITIONS_NOT_SATISFIED,
                "UNKNOWN", List.of("ENROLLED_KEY", "63"));
        recoveryCase("UNKNOWN status read failed", 1, 1, f -> f.firstStatusSw = Sw.WRONG_LENGTH,
                "UNKNOWN", List.of("ENROLLED_KEY", "63", "11"));
        recoveryCase("UNKNOWN inconsistent status with matching receipt", 1, 1,
                f -> { f.receiptBytes = f.receipt(); f.counter++; f.balance += 5; },
                "UNKNOWN", List.of("ENROLLED_KEY", "63", "11"));
        recoveryCase("UNKNOWN same counter different payload without ledger match", 1, 1, f -> f.receiptMode = 4,
                "UNKNOWN", List.of("ENROLLED_KEY", "63", "11", "LEDGER"));
        recoveryCase("UNKNOWN ledger visit mismatch", 2, 1, f -> f.ledgerVisit = "AA".repeat(16),
                "UNKNOWN", List.of("ENROLLED_KEY", "63", "11", "LEDGER"));
        recoveryCase("UNKNOWN Backend rejects ledger read", 1, 0,
                f -> { f.adminReceiptSw = Sw.CONDITIONS_NOT_SATISFIED; f.ledgerMode = 1; },
                "UNKNOWN", List.of("ENROLLED_KEY", "63", "11", "LEDGER"));
        recoveryCase("UNKNOWN inconsistent Backend ACK", 1, 1, f -> f.ackMode = 4,
                "UNKNOWN", List.of("ENROLLED_KEY", "63", "11", "POST"));
        try (Fixture f = new Fixture()) {
            PendingJournal.Entry entry = new PendingJournal.Entry(OTHER_CARD_ID, 1, Receipt.OPERATION_GRANT,
                    5, 5, null, System.currentTimeMillis());
            check("wrong-card fixture has pending intent", f.journal.writePending(entry));
            String before = Files.readString(f.journal.file());
            CardOperationResult result = new AdminGrantRecovery(f.gateway, f.journal, f.backend)
                    .reconcile(f.pinSource());
            check("wrong card leaves PENDING/UNKNOWN before PIN and I/O", !result.ok()
                    && f.events.isEmpty() && f.commands.isEmpty() && f.pinTakes == 0
                    && before.equals(Files.readString(f.journal.file()))
                    && f.gateway.reconciliation().state() == Reconciliation.State.UNKNOWN);
            System.out.println("TRACE wrong card: APDU=0 HTTP=0 GRANT_QUOTA=0 journal=PENDING");
        }
    }

    private static void committedRecoveryLedger(Fixture f) throws Exception {
        f.receiptBytes = f.receipt();
        f.ledgerCounter = f.counter;
        f.ledgerBalance = f.balance;
        f.ledgerVisit = f.activeVisit;
    }

    @FunctionalInterface
    private interface RecoverySetup {
        void configure(Fixture fixture) throws Exception;
    }

    private static void recoveryCase(String name, int expectedCounter, int statusCounter, RecoverySetup setup,
            String verdict, List<String> events) throws Exception {
        try (Fixture f = new Fixture()) {
            f.counter = statusCounter;
            f.balance = statusCounter * 5;
            if (expectedCounter == 2) {
                f.receiptBytes = f.receipt();
                f.ledgerCounter = 1;
                f.ledgerBalance = 5;
            }
            setup.configure(f);
            PendingJournal.Entry entry = new PendingJournal.Entry(CARD_ID, expectedCounter, Receipt.OPERATION_GRANT,
                    5, expectedCounter * 5, null, System.currentTimeMillis());
            check(name + " fixture has pending intent", f.journal.writePending(entry));
            String before = Files.readString(f.journal.file());
            CardOperationResult result = new AdminGrantRecovery(f.gateway, f.journal, f.backend)
                    .reconcile(f.pinSource());
            boolean resolved = !verdict.equals("UNKNOWN");
            check(name + " gives expected verdict and journal outcome", result.ok() == resolved
                    && result.text().contains(verdict)
                    && f.gateway.reconciliation().state() == (resolved ? Reconciliation.State.KNOWN : Reconciliation.State.UNKNOWN)
                    && (resolved ? !f.journal.load().present() : before.equals(Files.readString(f.journal.file()))));
            List<String> apdu = f.commands.stream().map(ins -> String.format("%02X", ins)).toList();
            check(name + " reads only Admin APDUs, wipes PIN, and makes only the expected HTTP calls",
                    f.events.equals(events) && f.pinTakes == 1 && f.eventsAtTake == 1 && allZero(f.pinInput)
                            && f.shapeValid && f.grants == 0 && (!events.contains("POST") || f.pinWipedBeforePost)
                            && (!events.contains("LEDGER") || f.pinWipedBeforeLedger && f.pendingAtLedger)
                            && (f.ackMode != 10 || result.text().contains("ACK=duplicate HTTP=201"))
                            && !f.leaks(result.text()));
            System.out.println("TRACE " + name + " APDU=" + apdu + " events=" + f.events
                    + " GRANT_QUOTA=0 journal=" + (resolved ? "closed" : "PENDING") + " | " + result.text());
        }
    }

    private static void reconcileFromUi(Fixture f, App app, ExecutorService worker,
            CardGateway gateway, PendingJournal journal) throws Exception {
        int apduBefore = f.commands.size();
        int eventsBefore = f.events.size();
        onFx(() -> {
            check("recover button requests Admin PIN without starting work", app.pressReconcile()
                    && !app.isBusy() && !app.reconcileDialogVisible());
            return null;
        });
        check("missing Admin PIN leaves PENDING before APDU or HTTP", f.commands.size() == apduBefore
                && f.events.size() == eventsBefore && journal.load().status() == PendingJournal.Status.PENDING);
        System.out.println("TRACE missing Admin PIN: APDU=0 HTTP=0 GRANT_QUOTA=0 journal=PENDING");
        f.ackMode = 10;
        f.ledgerCounter = 1;
        f.ledgerBalance = 5;
        char[] entered = f.correctPin.clone();
        CompletableFuture<Boolean> confirmed = new CompletableFuture<>();
        try {
            onFx(() -> {
                app.fillTransactionInputs(new char[0], entered, 5);
                Platform.runLater(() -> confirmed.complete(confirmRecoveryDialog()));
                check("operator can start Admin reconciliation from the real button", app.pressReconcile());
                return null;
            });
        } finally {
            Arrays.fill(entered, '\0');
        }
        check("confirmation identifies Admin PIN instead of Student PIN", confirmed.get(15, TimeUnit.SECONDS));
        worker.submit(() -> { }).get(15, TimeUnit.SECONDS);
        onFx(() -> {
            TransactionPanel panel = (TransactionPanel) field(app, "transactionPanel").get(app);
            ReceiptPanel receipts = (ReceiptPanel) field(app, "receiptPanel").get(app);
            check("UI completion clears Admin PIN and enables manual reconciliation",
                    emptyPinFields(panel) && !app.isBusy() && receipts.reconcileEnabled());
            return null;
        });
        check("UI EXECUTED accepts duplicate ACK and closes original journal",
                !journal.load().present() && gateway.reconciliation().state() == Reconciliation.State.KNOWN
                        && f.commands.subList(apduBefore, f.commands.size()).equals(List.of(0x63, 0x11))
                        && f.grants == 1 && f.posts == 2);
        System.out.println("TRACE UI EXECUTED: APDU=63 -> 11; ACK=duplicate; GRANT_QUOTA=0; journal=closed");
    }

    private static boolean confirmRecoveryDialog() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane pane) {
                boolean admin = pane.getContentText().contains("PIN quản trị");
                var confirm = pane.getButtonTypes().stream()
                        .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
                ((Button) pane.lookupButton(confirm)).fire();
                return admin;
            }
        }
        return false;
    }

    private static Field field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void restoreProperty(String name, String prior) {
        if (prior == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, prior);
        }
    }

    private static String labelTexts(TransactionPanel panel, String styleClass) {
        StringBuilder texts = new StringBuilder();
        panel.node().lookupAll(styleClass).forEach(node -> {
            if (node instanceof javafx.scene.control.Label label) texts.append(label.getText()).append('|');
        });
        return texts.toString();
    }

    private static void uiGrantRun(UiCase scenario) throws Exception {
        try (Fixture f = new Fixture()) {
            if (scenario == UiCase.REFRESH_SW) f.refreshStatusSw = Sw.WRONG_LENGTH;
            if (scenario == UiCase.REFRESH_WRONG_CARD) f.refreshMode = 2;
            boolean refreshFails = scenario == UiCase.REFRESH_SW || scenario == UiCase.REFRESH_WRONG_CARD;
            boolean pinAborted = scenario == UiCase.PIN_TIMEOUT || scenario == UiCase.PIN_INTERRUPTED;
            String mode = " (" + scenario + ")";
            CountDownLatch requestingPin = new CountDownLatch(1);
            CountDownLatch fxBlocked = new CountDownLatch(1);
            CountDownLatch releaseFx = new CountDownLatch(1);
            AtomicReference<TransactionPanel> panelRef = new AtomicReference<>();
            AtomicReference<FutureTask<CardOperationResult>> submitted = new AtomicReference<>();
            AtomicReference<char[]> capturedPin = new AtomicReference<>();
            AtomicReference<java.util.function.Consumer<Boolean>> callback = new AtomicReference<>();
            FutureTask<TransactionPanel> create = new FutureTask<>(() -> {
                CardOpRunner runner = (label, work, finished) -> {
                    submitted.set(new FutureTask<>(work));
                    callback.set(finished);
                    return true;
                };
                TransactionPanel panel = new TransactionPanel(f.gateway, runner, ignored -> { },
                        new TransactionPanel.Session(() -> false, () -> true,
                                (plan, command, pin) -> { throw new AssertionError("Student path used by GRANT"); },
                                (delta, source) -> {
                                    CardOperationResult result = f.service().grant(delta, () -> {
                                        requestingPin.countDown();
                                        char[] pin = source.take();
                                        Arrays.fill(f.pinInput, '\0');
                                        f.pinInput = pin;
                                        capturedPin.set(pin);
                                        f.eventsAtTake = f.events.size();
                                        f.commandsAtTake = f.commands.size();
                                        return pin;
                                    });
                                    if (scenario == UiCase.CLEANUP_DELAYED) blockFx(fxBlocked, releaseFx);
                                    return result;
                                },
                                () -> { }, () -> { },
                                ok -> {
                                    CardStatus refreshed = f.gateway.status();
                                    boolean known = ok && refreshed != null && refreshed.stateAvailable();
                                    if (!known) f.gateway.clearAuthenticatedStatus();
                                    panelRef.get().showState(f.gateway.status(), known);
                                },
                                () -> { }, () -> { }));
                panelRef.set(panel);
                SessionGates.Reasons gates = SessionGates.of(new SessionGates.State(false, true, true, false,
                        true, false, false, true, null, f.gateway.status()));
                panel.applyLock(false, gates.grant(), gates.start(), gates.end(), gates.readReceipt());
                if (scenario == UiCase.REFRESH_OK) {
                    panel.fillInputs(new char[0], new char[] {'x'}, 5);
                    panel.pressGrant();
                    check("malformed PIN at the form does not submit a task, HTTP, or APDU",
                            submitted.get() == null && f.events.isEmpty() && f.commands.isEmpty());
                }
                panel.fillInputs(new char[0], f.pinInput, 5);
                check("existing Grant button enabled without Student snapshot", panel.pressGrant());
                check("UI keeps Admin PIN in the form (no char[]) until preflight completes",
                        capturedPin.get() == null && panel.node().lookupAll(".password-field").stream()
                                .map(node -> (PasswordField) node).anyMatch(field -> !field.getText().isEmpty()));
                return panel;
            });
            Platform.runLater(create);
            TransactionPanel panel = create.get(10, TimeUnit.SECONDS);
            FutureTask<CardOperationResult> task = submitted.get();
            CardOperationResult result;
            FutureTask<Boolean> pinBeforeCompletion = new FutureTask<>(() -> !emptyPinFields(panel));
            try {
                if (pinAborted) blockFx(fxBlocked, releaseFx);
                Thread worker = Thread.ofVirtual().start(task);
                if (scenario == UiCase.PIN_INTERRUPTED) {
                    await(requestingPin, "PIN request after preflight");
                    worker.interrupt();
                }
                if (scenario == UiCase.CLEANUP_DELAYED) {
                    await(fxBlocked, "FX blocked following receipt ACK");
                    check("delayed cleanup fixture reached ACK and closed journal", f.posts == 1
                            && f.grants == 1 && !f.journal.load().present());
                }
                result = task.get(scenario == UiCase.CLEANUP_DELAYED ? 5 : 15, TimeUnit.SECONDS);
                if (pinAborted) Platform.runLater(pinBeforeCompletion);
            } finally {
                releaseFx.countDown();
            }
            if (pinAborted) {
                check("PIN wait aborted after both GETs with no APDU, POST, or journal" + mode,
                        !result.ok() && f.commands.isEmpty() && f.posts == 0 && !f.journal.load().present()
                                && capturedPin.get() == null && f.events.size() == 2);
                check("cancelled PIN callback leaves the field untouched before completion" + mode,
                        pinBeforeCompletion.get(10, TimeUnit.SECONDS));
            } else {
                check("real Grant button dispatches Admin-only service" + mode, result.ok()
                        && f.commands.equals(List.of(0x11, 0x50, 0x63, 0x11)) && allZero(capturedPin.get())
                        && f.pinWipedBeforePost);
                check("real button hands PIN over only after both GETs" + mode,
                        f.eventsAtTake == 2 && f.commandsAtTake == 0);
            }
            AtomicReference<String> balance = new AtomicReference<>();
            AtomicReference<String> chip = new AtomicReference<>();
            FutureTask<Void> read = new FutureTask<>(() -> {
                callback.get().accept(result.ok());
                check("FX completion clears PIN fields" + mode, emptyPinFields(panel));
                balance.set(labelTexts(panel, ".la-value"));
                chip.set(labelTexts(panel, ".la-chip"));
                return null;
            });
            Platform.runLater(read);
            read.get(10, TimeUnit.SECONDS);
            if (pinAborted) return;
            if (scenario == UiCase.REFRESH_WRONG_CARD) {
                check("wrong cardId invalidates identification after confirmed GRANT",
                        f.gateway.status() == null && !f.gateway.isReady() && f.posts == 1
                                && !f.journal.load().present());
            }
            if (scenario == UiCase.CLEANUP_DELAYED) {
                check("ACKed GRANT returned successfully while JavaFX remained blocked", result.ok());
            }
            if (refreshFails) {
                check("completion callback shows a confirmed Grant that needs re-reading",
                        balance.get().contains("Đã cấp — cần đọc lại") && !balance.get().contains("5 lượt")
                                && chip.get().contains("Chưa đọc"));
            } else {
                check("completion callback shows confirmed Staff balance after ACK",
                        balance.get().contains("5 lượt") && !balance.get().contains("Chưa đọc")
                                && !balance.get().contains("cần đọc lại") && chip.get().contains("Chưa mở"));
            }
            SessionGates.Reasons after = SessionGates.of(new SessionGates.State(false, true, f.gateway.isReady(),
                    !refreshFails, true, false, false, true, null, f.gateway.status()));
            check("Student VERIFY still required for START/END after Admin refresh" + mode,
                    after.start() != null && after.end() != null
                            && (f.gateway.status() == null || !f.gateway.status().studentStateAvailable()));
            System.out.println("TRACE JavaFX completion" + mode + ": balance=" + balance.get() + " chip=" + chip.get());
        }
    }

    private static boolean emptyPinFields(TransactionPanel panel) {
        return panel.node().lookupAll(".password-field").stream().map(node -> (PasswordField) node)
                .allMatch(field -> field.getText().isEmpty());
    }

    private static void blockFx(CountDownLatch blocked, CountDownLatch release) {
        Platform.runLater(() -> {
            blocked.countDown();
            await(release, "release FX thread");
        });
        await(blocked, "block FX thread");
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError(operation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(operation, interrupted);
        }
    }

    private static void pending(String label, Fixture f, CardOperationResult result, List<Integer> commands) {
        check(label, !result.ok() && f.journal.load().present()
                && f.journal.load().status() == PendingJournal.Status.PENDING
                && f.gateway.reconciliation().state() == Reconciliation.State.UNKNOWN
                && f.commands.equals(commands) && allZero(f.pinInput));
    }

    private static boolean allZero(char[] value) {
        for (char c : value) if (c != 0) return false;
        return true;
    }

    private static void check(String label, boolean passed) {
        if (!passed) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    private static final class Fixture extends CardChannel implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("adgr06-grant-");
        private PendingJournal journal = new PendingJournal(directory.resolve("pending.txt"));
        private final KeyPair keys = keyPair();
        private final KeyPair otherKeys = keyPair();
        private final char[] correctPin = RecoveryCardFixture.pin(8);
        private char[] pinInput = correctPin.clone();
        private final List<Integer> commands = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final CompletableFuture<Void> serving;

        private record HttpReply(int status, String body) {
        }
        private final BackendClient backend;
        private final CardGateway gateway;
        private long counter;
        private int balance;
        private String activeVisit;
        private long ledgerCounter;
        private int ledgerBalance;
        private String ledgerVisit;
        private int firstStatusSw = Sw.OK;
        private int adminReceiptSw = Sw.OK;
        private int refreshStatusSw = Sw.OK;
        private int grantSw = Sw.OK;
        private int refreshMode;
        private int receiptMode;
        private int ackMode;
        private int enrollmentMode;
        private int ledgerMode;
        private int pinTakes;
        private int eventsAtTake = -1;
        private int commandsAtTake = -1;
        private long takenAtNanos;
        private long getDelayMs;
        private Runnable onTake;
        private int grants;
        private int posts;
        private int keyReads;
        private int statusReads;
        private boolean failGrantTransport;
        private boolean badGrantResponse;
        private boolean pendingBeforeGrant;
        private boolean pinWipedBeforePost;
        private boolean pinWipedBeforeLedger;
        private boolean pendingAtLedger;
        private boolean refreshBeforePost;
        private boolean shapeValid = true;
        private String postBody = "";
        private byte[] receiptBytes;

        Fixture() throws Exception {
            server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            serving = CompletableFuture.runAsync(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                                StandardCharsets.US_ASCII));
                        String request = input.readLine();
                        int length = 0;
                        for (String line = input.readLine(); line != null && !line.isEmpty(); line = input.readLine()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
                            }
                        }
                        char[] body = new char[length];
                        int read = 0;
                        while (read < length) {
                            int part = input.read(body, read, length - read);
                            if (part < 0) throw new java.io.IOException("missing request body");
                            read += part;
                        }
                        HttpReply response = http(request.split(" ")[1], new String(body));
                        if (response.status() == 0) continue;
                        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                        String headers = "HTTP/1.1 " + response.status() + " Fixture\r\nConnection: close\r\n"
                                + "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n";
                        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(bytes);
                    } catch (Exception failure) {
                        if (!server.isClosed()) throw new IllegalStateException(failure);
                    }
                }
            });
            backend = new BackendClient("http://127.0.0.1:" + server.getLocalPort());
            gateway = new CardGateway(this);
            if (!gateway.identify().ok()) throw new AssertionError("fixture identify");
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "HTTP fixture registry"));
            commands.clear();
        }

        AdminGrantService service() { return new AdminGrantService(gateway, journal, backend); }
        CardOperationResult run() { return run(5); }
        CardOperationResult run(int delta) { return service().grant(delta, pinSource()); }

        AdminGrantService.PinSource pinSource() {
            return () -> {
                pinTakes++;
                eventsAtTake = events.size();
                commandsAtTake = commands.size();
                takenAtNanos = System.nanoTime();
                if (onTake != null) onTake.run();
                return pinInput;
            };
        }

        /** PIN chưa được lấy thì không có char[] nào cần xóa; đã lấy thì phải về 0. */
        boolean pinSafe() { return pinTakes == 0 || allZero(pinInput); }
        PendingJournal.Entry entry() { return new PendingJournal.Entry(CARD_ID, 1, 1, 5, 5, null, 1); }

        private HttpReply http(String path, String requestBody) throws Exception {
            if (getDelayMs > 0 && (path.startsWith("/enrollments/") || path.startsWith("/receipts/ledger/"))) {
                Thread.sleep(getDelayMs);
            }
            if (path.startsWith("/enrollments/cards/")) {
                events.add("ENROLLED_KEY");
                keyReads++;
                String key = CardGateway.hex(ReceiptVerifier.encodePoint(
                        enrollmentMode == 7 ? otherKeys.getPublic() : keys.getPublic()));
                if (enrollmentMode == 3) key = "00";
                if (enrollmentMode == 4) key = "00".repeat(65);
                if (enrollmentMode == 5) key = "Z".repeat(130);
                return new HttpReply(enrollmentMode == 1 ? 404 : enrollmentMode == 6 ? 503 : 200,
                        "{\"cardId\":\"" + (enrollmentMode == 2 ? OTHER_CARD_ID : CARD_ID)
                                + "\",\"publicKey\":\"" + key + "\"}");
            } else if (path.startsWith("/receipts/ledger/")) {
                events.add("LEDGER");
                pinWipedBeforeLedger = allZero(pinInput);
                pendingAtLedger = journal.load().status() == PendingJournal.Status.PENDING;
                if (ledgerMode == 1) return new HttpReply(404, "{}");
                if (ledgerMode == 2) return new HttpReply(503, "{}");
                if (ledgerMode == 3) return new HttpReply(200, "{}");
                return new HttpReply(200, "{\"cardId\":\"" + (ledgerMode == 4 ? OTHER_CARD_ID : CARD_ID)
                        + "\",\"lastCounter\":" + ledgerCounter
                        + ",\"lastBalance\":" + ledgerBalance + ",\"activeVisitId\":"
                        + (ledgerVisit == null ? "null" : "\"" + ledgerVisit + "\"") + "}");
            } else if (path.equalsIgnoreCase("/cards/" + CARD_ID + "/transactions")) {
                events.add("HISTORY");
                return new HttpReply(200, "{\"cardId\":\"" + CARD_ID + "\",\"transactions\":[{\"id\":"
                        + "\"11111111-1111-1111-1111-111111111111\",\"counterAfter\":1,\"operation\":\"GRANT\","
                        + "\"delta\":5,\"balanceAfter\":5,\"verifyStatus\":\"verified\",\"recordedAt\":"
                        + "\"2026-11-18T00:00:00Z\"}]}");
            } else if (path.equals("/receipts/verify")) {
                events.add("POST");
                posts++;
                pinWipedBeforePost = allZero(pinInput);
                refreshBeforePost = statusReads == 2;
                postBody = requestBody;
                JsonNode body = JSON.readTree(postBody);
                byte[] payload = HexFormat.of().parseHex(body.get("payload").textValue());
                byte[] signature = HexFormat.of().parseHex(body.get("signature").textValue());
                if (!ReceiptVerifier.verifyReceipt(keys.getPublic(), ReceiptCodec.parse(ByteBuffer
                        .allocate(35 + signature.length).put(payload).put((byte) signature.length).put(signature).array()).receipt()).ok()) {
                    throw new AssertionError("POST signature");
                }
                if (!Arrays.equals(payload, Arrays.copyOf(receiptBytes, 34))) throw new AssertionError("POST receipt changed");
                if (ackMode == 1) return new HttpReply(503, "{}");
                if (ackMode == 2) return new HttpReply(0, "");
                String status = ackMode == 9 ? "invalid" : ackMode == 10 ? "duplicate" : "recorded";
                return new HttpReply(201, "{\"status\":\"" + status + "\",\"cardId\":\""
                        + (ackMode == 3 ? OTHER_CARD_ID : CARD_ID) + "\",\"counterAfter\":"
                        + (ackMode == 4 ? counter + 1 : counter) + ",\"operation\":\""
                        + (ackMode == 5 ? "START" : "GRANT") + "\",\"delta\":"
                        + (ackMode == 6 ? 4 : ByteBuffer.wrap(payload).getShort(14))
                        + ",\"balanceAfter\":" + (ackMode == 7 ? balance + 1 : balance) + ",\"visitId\":"
                        + (ackMode == 8 ? "\"" + "11".repeat(16) + "\"" : "null") + "}");
            } else return new HttpReply(404, "{}");
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            int ins = command.getINS();
            commands.add(ins);
            if (ins == 0xA4) return response(new byte[0], Sw.OK);
            if (ins == 0x10) return response(Arrays.copyOf(status(CARD_ID), 27), Sw.OK);
            events.add(String.format("%02X", ins));
            if (ins != 0x11 && ins != 0x50 && ins != 0x63) throw new AssertionError("forbidden INS=" + ins);
            byte[] data = command.getData();
            boolean pinMatches = data.length >= correctPin.length;
            for (int i = 0; i < correctPin.length && pinMatches; i++) pinMatches &= data[i] == (byte) correctPin[i];
            shapeValid &= pinMatches && command.getCLA() == 0x80 && command.getP1() == 0 && command.getP2() == 0
                    && command.getNc() == (ins == 0x50 ? 10 : 8) && command.getNe() == (ins == 0x50 ? 6 : 256);
            if (ins == 0x11) {
                statusReads++;
                if (statusReads == 2 && refreshMode == 1) throw new CardException("fixture refresh lost");
                byte[] status = status(statusReads == 2 && refreshMode == 2 ? OTHER_CARD_ID : CARD_ID);
                if (statusReads == 2 && refreshMode == 3) status[28] ^= 1;
                return response(status, statusReads == 1 ? firstStatusSw : refreshStatusSw);
            }
            if (ins == 0x50) {
                grants++;
                PendingJournal.Loaded loaded = journal.load();
                pendingBeforeGrant = loaded.valid() && loaded.present() && loaded.status() == PendingJournal.Status.PENDING
                        && loaded.entry().expectedCounter() == counter + 1 && loaded.entry().operation() == 1;
                int delta = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
                Arrays.fill(data, (byte) 0);
                if (grantSw == Sw.OK || grantSw == Sw.INTERNAL_ERROR) { counter++; balance += delta; }
                if (failGrantTransport) throw new CardException("fixture response lost after commit");
                return response(badGrantResponse ? new byte[5]
                        : ByteBuffer.allocate(6).putShort((short) balance).putInt((int) counter).array(), grantSw);
            }
            try {
                if (adminReceiptSw != Sw.OK) return response(new byte[0], adminReceiptSw);
                if (receiptBytes == null) receiptBytes = receipt();
                return response(receiptBytes, receiptMode == 9 ? 0x63C4 : Sw.OK);
            } catch (Exception failure) {
                throw new CardException("fixture receipt", failure);
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        }

        private byte[] status(String cardId) {
            return ByteBuffer.allocate(50).put("LABACCESS".getBytes(StandardCharsets.US_ASCII))
                    .put((byte) 1).put((byte) 7).put(AID).put((byte) 1).put(HexFormat.of().parseHex(cardId))
                    .putShort((short) balance).putInt((int) counter).put((byte) (activeVisit == null ? 0 : 1))
                    .put(activeVisit == null ? new byte[16] : HexFormat.of().parseHex(activeVisit)).array();
        }

        private byte[] receipt() throws Exception {
            if (receiptMode == 8) return new byte[2];
            byte[] payload = ByteBuffer.allocate(34).put((byte) 1)
                    .put(HexFormat.of().parseHex(receiptMode == 1 ? OTHER_CARD_ID : CARD_ID))
                    .putInt((int) (receiptMode == 2 ? counter - 1 : counter))
                    .put((byte) (receiptMode == 3 ? 2 : 1)).putShort((short) (receiptMode == 3 ? -1 : receiptMode == 4 ? 4 : balance - ledgerBalance))
                    .putShort((short) (receiptMode == 5 ? balance - 1 : balance))
                    .put(receiptMode == 3 || receiptMode == 6 ? HexFormat.of().parseHex("11".repeat(16)) : new byte[16]).array();
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keys.getPrivate());
            signer.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
            signer.update(payload);
            byte[] signature = signer.sign();
            if (receiptMode == 7) signature[signature.length - 1] ^= 1;
            return ByteBuffer.allocate(35 + signature.length).put(payload).put((byte) signature.length).put(signature).array();
        }

        boolean leaks(String text) {
            byte[] bytes = new byte[correctPin.length];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) correctPin[i];
            try { return text.contains(new String(correctPin)) || text.toUpperCase().contains(CardGateway.hex(bytes)); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }

        private static KeyPair keyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        }

        private static ResponseAPDU response(byte[] bytes, int sw) {
            return new ResponseAPDU(ByteBuffer.allocate(bytes.length + 2).put(bytes).putShort((short) sw).array());
        }

        @Override public Card getCard() { return null; }
        @Override public int getChannelNumber() { return 0; }
        @Override public int transmit(ByteBuffer command, ByteBuffer response) { throw new UnsupportedOperationException(); }
        @Override public void close() throws CardException {
            try {
                server.close();
                serving.get(10, TimeUnit.SECONDS);
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (Exception failure) {
                throw new CardException("fixture cleanup", failure);
            } finally {
                Arrays.fill(correctPin, '\0');
                Arrays.fill(pinInput, '\0');
            }
        }
    }
}
