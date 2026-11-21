package vn.labaccess.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

public final class Adgr13StaffVerify {

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final byte[] AID = HexFormat.of().parseHex("F04C4142010101");
    private static final String VISIT_A = "016CD8412FBB181F929D037A51DE58A4";
    private static final String VISIT_B = "AABBCCDDEEFF00112233445566778899";
    private static final int GRANT_COUNTER = 2;
    private static final int GRANT_BALANCE = 10;
    private static final int GRANT_DELTA = 5;
    private static PrintStream log;

    public static void main(String[] args) throws Exception {
        Path evidence = Path.of(System.getProperty("labaccess.evidence"));
        Files.createDirectories(evidence);
        log = new PrintStream(Files.newOutputStream(evidence.resolve("Adgr13StaffVerify.log")),
                true, StandardCharsets.UTF_8);
        try {
            if (System.getProperty("labaccess.pinExit") != null) {
                String scenario = System.getProperty("labaccess.pinExit");
                if (scenario.startsWith("HANDOFF_")) {
                    pinHandoff(scenario);
                } else {
                    pinExit(scenario);
                }
                line("ADGR-13 PIN EXIT PASS");
                return;
            }
            if (Boolean.getBoolean("labaccess.pinLifetime")) {
                pinLifetime();
                line("ADGR-13 PIN LIFETIME PASS");
                return;
            }
            changedVisitStopsBeforePost();
            buttonCancelThenConfirm();
            line("ADGR-13 STAFF PASS");
        } finally {
            log.close();
        }
    }

    private static void pinLifetime() throws Exception {
        line("--- vong doi PIN xac nhan ---");
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        StaffRunner runner = new StaffRunner();
        Stage stage = onFx(() -> new Stage());
        try (CardScript card = new CardScript(false); LedgerDouble ledger = new LedgerDouble(card.publicKey())) {
            ledger.start();
            CardGateway gateway = card.gateway();
            BackendClient backend = new BackendClient(ledger.baseUrl());
            StaffManualReconcile manual = new StaffManualReconcile(gateway, backend);
            char[] admin = card.pin.clone();
            List<String> lines = new ArrayList<>();
            TransactionPanel pins = onFx(() -> transactionPins(gateway, runner));
            ReceiptPanel panel = onFx(() -> panel(gateway, runner, lines::add, manual, pins));
            onFx(() -> {
                panel.applyLock(false, null, null);
                stage.setScene(new Scene(panel.node()));
                stage.show();
                return null;
            });
            try {
                fillAdminPin(pins, admin);
                require(onFx(panel::pressReconcile), "nut mo cho tac vu bi tu choi");
                DialogPane rejected = awaitDialog();
                requirePanelPinEmpty(pins);
                typeDialogPin(rejected, admin);
                runner.rejectNext = true;
                click(rejected, "Ghi sổ");
                await(runner, () -> lines.stream().anyMatch(item -> item.contains("không nhận tác vụ")));
                require(dialogPinBlank(rejected) && ledger.posts.get() == 0 && stateCount(card.commands) == 0
                        && card.commands.equals(List.of(0x63, 0x11)),
                        "TU CHOI post=" + ledger.posts.get() + " apdu=" + card.commands);
                line("TU CHOI post=0 apdu=" + card.commands + " oPIN=trong");
                line("TU CHOI lines=" + String.join(" || ", lines));

                fillAdminPin(pins, admin);
                require(onFx(panel::pressReconcile), "nut mo cho GET cham");
                DialogPane confirm = awaitDialog();
                requirePanelPinEmpty(pins);
                typeDialogPin(confirm, admin);
                int apduAtDialog = card.commands.size();
                ledger.pauseEnroll = new CountDownLatch(1);
                ledger.pauseNextEnroll = true;
                click(confirm, "Ghi sổ");
                awaitFlag(() -> ledger.enrollPaused);
                require(dialogPinMatches(confirm, admin) && ledger.posts.get() == 0
                        && card.commands.size() == apduAtDialog && stateCount(card.commands) == 0,
                        "GET dang cho, o PIN con, chua co APDU moi, post=0");
                line("GET dang cho post=0 apdu=" + card.commands + " oPIN=con");
                ledger.pauseEnroll.countDown();
                await(runner, () -> ledger.posts.get() == 1);
                require(dialogPinBlank(confirm) && ledger.posts.get() == 1 && stateCount(card.commands) == 0
                        && card.commands.equals(List.of(0x63, 0x11, 0x63, 0x11, 0x63, 0x11)),
                        "GET xong post=" + ledger.posts.get() + " apdu=" + card.commands);
                line("GET xong post=1 apdu=" + card.commands + " oPIN=trong ledger=counter="
                        + ledger.counter + " balance=" + ledger.balance);
            } finally {
                if (ledger.pauseEnroll != null) {
                    ledger.pauseEnroll.countDown();
                }
                Arrays.fill(admin, '\0');
                onFx(() -> {
                    stage.close();
                    return null;
                });
            }
        } finally {
            runner.close();
            Platform.exit();
        }
    }

