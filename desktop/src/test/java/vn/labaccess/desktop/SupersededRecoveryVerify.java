package vn.labaccess.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptVerifier;
import vn.labaccess.desktop.card.Reconciliation;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.AdminGrantRecovery;
import vn.labaccess.desktop.session.SupersededRecovery;

public final class SupersededRecoveryVerify {

    private static final String CARD_ID = RecoveryCardFixture.CARD_ID;
    private static final String OTHER_CARD = "1122334455667788";
    private static final String VISIT = "112233445566778899AABBCCDDEEFF10";
    private static final int INTENT_COUNTER = 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "staff-stale" -> staffConfirmation(true);
                case "retry-preserved" -> runJavaFx(true, false);
                default -> throw new IllegalArgumentException(args[0]);
            }
            return;
        }
        rejectedEvidence("unregistered signing key", f -> f.enrolledKey = keyPair().getPublic());
        rejectedEvidence("ledger differs from card", f -> f.ledgerCounter++);
        rejectedEvidence("missing row at expectedCounter", f -> f.rowHttp = 404);
        rejectedEvidence("Backend 409 reading current ledger", f -> f.ledgerHttp = 409);
        rejectedEvidence("Backend 409 reading occupied row", f -> f.rowHttp = 409);
        rejectedEvidence("network disconnect reading occupied row", f -> f.rowHttp = 0);
        rejectedEvidence("stored payload equals intent", f -> f.occupied = payload(2, Receipt.OPERATION_START, -1, 4, VISIT));
        rejectedEvidence("stored payload differs from signed latest receipt at same counter",
                f -> f.occupied = payload(2, Receipt.OPERATION_GRANT, 4, 9, ""));
        rejectedEvidence("ledger row contains another counter",
                f -> f.occupied = payload(1, Receipt.OPERATION_GRANT, 5, 5, ""));
        executed();
        confirmationRejected("wrong Admin PIN", f -> f.adminSw = 0x63C4, List.of(0x11));
        confirmationRejected("different physical card", f -> f.cardId = OTHER_CARD, List.of(0x11));
        confirmationRejected("journal replaced before confirmation", f -> {
            f.journal.remove();
            if (!f.journal.writePending(startIntent(900))) throw new AssertionError("replacement journal");
        }, List.of());
        confirmationRejected("ledger changed while confirmation was pending", f -> f.ledgerBalance++, List.of(0x11));
        laterReceipt();
        staffConfirmation(false);
        staffConfirmation(true);
        runJavaFx(true, true);
        System.out.println("SUPERSEDED PASS: " + checks + " checks");
    }

    private static void runJavaFx(boolean preserveRetries, boolean successCase) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            if (successCase) uiConfirmation(false);
            if (preserveRetries) uiConfirmation(true);
        } finally {
            Platform.exit();
        }
    }

    @FunctionalInterface
    private interface Setup {
        void apply(Fixture fixture) throws Exception;
    }

    private static void rejectedEvidence(String label, Setup setup) throws Exception {
        try (Fixture f = new Fixture()) {
            setup.apply(f);
            String original = f.journalContents();
            CardOperationResult result = f.readStudent();
            check(label + " retains PENDING/UNKNOWN and produces no confirmation",
                    result.superseded() == null && !result.ok() && f.pending(original)
                            && !f.apdus.contains(0x11) && f.readOnly());
            f.trace(label, result);
        }
    }

    private static void confirmationRejected(String label, Setup setup, List<Integer> expectedApdus) throws Exception {
        try (Fixture f = new Fixture()) {
            CardOperationResult reading = f.readStudent();
            check(label + " starts from verified evidence", reading.superseded() != null && f.journal.load().present());
            setup.apply(f);
            String original = f.journalContents();
            f.apdus.clear();
            f.http.clear();
            char[] pin = f.adminPin.clone();
            var attempt = f.recovery.confirm(reading.superseded(), pin);
            CardOperationResult result = attempt.result();
            check(label + " preserves journal and wipes Admin PIN", !result.ok() && f.pending(original)
                    && f.apdus.equals(expectedApdus) && f.readOnly() && allZero(pin)
                    && attempt.adminStatusWord().isPresent() == !expectedApdus.isEmpty());
            f.trace(label, result);
        }
    }

    private static void executed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.setLast(2, Receipt.OPERATION_START, -1, 4, VISIT);
            f.occupied = f.latest.clone();
            CardOperationResult result = f.readStudent();
            check("canonical payload equal to START intent uses EXECUTED only", result.ok()
                    && result.text().contains("kết luận=EXECUTED") && result.superseded() == null
                    && !f.journal.load().present() && f.http.stream().filter(e -> e.startsWith("POST ")).count() == 1
                    && f.http.stream().noneMatch(e -> e.contains("/transactions/")));
            f.trace("exact intent", result);
        }
    }

    private static void laterReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.setLast(3, Receipt.OPERATION_END, 0, 10, VISIT);
            CardOperationResult result = f.readStudent();
            check("later receipt uses the ledger row at expectedCounter, not the last counter",
                    result.superseded() != null && f.http.contains("GET /cards/" + CARD_ID + "/transactions/2")
                            && f.http.stream().noneMatch(e -> e.endsWith("/transactions/3")) && f.readOnly());
            f.trace("later receipt with occupied counter", result);
        }
    }

    private static void staffConfirmation(boolean staleCardState) throws Exception {
        try (Fixture f = new Fixture()) {
            f.journal.remove();
            check("Staff fixture starts with a GRANT pending",
                    f.journal.writePending(new PendingJournal.Entry(CARD_ID, 2, Receipt.OPERATION_GRANT,
                            5, 10, null, 700)));
            f.setLast(2, Receipt.OPERATION_START, -1, 4, VISIT);
            f.occupied = f.latest.clone();
            String original = f.journalContents();
            CardOperationResult reading = new AdminGrantRecovery(f.gateway, f.journal, f.backend)
                    .reconcile(f.adminPin::clone);
            check("Staff proof uses Admin APDUs and waits for explicit confirmation",
                    reading.superseded() != null && !reading.superseded().fromStudent()
                            && f.apdus.equals(List.of(0x63, 0x11)) && f.pending(original) && f.readOnly());
            f.trace("Staff evidence", reading);
            f.apdus.clear();
            f.http.clear();
            if (staleCardState) {
                f.counter++;
                f.activeVisit = null;
            }
            char[] pin = f.adminPin.clone();
            var attempt = f.recovery.confirm(reading.superseded(), pin);
            CardOperationResult result = attempt.result();
            if (staleCardState) {
                f.trace("Staff same card, changed counter and visit, unchanged ledger", result);
                check("fresh Admin snapshot blocks Staff close when same card has advanced beyond ledger",
                        !result.ok() && f.pending(original) && f.apdus.equals(List.of(0x11)) && f.http.isEmpty()
                                && f.ledgerCounter == 2 && VISIT.equals(f.ledgerVisit) && f.cardId.equals(CARD_ID)
                                && allZero(pin) && attempt.adminStatusWord().orElse(-1) == Sw.OK);
                return;
            }
            check("Staff confirmation closes journal after one Admin status and no writes",
                    result.ok() && result.text().contains("SUPERSEDED") && !f.journal.load().present()
                            && f.apdus.equals(List.of(0x11)) && f.readOnly() && allZero(pin));
            f.trace("Staff confirmed", result);
        }
    }

    private static void uiConfirmation(boolean preserveRetries) throws Exception {
        try (Fixture f = new Fixture()) {
            String original = f.journalContents();
            String oldFile = System.setProperty("labaccess.journal", f.journal.file().toString());
            String oldUrl = System.setProperty("labaccess.backend", f.url);
            App app = new App();
            Stage stage = onFx(Stage::new);
            Object taskRunner = field(app, "taskRunner").get(app);
            ExecutorService worker = (ExecutorService) field(taskRunner, "executor").get(taskRunner);
            try {
                Object session = field(app, "session").get(app);
                CardGateway gateway = (CardGateway) field(session, "gateway").get(session);
                Object transport = field(gateway, "transport").get(gateway);
                field(transport, "channel").set(transport, f);
                field(transport, "connectedFlag").setBoolean(transport, true);
                check("UI fixture recognized the test card", gateway.identify().ok());
                var sync = App.class.getDeclaredMethod("syncCardConnection");
                sync.setAccessible(true);
                f.apdus.clear();
                onFx(() -> {
                    app.start(stage);
                    sync.invoke(app);
                    app.refreshButtons();
                    return null;
                });
                if (preserveRetries) f.adminSw = 0x63C4;
                DialogPane pane = openOperatorDialog(app, f.studentPin);
                onFx(() -> {
                    check("confirmation is separate and journal remains PENDING before Admin PIN",
                            pane.getHeaderText().contains("giao dịch khác") && f.pending(original, gateway)
                                    && !f.apdus.contains(0x11) && f.readOnly());
                    f.trace("START pending, GRANT occupies counter before staff confirmation", null);
                    PasswordField field = (PasswordField) pane.lookup(".password-field");
                    Button confirm = confirmButton(pane);
                    check("empty and malformed Admin PIN cannot dispatch APDU", confirm.isDisabled());
                    field.setText("x");
                    check("Admin PIN format is enforced inside the operator dialog", confirm.isDisabled());
                    field.setText(new String(f.adminPin));
                    confirm.fire();
                    return null;
                });
                f.adminAuthorization.get(20, TimeUnit.SECONDS);
                worker.submit(() -> { }).get(20, TimeUnit.SECONDS);
                if (preserveRetries) {
                    checkRetriesAfterPreflightAbort(f, app, gateway);
                    return;
                }
                onFx(() -> {
                    check("Admin authorization emits exactly one 11 and then closes journal",
                            !app.isBusy() && !f.journal.load().present() && gateway.reconciliation().transactionAllowed()
                                    && f.apdus.equals(List.of(0x61, 0x20, 0x60, 0x20, 0x10, 0x11))
                                    && f.readOnly());
                    check("Admin state from operator authorization is not published to Student",
                            gateway.status().stateSource() == CardStatus.StateSource.PUBLIC);
                    return null;
                });
                f.trace("UI confirmed SUPERSEDED", null);
            } finally {
                worker.shutdownNow();
                try {
                    onFx(() -> { stage.hide(); return null; });
                } finally {
                    restore("labaccess.journal", oldFile);
                    restore("labaccess.backend", oldUrl);
                }
            }
        }
    }

    private static DialogPane openOperatorDialog(App app, char[] studentPin) throws Exception {
        CompletableFuture<DialogPane> operatorDialog = new CompletableFuture<>();
        ListChangeListener<Window> windows = change -> Window.getWindows().stream()
                .map(Window::getScene).filter(scene -> scene.getRoot() instanceof DialogPane)
                .map(scene -> (DialogPane) scene.getRoot())
                .filter(pane -> pane.lookup(".password-field") != null)
                .findFirst().ifPresent(operatorDialog::complete);
        try {
            onFx(() -> {
                Window.getWindows().addListener(windows);
                app.fillTransactionInputs(studentPin, null, 5);
                Platform.runLater(() -> clickConfirm(dialog()));
                check("real JavaFX reconcile button reads with Student PIN", app.pressReconcile());
                return null;
            });
            return operatorDialog.get(20, TimeUnit.SECONDS);
        } finally {
            onFx(() -> { Window.getWindows().removeListener(windows); return null; });
        }
    }

    private static void checkRetriesAfterPreflightAbort(Fixture f, App app, CardGateway gateway) throws Exception {
        onFx(() -> {
            check("wrong PIN used one Admin APDU and retained journal",
                    !app.isBusy() && f.pending(f.journalContents(), gateway)
                            && f.apdus.stream().filter(ins -> ins == 0x11).count() == 1);
            return null;
        });
        DialogPane blocked = openOperatorDialog(app, f.studentPin);
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        List<Integer> before = List.copyOf(f.apdus);
        int httpBefore = f.http.size();
        onFx(() -> {
            check("operator dialog retained four tries from actual 63C4", displayedTries(blocked) == 4);
            check("shared Gateway SW is now 9000 from Student evidence", gateway.lastResponseSw() == Sw.OK);
            f.journal.remove();
            check("replacement journal fixture", f.journal.writePending(startIntent(900)));
            TitledPane logPane = (TitledPane) field(app, "logPane").get(app);
            TextArea log = (TextArea) logPane.getContent();
            log.textProperty().addListener((observable, old, next) -> {
                if (next.contains("Journal đã đổi trước khi xác nhận")) stopped.complete(null);
            });
            ((PasswordField) blocked.lookup(".password-field")).setText(new String(f.adminPin));
            clickConfirm(blocked);
            return null;
        });
        stopped.get(20, TimeUnit.SECONDS);
        onFx(() -> {
            check("replaced journal stops before any APDU or HTTP",
                    !app.isBusy() && f.apdus.equals(before) && f.http.size() == httpBefore && f.readOnly()
                            && f.pending(f.journalContents(), gateway));
            return null;
        });
        DialogPane next = openOperatorDialog(app, f.studentPin);
        onFx(() -> {
            int after = displayedTries(next);
            ((Button) next.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
            System.out.println("TRACE retry preservation: actual Admin SW=63C4; stale common SW=9000;"
                    + " journal replacement confirmation APDU=0 HTTP=0; displayed remaining before=4 after=" + after);
            check("pre-APDU abort preserves the four known tries", after == 4);
            return null;
        });
    }

    private static int displayedTries(DialogPane pane) {
        return pane.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                .map(Label::getText).filter(value -> value.startsWith("Số lượt thử PIN quản trị còn lại"))
                .mapToInt(value -> Integer.parseInt(value.substring(value.lastIndexOf(' ') + 1)))
                .findFirst().orElse(-1);
    }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static DialogPane dialog() {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .filter(scene -> scene.getRoot() instanceof DialogPane)
                .map(scene -> (DialogPane) scene.getRoot()).findFirst().orElseThrow();
    }

    private static Button confirmButton(DialogPane pane) {
        return (Button) pane.lookupButton(pane.getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
    }

    private static void clickConfirm(DialogPane pane) {
        confirmButton(pane).fire();
    }

    private static void restore(String key, String before) {
        if (before == null) System.clearProperty(key);
        else System.setProperty(key, before);
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(25, TimeUnit.SECONDS);
    }

    private static boolean allZero(char[] pin) {
        for (char c : pin) if (c != 0) return false;
        return true;
    }

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    private static PendingJournal.Entry startIntent(long createdAt) {
        return new PendingJournal.Entry(CARD_ID, INTENT_COUNTER, Receipt.OPERATION_START,
                -1, 4, HexFormat.of().parseHex(VISIT), createdAt);
    }

    private static byte[] payload(int counter, int operation, int delta, int balance, String visit) {
        return ByteBuffer.allocate(34).put((byte) 1).put(HexFormat.of().parseHex(CARD_ID)).putInt(counter)
                .put((byte) operation).putShort((short) delta).putShort((short) balance)
                .put(HexFormat.of().parseHex(visit)).array();
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class Fixture extends CardChannel implements CardGateway.ReceiptSink, AutoCloseable {

        private final Path directory = Files.createTempDirectory("superseded-fixture-");
        private final PendingJournal journal = new PendingJournal(directory.resolve("pending.txt"));
        private final KeyPair keys = keyPair();
        private java.security.PublicKey enrolledKey = keys.getPublic();
        private final char[] studentPin = RecoveryCardFixture.pin(6);
        private final char[] adminPin = RecoveryCardFixture.pin(8);
        private final List<Integer> apdus = new CopyOnWriteArrayList<>();
        private final List<String> http = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> adminAuthorization = new CompletableFuture<>();
        private final RecoveryBackendFixture server;
        private final String url;
        private final BackendClient backend;
        private final CardGateway gateway;
        private final SupersededRecovery recovery;
        private String cardId = CARD_ID;
        private int counter = 2;
        private int balance = 10;
        private String activeVisit;
        private int ledgerCounter = counter;
        private int ledgerBalance = balance;
        private String ledgerVisit;
        private int ledgerHttp = 200;
        private int rowHttp = 200;
        private int adminSw = Sw.OK;
        private boolean studentVerified;
        private byte[] latest = payload(counter, Receipt.OPERATION_GRANT, 5, balance, "");
        private byte[] occupied = latest.clone();

        Fixture() throws Exception {
            if (!journal.writePending(startIntent(600))) throw new AssertionError("fixture journal");
            server = new RecoveryBackendFixture(this::reply);
            url = server.url();
            backend = new BackendClient(url);
            gateway = new CardGateway(this);
            if (!gateway.identify().ok()) throw new AssertionError("fixture card is not identified");
            gateway.setReceiptSink(this);
            recovery = new SupersededRecovery(gateway, journal, backend);
            apdus.clear();
        }

        private void setLast(int currentCounter, int operation, int delta, int currentBalance, String visit) {
            counter = currentCounter;
            balance = currentBalance;
            ledgerCounter = counter;
            ledgerBalance = balance;
            activeVisit = operation == Receipt.OPERATION_START ? visit : null;
            ledgerVisit = activeVisit;
            latest = payload(counter, operation, delta, balance, visit);
        }

        CardOperationResult readStudent() throws Exception {
            char[] entered = studentPin.clone();
            try {
                return gateway.reconcilePending(journal, entered, recovery::studentEvidence);
            } finally {
                Arrays.fill(entered, '\0');
            }
        }

        String journalContents() throws IOException {
            return Files.readString(journal.file());
        }

        boolean pending(String contents) throws IOException {
            return pending(contents, gateway);
        }

        boolean pending(String contents, CardGateway current) throws IOException {
            return journal.load().status() == PendingJournal.Status.PENDING && contents.equals(journalContents())
                    && current.reconciliation().state() == Reconciliation.State.UNKNOWN;
        }

        boolean readOnly() {
            return apdus.stream().noneMatch(ins -> ins >= 0x50 && ins <= 0x52)
                    && http.stream().noneMatch(request -> request.startsWith("POST "));
        }

        void trace(String label, CardOperationResult result) {
            System.out.printf("TRACE %s | APDU=%s | HTTP=%s | stateWrites=%d POST=%d | journal=%s%s%n",
                    label, apdus.stream().map(ins -> String.format("%02X", ins)).toList(), http,
                    apdus.stream().filter(ins -> ins >= 0x50 && ins <= 0x52).count(),
                    http.stream().filter(request -> request.startsWith("POST ")).count(),
                    journal.load().status() == null ? "closed" : journal.load().status(),
                    result == null ? "" : " | " + result.text());
        }

        private RecoveryBackendFixture.Reply reply(String line, String body) throws IOException {
            String[] parts = line.split(" ");
            String request = parts[0];
            String path = parts[1];
            http.add(request + " " + path);
            String card = "\"cardId\":\"" + CARD_ID + "\"";
            int status = 200;
            String data;
            if (path.equalsIgnoreCase("/enrollments/cards/" + CARD_ID)) {
                data = "{" + card + ",\"publicKey\":\"" + CardGateway.hex(ReceiptVerifier.encodePoint(enrolledKey)) + "\"}";
            } else if (path.equalsIgnoreCase("/receipts/ledger/" + CARD_ID)) {
                status = ledgerHttp;
                data = "{" + card + ",\"lastCounter\":" + ledgerCounter + ",\"lastBalance\":" + ledgerBalance
                        + ",\"activeVisitId\":" + (ledgerVisit == null ? "null" : "\"" + ledgerVisit + "\"") + "}";
            } else if (path.equalsIgnoreCase("/cards/" + CARD_ID + "/transactions/2")) {
                status = rowHttp;
                data = "{" + card + ",\"counterAfter\":2,\"payload\":\"" + CardGateway.hex(occupied) + "\"}";
            } else if (request.equals("POST") && path.equals("/receipts/verify")) {
                var sent = JSON.readTree(body);
                boolean correct = CardGateway.hex(latest).equalsIgnoreCase(sent.path("payload").asText());
                status = correct ? 201 : 409;
                data = "{" + card + ",\"status\":\"duplicate\",\"operation\":\"START\",\"delta\":-1,"
                        + "\"counterAfter\":2,\"balanceAfter\":4,\"visitId\":\"" + VISIT + "\"}";
            } else {
                status = 404;
                data = "{}";
            }
            return new RecoveryBackendFixture.Reply(status, data);
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) {
            int ins = command.getINS();
            apdus.add(ins);
            return switch (ins) {
                case 0xA4 -> response(new byte[0], Sw.OK);
                case 0x20 -> {
                    studentVerified = matchesPin(command, studentPin);
                    yield response(new byte[0], studentVerified ? Sw.OK : 0x63C4);
                }
                case 0x61 -> response(ReceiptVerifier.encodePoint(keys.getPublic()), Sw.OK);
                case 0x60 -> studentAccess(signedReceipt());
                case 0x10 -> {
                    boolean authenticated = studentVerified;
                    studentVerified = false;
                    yield response(status(authenticated), Sw.OK);
                }
                case 0x63, 0x11 -> {
                    int sw = matchesPin(command, adminPin) ? adminSw : 0x63C4;
                    if (ins == 0x11) adminAuthorization.complete(null);
                    yield response(sw != Sw.OK ? new byte[0] : ins == 0x11 ? status(true) : signedReceipt(), sw);
                }
                default -> throw new AssertionError("unexpected APDU " + ins);
            };
        }

        private static boolean matchesPin(CommandAPDU command, char[] expected) {
            byte[] data = command.getData();
            try {
                if (data.length != expected.length) return false;
                for (int i = 0; i < expected.length; i++) if (data[i] != (byte) expected[i]) return false;
                return true;
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        }

        private ResponseAPDU studentAccess(byte[] data) {
            boolean allowed = studentVerified;
            studentVerified = false;
            return response(allowed ? data : new byte[0], allowed ? Sw.OK : 0x6982);
        }

        private byte[] status(boolean authenticated) {
            byte[] aid = RecoveryCardFixture.AID;
            ByteBuffer bytes = ByteBuffer.allocate(authenticated ? 50 : 27);
            bytes.put("LABACCESS".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) aid.length)
                    .put(aid).put((byte) 1).put(HexFormat.of().parseHex(cardId));
            if (authenticated) {
                bytes.putShort((short) balance).putInt(counter).put((byte) (activeVisit == null ? 0 : 1))
                        .put(activeVisit == null ? new byte[16] : HexFormat.of().parseHex(activeVisit));
            }
            return bytes.array();
        }

        private byte[] signedReceipt() {
            try {
                Signature signer = Signature.getInstance("SHA256withECDSA");
                signer.initSign(keys.getPrivate());
                signer.update(ReceiptVerifier.DOMAIN);
                signer.update(latest);
                byte[] signature = signer.sign();
                return ByteBuffer.allocate(35 + signature.length).put(latest).put((byte) signature.length)
                        .put(signature).array();
            } catch (java.security.GeneralSecurityException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private static ResponseAPDU response(byte[] data, int sw) {
            return RecoveryCardFixture.response(data, sw);
        }

        @Override
        public CardGateway.ReceiptSave save(Receipt receipt) {
            try {
                BackendClient.SavedReceipt saved = backend.verifyReceipt(receipt);
                return new CardGateway.ReceiptSave(true, "fixture ACK=" + saved.status());
            } catch (BackendClient.Failure failure) {
                return new CardGateway.ReceiptSave(false, "fixture ACK failed");
            }
        }

        @Override
        public CardGateway.LedgerCheck ledger(String card) {
            try {
                BackendClient.LedgerSnapshot ledger = backend.ledgerOf(card);
                return new CardGateway.LedgerCheck(true, "fixture ledger", ledger.cardId(), ledger.lastCounter(),
                        ledger.lastBalance(), ledger.activeVisitId());
            } catch (BackendClient.Failure failure) {
                return new CardGateway.LedgerCheck(false, "fixture ledger failed", card, -1, -1, null);
            }
        }

        @Override public Card getCard() { return null; }
        @Override public int getChannelNumber() { return 0; }
        @Override public int transmit(ByteBuffer command, ByteBuffer response) { throw new UnsupportedOperationException(); }
        @Override public void close() {
            Arrays.fill(studentPin, '\0');
            Arrays.fill(adminPin, '\0');
            try {
                server.close();
            } catch (Exception failure) {
                throw new IllegalStateException("fixture HTTP shutdown failed", failure);
            } finally {
                deleteJournal();
            }
        }

        private void deleteJournal() {
            try {
                Files.deleteIfExists(journal.file());
                Files.delete(directory);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
    }
}
