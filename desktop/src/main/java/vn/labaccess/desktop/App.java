package vn.labaccess.desktop;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Gate 0 — smoke UI: Connect / SELECT+kiem tra / Echo / Disconnect qua
 * javax.smartcardio va socket provider cua Oracle Simulator.
 *
 * Khong co logic nghiep vu. Moi thao tac the chay tren mot executor nen tuan tu;
 * giao dien chi duoc cap nhat qua Platform.runLater.
 *
 * Khi dong cua so: dat closing=true, dung demo, khoa moi nut, roi moi xep hang
 * viec ngat ket noi tren cung executor I/O. Khong thao tac nao duoc xep sau do.
 */
public class App extends Application {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final CardGateway gateway = new CardGateway(
            System.getProperty("labaccess.host", "127.0.0.1"),
            Integer.getInteger("labaccess.port", 9025));

    /* Executor tuan tu: the khong an toan da luong. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "card-io");
        t.setDaemon(true);
        return t;
    });

    private TextArea logArea;
    private Label statusLabel;

    private Button btnConnect;
    private Button btnSelect;
    private Button btnEcho;
    private Button btnDisconnect;

    /* Timeline phai giu thanh field de stop() duoc khi dong cua so. */
    private Timeline demoTimeline;

    /* Bat true khi viec don dep da chay xong, cho watchdog biet khong can cuong che. */
    private final java.util.concurrent.atomic.AtomicBoolean cleanupDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /* Trang thai chi doc/ghi tren FX thread. */
    private boolean busy;
    private boolean closing;
    private boolean uiConnected;
    private boolean uiReady;

    private long pid;

    @Override
    public void start(Stage stage) {
        pid = ProcessHandle.current().pid();
        log("PID=" + pid);
        log("Java " + System.getProperty("java.version")
                + " | JavaFX " + System.getProperty("javafx.runtime.version", "?"));
        log("Socket target " + System.getProperty("labaccess.host", "127.0.0.1")
                + ":" + Integer.getInteger("labaccess.port", 9025));

        btnConnect = new Button("Connect");
        btnSelect = new Button("SELECT + kiem tra");
        btnEcho = new Button("Echo");
        btnDisconnect = new Button("Disconnect");

        btnConnect.setOnAction(e -> doConnect());
        btnSelect.setOnAction(e -> doSelect());
        btnEcho.setOnAction(e -> doEcho());
        btnDisconnect.setOnAction(e -> doDisconnect());

        HBox buttons = new HBox(10, btnConnect, btnSelect, btnEcho, btnDisconnect);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(10));

