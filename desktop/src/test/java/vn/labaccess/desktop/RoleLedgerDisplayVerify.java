package vn.labaccess.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.card.CardLifecycle;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.LedgerAlignment;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptVerifier;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.session.CardSession;

import vn.labaccess.desktop.ui.kiosk.KioskPinPrompt;
import vn.labaccess.desktop.ui.kiosk.KioskWorkspace;

import vn.labaccess.desktop.ui.transaction.GrantPanel;

import vn.labaccess.desktop.ui.staff.StaffWorkspace;

public final class RoleLedgerDisplayVerify {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Object> SNAPSHOTS = new ArrayList<>();
    private static int checks;
    private static Path evidence;

    public static void main(String[] args) throws Exception {
        evidence = Path.of(args[0]);
        Files.createDirectories(evidence);
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            if (args.length > 1 && args[1].equals("info")) {
                infoLedgerChecks();
                JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("checkpoints.json").toFile(), SNAPSHOTS);
                System.out.println("INFO LEDGER DISPLAY PASS: " + checks + " checks");
                return;
            }
            alignmentChecks();
            for (String role : List.of("Staff", "START", "END")) {
                ackChecks(role, false);
                ackChecks(role, true);
                transferChecks(role);
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("checkpoints.json").toFile(), SNAPSHOTS);
            System.out.println("ROLE LEDGER DISPLAY PASS: " + checks + " checks");
        } finally {
            Platform.exit();
        }
    }

    private static void infoLedgerChecks() throws Exception {
        try (Fixture fixture = new Fixture("Staff");
                RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply)) {
            fixture.reject = true;
            Path senderJournal;
            byte[] original;
            try (Window sender = new Window(true, "info-sender", fixture, backend.url())) {
                sender.act("Staff");
                sender.idle();
                senderJournal = sender.session.journalFile();
                original = Files.readAllBytes(senderJournal);
                check("INFO setup committed GRANT without ledger ACK", fixture.card.counter == 2
                        && fixture.card.balance == 10 && fixture.ledgerCounter == 1 && fixture.ledgerBalance == 5);
                snapshot("info-sender-pending", sender, fixture);
            }
            try (Window receiver = new Window(false, "info-receiver", fixture, backend.url())) {
                for (boolean aligned : new boolean[] {false, true}) {
                    if (aligned) {
                        fixture.ledgerCounter = fixture.card.counter;
                        fixture.ledgerBalance = fixture.card.balance;
                        fixture.ledgerVisit = fixture.visit;
                    }
                    String name = aligned ? "info-ledger-matched" : "info-ledger-mismatched";
                    int mutations = fixture.mutations(), posts = fixture.posts, traceStart = fixture.ins.size();
                    receiver.act("INFO");
                    receiver.idle();
                    snapshot(name, receiver, fixture);
                    check(name + " zero mutation APDU", fixture.mutations() == mutations);
                    check(name + " zero POST", fixture.posts == posts);
                    check(name + " no receiver journal", !Files.exists(receiver.session.journalFile()));
                    check(name + " sender journal byte-preserved", Arrays.equals(original, Files.readAllBytes(senderJournal)));
                    check(name + " Student VERIFY performed", fixture.ins.subList(traceStart, fixture.ins.size()).contains(0x20));
                    if (aligned) {
                        check(name + " verified Student source", receiver.text("stateOrigin").equals("Đã xác minh · PIN sinh viên"));
                        check(name + " publishes matching quota", receiver.text("quota").equals("10 lượt"));
                        check(name + " profile visible after Student read", !receiver.text("name").isEmpty());
                    } else {
                        check(name + " no verified quota label", !receiver.text("stateOrigin").contains("Đã xác minh"));
                        check(name + " no numeric quota", !receiver.text("quota").matches(".*[0-9]+ lượt.*"));
                        check(name + " explicitly unverified quota", receiver.text("feedback").contains("Số lượt chưa được xác minh"));
                        check(name + " all ledger axes reported", receiver.text("feedback").contains("cardId ")
                                && receiver.text("feedback").contains("counter 2/1")
                                && receiver.text("feedback").contains("số lượt 10/5") && receiver.text("feedback").contains("ca "));
                        check(name + " directs to journal owner", receiver.text("feedback").contains("Quay lại máy đang giữ journal"));
                    }
                    System.out.println("INFO READ " + name + " INS=" + fixture.ins.subList(traceStart, fixture.ins.size())
                            + " mutation=0 POST=0 receiverJournal=ABSENT senderJournal=PENDING/unchanged");
                }
            }
        }
    }

    private static void alignmentChecks() {
        String id = RecoveryCardFixture.CARD_ID;
        String visit = "01010101010101010101010101010101";
        CardStatus state = new CardStatus(1, 1, "", id, CardStatus.StateSource.STUDENT, 5, 1, true, visit);
        check("alignment exact match", LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, id, 1, 5, visit));
        check("alignment rejects cardId", !LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, "0011223344556677", 1, 5, visit));
        check("alignment rejects counter", !LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, id, 2, 5, visit));
        check("alignment rejects balance", !LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, id, 1, 6, visit));
        check("alignment rejects active visit", !LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, id, 1, 5, null));
        check("alignment rejects different visit", !LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, id, 1, 5, "02020202020202020202020202020202"));
        check("alignment rejects role", !LedgerAlignment.matches(state, CardStatus.StateSource.ADMIN, id, 1, 5, visit));
        check("alignment rejects unexpected ledger visit", !LedgerAlignment.matches(new CardStatus(1, 1, "", id,
                CardStatus.StateSource.ADMIN, 5, 1, false, ""), CardStatus.StateSource.ADMIN, id, 1, 5, visit));
    }

    private static void ackChecks(String role, boolean failRefresh) throws Exception {
        String name = role + (failRefresh ? "-ack-reread" : "-ack");
        try (Fixture fixture = new Fixture(role);
                RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply);
                Window window = new Window(role.equals("Staff"), name, fixture, backend.url())) {
            fixture.failRefresh = failRefresh;
            fixture.hold = true;
            snapshot(name + "-public", window, fixture);
            check(name + " public source", window.text("stateOrigin").equals("Chưa xác minh"));
            check(name + " public hides quota", !window.text("quota").contains(" lượt"));
            window.act(role);
            check(name + " reaches POST before ACK", fixture.postEntered.await(10, TimeUnit.SECONDS));
            snapshot(name + "-before-ack", window, fixture);
            check(name + " before ACK source unknown", window.text("stateOrigin").equals("Chưa rõ kết quả"));
            check(name + " before ACK hides new quota", window.text("quota").equals("Chưa rõ kết quả"));
            check(name + " before ACK PENDING", window.session.pendingJournal().status() == PendingJournal.Status.PENDING);
            check(name + " card committed but ledger unchanged", fixture.card.counter == 2 && fixture.ledgerCounter == 1);
            fixture.release.countDown();
            window.idle();
            snapshot(name + "-after-ack", window, fixture);
            check(name + " exactly one mutation and POST", fixture.mutations() == 1 && fixture.posts == 1);
            check(name + " ACK closes journal", !Files.exists(window.session.journalFile()) && fixture.ledgerCounter == 2);
            if (failRefresh) {
                check(name + " reread source", window.text("stateOrigin").equals("Cần đọc lại"));
                check(name + " no unconfirmed number", !window.text("quota").matches(".*[0-9]+ lượt.*"));
                check(name + " preserves no-replay warning", window.text("feedback").contains("Không thực hiện lại"));
            } else {
                String source = role.equals("Staff") ? "PIN quản trị" : "PIN sinh viên";
                check(name + " verified role", window.text("stateOrigin").equals("Đã xác minh · " + source));
                check(name + " publishes ACKed quota", window.text("quota").equals(fixture.card.balance + " lượt"));
                if (!role.equals("Staff")) {
                    CountDownLatch reading = new CountDownLatch(1), finishReading = new CountDownLatch(1);
                    fx(() -> {
                        var method = KioskApp.class.getDeclaredMethod("runBackend", String.class, Callable.class,
                                java.util.function.Consumer.class, java.util.function.Consumer.class);
                        method.setAccessible(true);
                        method.invoke(window.app, "Lịch sử", (Callable<Object>) () -> {
                            reading.countDown();
                            if (!finishReading.await(10, TimeUnit.SECONDS)) throw new AssertionError("read-only timeout");
                            return null;
                        }, (java.util.function.Consumer<Object>) ignored -> { },
                                (java.util.function.Consumer<Exception>) failure -> { throw new AssertionError(failure); });
                        return null;
                    });
                    check(name + " read-only task started", reading.await(10, TimeUnit.SECONDS));
                    check(name + " busy hides quota", window.text("quota").equals("Chưa rõ kết quả"));
                    finishReading.countDown();
                    window.idle();
                    check(name + " read-only refresh restores quota", window.text("quota").equals(fixture.card.balance + " lượt"));
                    fx(() -> { ((Button) field(window.workspace, "done")).fire(); return null; });
                    check(name + " end session clears verified state", window.text("stateOrigin").equals("Chưa xác minh")
                            && window.text("quota").equals("Chưa xác thực"));
                }
            }
        }
    }

    private static void transferChecks(String role) throws Exception {
        String name = role + "-unposted-transfer";
        try (Fixture fixture = new Fixture(role);
                RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply)) {
            fixture.reject = true;
            Path senderJournal;
            byte[] original;
            try (Window sender = new Window(role.equals("Staff"), name + "-sender", fixture, backend.url())) {
                sender.act(role);
                sender.idle();
                senderJournal = sender.session.journalFile();
                original = Files.readAllBytes(senderJournal);
                snapshot(name + "-sender", sender, fixture);
                check(name + " sender PENDING", sender.session.pendingJournal().status() == PendingJournal.Status.PENDING);
                check(name + " sender source unknown", sender.text("stateOrigin").equals("Chưa rõ kết quả"));
                check(name + " committed once without ACK", fixture.mutations() == 1 && fixture.posts == 1 && fixture.ledgerCounter == 1);
            }
            List<String> actions = role.equals("Staff") ? List.of("START", "END") : List.of("Staff");
            for (String action : actions) {
                try (Window receiver = new Window(action.equals("Staff"), name + "-receiver-" + action, fixture, backend.url())) {
                    int mutations = fixture.mutations(), posts = fixture.posts;
                    int traceStart = fixture.ins.size();
                    receiver.act(action);
                    receiver.idle();
                    snapshot(name + "-receiver-" + action, receiver, fixture);
                    check(name + " " + action + " zero mutation APDU", fixture.mutations() == mutations);
                    check(name + " " + action + " zero POST", fixture.posts == posts);
                    check(name + " " + action + " no receiver journal", !Files.exists(receiver.session.journalFile()));
                    check(name + " " + action + " sender bytes unchanged", Arrays.equals(original, Files.readAllBytes(senderJournal)));
                    check(name + " " + action + " return to journal owner", receiver.text("feedback").contains("Quay lại máy đang giữ journal"));
                    check(name + " " + action + " compares all fields", receiver.text("feedback").contains("cardId ")
                            && receiver.text("feedback").contains("counter ") && receiver.text("feedback").contains("số lượt ")
                            && receiver.text("feedback").contains("ca "));
                    check(name + " " + action + " no quota publication", !receiver.text("quota").matches(".*[0-9]+ lượt.*"));
                    System.out.println("RECEIVER " + name + "/" + action + " INS=" + fixture.ins.subList(traceStart, fixture.ins.size())
                            + " mutation=0 POST=0 journal=ABSENT sender=PENDING/unchanged");
                }
            }
        }
    }

    private static void snapshot(String name, Window window, Fixture fixture) throws Exception {
        var value = JSON.createObjectNode().put("checkpoint", name).put("runtime", "JavaFX + CardChannel/HTTP fixture")
                .put("displaySource", window.text("stateOrigin")).put("quota", window.text("quota"))
                .put("feedback", window.text("feedback")).put("journal", window.session.pendingJournal().present()
                        ? window.session.pendingJournal().status().toString() : "ABSENT")
                .put("gatewaySource", window.session.gateway().status() == null ? "NONE"
                        : window.session.gateway().status().stateSource().toString())
                .put("cardId", fixture.card.cardId).put("cardCounter", fixture.card.counter).put("cardBalance", fixture.card.balance)
                .put("cardVisit", fixture.visit).put("ledgerCounter", fixture.ledgerCounter)
                .put("ledgerBalance", fixture.ledgerBalance).put("ledgerVisit", fixture.ledgerVisit)
                .put("mutations", fixture.mutations()).put("posts", fixture.posts);
        value.set("ins", JSON.valueToTree(fixture.ins));
        SNAPSHOTS.add(value);
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name + ".json").toFile(), value);
        System.out.println("CHECKPOINT " + name + " source=" + value.get("displaySource") + " quota=" + value.get("quota")
                + " journal=" + value.get("journal"));
    }

    static final class Window implements AutoCloseable {
        final Object app;
        final Object workspace;
        final CardSession session;
        final ExecutorService executor;
        final Stage stage;
        final boolean staff;
        final char[] pin;

        Window(boolean staff, String name, Fixture fixture, String url) throws Exception {
            this(staff, name, fixture, url, evidence.resolve(name + "-journal.txt"));
        }

        Window(boolean staff, String name, Fixture fixture, String url, Path override) throws Exception {
            this.staff = staff;
            pin = RecoveryCardFixture.pin(staff ? 8 : 6);
            System.setProperty("labaccess.backend", url);
            if (override == null) System.clearProperty("labaccess.journal");
            else System.setProperty("labaccess.journal", override.toString());
            app = staff ? new StaffApp() : new KioskApp();
            stage = fx(() -> {
                Stage result = new Stage();
                if (staff) ((StaffApp) app).start(result);
                else ((KioskApp) app).start(result);
                return result;
            });
            session = (CardSession) field(app, "session");
            workspace = field(app, "workspace");
            executor = (ExecutorService) field(field(app, "tasks"), "executor");
            fx(() -> {
                Object transport = field(session.gateway(), "transport");
                set(transport, "channel", fixture);
                set(transport, "connectedFlag", true);
                refresh();
                ((Button) field(workspace, "identify")).fire();
                return null;
            });
            idle();
            check(name + " identified through UI", session.gateway().isReady());
        }

        void act(String action) throws Exception {
            fx(() -> {
                if (staff) {
                    GrantPanel grant = (GrantPanel) field(workspace, "grants");
                    grant.fillInputs(pin, 5);
                    check("Staff GRANT button enabled", grant.press());
                } else {
                    Button button = (Button) field(workspace, action.toLowerCase(java.util.Locale.ROOT));
                    check(action + " button enabled", !button.isDisabled());
                    button.fire();
                    KioskPinPrompt prompt = (KioskPinPrompt) field(workspace, "prompt");
                    check(action + " PIN form open", prompt.isOpen());
                    ((PasswordField) field(prompt, "pin")).setText(new String(pin));
                    ((Button) field(prompt, "submit")).fire();
                }
                return null;
            });
        }

        String text(String name) throws Exception { return fx(() -> ((Label) field(workspace, name)).getText()); }
        void refresh() { if (staff) ((StaffWorkspace) workspace).refresh(); else ((KioskWorkspace) workspace).refresh(); }

        void idle() throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            executor.submit(() -> Platform.runLater(done::countDown));
            if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("worker/FX timeout");
        }

        @Override public void close() throws Exception {
            executor.submit(session::disconnect).get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
            fx(() -> { stage.hide(); return null; });
            Arrays.fill(pin, '\0');
        }
    }

    static final class Fixture extends CardChannel implements AutoCloseable {
        final RecoveryCardFixture card = new RecoveryCardFixture();
        final List<Integer> ins = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch postEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile int ledgerCounter = 1;
        volatile int ledgerBalance = 5;
        volatile String ledgerVisit;
        volatile int posts;
        volatile int requests;
        volatile boolean hold;
        volatile boolean reject;
        volatile boolean failRefresh;
        boolean adminReceipt;
        String visit;
        int operation = Receipt.OPERATION_GRANT;
        int delta = 5;
        byte[] receiptVisit = new byte[16];

        Fixture(String action) throws Exception {
            if (action.equals("END")) {
                visit = "01010101010101010101010101010101";
                ledgerVisit = visit;
                card.balance = 0;
                ledgerBalance = 0;
                operation = Receipt.OPERATION_START;
                delta = -1;
                receiptVisit = HexFormat.of().parseHex(visit);
            }
        }

        int mutations() { return (int) ins.stream().filter(value -> value == 0x50 || value == 0x51 || value == 0x52).count(); }

        @Override public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            int instruction = command.getINS();
            ins.add(instruction);
            if ((instruction == 0x11 && adminReceipt || instruction == 0x10 && posts > 0 && !reject) && failRefresh) {
                return RecoveryCardFixture.response(new byte[0], Sw.INTERNAL_ERROR);
            }
            if (instruction == 0x10 || instruction == 0x11) {
                byte[] state = ByteBuffer.allocate(50).put("LABACCESS".getBytes(StandardCharsets.US_ASCII))
                        .put((byte) 1).put((byte) 7).put(RecoveryCardFixture.AID).put((byte) CardLifecycle.ISSUED)
                        .put(HexFormat.of().parseHex(card.cardId)).putShort((short) card.balance).putInt(card.counter)
                        .put((byte) (visit == null ? 0 : 1)).put(visit == null ? new byte[16] : HexFormat.of().parseHex(visit)).array();
                return RecoveryCardFixture.response(state, Sw.OK);
            }
            if (instruction == 0x60 || instruction == 0x63) {
                adminReceipt = instruction == 0x63;
                return RecoveryCardFixture.response(card.receiptMode == 1 ? new byte[] {1} : receipt(), Sw.OK);
            }
            if (instruction == 0x50 || instruction == 0x51 || instruction == 0x52) {
                card.counter++;
                operation = instruction == 0x50 ? Receipt.OPERATION_GRANT
                        : instruction == 0x51 ? Receipt.OPERATION_START : Receipt.OPERATION_END;
                delta = instruction == 0x50 ? 5 : instruction == 0x51 ? -1 : 0;
                card.balance += delta;
                receiptVisit = instruction == 0x50 ? new byte[16] : command.getData();
                if (instruction == 0x51) visit = HexFormat.of().formatHex(receiptVisit).toUpperCase(java.util.Locale.ROOT);
                if (instruction == 0x52) visit = null;
                return RecoveryCardFixture.response(ByteBuffer.allocate(6).putShort((short) card.balance).putInt(card.counter).array(), Sw.OK);
            }
            return card.transmit(command);
        }

        byte[] receipt() {
            try {
                byte[] payload = ByteBuffer.allocate(34).put((byte) 1).put(HexFormat.of().parseHex(card.cardId))
                        .putInt(card.counter).put((byte) operation).putShort((short) delta).putShort((short) card.balance)
                        .put(receiptVisit).array();
                Signature signer = Signature.getInstance("SHA256withECDSA");
                signer.initSign(card.keys.getPrivate());
                signer.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
                signer.update(payload);
                byte[] signature = signer.sign();
                return ByteBuffer.allocate(35 + signature.length).put(payload).put((byte) signature.length).put(signature).array();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }

        RecoveryBackendFixture.Reply reply(String request, String body) throws java.io.IOException {
            requests++;
            String route = request.split(" ")[1];
            var result = JSON.createObjectNode().put("cardId", card.cardId);
            if (route.startsWith("/enrollments/cards/")) {
                result.put("publicKey", HexFormat.of().formatHex(ReceiptVerifier.encodePoint(card.keys.getPublic())));
            } else if (route.startsWith("/receipts/ledger/")) {
                result.put("lastCounter", ledgerCounter).put("lastBalance", ledgerBalance).put("activeVisitId", ledgerVisit);
            } else if (route.equals("/receipts/verify")) {
                posts++;
                postEntered.countDown();
                if (hold) {
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("ACK hold timeout"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                }
                if (reject) return new RecoveryBackendFixture.Reply(503, "{}");
                ledgerCounter = card.counter;
                ledgerBalance = card.balance;
                ledgerVisit = visit;
                result.put("status", "recorded").put("counterAfter", card.counter).put("operation", operationName())
                        .put("delta", delta).put("balanceAfter", card.balance)
                        .put("visitId", operation == Receipt.OPERATION_GRANT ? null : HexFormat.of().formatHex(receiptVisit));
                return new RecoveryBackendFixture.Reply(201, result.toString());
            } else if (route.endsWith("/transactions")) {
                result.putArray("transactions");
            } else return new RecoveryBackendFixture.Reply(404, "{}");
            return new RecoveryBackendFixture.Reply(200, result.toString());
        }

        String operationName() { return operation == Receipt.OPERATION_GRANT ? "GRANT" : operation == Receipt.OPERATION_START ? "START" : "END"; }
        @Override public Card getCard() { return null; }
        @Override public int getChannelNumber() { return 0; }
        @Override public int transmit(ByteBuffer command, ByteBuffer response) { throw new UnsupportedOperationException(); }
        @Override public void close() { release.countDown(); }
    }

    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    static Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field target = owner.getClass().getDeclaredField(name);
        target.setAccessible(true);
        target.set(owner, value);
    }

    private static void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
}
