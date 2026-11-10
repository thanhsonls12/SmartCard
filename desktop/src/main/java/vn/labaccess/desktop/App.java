package vn.labaccess.desktop;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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
    private Label valueReceipt;
    private Label valueReconcile;

    private Button btnConnect;
    private Button btnIdentify;
    private Button btnDisconnect;
    private Button btnReadReceipt;
    private Button btnGrant;
    private Button btnStart;
    private Button btnEnd;
    private Button btnReconcile;

    private TextField fieldVisitId;
    private TextField fieldDelta;
    private PasswordField fieldStudentPin;
    private PasswordField fieldAdminPin;

    private PinPanel pinPanel;
    private PersonalizePanel personalizePanel;
    private ProfilePanel profilePanel;
    private PhotoPanel photoPanel;
    private DemoScenario demo;

    private final PendingJournal journal = new PendingJournal(
            Path.of(System.getProperty("labaccess.journal",
                    System.getProperty("user.home") + "/.labaccess/pending-journal.txt")));

    private final java.util.concurrent.atomic.AtomicBoolean cleanupDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private boolean busy;
    private boolean closing;
    private boolean uiConnected;
    private boolean uiReady;
    private boolean stateSnapshotKnown;

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
        btnReadReceipt = new Button("GET_LAST_RECEIPT");
        btnGrant = new Button("GRANT_QUOTA");
        btnStart = new Button("START_VISIT");
        btnEnd = new Button("END_VISIT");
        btnReconcile = new Button("RECONCILE_PENDING");

        fieldVisitId = new TextField();
        fieldVisitId.setPrefColumnCount(34);
        fieldVisitId.setPromptText("visitId 16 byte hex của ca đang mở");
        fieldVisitId.setEditable(false);
        fieldDelta = new TextField("5");
        fieldDelta.setPrefColumnCount(6);
        fieldStudentPin = new PasswordField();
        fieldStudentPin.setPrefColumnCount(8);
        fieldAdminPin = new PasswordField();
        fieldAdminPin.setPrefColumnCount(10);

        btnConnect.setOnAction(e -> doConnect());
        btnIdentify.setOnAction(e -> doIdentify());
        btnDisconnect.setOnAction(e -> doDisconnect());
        btnReadReceipt.setOnAction(e -> doReadReceipt());
        btnGrant.setOnAction(e -> doGrant());
        btnStart.setOnAction(e -> doStart());
        btnEnd.setOnAction(e -> doEnd());
        btnReconcile.setOnAction(e -> doReconcilePending());

        pinPanel = new PinPanel(gateway, this::runOp, this::log);
        personalizePanel = new PersonalizePanel(gateway, this::runOp, this::log, ok -> {
            uiReady = ok && gateway.isReady();
            stateSnapshotKnown = false;
            showStatus();
        });
        profilePanel = new ProfilePanel(gateway, this::runOp, this::log);
        photoPanel = new PhotoPanel(gateway, this::runOp, this::log);
        demo = new DemoScenario(this, pinPanel, personalizePanel, profilePanel, photoPanel,
                this::log);

        HBox buttons = new HBox(10, btnConnect, btnIdentify, btnDisconnect, btnReconcile);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(10));

        HBox receiptControls = new HBox(8,
                UiLabels.caption("Visit ID đang mở"), fieldVisitId,
                btnReadReceipt);
        receiptControls.setAlignment(Pos.CENTER_LEFT);
        receiptControls.setPadding(new Insets(0, 10, 10, 10));

        HBox txnControls = new HBox(8,
                UiLabels.caption("Student PIN"), fieldStudentPin,
                UiLabels.caption("Admin PIN"), fieldAdminPin,
                UiLabels.caption("Delta"), fieldDelta,
                btnGrant, btnStart, btnEnd);
        txnControls.setAlignment(Pos.CENTER_LEFT);
        txnControls.setPadding(new Insets(0, 10, 10, 10));

        valueAid = UiLabels.value();
        valueProtocol = UiLabels.value();
        valueLifecycle = UiLabels.value();
        valueCardId = UiLabels.value();
        valueResult = UiLabels.value();
        valueReceipt = UiLabels.value();
        valueReconcile = UiLabels.value();

        GridPane info = new GridPane();
        info.setHgap(12);
        info.setVgap(6);
        info.setPadding(new Insets(4, 10, 10, 10));
        info.add(UiLabels.caption("Instance AID"), 0, 0);
        info.add(valueAid, 1, 0);
        info.add(UiLabels.caption("Protocol"), 0, 1);
        info.add(valueProtocol, 1, 1);
        info.add(UiLabels.caption("Lifecycle"), 0, 2);
        info.add(valueLifecycle, 1, 2);
        info.add(UiLabels.caption("Card ID"), 0, 3);
        info.add(valueCardId, 1, 3);
        info.add(UiLabels.caption("Nhận diện"), 0, 4);
        info.add(valueResult, 1, 4);
        info.add(UiLabels.caption("Receipt cuối"), 0, 5);
        info.add(valueReceipt, 1, 5);
        info.add(UiLabels.caption("Đối soát"), 0, 6);
        info.add(valueReconcile, 1, 6);

        VBox top = new VBox(buttons, personalizePanel.node(), pinPanel.node(),
                profilePanel.node(), photoPanel.node(), receiptControls, txnControls,
                new Separator(), info);

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

        Scene scene = new Scene(root, 1180, 760);
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
            demo.start();
        }
    }

    private void doConnect() {
        runOp("CONNECT", gateway::connect, ok -> {
            uiConnected = ok;
            uiReady = false;
            stateSnapshotKnown = false;
            clearInfo();
            photoPanel.clearCardImage(ok
                    ? "vừa kết nối, chưa đọc ảnh từ thẻ"
                    : "kết nối lỗi, không giữ ảnh của phiên trước");
            if (ok) {
                pinPanel.resetState("vừa kết nối, chưa đọc trạng thái PIN từ thẻ");
                profilePanel.resetView("vừa kết nối, chưa đọc hồ sơ");
            }
        });
    }

    private void doIdentify() {
        profilePanel.resetView("bắt đầu SELECT lại, không giữ hồ sơ của lần đọc trước");
        pinPanel.resetState("bắt đầu SELECT lại, chưa thử PIN");
        photoPanel.clearCardImage("bắt đầu SELECT lại, không giữ ảnh của lần đọc trước");
        runOp("SELECT + GET_STATUS", gateway::identify, ok -> {
            uiReady = ok;
            stateSnapshotKnown = false;
            showStatus();
            if (ok) {
                Platform.runLater(this::offerPendingReconciliation);
            }
        });
    }

    private void doDisconnect() {
        runOp("DISCONNECT", gateway::disconnect, ok -> {
            uiConnected = false;
            uiReady = false;
            stateSnapshotKnown = false;
            clearInfo();
            photoPanel.clearCardImage(ok
                    ? "đã ngắt kết nối, không giữ ảnh cũ"
                    : "ngắt kết nối lỗi, vẫn không giữ ảnh cũ");
            if (ok) {
                pinPanel.resetState("đã ngắt kết nối, không giữ trạng thái cũ");
                profilePanel.resetView("đã ngắt kết nối, không giữ hồ sơ cũ");
            }
        });
    }

    private void doReadReceipt() {
        if (closing || busy) {
            return;
        }
        PendingJournal.Loaded loaded = journal.load();
        if (loaded.present()) {
            log("[FAIL] GET_LAST_RECEIPT: còn journal "
                    + (loaded.valid() ? loaded.status() : "không hợp lệ")
                    + ", phải đối soát trước");
            updateButtons();
            return;
        }
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        java.util.concurrent.atomic.AtomicBoolean receiptReadStarted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        runOp("VERIFY + GET_LAST_RECEIPT", () -> {
            try {
                CardGateway.Result keyReady = gateway.prepareReceiptVerification();
                if (!keyReady.ok()) {
                    return CardGateway.Result.fail("public key: " + keyReady.text());
                }
                CardGateway.Result verify = gateway.verifyStudentPin(studentPin);
                if (!verify.ok()) {
                    return CardGateway.Result.fail("VERIFY: " + verify.text());
                }
                receiptReadStarted.set(true);
                CardGateway.Result read = gateway.readLastReceipt(null);
                if (!read.ok() && gateway.lastResponseSw() == Sw.CONDITIONS_NOT_SATISFIED) {
                    return CardGateway.Result.ok(
                            "thẻ ISSUED chưa có receipt; counter=0 balance=0");
                }
                return read;
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            stateSnapshotKnown = ok;
            if (!ok && receiptReadStarted.get()) {
                gateway.markTransactionUnknown("không khôi phục được snapshot card state");
            }
            showReceipt();
        });
    }

    private void offerPendingReconciliation() {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            return;
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            gateway.markTransactionUnknown("pending journal cần xử lý thủ công: " + loaded.problem());
            log("[FAIL] Pending journal: " + loaded.problem() + " — khóa giao dịch");
            updateButtons();
            return;
        }
        gateway.markTransactionUnknown("pending journal chờ operator đối soát");
        updateButtons();
        doReconcilePending();
    }

    private void doReconcilePending() {
        if (closing || busy) {
            return;
        }
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present() || !loaded.valid()
                || loaded.status() != PendingJournal.Status.PENDING) {
            log("[FAIL] RECONCILE_PENDING: journal không ở trạng thái PENDING hợp lệ");
            updateButtons();
            return;
        }
        PendingJournal.Entry entry = loaded.entry();
        javafx.scene.control.Alert confirmation = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận đối soát");
        confirmation.setHeaderText("Đọc receipt để xử lý pending journal?");
        confirmation.setContentText(String.format(
                "cardId=%s, counter=%d, operation=%s%n"
                        + "Hệ thống sẽ VERIFY student PIN và đọc GET_LAST_RECEIPT. Không gửi lại APDU đổi state.",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation())));
        java.util.Optional<javafx.scene.control.ButtonType> choice = confirmation.showAndWait();
        if (choice.isEmpty() || choice.get() != javafx.scene.control.ButtonType.OK) {
            log("[INFO] RECONCILE_PENDING: operator chưa xác nhận, journal vẫn được giữ");
            return;
        }

        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, null);
            log("[FAIL] RECONCILE_PENDING: student PIN phải đúng 6 chữ số");
            return;
        }
        runOp("RECONCILE_PENDING", () -> {
            try {
                return gateway.reconcilePending(journal, studentPin);
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            stateSnapshotKnown = ok && !journal.load().present()
                    && gateway.reconciliation().transactionAllowed();
            showReceipt();
        });
    }

    private void doGrant() {
        int delta;
        try {
            delta = Integer.parseInt(fieldDelta.getText().trim());
        } catch (NumberFormatException ex) {
            log("[FAIL] GRANT_QUOTA: delta phải là số nguyên");
            return;
        }
        if (delta <= 0 || delta > 9999) {
            log("[FAIL] GRANT_QUOTA: delta phải trong 1..9999");
            return;
        }
        if (!stateSnapshotKnown) {
            log("[FAIL] GRANT_QUOTA: đọc state hiện tại bằng VERIFY + GET_LAST_RECEIPT trước");
            return;
        }
        CardGateway.Status status = gateway.status();
        if (status == null) {
            log("[FAIL] GRANT_QUOTA: chưa nhận diện thẻ");
            return;
        }
        Receipt current = gateway.lastReceipt();
        long counter = current == null ? 0 : current.counterAfter();
        if (counter >= Receipt.COUNTER_MASK) {
            log("[FAIL] GRANT_QUOTA: counter đã đạt giới hạn");
            return;
        }
        int expectedBalance = currentBalance() + delta;
        if (expectedBalance > Receipt.MAX_BALANCE) {
            log("[FAIL] GRANT_QUOTA: balance dự kiến vượt 9999");
            return;
        }
        byte[] adminPin = pinToBytes(fieldAdminPin);
        if (adminPin == null) {
            log("[FAIL] GRANT_QUOTA: admin PIN phải gồm đúng 8 chữ số");
            return;
        }
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldAdminPin.clear();
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), counter + 1,
                Receipt.OPERATION_GRANT, null);
        runTransaction("GRANT_QUOTA", studentPin, adminPin, expectation,
                delta, expectedBalance, gateway.grantCommand(adminPin, delta));
    }

    private void doStart() {
        CardGateway.Status status = gateway.status();
        Receipt current = gateway.lastReceipt();
        if (!stateSnapshotKnown) {
            log("[FAIL] START_VISIT: đọc state hiện tại bằng VERIFY + GET_LAST_RECEIPT trước");
            return;
        }
        if (status == null) {
            log("[FAIL] START_VISIT: chưa nhận diện thẻ");
            return;
        }
        if (current == null) {
            log("[FAIL] START_VISIT: cần receipt hiện tại để biết balance/counter");
            return;
        }
        if (current.balanceAfter() <= 0) {
            log("[FAIL] START_VISIT: balance hiện tại không còn lượt");
            return;
        }
        if (current.counterAfter() >= Receipt.COUNTER_MASK) {
            log("[FAIL] START_VISIT: counter đã đạt giới hạn");
            return;
        }
        byte[] visitId = newVisitId();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), current.counterAfter() + 1,
                Receipt.OPERATION_START, visitId);
        runTransaction("START_VISIT", studentPin, null, expectation, -1,
                current.balanceAfter() - 1, gateway.visitCommand((byte) 0x51, visitId));
    }

    private void doEnd() {
        CardGateway.Status status = gateway.status();
        Receipt current = gateway.lastReceipt();
        if (!stateSnapshotKnown) {
            log("[FAIL] END_VISIT: đọc state hiện tại bằng VERIFY + GET_LAST_RECEIPT trước");
            return;
        }
        if (status == null) {
            log("[FAIL] END_VISIT: chưa nhận diện thẻ");
            return;
        }
        if (current == null || current.operation() != Receipt.OPERATION_START) {
            log("[FAIL] END_VISIT: receipt hiện tại không chứng minh ca đang mở");
            return;
        }
        byte[] visitId = current.visitId();
        if (current.counterAfter() >= Receipt.COUNTER_MASK) {
            log("[FAIL] END_VISIT: counter đã đạt giới hạn");
            return;
        }
        long counter = current.counterAfter();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), counter + 1,
                Receipt.OPERATION_END, visitId);
        runTransaction("END_VISIT", studentPin, null, expectation, 0,
                current.balanceAfter(), gateway.visitCommand((byte) 0x52, visitId));
    }

    private void runTransaction(String label, char[] studentPin, byte[] adminPin,
            ReceiptExpectation expectation, int delta, int expectedBalance,
            CardGateway.CommandSupplier supplier) {
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, adminPin);
            log("[FAIL] " + label + ": student PIN phải đúng 6 chữ số");
            return;
        }
        if (closing || busy) {
            clearBuffers(studentPin, adminPin);
            log("[FAIL] " + label + ": đang xử lý thao tác khác hoặc đang đóng");
            return;
        }
        runOp(label, () -> {
            try {
                CardGateway.Result verify = gateway.verifyStudentPin(studentPin);
                if (!verify.ok()) {
                    return CardGateway.Result.fail(label + " không chạy — VERIFY: " + verify.text());
                }
                return gateway.runStateTransaction(journal,
                        new CardGateway.TransactionPlan(expectation, delta, expectedBalance),
                        supplier, studentPin);
            } finally {
                clearBuffers(studentPin, adminPin);
            }
        }, ok -> showReceipt());
    }

    private int currentBalance() {
        Receipt receipt = gateway.lastReceipt();
        return receipt == null ? 0 : receipt.balanceAfter();
    }

    private byte[] newVisitId() {
        byte[] visitId = new byte[16];
        java.security.SecureRandom random = new java.security.SecureRandom();
        do {
            random.nextBytes(visitId);
        } while (allZero(visitId));
        return visitId;
    }

    private byte[] pinToBytes(PasswordField field) {
        String text = field.getText();
        field.clear();
        if (text == null || text.length() != 8) {
            return null;
        }
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                java.util.Arrays.fill(bytes, (byte) 0);
                return null;
            }
            bytes[i] = (byte) c;
        }
        return bytes;
    }

    private static boolean validPin(char[] pin, int length) {
        if (pin == null || pin.length != length) {
            return false;
        }
        for (char c : pin) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void clearBuffers(char[] studentPin, byte[] adminPin) {
        if (studentPin != null) {
            java.util.Arrays.fill(studentPin, '\0');
        }
        if (adminPin != null) {
            java.util.Arrays.fill(adminPin, (byte) 0);
        }
    }

    private void showReceipt() {
        Receipt receipt = gateway.lastReceipt();
        if (receipt == null) {
            valueReceipt.setText("-");
            fieldVisitId.clear();
        } else {
            valueReceipt.setText(receipt + " | sigLen=" + receipt.signature().length);
            fieldVisitId.setText(receipt.operation() == Receipt.OPERATION_START
                    ? receipt.visitIdHex() : "");
        }
        Reconciliation reconciliation = gateway.reconciliation();
        valueReconcile.setText(reconciliation.state() + (reconciliation.detail().isEmpty()
                ? ""
                : " — " + reconciliation.detail()));
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
        personalizePanel.showPublicKey();
    }

    private void clearInfo() {
        valueAid.setText("-");
        valueProtocol.setText("-");
        valueLifecycle.setText("-");
        valueCardId.setText("-");
        valueResult.setText("CHƯA SẴN SÀNG");
        valueReceipt.setText("-");
        valueReconcile.setText(gateway.reconciliation().state().toString());
        personalizePanel.resetView();
        pinPanel.clearState();
        profilePanel.clearView();
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
        PendingJournal.Loaded loaded = journal.load();
        boolean journalPresent = loaded.present();
        boolean validPending = loaded.present() && loaded.valid()
                && loaded.status() == PendingJournal.Status.PENDING;
        boolean issued = gateway.status() != null && gateway.status().lifecycle() == 0x01;

        if (closing) {
            btnConnect.setDisable(true);
            btnIdentify.setDisable(true);
            btnDisconnect.setDisable(true);
            btnReadReceipt.setDisable(true);
            btnGrant.setDisable(true);
            btnStart.setDisable(true);
            btnEnd.setDisable(true);
            btnReconcile.setDisable(true);
            pinPanel.setEnabled(false);
            personalizePanel.updateAvailability(false, false);
            profilePanel.setEnabled(false);
            photoPanel.setEnabled(false);
            statusLabel.setText("Trạng thái: đang đóng cửa sổ");
            return;
        }

        if (journalPresent && !loaded.valid()) {
            gateway.markTransactionUnknown("pending journal không hợp lệ — cần xử lý thủ công");
        } else if (journalPresent && loaded.status() == PendingJournal.Status.RESOLVED) {
            gateway.markTransactionUnknown("journal đã RESOLVED nhưng chưa xóa — cần xử lý thủ công");
        }

        btnConnect.setDisable(busy || uiConnected);
        btnIdentify.setDisable(busy || !uiConnected);
        btnDisconnect.setDisable(busy || !uiConnected);
        btnReadReceipt.setDisable(busy || !uiReady || journalPresent
                || !gateway.reconciliation().transactionAllowed());
        boolean txnAllowed = !busy && uiReady && stateSnapshotKnown
                && gateway.reconciliation().transactionAllowed() && !journalPresent;
        btnGrant.setDisable(!txnAllowed);
        btnStart.setDisable(!txnAllowed);
        btnEnd.setDisable(!txnAllowed);
        btnReconcile.setDisable(busy || !uiReady || !validPending);
        pinPanel.setEnabled(!busy && uiConnected);
        personalizePanel.updateAvailability(
                !busy && uiConnected && !journalPresent && !issued,
                !busy && uiConnected);
        profilePanel.setEnabled(!busy && uiConnected && issued);
        photoPanel.setEnabled(!busy && uiConnected && issued);

        String state = busy ? "đang xử lý"
                : !uiConnected ? "chưa kết nối"
                : !uiReady ? "đã kết nối, chưa sẵn sàng"
                : "đã kết nối, sẵn sàng";
        String journalText = !journalPresent ? "pending journal: không"
                : loaded.valid() ? "pending journal: " + loaded.status()
                : "pending journal: KHÔNG HỢP LỆ";
        statusLabel.setText("Trạng thái: " + state
                + " | đối soát=" + gateway.reconciliation().state()
                + " | " + journalText);
    }

    boolean pressConnect() {
        return press(btnConnect);
    }

    boolean pressIdentify() {
        return press(btnIdentify);
    }

    boolean pressDisconnect() {
        return press(btnDisconnect);
    }

    boolean pressReadReceipt() {
        return press(btnReadReceipt);
    }

    boolean pressGrant() {
        return press(btnGrant);
    }

    void refreshButtons() {
        updateButtons();
    }

    private static boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
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

        demo.stop();

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

    public static void main(String[] args) {
        launch(args);
    }
}