    private static void pinHandoff(String scenario) throws Exception {
        line("--- PIN handoff " + scenario + " ---");
        boolean race = scenario.equals("HANDOFF_RACE");
        boolean timeout = scenario.equals("HANDOFF_TIMEOUT");
        if (!race && !timeout && !scenario.equals("HANDOFF_INTERRUPTED")) {
            throw new IllegalArgumentException(scenario);
        }
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        CountDownLatch copied = new CountDownLatch(1);
        CountDownLatch releaseCopy = new CountDownLatch(1);
        AtomicInteger copies = new AtomicInteger();
        AtomicReference<char[]> captured = new AtomicReference<>();
        AtomicReference<char[]> returned = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
        FutureTask<Void> work = new FutureTask<>(() -> {
            try {
                returned.set(FxPinSource.take(() -> {
                    copies.incrementAndGet();
                    char[] pin = RecoveryCardFixture.pin(8);
                    captured.set(pin);
                    copied.countDown();
                    if (race) {
                        awaitLatch(releaseCopy, "release copied PIN");
                    }
                    return pin;
                }));
            } catch (IllegalStateException stopped) {
                failure.set(stopped.getCause());
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
            return null;
        });
        Thread worker = new Thread(work, "pin-handoff-control");
        try {
            if (!race) {
                Platform.runLater(() -> {
                    fxBlocked.countDown();
                    awaitLatch(releaseFx, "release queued PIN");
                });
                awaitLatch(fxBlocked, "block JavaFX before PIN");
            }
            worker.start();
            if (race) {
                awaitLatch(copied, "PIN created before cancellation");
            } else {
                awaitFlag(() -> worker.getState() == Thread.State.TIMED_WAITING);
            }
            if (!timeout) {
                worker.interrupt();
            }
            work.get(15, TimeUnit.SECONDS);
            require(returned.get() == null && (timeout
                    ? failure.get() instanceof java.util.concurrent.TimeoutException
                            && Boolean.FALSE.equals(interruptFlag.get())
                    : failure.get() instanceof InterruptedException && Boolean.TRUE.equals(interruptFlag.get())),
                    scenario + " aborted without handing PIN to caller");
            releaseCopy.countDown();
            releaseFx.countDown();
            onFx(() -> null);
            char[] pin = captured.get();
            require(race ? copies.get() == 1 && pin != null && allZero(pin)
                    : copies.get() == 0 && pin == null,
                    scenario + " copies=" + copies.get() + " lateCopy=0 raceCopyWiped=" + race);
        } finally {
            releaseCopy.countDown();
            releaseFx.countDown();
            worker.interrupt();
            worker.join(15000);
            if (captured.get() != null) {
                Arrays.fill(captured.get(), '\0');
            }
            Platform.exit();
        }
    }

    private static boolean allZero(char[] pin) {
        for (char value : pin) {
            if (value != '\0') {
                return false;
            }
        }
        return true;
    }

    private static void pinExit(String scenario) throws Exception {
        line("--- PIN exit " + scenario + " ---");
        boolean getFailure = scenario.equals("GET_FAILURE");
        boolean interrupted = scenario.equals("PIN_INTERRUPTED");
        if (!getFailure && !interrupted && !scenario.equals("PIN_TIMEOUT")) {
            throw new IllegalArgumentException(scenario);
        }
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        StaffRunner runner = new StaffRunner();
        Stage stage = onFx(Stage::new);
        CountDownLatch releaseFx = new CountDownLatch(1);
        try (CardScript card = new CardScript(false); LedgerDouble ledger = new LedgerDouble(card.publicKey())) {
            ledger.start();
            CardGateway gateway = card.gateway();
            StaffManualReconcile manual = new StaffManualReconcile(gateway, new BackendClient(ledger.baseUrl()));
            TransactionPanel pins = onFx(() -> transactionPins(gateway, runner));
            CountDownLatch requestingPin = new CountDownLatch(1);
            AtomicInteger pinTakes = new AtomicInteger();
            AtomicReference<char[]> takenPin = new AtomicReference<>();
            AtomicReference<RuntimeException> pinFailure = new AtomicReference<>();
            ReceiptPanel panel = onFx(() -> panel(gateway, runner, Adgr13StaffVerify::line, manual, pins,
                    (preview, source) -> manual.confirm(preview, () -> {
                        pinTakes.incrementAndGet();
                        requestingPin.countDown();
                        try {
                            char[] copy = source.take();
                            takenPin.set(copy);
                            return copy;
                        } catch (RuntimeException failure) {
                            pinFailure.set(failure);
                            throw failure;
                        }
                    })));
            onFx(() -> {
                panel.applyLock(false, null, null);
                stage.setScene(new Scene(panel.node()));
                stage.show();
                return null;
            });
            try {
                fillAdminPin(pins, card.pin);
                require(onFx(panel::pressReconcile), "nut mo " + scenario);
                DialogPane confirm = awaitDialog();
                requirePanelPinEmpty(pins);
                typeDialogPin(confirm, card.pin);
                ledger.failNextEnroll = getFailure;
                ledger.pauseEnroll = new CountDownLatch(1);
                ledger.pauseNextEnroll = !getFailure;
                click(confirm, "Ghi sổ");
                if (!getFailure) {
                    awaitFlag(() -> ledger.enrollPaused);
                    CountDownLatch fxBlocked = new CountDownLatch(1);
                    Platform.runLater(() -> {
                        fxBlocked.countDown();
                        awaitLatch(releaseFx, "release JavaFX");
                    });
                    awaitLatch(fxBlocked, "JavaFX blocked");
                    ledger.pauseEnroll.countDown();
                    awaitLatch(requestingPin, "PIN requested after GET");
                    if (interrupted) {
                        runner.activeThread.interrupt();
                    }
                    awaitLatch(runner.operationEnded, "service returned while JavaFX blocked");
                    require(!runner.lastResult.ok() && ledger.posts.get() == 0
                            && card.commands.equals(List.of(0x63, 0x11)) && takenPin.get() == null
                            && pinFailure.get() instanceof IllegalStateException
                            && runner.interruptedAfter == interrupted,
                            scenario + " service stopped, no PIN handed over, newAPDU=0 post=0 interrupt="
                                    + runner.interruptedAfter);
                    releaseFx.countDown();
                }
                await(runner, () -> !runner.lastResult.ok());
                require(dialogPinBlank(confirm) && ledger.posts.get() == 0
                        && card.commands.equals(List.of(0x63, 0x11)) && stateCount(card.commands) == 0
                        && takenPin.get() == null && pinTakes.get() == (getFailure ? 0 : 1),
                        scenario + " field empty after FX resumes, newAPDU=0 post=0 apdu=" + card.commands);
                line(scenario + " ledger=counter=" + ledger.counter + " balance=" + ledger.balance);
            } finally {
                releaseFx.countDown();
                if (ledger.pauseEnroll != null) {
                    ledger.pauseEnroll.countDown();
                }
                onFx(() -> {
                    stage.close();
                    return null;
                });
            }
        } finally {
            runner.close();
            Platform.exit();
        }
    }

    private static void awaitLatch(CountDownLatch latch, String label) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError(label);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label, failure);
        }
    }

    private static boolean dialogPinBlank(DialogPane pane) throws Exception {
        return onFx(() -> dialogPinField(pane).getText().isEmpty());
    }

    private static boolean dialogPinMatches(DialogPane pane, char[] expected) throws Exception {
        return onFx(() -> {
            char[] text = dialogPinField(pane).getText().toCharArray();
            try {
                return Arrays.equals(text, expected);
            } finally {
                Arrays.fill(text, '\0');
            }
        });
    }

    private static void awaitFlag(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("het han cho GET khoa");
    }

    private static void changedVisitStopsBeforePost() throws Exception {
        line("--- GRANT co ca mo, ma ca doi truoc xac nhan ---");
        try (CardScript card = new CardScript(true); LedgerDouble ledger = new LedgerDouble(card.publicKey())) {
            ledger.start();
            CardGateway gateway = card.gateway();
            BackendClient backend = new BackendClient(ledger.baseUrl());
            StaffManualReconcile manual = new StaffManualReconcile(gateway, backend);
            char[] admin = card.pin.clone();
            try {
                StaffManualReconcile.Decision preview = manual.inspect(() -> admin.clone());
                require(preview.kind() == StaffManualReconcile.Kind.NEEDS_CONFIRMATION
                        && preview.detail().contains(VISIT_A)
                        && preview.state().activeVisitId().equals(VISIT_A), preview.detail());
                line("GRANT xem=" + preview.detail());
                int posts = ledger.posts.get();
                CardOperationResult stopped = manual.confirm(preview, () -> admin.clone());
                require(!stopped.ok() && stopped.text().contains("Thẻ đã đổi sau khi xem giao dịch")
                        && ledger.posts.get() == posts && stateCount(card.commands) == 0, stopped.text());
                line("GRANT doi ca post=" + ledger.posts.get() + " apdu=" + card.commands);
                line("GRANT " + stopped.text());
            } finally {
                Arrays.fill(admin, '\0');
            }
        }
    }

    private static void buttonCancelThenConfirm() throws Exception {
        line("--- nut Doi soat giao dich, khong journal ---");
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        StaffRunner runner = new StaffRunner();
        Stage stage = onFx(() -> new Stage());
        try (CardScript card = new CardScript(false); LedgerDouble ledger = new LedgerDouble(card.publicKey())) {
            ledger.start();
            CardGateway gateway = card.gateway();
            BackendClient backend = new BackendClient(ledger.baseUrl());
            StaffManualReconcile manual = new StaffManualReconcile(gateway, backend);
            char[] admin = card.pin.clone();
            List<String> lines = new ArrayList<>();
            TransactionPanel pins = onFx(() -> transactionPins(gateway, runner));
            ReceiptPanel panel = onFx(() -> panel(gateway, runner, lines::add, manual, pins));
            String open = SessionGates.of(new SessionGates.State(false, true, true, true,
                    true, false, false, true, null, gateway.status())).reconcile();
            String locked = SessionGates.of(new SessionGates.State(false, true, true, false,
                    false, false, false, true, null, gateway.status())).reconcile();
            require(open == null && locked != null, "khong journal va duoc giao dich thi nut mo");
            onFx(() -> {
                panel.applyLock(false, null, open);
                stage.setScene(new Scene(panel.node()));
                stage.show();
                return null;
            });
            try {
                fillAdminPin(pins, admin);
                require(onFx(panel::pressReconcile), "nut Doi soat giao dich mo");
                DialogPane cancel = awaitDialog();
                requirePanelPinEmpty(pins);
                String cancelText = dialogText(cancel);
                require(cancelText.contains(VISIT_A) && cancelText.contains("GRANT_QUOTA")
                        && cancelText.contains("Không gửi lệnh đổi trạng thái thẻ."), cancelText);
                require(hasButton(cancel, "Hủy") && hasButton(cancel, "Ghi sổ")
                        && dialogPin(cancel) != null, "hop thoai co Huy, Ghi so va o PIN");
                line("HUY dialog=" + cancelText.replace("\n", " | "));
                click(cancel, "Hủy");
                await(runner, () -> ledger.posts.get() == 0 && lines.stream()
                        .anyMatch(item -> item.contains("nhân viên hủy, không ghi sổ")));
                require(ledger.posts.get() == 0 && stateCount(card.commands) == 0
                        && card.commands.equals(List.of(0x63, 0x11)) && runner.skipped.get() == 0,
                        "HUY post=" + ledger.posts.get() + " apdu=" + card.commands);
                line("HUY post=0 apdu=" + card.commands);

                fillAdminPin(pins, admin);
                require(onFx(panel::pressReconcile), "nut lan thieu PIN van mo");
                DialogPane missing = awaitDialog();
                requirePanelPinEmpty(pins);
                click(missing, "Ghi sổ");
                await(runner, () -> lines.stream().anyMatch(item -> item.contains("thiếu PIN quản trị")));
                require(ledger.posts.get() == 0 && stateCount(card.commands) == 0
                        && card.commands.equals(List.of(0x63, 0x11, 0x63, 0x11)) && runner.skipped.get() == 0,
                        "THIEU PIN post=" + ledger.posts.get() + " apdu=" + card.commands);
                line("THIEU PIN post=0 apdu=" + card.commands);

                fillAdminPin(pins, admin);
                require(onFx(panel::pressReconcile), "nut lan PIN moi van mo");
                DialogPane confirm = awaitDialog();
                requirePanelPinEmpty(pins);
                String confirmText = dialogText(confirm);
                require(confirmText.contains(VISIT_A) && confirmText.contains("counter=2"), confirmText);
                line("GHI SO dialog=" + confirmText.replace("\n", " | "));
                typeDialogPin(confirm, admin);
                click(confirm, "Ghi sổ");
                await(runner, () -> ledger.posts.get() == 1);
                require(ledger.posts.get() == 1 && stateCount(card.commands) == 0
                        && card.commands.equals(List.of(0x63, 0x11, 0x63, 0x11, 0x63, 0x11, 0x63, 0x11))
                        && runner.skipped.get() == 0 && ledger.counter == GRANT_COUNTER
                        && ledger.balance == GRANT_BALANCE && VISIT_A.equals(ledger.visit),
                        "GHI SO post=" + ledger.posts.get() + " apdu=" + card.commands);
                line("GHI SO post=1 apdu=" + card.commands + " ledger=counter=" + ledger.counter
                        + " balance=" + ledger.balance + " ca=" + ledger.visit);
                line("GHI SO lines=" + String.join(" || ", lines));
            } finally {
                Arrays.fill(admin, '\0');
                onFx(() -> {
                    stage.close();
                    return null;
                });
            }
        } finally {
            runner.close();
            Platform.exit();
        }
    }

    private static TransactionPanel transactionPins(CardGateway gateway, StaffRunner runner) {
        return new TransactionPanel(gateway, runner, ignored -> { },
                new TransactionPanel.Session(() -> false, () -> true,
                        (plan, supplier, studentPin) -> {
                            throw new AssertionError("khong gui lenh doi state");
                        },
                        (delta, source) -> {
                            throw new AssertionError("khong cap luot");
                        },
                        () -> { }, () -> { }, ignored -> { }, () -> { }, () -> { }));
    }

    private static void fillAdminPin(TransactionPanel pins, char[] admin) throws Exception {
        onFx(() -> {
            pins.fillInputs(new char[0], admin, 5);
            return null;
        });
    }

    private static void requirePanelPinEmpty(TransactionPanel pins) throws Exception {
        char[] leftover = onFx(pins::takeAdminPin);
        try {
            require(leftover != null && leftover.length == 0, "o PIN lan dau da trong");
        } finally {
            if (leftover != null) {
                Arrays.fill(leftover, '\0');
            }
        }
    }

    private static ReceiptPanel panel(CardGateway gateway, StaffRunner runner, Consumer<String> lines,
            StaffManualReconcile manual, TransactionPanel pins) {
        return panel(gateway, runner, lines, manual, pins, manual::confirm);
    }

    private static ReceiptPanel panel(CardGateway gateway, StaffRunner runner, Consumer<String> lines,
            StaffManualReconcile manual, TransactionPanel pins,
            BiFunction<StaffManualReconcile.Decision, AdminGrantService.PinSource, CardOperationResult> confirm) {
        ReceiptPanel.AdminRecovery recovery = new ReceiptPanel.AdminRecovery(pins::requireAdminPin,
                pins::takeAdminPin, pins::clearAdminPin,
                source -> { throw new AssertionError("pending GRANT khong thuoc ca nay"); },
                (proof, pin) -> { throw new AssertionError("SUPERSEDED khong thuoc ca nay"); },
                manual::inspect, confirm);
        return new ReceiptPanel(gateway, runner, lines, new ReceiptPanel.Session(() -> true,
                PendingJournal.Loaded::absent, Path.of("fixture", "absent-journal.txt"),
                () -> new char[0], () -> { },
                pin -> { throw new AssertionError("doi soat pending khong thuoc ca nay"); },
                recovery, () -> { }, () -> { }, () -> { }, () -> { },
                ignored -> { }, ignored -> { }, () -> { }));
    }

    private static DialogPane awaitDialog() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            DialogPane pane = onFx(Adgr13StaffVerify::visibleDialog);
            if (pane != null) {
                return pane;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("khong thay hop thoai");
    }

    private static DialogPane visibleDialog() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    private static boolean hasButton(DialogPane pane, String label) {
        return pane.getButtonTypes().stream().anyMatch(type -> label.equals(type.getText()));
    }

    private static String dialogText(DialogPane pane) throws Exception {
        return onFx(() -> {
            StringBuilder text = new StringBuilder();
            pane.lookupAll(".label").forEach(node -> {
                if (node instanceof Label label && label.getText() != null) {
                    text.append(label.getText()).append('\n');
                }
            });
            return text.toString();
        });
    }

    private static PasswordField dialogPin(DialogPane pane) throws Exception {
        return onFx(() -> (PasswordField) pane.lookup(".password-field"));
    }

    private static void typeDialogPin(DialogPane pane, char[] pin) throws Exception {
        onFx(() -> {
            PasswordField field = dialogPinField(pane);
            field.setText(new String(pin));
            return null;
        });
    }

    private static PasswordField dialogPinField(DialogPane pane) {
        PasswordField field = (PasswordField) pane.lookup(".password-field");
        if (field == null) {
            throw new AssertionError("hop thoai khong co o PIN");
        }
        return field;
    }

    private static void click(DialogPane pane, String label) throws Exception {
        onFx(() -> {
            ButtonType chosen = pane.getButtonTypes().stream()
                    .filter(type -> label.equals(type.getText())).findFirst().orElseThrow();
            ((Button) pane.lookupButton(chosen)).fire();
            return null;
        });
    }

    private static void await(StaffRunner runner, BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!runner.busy && done.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("het han cho nut doi soat");
    }

    private static int stateCount(List<Integer> commands) {
        int count = 0;
        for (int ins : commands) {
            if (ins == 0x50 || ins == 0x51 || ins == 0x52) {
                count++;
            }
        }
        return count;
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        line("PASS " + message);
    }

    private static void line(String text) {
        System.out.println(text);
        log.println(text);
    }

    private static final class StaffRunner implements CardOpRunner {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "doi-soat-tay");
            thread.setDaemon(true);
            return thread;
        });
        private volatile boolean busy;
        private volatile boolean rejectNext;
        private volatile Thread activeThread;
        private volatile CardOperationResult lastResult;
        private volatile boolean interruptedAfter;
        private volatile CountDownLatch operationEnded = new CountDownLatch(1);
        private final AtomicInteger skipped = new AtomicInteger();

        @Override
        public boolean run(String label, Callable<CardOperationResult> operation, Consumer<Boolean> onResult) {
            if (rejectNext) {
                rejectNext = false;
                skipped.incrementAndGet();
                line("[" + label + "] bo qua — dang dong hoac ban");
                return false;
            }
            if (busy) {
                skipped.incrementAndGet();
                line("[" + label + "] bo qua — dang xu ly thao tac khac");
                return false;
            }
            busy = true;
            operationEnded = new CountDownLatch(1);
            worker.submit(() -> {
                activeThread = Thread.currentThread();
                CardOperationResult result;
                try {
                    result = operation.call();
                } catch (Exception failure) {
                    result = CardOperationResult.fail(failure.getClass().getSimpleName());
                }
                lastResult = result;
                interruptedAfter = Thread.currentThread().isInterrupted();
                operationEnded.countDown();
                CardOperationResult done = result;
                Platform.runLater(() -> {
                    line((done.ok() ? "[OK] " : "[FAIL] ") + label + ": " + done.text());
                    onResult.accept(done.ok());
                    busy = false;
                });
            });
            return true;
        }

        void close() {
            worker.shutdown();
        }
    }

    private static byte[] statusBytes(String visitHex) {
        byte[] visit = HexFormat.of().parseHex(visitHex);
        return ByteBuffer.allocate(43 + AID.length).put("LABACCESS".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1).put((byte) AID.length).put(AID).put((byte) 1)
                .put(HexFormat.of().parseHex(CARD_ID)).putShort((short) GRANT_BALANCE).putInt(GRANT_COUNTER)
                .put((byte) 1).put(visit).array();
    }

    private static byte[] signedGrant(KeyPair key) throws Exception {
        byte[] payload = ByteBuffer.allocate(34).put((byte) 1).put(HexFormat.of().parseHex(CARD_ID))
                .putInt(GRANT_COUNTER).put((byte) Receipt.OPERATION_GRANT).putShort((short) GRANT_DELTA)
                .putShort((short) GRANT_BALANCE).put(new byte[16]).array();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
        signer.update(payload);
        byte[] signature = signer.sign();
        return ByteBuffer.allocate(35 + signature.length).put(payload).put((byte) signature.length)
                .put(signature).array();
    }

    private static ResponseAPDU response(byte[] data, int sw) {
        return new ResponseAPDU(ByteBuffer.allocate(data.length + 2).put(data).putShort((short) sw).array());
    }

    private static final class CardScript extends CardChannel implements AutoCloseable {
        private final char[] pin = RecoveryCardFixture.pin(8);
        private final KeyPair keys;
        private final byte[] receipt;
        private final byte[] openVisit = statusBytes(VISIT_A);
        private final byte[] otherVisit = statusBytes(VISIT_B);
        private final boolean swapVisit;
        private final List<Integer> commands = new ArrayList<>();
        private int statusReads;

        CardScript(boolean swapVisit) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keys = generator.generateKeyPair();
            receipt = signedGrant(keys);
            this.swapVisit = swapVisit;
        }

        String publicKey() {
            return HexFormat.of().formatHex(ReceiptVerifier.encodePoint(keys.getPublic()));
        }

        CardGateway gateway() throws Exception {
            CardGateway gateway = new CardGateway(this);
            if (!gateway.identifyWith(AID).ok()) {
                throw new AssertionError("fixture identify");
            }
            commands.clear();
            return gateway;
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            int ins = command.getINS();
            if (ins == 0xA4) {
                return response(new byte[0], Sw.OK);
            }
            if (ins == 0x10) {
                return response(Arrays.copyOf(openVisit, 20 + (openVisit[10] & 0xFF)), Sw.OK);
            }
            if (ins != 0x11 && ins != 0x63) {
                commands.add(ins);
                throw new AssertionError("unexpected instruction " + ins);
            }
            commands.add(ins);
            if (!adminPinMatches(command)) {
                return response(new byte[0], Sw.SECURITY_NOT_SATISFIED);
            }
            byte[] body = receipt;
            if (ins == 0x11) {
                statusReads++;
                body = swapVisit && statusReads >= 2 ? otherVisit : openVisit;
            }
            return response(body, Sw.OK);
        }

        private boolean adminPinMatches(CommandAPDU command) {
            byte[] actual = command.getBytes();
            try {
                if (command.getNc() != pin.length || actual.length < 5 + pin.length) {
                    return false;
                }
                for (int index = 0; index < pin.length; index++) {
                    if (actual[5 + index] != (byte) pin[index]) {
                        return false;
                    }
                }
                return true;
            } finally {
                Arrays.fill(actual, (byte) 0);
            }
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
        }
    }

    private static final class LedgerDouble implements AutoCloseable {
        private final ServerSocket server;
        private final String publicKey;
        final AtomicInteger posts = new AtomicInteger();
        volatile CountDownLatch pauseEnroll;
        volatile boolean pauseNextEnroll;
        volatile boolean enrollPaused;
        volatile boolean failNextEnroll;
        volatile long counter = 1;
        volatile int balance = GRANT_BALANCE - GRANT_DELTA;
        volatile String visit = VISIT_A;
        private volatile boolean open = true;

        LedgerDouble(String publicKey) throws IOException {
            this.publicKey = publicKey;
            server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        }

        void start() {
            Thread thread = new Thread(this::serve, "adgr13-staff-ledger");
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/";
        }

        @Override
        public void close() throws IOException {
            open = false;
            server.close();
        }

        private void serve() {
            while (open) {
                try (Socket socket = server.accept()) {
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    String request = readHeaders(input);
                    byte[] body = input.readNBytes(contentLength(request));
                    String head = request.substring(0, request.indexOf("\r\n"));
                    String method = head.substring(0, head.indexOf(' '));
                    String path = head.substring(head.indexOf(' ') + 1, head.lastIndexOf(' '));
                    byte[] reply = route(method, path, body);
                    if (reply == null) {
                        continue;
                    }
                    BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                    byte[] header = ("HTTP/1.1 200 X\r\nContent-Type: application/json\r\nContent-Length: "
                            + reply.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    if (new String(reply, StandardCharsets.UTF_8).contains("\"status\"")) {
                        header = ("HTTP/1.1 201 X\r\nContent-Type: application/json\r\nContent-Length: "
                                + reply.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII);
                    }
                    if (new String(reply, StandardCharsets.UTF_8).contains("enrollmentFailure")) {
                        header = ("HTTP/1.1 503 X\r\nContent-Type: application/json\r\nContent-Length: "
                                + reply.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII);
                    }
                    output.write(header);
                    output.write(reply);
                    output.flush();
                } catch (Exception failure) {
                    if (open) {
                        line("ledger socket: " + failure.getClass().getSimpleName());
                    }
                }
            }
        }

        private byte[] route(String method, String path, byte[] raw) {
            if ("GET".equals(method) && path.equalsIgnoreCase("/enrollments/cards/" + CARD_ID)) {
                if (failNextEnroll) {
                    failNextEnroll = false;
                    return json("{\"message\":\"enrollmentFailure\"}");
                }
                if (pauseNextEnroll) {
                    pauseNextEnroll = false;
                    enrollPaused = true;
                    try {
                        if (pauseEnroll != null) {
                            pauseEnroll.await(25, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return json("{\"cardId\":\"" + CARD_ID + "\",\"publicKey\":\"" + publicKey + "\"}");
            }
            if ("GET".equals(method) && path.equalsIgnoreCase("/receipts/ledger/" + CARD_ID)) {
                return json("{\"cardId\":\"" + CARD_ID + "\",\"lastCounter\":" + counter
                        + ",\"lastBalance\":" + balance + ",\"activeVisitId\":\"" + visit + "\"}");
            }
            if ("GET".equals(method) && path.startsWith("/cards/")) {
                return null;
            }
            if ("POST".equals(method) && "/receipts/verify".equals(path)) {
                return verify(new String(raw, StandardCharsets.UTF_8));
            }
            return json("{\"message\":\"khong co\"}");
        }

        private byte[] verify(String body) {
            posts.incrementAndGet();
            String payloadHex = jsonString(body, "payload");
            String signatureHex = jsonString(body, "signature");
            Receipt receipt = ReceiptCodec.parsePayload(HexFormat.of().parseHex(payloadHex),
                    HexFormat.of().parseHex(signatureHex)).receipt();
            counter = receipt.counterAfter();
            balance = receipt.balanceAfter();
            return json("{\"status\":\"recorded\",\"cardId\":\"" + receipt.cardIdHex()
                    + "\",\"operation\":\"GRANT\",\"counterAfter\":" + receipt.counterAfter()
                    + ",\"delta\":" + receipt.delta() + ",\"balanceAfter\":" + receipt.balanceAfter()
                    + ",\"visitId\":null}");
        }

        private static String jsonString(String body, String key) {
            String mark = "\"" + key + "\":\"";
            int start = body.indexOf(mark) + mark.length();
            return body.substring(start, body.indexOf('"', start));
        }

        private static byte[] json(String body) {
            return body.getBytes(StandardCharsets.UTF_8);
        }

        private static String readHeaders(BufferedInputStream input) throws IOException {
            StringBuilder text = new StringBuilder();
            int previous = -1;
            int current;
            while ((current = input.read()) >= 0) {
                text.append((char) current);
                if (previous == '\r' && current == '\n' && text.toString().endsWith("\r\n\r\n")) {
                    return text.toString();
                }
                previous = current;
            }
            return text.toString();
        }

        private static int contentLength(String headers) {
            for (String header : headers.split("\r\n")) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    return Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                }
            }
            return 0;
        }
    }
}
