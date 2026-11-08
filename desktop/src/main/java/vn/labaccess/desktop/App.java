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
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public class App extends Application {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final CardGateway gateway = new CardGateway(
            System.getProperty("labaccess.host", "127.0.0.1"),
            Integer.getInteger("labaccess.port", 9025));

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "card-io");
        t.setDaemon(true);
        return t;
    });

    private TextArea logArea;
    private Label statusLabel;

    private Label valueAid;
    private Label valueProtocol;
    private Label valueLifecycle;
    private Label valueCardId;
    private Label valueResult;

    private Button btnConnect;
    private Button btnIdentify;
    private Button btnDisconnect;

    private Timeline demoTimeline;

    private final java.util.concurrent.atomic.AtomicBoolean cleanupDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
        btnIdentify = new Button("SELECT + GET_STATUS");
        btnDisconnect = new Button("Disconnect");

        btnConnect.setOnAction(e -> doConnect());
        btnIdentify.setOnAction(e -> doIdentify());
        btnDisconnect.setOnAction(e -> doDisconnect());

        HBox buttons = new HBox(10, btnConnect, btnIdentify, btnDisconnect);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(10));

        valueAid = infoValue();
        valueProtocol = infoValue();
        valueLifecycle = infoValue();
        valueCardId = infoValue();
        valueResult = infoValue();

        GridPane info = new GridPane();
        info.setHgap(12);
        info.setVgap(6);
        info.setPadding(new Insets(4, 10, 10, 10));
        info.add(infoCaption("Instance AID"), 0, 0);
        info.add(valueAid, 1, 0);
        info.add(infoCaption("Protocol"), 0, 1);
        info.add(valueProtocol, 1, 1);
        info.add(infoCaption("Lifecycle"), 0, 2);
        info.add(valueLifecycle, 1, 2);
        info.add(infoCaption("Card ID"), 0, 3);
        info.add(valueCardId, 1, 3);
        info.add(infoCaption("Nhận diện"), 0, 4);
        info.add(valueResult, 1, 4);

        VBox top = new VBox(buttons, new Separator(), info);

        statusLabel = new Label("Chưa kết nối");
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
        root.setTop(top);
        root.setCenter(logArea);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 980, 620);
        stage.setTitle("LabAccess — nhận diện applet (PID " + pid + ")");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (closing) {
                return;
            }
            e.consume();
            closing = true;
            shutdown();
        });
        stage.show();

        clearInfo();
        updateButtons();

        if (Boolean.getBoolean("labaccess.demo")) {
            stage.setAlwaysOnTop(true);
            startDemo();
        }
    }

    private static Label infoCaption(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;"
                + " -fx-text-fill: #555555;");
        return label;
    }

    private static Label infoValue() {
        Label label = new Label("-");
        label.setId("value");
        label.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        return label;
    }

    private void doConnect() {
        runOp("CONNECT", gateway::connect, ok -> {
            uiConnected = ok;
            uiReady = false;
            clearInfo();
        });
    }

    private void doIdentify() {
        runOp("SELECT + GET_STATUS", gateway::identify, ok -> {
            uiReady = ok;
            showStatus();
        });
    }

    private void doDisconnect() {
        runOp("DISCONNECT", gateway::disconnect, ok -> {
            uiConnected = false;
            uiReady = false;
            clearInfo();
        });
    }

    private void showStatus() {
        CardGateway.Status status = gateway.status();
        if (!uiReady || status == null) {
            valueAid.setText("-");
            valueProtocol.setText("-");
            valueLifecycle.setText("-");
            valueCardId.setText("-");
            valueResult.setText("CHƯA SẴN SÀNG");
            return;
        }
        valueAid.setText(status.instanceAid());
        valueProtocol.setText(String.format("%02X", status.protocolVersion()));
        valueLifecycle.setText(CardGateway.lifecycleText(status.lifecycle()));
        valueCardId.setText(status.cardId());
        valueResult.setText("SẴN SÀNG");
    }

    private void clearInfo() {
        valueAid.setText("-");
        valueProtocol.setText("-");
        valueLifecycle.setText("-");
        valueCardId.setText("-");
        valueResult.setText("CHƯA SẴN SÀNG");
    }

    private void runOp(String label, Callable<CardGateway.Result> op, Consumer<Boolean> onResult) {
        if (closing) {
            log("[" + label + "] bỏ qua — đang đóng cửa sổ");
            return;
        }
        if (busy) {
            log("[" + label + "] bỏ qua — đang xử lý thao tác khác");
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
        if (closing) {
            btnConnect.setDisable(true);
            btnIdentify.setDisable(true);
            btnDisconnect.setDisable(true);
            statusLabel.setText("Trạng thái: đang đóng cửa sổ");
            return;
        }

        btnConnect.setDisable(busy || uiConnected);
        btnIdentify.setDisable(busy || !uiConnected);
        btnDisconnect.setDisable(busy || !uiConnected);

        String state = busy ? "đang xử lý"
                : !uiConnected ? "chưa kết nối"
                : !uiReady ? "đã kết nối, chưa sẵn sàng"
                : "đã kết nối, sẵn sàng";
        statusLabel.setText("Trạng thái: " + state);
    }

    private void log(String message) {
        System.out.println("[" + LocalTime.now().format(TS) + "] " + message);
        if (logArea != null) {
            logArea.appendText("[" + LocalTime.now().format(TS) + "] " + message
                    + System.lineSeparator());
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void shutdown() {
        log("Đóng cửa sổ — dừng demo và ngắt kết nối trên luồng nền");

        if (demoTimeline != null) {
            demoTimeline.stop();
            demoTimeline = null;
            log("DEMO: đã dừng Timeline");
        }

        updateButtons();

        String pending = busy ? "có thao tác đang chạy, sẽ xếp sau khi xong"
                : "không có thao tác đang chạy";
        log("Đóng cửa sổ — " + pending);

        worker.submit(() -> {
            String line;
            try {
                line = "[OK]   Đóng kết nối: " + gateway.disconnect().text();
            } catch (Exception ex) {
                line = "[ERROR] Lỗi khi đóng kết nối: " + ex;
            }
            final String lineFinal = line;
            cleanupDone.set(true);
            Platform.runLater(() -> {
                log(lineFinal);
                log("Đóng cửa sổ — thoát");
                worker.shutdownNow();
                Platform.exit();
            });
        });

        startCloseWatchdog();
    }

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
                log("Đóng cửa sổ — quá 5s chưa dọn dẹp xong, cưỡng chế thoát");
                Platform.exit();
            });
        }, "close-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void startDemo() {
        log("DEMO: tự động bấm nút theo thứ tự (cùng handler với người dùng)");
        Runnable[] steps = {
                () -> fire(btnConnect, "Connect"),
                () -> fire(btnIdentify, "SELECT + GET_STATUS"),
                () -> fire(btnDisconnect, "Disconnect"),
        };

        demoTimeline = new Timeline();
        for (int i = 0; i < steps.length; i++) {
            final Runnable step = steps[i];
            demoTimeline.getKeyFrames()
                    .add(new KeyFrame(Duration.millis(800 + i * 5000L), e -> step.run()));
        }
        demoTimeline.getKeyFrames().add(new KeyFrame(
                Duration.millis(800 + steps.length * 5000L),
                e -> {
                    log("DEMO: xong");
                    updateButtons();
                }));
        demoTimeline.play();
    }

    private void fire(Button button, String name) {
        if (button.isDisabled()) {
            log("DEMO: bỏ qua bước " + name + " (nút đang bị khoá)");
            return;
        }
        button.fire();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
