package vn.labaccess.desktop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptVerifier;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.ui.shared.ActionNote;
import vn.labaccess.desktop.ui.shared.HistoryPanel;
import vn.labaccess.desktop.ui.shared.SystemConnectionPanel;

import vn.labaccess.desktop.ui.pin.PinPanel;

import vn.labaccess.desktop.ui.receipt.ReceiptPanel;

public final class OperatorRecoveryUiVerify {
    private static App app;
    private static Stage stage;
    private static RecoveryCardFixture card;
    private static CardGateway gateway;
    private static PendingJournal journal;
    private static int checks;
    private static final Path EVIDENCE = Path.of("desktop/target/operator-recovery-ui");

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("labaccess-ui-recovery");
        System.setProperty("labaccess.journal", temporary.resolve("pending.txt").toString());
        System.setProperty("labaccess.demo", "false");
        card = new RecoveryCardFixture();
        try (RecoveryBackendFixture backend = new RecoveryBackendFixture(OperatorRecoveryUiVerify::grantReply)) {
            System.setProperty("labaccess.backend", backend.url());
            Platform.startup(() -> { });
            Platform.setImplicitExit(false);
            try {
                fx(() -> {
                    app = new App();
                    stage = new Stage();
                    app.start(stage);
                    stage.setWidth(1180);
                    stage.setHeight(760);
                    return null;
                });
                gateway = (CardGateway) field(field(app, "session"), "gateway");
                Object transport = field(gateway, "transport");
                set(transport, "channel", card);
                set(transport, "connectedFlag", true);
                gateway.identify();
                gateway.setReceiptSink(card);
                gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "fixture"));
                journal = (PendingJournal) field(field(app, "session"), "journal");
                fx(() -> { invoke(app, "syncCardConnection"); app.refreshButtons(); return null; });
                receiptRecovery();
                wrongPinKeepsLogChoice();
                confirmedTransaction();
                backend.counter = card.counter;
                backend.balance = card.balance;
                confirmedReconciliation();
                backend.counter = 1;
                wrongCard();
                backendBadge(backend);
                brokenConnection();
                System.out.println("OPERATOR RECOVERY UI PASS: " + checks + " checks");
            } finally {
                if (app != null) {
                    Object taskRunner = field(app, "taskRunner");
                    ((ExecutorService) field(taskRunner, "executor")).shutdownNow();
                }
                fx(() -> { if (stage != null) stage.hide(); return null; });
                Platform.exit();
            }
        } finally {
            Files.deleteIfExists(temporary.resolve("pending.txt"));
            Files.delete(temporary);
        }
    }

    private static RecoveryBackendFixture.Reply grantReply(String request, String body) {
        String route = request.split(" ")[1];
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("cardId", card.cardId);
        if (route.startsWith("/enrollments/cards/")) {
            result.put("publicKey", java.util.HexFormat.of().formatHex(ReceiptVerifier.encodePoint(card.keys.getPublic())));
            return new RecoveryBackendFixture.Reply(200, result.toString());
        }
        if (route.equals("/receipts/verify")) {
            result.put("status", "recorded").put("operation", "GRANT").put("counterAfter", card.counter)
                    .put("delta", 5).put("balanceAfter", card.balance).putNull("visitId");
            return new RecoveryBackendFixture.Reply(201, result.toString());
        }
        return null;
    }

    private static void readCard() throws Exception {
        char[] pin = RecoveryCardFixture.pin(6);
        try {
            fx(() -> {
                app.showTab(App.TAB_OPERATIONS);
                app.fillTransactionInputs(pin, null, 5);
                check("read button starts operation", app.pressReadReceipt());
                return null;
            });
            awaitOperation();
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    private static void receiptRecovery() throws Exception {
        card.receiptMode = 3;
        readCard();
        fx(() -> {
            check("unverified receipt has recovery banner", note().contains("Chưa xác minh được biên nhận"));
            check("unverified receipt has distinct header",
                    ((Label) ((ReceiptPanel) field(app, "receiptPanel")).reconcileStatus()).getText().equals("Cần xác minh biên nhận"));
            check("invalid receipt keeps mutation locked", !app.pressGrant() && !app.pressReconcile());
            check("invalid receipt does not force log open", !((TitledPane) field(app, "logPane")).isExpanded());
            return null;
        });
        card.receiptMode = 0;
        readCard();
        fx(() -> {
            check("verified read clears verification header",
                    ((Label) ((ReceiptPanel) field(app, "receiptPanel")).reconcileStatus()).getText().equals("Không có giao dịch chờ"));
            check("same read action recovers without mutation", gateway.reconciliation().transactionAllowed()
                    && (boolean) field(field(app, "session"), "snapshotKnown") && card.stateCommands == 0);
            return null;
        });
    }

    private static void wrongPinKeepsLogChoice() throws Exception {
        for (boolean expanded : new boolean[] {false, true}) {
            char[] pin = RecoveryCardFixture.pin(6);
            try {
                card.pinSw = 0x63C2;
                fx(() -> {
                    ((ToggleButton) field(app, "logToggle")).setSelected(expanded);
                    app.showTab(App.TAB_CARD);
                    PinPanel panel = (PinPanel) field(app, "pinPanel");
                    panel.fillInputs(pin, null, null);
                    check("PIN button starts operation", panel.pressVerify());
                    return null;
                });
                awaitOperation();
                fx(() -> {
                    check("PIN failure preserves log choice " + expanded,
                            ((TitledPane) field(app, "logPane")).isExpanded() == expanded);
                    check("PIN failure explains remaining tries", note().contains("còn 2 lượt thử"));
                    ((ToggleButton) field(app, "logToggle")).setSelected(false);
                    return null;
                });
            } finally {
                Arrays.fill(pin, '\0');
                card.pinSw = Sw.OK;
            }
        }
    }

    private static void confirmedTransaction() throws Exception {
        card.failRefreshAfterSave = true;
        char[] pin = RecoveryCardFixture.pin(6);
        char[] admin = RecoveryCardFixture.pin(8);
        try {
            fx(() -> {
                app.showTab(App.TAB_OPERATIONS);
                app.fillTransactionInputs(pin, admin, 5);
                check("grant button starts operation", app.pressGrant());
                return null;
            });
            awaitOperation();
            fx(() -> {
                check("completed grant is not reported as failure", note().contains("cấp lượt")
                        && note().contains("cần đọc lại trạng thái") && !note().contains("Lỗi:"));
                check("completed grant keeps Student operations locked on a stale snapshot",
                        !(boolean) field(field(app, "session"), "snapshotKnown") && !app.pressStart());
                check("grant executed once and journal resolved", card.stateCommands == 1 && card.counter == 2
                        && !journal.load().present());
                app.captureScene(EVIDENCE.resolve("confirmed-refresh-wide.png"));
                stage.setWidth(900);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                app.captureScene(EVIDENCE.resolve("confirmed-refresh-narrow.png"));
                return null;
            });
        } finally {
            card.failRefreshAfterSave = false;
            card.statusSw = Sw.OK;
            Arrays.fill(pin, '\0');
            Arrays.fill(admin, '\0');
        }
        readCard();
    }

    private static void confirmedReconciliation() throws Exception {
        check("fixture pending represents committed grant", journal.writePending(new PendingJournal.Entry(card.cardId,
                card.counter, Receipt.OPERATION_GRANT, 5, card.balance, null, System.currentTimeMillis())));
        gateway.markTransactionUnknown("fixture lost response");
        char[] pin = RecoveryCardFixture.pin(8);
        int commandsBefore = card.stateCommands;
        try {
            fx(() -> {
                app.refreshButtons();
                app.fillTransactionInputs(new char[0], pin, 5);
                Platform.runLater(() -> {
                    for (var window : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
                        if (window instanceof Stage dialog && dialog != stage) {
                            var pane = (javafx.scene.control.DialogPane) dialog.getScene().lookup(".dialog-pane");
                            if (pane != null) {
                                var button = pane.getButtonTypes().stream()
                                        .filter(type -> type.getText().equals("Đối soát giao dịch")).findFirst();
                                if (button.isPresent()) ((Button) pane.lookupButton(button.get())).fire();
                            }
                        }
                    }
                });
                check("reconcile button starts confirmed dialog flow", app.pressReconcile());
                return null;
            });
            awaitOperation();
            fx(() -> {
                check("completed Admin reconciliation closes the journal",
                        !note().contains("Lỗi:") && !journal.load().present());
                check("reconciliation never repeats state command", card.stateCommands == commandsBefore);
                return null;
            });
        } finally {
            Arrays.fill(pin, (char) 0);
            card.failRefreshAfterSave = false;
            card.statusSw = Sw.OK;
        }
        readCard();
    }

    private static void wrongCard() throws Exception {
        String expected = "0011223344556677";
        check("fixture writes pending", journal.writePending(new PendingJournal.Entry(expected, 1,
                Receipt.OPERATION_GRANT, 5, 5, null, System.currentTimeMillis())));
        int pins = card.pinCommands;
        fx(() -> {
            app.refreshButtons();
            check("pending journal overrides a still-known session in header",
                    ((Label) ((ReceiptPanel) field(app, "receiptPanel")).reconcileStatus()).getText().equals("Cần đối soát"));
            ((ReceiptPanel) field(app, "receiptPanel")).offerPendingReconciliation();
            check("wrong card explains both IDs", note().contains(expected)
                    && note().contains(card.cardId) && note().contains("Đưa lại thẻ"));
            check("wrong card updates header", ((Label) ((ReceiptPanel) field(app, "receiptPanel")).reconcileStatus()).getText().equals("Cần đối soát"));
            check("wrong card blocks reconciliation without PIN", !app.pressReconcile()
                    && pins == card.pinCommands && journal.load().present());
            app.captureScene(EVIDENCE.resolve("wrong-card-narrow.png"));
            return null;
        });
        card.cardId = expected;
        gateway.identify();
        fx(() -> {
            invoke(app, "syncCardConnection");
            app.refreshButtons();
            check("right card restores reconciliation action", ((ReceiptPanel) field(app, "receiptPanel"))
                    .reconcileEnabled() && journal.load().present());
            return null;
        });
        journal.remove();
        gateway.reconciliation().markKnown("fixture teardown");
        card.cardId = RecoveryCardFixture.CARD_ID;
        gateway.identify();
        gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "fixture"));
        fx(() -> { invoke(app, "syncCardConnection"); app.refreshButtons(); return null; });
    }

    private static void backendBadge(RecoveryBackendFixture backend) throws Exception {
        backend.balance = 99;
        for (int status : new int[] {200, 500, 404, 200}) {
            backend.status = status;
            fx(() -> { check("history load starts", app.pressLoadHistory()); return null; });
            awaitOperation();
            fx(() -> {
                Label badge = (Label) ((SystemConnectionPanel) field(app, "systemConnectionPanel")).statusNode();
                String expected = status == 500 ? "Hệ thống báo lỗi" : "Có phản hồi";
                if (status == 500) {
                    HistoryPanel history = (HistoryPanel) field(app, "historyPanel");
                    Label verdict = (Label) history.node().lookup("#history-verdict");
                    Label load = (Label) history.node().lookup("#history-load-status");
                    check("live reload failure retains mismatch", verdict.getText().contains("khác sổ Backend 99")
                            && load.isVisible() && load.getText().contains("lần tải trước") && history.rowCount() == 1);
                    app.revealHistory();
                    app.captureScene(EVIDENCE.resolve("history-mismatch-with-load-error.png"));
                }
                check("latest HTTP " + status + " reaches badge", badge.getText().startsWith(expected)
                        && badge.getText().contains(" · ") && badge.getTooltip().getText().contains("HTTP " + status));
                return null;
            });
        }
        backend.close();
        fx(() -> { app.pressLoadHistory(); return null; });
        awaitOperation();
        fx(() -> {
            Label badge = (Label) ((SystemConnectionPanel) field(app, "systemConnectionPanel")).statusNode();
            check("history transport failure updates badge", badge.getText().startsWith("Mất kết nối"));
            return null;
        });
    }

    private static void brokenConnection() throws Exception {
        card.failTransport = true;
        readCard();
        fx(() -> {
            check("transport loss removes ready badge", !((Label) field(app, "valueResult")).getText().equals("Sẵn sàng")
                    && ((Label) field(app, "valueConnection")).getText().equals("Cần kết nối lại"));
            check("transport loss offers disconnect instead of stale reads",
                    !((Button) field(app, "btnDisconnect")).isDisabled()
                    && ((Button) field(app, "btnConnect")).isDisabled() && !app.pressReadReceipt()
                    && note().contains("Ngắt kết nối rồi Kết nối lại"));
            check("transport loss states the reconnect guidance once",
                    count(note(), "Ngắt kết nối rồi Kết nối lại") == 1);
            app.captureScene(EVIDENCE.resolve("transport-recovery-narrow.png"));
            check("disconnect works after transport loss", app.pressDisconnect());
            return null;
        });
        awaitOperation();
        fx(() -> {
            check("disconnect restores connect action", !((Button) field(app, "btnConnect")).isDisabled()
                    && !gateway.needsReconnect());
            return null;
        });
    }

    private static int count(String text, String part) {
        int total = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length())) {
            total++;
        }
        return total;
    }

    private static String note() throws Exception {
        ActionNote note = (ActionNote) field(app, "actionNote");
        return ((Label) note.node().getChildren().getFirst()).getText();
    }

    private static void awaitOperation() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        Object taskRunner = field(app, "taskRunner");
        ((ExecutorService) field(taskRunner, "executor")).submit(() -> Platform.runLater(finished::countDown));
        if (!finished.await(25, TimeUnit.SECONDS)) throw new AssertionError("UI operation did not finish");
        fx(() -> { check("operation exits busy", !app.isBusy()); return null; });
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void invoke(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> future = new FutureTask<>(action);
        Platform.runLater(future);
        return future.get(15, TimeUnit.SECONDS);
    }

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
}