        statusLabel = new Label("Chua ket noi");
        statusLabel.setId("status");
        statusLabel.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(6, 10, 8, 10));

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(buttons);
        root.setCenter(logArea);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 980, 560);
        stage.setTitle("LabAccess Gate 0 — JavaFX smoke (PID " + pid + ")");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (closing) {
                return;   /* dang dong, bo qua yeu cau lap lai */
            }
            e.consume();  /* hoan viec dong lai cho toi khi da ngat ket noi */
            closing = true;
            shutdown();
        });
        stage.show();

        updateButtons();

        if (Boolean.getBoolean("labaccess.demo")) {
            stage.setAlwaysOnTop(true);
            startDemo();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Actions                                                            */
    /* ------------------------------------------------------------------ */

    private void doConnect() {
        runOp("CONNECT", gateway::connect, ok -> {
            uiConnected = ok;
            uiReady = false;
        });
    }

    /** SELECT + kiem tra: chi dat ready khi ca SW lan probe deu dat. */
    private void doSelect() {
        runOp("SELECT + kiem tra", gateway::identify, ok -> uiReady = ok);
    }

    /** Test am: SELECT khong co AID -> Simulator tra 9000 nhung probe phai hong. */
    private void doSelectNoAid() {
        runOp("SELECT khong AID + kiem tra", gateway::identifyNoAid, ok -> uiReady = ok);
    }

    private void doEcho() {
        runOp("ECHO", gateway::echo, null);
    }

    private void doDisconnect() {
        runOp("DISCONNECT", gateway::disconnect, ok -> {
            uiConnected = false;
            uiReady = false;
        });
    }

    /**
     * Chay mot thao tac the tren luong nen, khoa nut trong luc xu ly.
     * onResult nhan CA true va false de UI khong giu trang thai cu khi that bai.
     * Tu choi moi thao tac moi khi dang dong cua so.
     */
    private void runOp(String label, Callable<CardGateway.Result> op, Consumer<Boolean> onResult) {
        if (closing) {
            log("[" + label + "] bo qua — dang dong cua so");
            return;
        }
        if (busy) {
            log("[" + label + "] bo qua — dang xu ly thao tac khac");
            return;
        }
        busy = true;
        updateButtons();
        log("> " + label + " ...");

        worker.submit(() -> {
            String line;
            boolean ok = false;
            try {
                CardGateway.Result r = op.call();
                ok = r.ok();
                line = (ok ? "[OK]   " : "[FAIL] ") + label + ": " + r.text();
            } catch (Exception ex) {
                line = "[ERROR] " + label + ": " + ex;
            }
            final boolean okFinal = ok;
            final String lineFinal = line;
            Platform.runLater(() -> {
                log(lineFinal);
                if (onResult != null) {
                    onResult.accept(okFinal);
                }
                busy = false;
                updateButtons();
            });
        });
    }

    private void updateButtons() {
        /* Dang dong: khoa toan bo, khong cho bat ky thao tac moi */
        if (closing) {
            btnConnect.setDisable(true);
            btnSelect.setDisable(true);
            btnEcho.setDisable(true);
            btnDisconnect.setDisable(true);
            statusLabel.setText("Trang thai: dang dong cua so");
            return;
        }

        boolean connected = uiConnected;
        boolean ready = uiReady;

        btnConnect.setDisable(busy || connected);
        btnSelect.setDisable(busy || !connected);
        /* Echo chi bat sau khi SELECT + kiem tra thanh cong */
        btnEcho.setDisable(busy || !connected || !ready);
        btnDisconnect.setDisable(busy || !connected);

        String state = busy ? "dang xu ly"
                : !connected ? "chua ket noi"
                : !ready ? "da ket noi, chua san sang"
                : "da ket noi, san sang";
        statusLabel.setText("Trang thai: " + state);
    }

    /* ------------------------------------------------------------------ */

    private void log(String message) {
        System.out.println("[" + LocalTime.now().format(TS) + "] " + message);
        if (logArea != null) {
            logArea.appendText("[" + LocalTime.now().format(TS) + "] " + message + System.lineSeparator());
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    /**
     * Dong cua so theo kieu hoan: dung demo, khoa nut, ngat ket noi the tren
     * luong nen (khong chan UI), roi moi thoat.
     */
    private void shutdown() {
        log("Dong cua so — dung demo va ngat ket noi tren luong nen");

        if (demoTimeline != null) {
            demoTimeline.stop();
            demoTimeline = null;
            log("DEMO: da dung Timeline");
        }

        updateButtons();   /* closing=true => khoa het */

        String pending = busy ? "co thao tac dang chay, se xep sau khi xong" : "khong co thao tac dang chay";
        log("Dong cua so — " + pending);

        /* Xep hang viec don dep SAU cung tren cung executor I/O */
        worker.submit(() -> {
            String line;
            try {
                line = "[OK]   Dong ket noi: " + gateway.disconnect().text();
            } catch (Exception ex) {
                line = "[ERROR] Loi khi dong ket noi: " + ex;
            }
            final String lineFinal = line;
            cleanupDone.set(true);
            Platform.runLater(() -> {
                log(lineFinal);
                log("Dong cua so — thoat");
                worker.shutdownNow();
                /* cac luong card-io la daemon nen JVM thoat du I/O con treo */
                Platform.exit();
            });
        });

        startCloseWatchdog();
    }

    /**
     * Neu mot thao tac the dang bi treo vo han, viec don dep xep sau se khong bao gio
     * chay va cua so khong dong duoc. Watchdog cuong che thoat sau 5 giay.
     */
    private void startCloseWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                return;
            }
            if (cleanupDone.get()) {
                return;
            }
            Platform.runLater(() -> {
                log("Dong cua so — qua 5s chua don dep xong, cuong che thoat");
                Platform.exit();
            });
        }, "close-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /* ------------------------------------------------------------------ */
    /* Demo: tu bam dung cac nut do, khong di tat qua logic              */
    /* ------------------------------------------------------------------ */

    private void startDemo() {
        log("DEMO: tu dong bam nut theo thu tu (cung handler voi nguoi dung)");
        Runnable[] steps = {
                () -> fire(btnConnect, "Connect"),
                /* Am 1: Echo phai bi khoa truoc khi san sang */
                () -> fire(btnEcho, "Echo truoc SELECT (ky vong bi khoa)"),
                () -> fire(btnSelect, "SELECT + kiem tra"),
                () -> fire(btnEcho, "Echo"),
                /* Am 2: SELECT khong AID -> SW 9000 nhung probe hong => chua san sang */
                () -> runDemoSelectNoAid(),
                /* Sau buoc am, Echo phai bi khoa lai */
                () -> fire(btnEcho, "Echo sau nhan dien that bai (ky vong bi khoa)"),
                () -> fire(btnSelect, "SELECT + kiem tra"),
                () -> fire(btnEcho, "Echo"),
                () -> fire(btnDisconnect, "Disconnect"),
        };

        demoTimeline = new Timeline();
        for (int i = 0; i < steps.length; i++) {
            final Runnable step = steps[i];
            demoTimeline.getKeyFrames()
                    .add(new KeyFrame(Duration.millis(800 + i * 2000L), e -> step.run()));
        }
        demoTimeline.getKeyFrames().add(new KeyFrame(
                Duration.millis(800 + steps.length * 2000L),
                e -> {
                    log("DEMO: xong");
                    updateButtons();
                }));
        demoTimeline.play();
    }

    private void fire(Button button, String name) {
        if (button.isDisabled()) {
            log("DEMO: bo qua buoc " + name + " (nut dang bi khoa)");
            return;
        }
        button.fire();
    }

    /** Buoc demo: nhan dien bang SELECT khong co AID (khong co nut rieng tren UI). */
    private void runDemoSelectNoAid() {
        if (busy || closing) {
            log("DEMO: bo qua buoc SELECT khong AID (dang ban hoac dang dong)");
            return;
        }
        doSelectNoAid();
        log("DEMO: sau buoc nay Echo phai bi khoa neu xu ly dung");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
