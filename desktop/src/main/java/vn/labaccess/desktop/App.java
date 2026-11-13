package vn.labaccess.desktop;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
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

    private static final int LOG_MAX_CHARS = 200_000;
    private static final int LOG_TRIM_CHARS = 20_000;

    private TextArea logArea;
    private Label statusLabel;
    private Label valueConnection;
    private TabPane mainTabs;

    static final int TAB_OPERATIONS = 0;
    static final int TAB_CARD = 1;
    static final int TAB_ENROLL = 2;
    static final int TAB_RECORDS = 3;

    private Label valueAid;
    private Label valueProtocol;
    private Label valueLifecycle;
    private Label valueCardId;
    private Label valueResult;
    private Label valueReconcile;

    private Button btnConnect;
    private Button btnIdentify;
    private Button btnDisconnect;

    private ToggleButton techToggle;
    private SplitPane workSplit;
    private TitledPane logPane;
    private HistoryPanel historyPanel;
    private BackendClient.HistoryLoad loadedHistory;
    private Exception historyError;
    private boolean historyReady;
    private String busyLabel;
    private String lastFeedback = "Chưa có thao tác";
    private UiLabels.Tone lastTone = UiLabels.Tone.NEUTRAL;
    private boolean logShowsFailure;
    private boolean logUiClosed;

    private static final double NARROW_WIDTH = 1000;
    private static final double SPLIT_BUSINESS = 0.60;
    private static final String BUSY_WHY = "đang xử lý thao tác khác";
    static final String BUSY_NOTE = "Đang xử lý thao tác khác — chờ xong.";

    private Label actionWhy;
    private Label nextStepLabel;

    private TransactionPanel transactionPanel;
    private ReceiptPanel receiptPanel;
    private PinPanel pinPanel;
    private PersonalizePanel personalizePanel;
    private EnrollmentPanel enrollmentPanel;
    private BackendClient backendClient;
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

        btnConnect = new Button("Kết nối", Icons.connect());
        btnIdentify = new Button("Nhận diện thẻ", Icons.identify());
        btnDisconnect = new Button("Ngắt kết nối", Icons.disconnect());
        for (Button action : new Button[] {btnConnect, btnIdentify, btnDisconnect}) {
            action.getStyleClass().add("la-action");
        }
        UiLabels.apduHint(btnConnect, "CONNECT");
        UiLabels.apduHint(btnIdentify, "SELECT + GET_STATUS\nCần kết nối thẻ trước");
        UiLabels.apduHint(btnDisconnect, "DISCONNECT");
        techToggle = new ToggleButton("Chi tiết kỹ thuật");
        techToggle.getStyleClass().add("la-technical-toggle");

        actionWhy = whyNote();

        btnConnect.setOnAction(e -> doConnect());
        btnIdentify.setOnAction(e -> doIdentify());
        btnDisconnect.setOnAction(e -> doDisconnect());

        backendClient = new BackendClient(
                System.getProperty("labaccess.backend", "http://127.0.0.1:3100"));
        gateway.setDiagnostics(text -> log(text));
        gateway.setReceiptSink(new CardGateway.ReceiptSink() {
            @Override
            public CardGateway.ReceiptSave save(Receipt receipt) {
                return saveReceiptToLedger(receipt);
            }

            @Override
            public CardGateway.LedgerCheck ledger(String cardIdHex) {
                return ledgerOf(cardIdHex);
            }
        });
        transactionPanel = new TransactionPanel(gateway, this::runOp, this::log,
                new TransactionPanel.Session(this::snapshotKnown, this::sessionOpen,
                        this::runCardStateTransaction, this::invalidateHistory,
                        this::rememberHistory, this::transactionFinished));
        receiptPanel = new ReceiptPanel(gateway, this::runOp, this::log,
                new ReceiptPanel.Session(this::sessionOpen, this::pendingJournal,
                        this::studentPinInput, this::clearStudentPinInput,
                        this::reconcileCardPending, this::invalidateHistory,
                        this::rememberHistory, this::publishHistory, this::refreshButtons,
                        this::showReconcile, this::receiptFinished, this::reconcileFinished));
        pinPanel = new PinPanel(gateway, this::runOp, this::log);
        enrollmentPanel = new EnrollmentPanel(gateway, this::runEnrollmentOp, backendClient,
                this::log, this::reportEnrollment);
        personalizePanel = new PersonalizePanel(gateway, this::runOp, this::log,
                (ok, issued) -> {
                    uiReady = ok && gateway.isReady();
                    stateSnapshotKnown = false;
                    showStatus();
                    if (ok && issued != null) {
                        enrollmentPanel.prefill(issued.cardId(), issued.studentId());
                    }
                });
        profilePanel = new ProfilePanel(gateway, this::runOp, this::log);
        photoPanel = new PhotoPanel(gateway, this::runOp, this::log);
        demo = new DemoScenario(this, pinPanel, personalizePanel, enrollmentPanel,
                profilePanel, photoPanel, this::log);

        valueConnection = UiLabels.chip();
        valueAid = UiLabels.value();
        valueProtocol = UiLabels.value();
        valueLifecycle = UiLabels.chip();
        valueCardId = UiLabels.value();
        valueCardId.getStyleClass().add("la-card-id");
        valueResult = UiLabels.chip();
        valueReconcile = UiLabels.chip();

        FlowPane summary = UiRows.wrapping(
                UiRows.pair(UiLabels.caption("Kết nối"), valueConnection),
                UiRows.pair(UiLabels.caption("Mã thẻ"), valueCardId),
                UiRows.pair(UiLabels.caption("Vòng đời"), valueLifecycle),
                UiRows.pair(UiLabels.caption("Nhận diện"), valueResult),
                UiRows.pair(UiLabels.caption("Đối soát"), valueReconcile));

        FlowPane sessionActions = UiRows.wrapping(btnConnect, btnIdentify, btnDisconnect,
                receiptPanel.reconcileAction(), techToggle);

        GridPane technical = new GridPane();
        technical.getStyleClass().add("la-info");
        technical.setHgap(UiScale.SPACE_3);
        technical.setVgap(UiScale.SPACE_1);
        technical.setPadding(UiScale.blockInsets());
        technical.add(UiLabels.caption("Instance AID"), 0, 0);
        technical.add(valueAid, 1, 0);
        technical.add(UiLabels.caption("Protocol"), 0, 1);
        technical.add(valueProtocol, 1, 1);
        technical.setManaged(false);
        technical.setVisible(false);
        techToggle.selectedProperty().addListener((observable, was, now) -> {
            technical.setManaged(now);
            technical.setVisible(now);
        });

        VBox header = new VBox(UiScale.SPACE_2, summary, sessionActions, actionWhy, technical);
        header.getStyleClass().add("la-header");
        header.setPadding(UiScale.headerInsets());

        historyPanel = new HistoryPanel(backendClient, this::runEnrollmentOp,
                () -> {
                    CardStatus current = gateway.status();
                    return current == null ? "" : current.cardId();
                },
                this::log);
        Tab tabOperations = tab("Vận hành", transactionPanel.node(), receiptPanel.node(),
                historyPanel.node());

        Tab tabRecords = tab("Hồ sơ & ảnh", profilePanel.node(), photoPanel.node());

        Tab tabCard = tab("Cấp thẻ & bảo mật", personalizePanel.node(), pinPanel.node());

        Tab tabEnroll = tab("Đăng ký thẻ", enrollmentPanel.node());

        mainTabs = new TabPane(tabOperations, tabCard, tabEnroll, tabRecords);
        mainTabs.getStyleClass().add("la-tabs");
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.getSelectionModel().selectFirst();
        mainTabs.setMinHeight(0);
        VBox.setVgrow(mainTabs, Priority.ALWAYS);

        statusLabel = new Label("Chưa kết nối");
        statusLabel.setId("status");
        statusLabel.getStyleClass().add("la-status");
        nextStepLabel = new Label();
        nextStepLabel.setId("next-step");
        nextStepLabel.getStyleClass().add("la-next-step");
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(statusLabel, statusSpacer, nextStepLabel);
        statusBar.getStyleClass().add("la-status-bar");
        statusBar.setPadding(UiScale.statusBarInsets());

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("la-log");
        TitledPane logPane = new TitledPane("Nhật ký thao tác", logArea);
        logPane.setExpanded(true);
        logPane.setMinWidth(200);
        logPane.setMaxHeight(Double.MAX_VALUE);
        logPane.getStyleClass().add("la-log-pane");
        this.logPane = logPane;

        VBox business = new VBox(mainTabs);
        business.setMinHeight(0);
        business.setPadding(UiScale.centerInsets());

        VBox logColumn = new VBox(logPane);
        logColumn.setMinWidth(200);
        logColumn.setPadding(UiScale.centerInsets());
        VBox.setVgrow(logPane, Priority.ALWAYS);

        workSplit = new SplitPane(business, logColumn);
        workSplit.getStyleClass().add("la-split");
        workSplit.setDividerPositions(SPLIT_BUSINESS);
        SplitPane.setResizableWithParent(logColumn, false);
        VBox.setVgrow(workSplit, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(workSplit);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, windowWidth(), windowHeight());
        UiTheme.apply(scene);
        scene.widthProperty().addListener((observable, was, now) -> layoutForWidth(now.doubleValue()));
        mainTabs.getSelectionModel().selectedIndexProperty().addListener(
                (observable, was, now) -> updateButtons());
        layoutForWidth(windowWidth());
        stage.setTitle("LabAccess — nhận diện applet (PID " + pid + ")");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
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
        stage.centerOnScreen();

        clearInfo();
        blockOnStartupJournal();
        updateButtons();

        if (Boolean.getBoolean("labaccess.demo")) {
            stage.setAlwaysOnTop(true);
            demo.start();
        }
    }

    private static Tab tab(String title, Node... content) {
        VBox body = new VBox(UiScale.SPACE_4, content);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("la-scroll");
        scroll.setMinHeight(0);
        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
    }

    void revealHistory() {
        if (mainTabs == null) {
            return;
        }
        javafx.scene.Node content = mainTabs.getTabs().get(TAB_OPERATIONS).getContent();
        if (content instanceof ScrollPane scroll) {
            scroll.setVvalue(1);
            scroll.layout();
        }
    }

    void showTab(int index) {
        if (mainTabs != null) {
            mainTabs.getSelectionModel().select(index);
        }
    }

    private void layoutForWidth(double width) {
        if (workSplit == null) {
            return;
        }
        boolean narrow = width < NARROW_WIDTH;
        workSplit.setOrientation(narrow ? Orientation.VERTICAL : Orientation.HORIZONTAL);
        workSplit.setDividerPositions(SPLIT_BUSINESS);
        if (logPane != null) {
            logPane.setExpanded(!narrow || logShowsFailure);
        }
    }

    private void blockOnStartupJournal() {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            log("Khởi động: không có pending journal");
            return;
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            gateway.markTransactionUnknown("pending journal khi khởi động cần xử lý thủ công: "
                    + loaded.problem());
            showReconcile();
            log("[FAIL] Khởi động: journal không dùng được ("
                    + (loaded.valid() ? loaded.status() : loaded.problem())
                    + ") — KHÓA giao dịch, giữ nguyên file, không gửi APDU");
            return;
        }
        PendingJournal.Entry entry = loaded.entry();
        gateway.markTransactionUnknown("khôi phục pending journal khi mở lại ứng dụng");
        showReconcile();
        log(String.format("[FAIL] Khởi động: còn pending journal cardId=%s counter=%d operation=%s"
                + " tạo lúc %s — KHÓA giao dịch ngay; chưa gửi APDU, chưa đọc thẻ, chưa dùng PIN;"
                + " kết nối và nhận diện đúng thẻ rồi đối soát",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation()),
                java.time.Instant.ofEpochMilli(entry.createdAtEpochMillis())));
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
        runOp("SELECT + GET_STATUS", () -> {
            CardOperationResult identified = gateway.identify();
            if (!identified.ok()) {
                return identified;
            }
            return CardOperationResult.ok(identified.text()
                    + " | đăng ký thẻ trên Backend: " + probeCardEnrollment());
        }, ok -> {
            uiReady = ok;
            stateSnapshotKnown = false;
            showStatus();
            if (ok) {
                Platform.runLater(receiptPanel::offerPendingReconciliation);
            }
        });
    }

    private String probeCardEnrollment() {
        CardStatus current = gateway.status();
        if (current == null) {
            gateway.clearCardEnrollment("chưa nhận diện thẻ");
            return "chưa nhận diện thẻ";
        }
        try {
            boolean enrolled = backendClient.cardEnrolled(current.cardId());
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, enrolled,
                    enrolled ? "Backend có sổ ledger cho thẻ"
                            : "Backend không có sổ ledger cho thẻ"));
            return enrolled
                    ? "đã đăng ký (ledger Backend có cardId này)"
                    : "CHƯA đăng ký (Backend không có sổ ledger) — GRANT/START/END bị chặn";
        } catch (BackendClient.Failure failure) {
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(false, false,
                    failure.getMessage()));
            return "chưa xác nhận được (" + failure.getMessage()
                    + ") — GRANT/START/END bị chặn";
        }
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

    static boolean validPin(char[] pin, int length) {
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

    static void clearBuffers(char[] studentPin, byte[] adminPin) {
        if (studentPin != null) {
            java.util.Arrays.fill(studentPin, '\0');
        }
        if (adminPin != null) {
            java.util.Arrays.fill(adminPin, (byte) 0);
        }
    }

    private void showStatus() {
        CardStatus status = gateway.status();
        if (!uiReady || status == null) {
            valueConnection.setText(uiConnected ? "Đã kết nối" : "Chưa kết nối");
            UiLabels.applyChip(valueConnection, uiConnected
                    ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL);
            valueAid.setText("-");
            valueProtocol.setText("-");
            valueLifecycle.setText("Chưa rõ");
            UiLabels.applyChip(valueLifecycle, UiLabels.Tone.NEUTRAL);
            valueCardId.setText("-");
            valueResult.setText("Chưa sẵn sàng");
            UiLabels.applyChip(valueResult, UiLabels.Tone.PENDING);
            showReconcile();
            return;
        }
        valueConnection.setText("Đã kết nối");
        UiLabels.applyChip(valueConnection, UiLabels.Tone.OK);
        valueAid.setText(status.instanceAid());
        valueProtocol.setText(String.format("%02X", status.protocolVersion()));
        valueLifecycle.setText(CardLifecycle.caption(status.lifecycle()));
        UiLabels.applyChip(valueLifecycle, status.lifecycle() == CardLifecycle.ISSUED
                ? UiLabels.Tone.OK : UiLabels.Tone.PENDING);
        valueCardId.setText(status.cardId());
        valueResult.setText("Sẵn sàng");
        UiLabels.applyChip(valueResult, UiLabels.Tone.OK);
        showReconcile();
        personalizePanel.showPublicKey();
    }

    private void showReconcile() {
        boolean known = gateway.reconciliation().transactionAllowed();
        valueReconcile.setText(known ? "Không treo" : "Cần đối soát");
        UiLabels.Tone tone = !known
                ? UiLabels.Tone.ERROR
                : (uiConnected ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
        UiLabels.applyChip(valueReconcile, tone);
    }

    private void clearInfo() {
        valueConnection.setText("Chưa kết nối");
        UiLabels.applyChip(valueConnection, UiLabels.Tone.NEUTRAL);
        valueLifecycle.setText("Chưa rõ");
        UiLabels.applyChip(valueLifecycle, UiLabels.Tone.NEUTRAL);
        valueAid.setText("-");
        valueProtocol.setText("-");
        valueCardId.setText("-");
        valueResult.setText("Chưa sẵn sàng");
        UiLabels.applyChip(valueResult, UiLabels.Tone.NEUTRAL);
        showReconcile();
        receiptPanel.clear();
        historyPanel.clear();
        personalizePanel.resetView();
        enrollmentPanel.resetView();
        pinPanel.clearState();
        profilePanel.clearView();
        photoPanel.resetView();
    }

    private <T> void runEnrollmentOp(String label, Callable<T> operation,
            Consumer<T> onSuccess, Consumer<Exception> onFailure) {
        if (closing) {
            log("[" + label + "] bỏ qua — đang đóng cửa sổ");
            return;
        }
        if (busy) {
            log("[" + label + "] bỏ qua — đang xử lý thao tác khác");
            return;
        }
        busy = true;
        busyLabel = label;
        updateButtons();
        log("> " + label + " ...");

        worker.submit(() -> {
            T value = null;
            Exception failure = null;
            try {
                log("   " + label + " chạy trên thread=" + Thread.currentThread().getName());
                value = operation.call();
            } catch (Exception ex) {
                failure = ex;
            }
            final T resultValue = value;
            final Exception resultFailure = failure;
            Platform.runLater(() -> {
                log("   " + label + " cập nhật UI trên thread=" + Thread.currentThread().getName());
                busy = false;
                busyLabel = null;
                try {
                    if (resultFailure == null) {
                        onSuccess.accept(resultValue);
                    } else {
                        onFailure.accept(resultFailure);
                    }
                } finally {
                    updateButtons();
                }
            });
        });
    }

    private void reportEnrollment(EnrollmentPanel.OutcomeKind kind, String message) {
        lastFeedback = message;
        lastTone = switch (kind) {
            case SUCCESS -> UiLabels.Tone.OK;
            case ERROR -> UiLabels.Tone.ERROR;
            case UNKNOWN -> UiLabels.Tone.PENDING;
        };
        if (kind == EnrollmentPanel.OutcomeKind.SUCCESS) {
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true,
                    "đăng ký thẻ vừa hoàn tất trên Backend"));
            updateButtons();
        }
    }

    private CardGateway.ReceiptSave saveReceiptToLedger(Receipt receipt) {
        try {
            BackendClient.SavedReceipt saved = backendClient.verifyReceipt(receipt);
            return new CardGateway.ReceiptSave(true, String.format(
                    "lưu ledger=OK (%s, counter=%d, %s, HTTP %d)", saved.status(),
                    saved.counterAfter(), saved.operation(), saved.httpStatus()));
        } catch (BackendClient.Failure failure) {
            return new CardGateway.ReceiptSave(false, failure.getMessage());
        }
    }

    private CardGateway.LedgerCheck ledgerOf(String cardIdHex) {
        try {
            BackendClient.LedgerSnapshot snapshot = backendClient.ledgerOf(cardIdHex);
            return new CardGateway.LedgerCheck(true, "ledger đọc được", snapshot.lastCounter(),
                    snapshot.lastBalance(), snapshot.activeVisitId());
        } catch (BackendClient.Failure failure) {
            return new CardGateway.LedgerCheck(false, failure.getMessage(), -1, -1, null);
        }
    }

    private void runOp(String label, Callable<CardOperationResult> op, Consumer<Boolean> onResult) {
        if (closing) {
            log("[" + label + "] bỏ qua — đang đóng cửa sổ");
            return;
        }
        if (busy) {
            log("[" + label + "] bỏ qua — đang xử lý thao tác khác");
            return;
        }
        busy = true;
        busyLabel = label;
        updateButtons();
        log("> " + label + " ...");

        worker.submit(() -> {
            String line;
            boolean ok = false;
            try {
                log("   " + label + " chạy trên thread=" + Thread.currentThread().getName());
                CardOperationResult r = op.call();
                ok = r.ok();
                line = (ok ? "[OK]   " : "[FAIL] ") + label + ": " + r.text();
            } catch (Exception ex) {
                line = "[ERROR] " + label + ": " + ex;
            }
            final boolean okFinal = ok;
            final String lineFinal = line;
            Platform.runLater(() -> {
                log(lineFinal);
                log("   " + label + " cập nhật UI trên thread=" + Thread.currentThread().getName());
                if (onResult != null) {
                    onResult.accept(okFinal);
                }
                busy = false;
                busyLabel = null;
                lastFeedback = UiLabels.outcome(okFinal, label);
                lastTone = okFinal ? UiLabels.Tone.OK : UiLabels.Tone.ERROR;
                updateButtons();
            });
        });
    }

    static Label whyNote() {
        Label note = new Label();
        note.getStyleClass().add("la-note");
        UiLabels.applyNoteTone(note, UiLabels.Tone.NEUTRAL);
        note.setWrapText(true);
        note.setManaged(false);
        note.setVisible(false);
        return note;
    }

    static void setWhy(Label note, String text) {
        boolean show = text != null && !text.isBlank();
        note.setText(show ? text : "");
        note.setVisible(show);
        note.setManaged(show);
    }

    private void showReasons(String whyConnect, String whyIdentify, String whyDisconnect,
            String whyReconcile) {
        if (busy) {
            setWhy(actionWhy, BUSY_NOTE);
            return;
        }
        setWhy(actionWhy, joinReasons(
                whyLine(btnConnect, whyConnect, btnIdentify, whyIdentify,
                        btnDisconnect, whyDisconnect),
                receiptPanel.reconcileLockReason(whyReconcile)));
    }

    private static String joinReasons(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + " · " + second;
    }

    static String whyLine(Object... pairs) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Button button = (Button) pairs[i];
            String reason = (String) pairs[i + 1];
            if (reason != null && button.isDisable()) {
                parts.add(button.getText() + ": " + reason);
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private String nextStep(boolean journalPresent, boolean issued, CardStatus current) {
        if (closing) {
            return "chờ cửa sổ đóng";
        }
        if (busy) {
            return "chờ thao tác đang chạy xong";
        }
        if (!uiConnected) {
            return "kết nối đầu đọc";
        }
        if (!uiReady) {
            return "nhận diện thẻ";
        }
        if (journalPresent) {
            return "đối soát giao dịch đang chờ";
        }
        if (!issued) {
            return "phát hành thẻ (tab Cấp thẻ & bảo mật)";
        }
        if (!enrollmentPanel.enrolled()) {
            return "đăng ký thẻ (tab Đăng ký thẻ)";
        }
        if (current == null || !current.hasActiveVisit()) {
            return "cấp quota rồi mở ca (tab Vận hành)";
        }
        return "đóng ca khi kết thúc (tab Vận hành)";
    }

    private void updateButtons() {
        PendingJournal.Loaded loaded = journal.load();
        boolean journalPresent = loaded.present();
        boolean validPending = loaded.present() && loaded.valid()
                && loaded.status() == PendingJournal.Status.PENDING;
        boolean issued = gateway.status() != null
                && gateway.status().lifecycle() == CardLifecycle.ISSUED;

        if (closing) {
            btnConnect.setDisable(true);
            btnIdentify.setDisable(true);
            btnDisconnect.setDisable(true);
            receiptPanel.lockAll();
            transactionPanel.lockAll();
            pinPanel.setEnabled(false);
            personalizePanel.updateAvailability(false, false, "Đang đóng cửa sổ.");
            profilePanel.setEnabled(false);
            photoPanel.setEnabled(false);
            enrollmentPanel.setEnabled(false, "Đang đóng cửa sổ.");
            historyPanel.setEnabled(false);
            statusLabel.setText("Đang đóng cửa sổ");
            UiLabels.applyTone(statusLabel, UiLabels.Tone.PENDING);
            nextStepLabel.setText("Tiếp theo: chờ cửa sổ đóng");
            return;
        }

        if (journalPresent && !loaded.valid()) {
            gateway.markTransactionUnknown("pending journal không hợp lệ — cần xử lý thủ công");
        } else if (journalPresent && loaded.status() == PendingJournal.Status.RESOLVED) {
            gateway.markTransactionUnknown("journal đã RESOLVED nhưng chưa xóa — cần xử lý thủ công");
        }

        CardStatus currentStatus = gateway.status();
        boolean authenticatedState = currentStatus != null && currentStatus.studentStateAvailable();
        boolean txnAllowed = !busy && uiReady && stateSnapshotKnown
                && gateway.reconciliation().transactionAllowed() && !journalPresent;

        String whyConnect = busy ? BUSY_WHY : null;
        String whyIdentify = busy ? BUSY_WHY : !uiConnected ? "chưa kết nối thẻ" : null;
        String whyDisconnect = busy ? BUSY_WHY : !uiConnected ? "chưa kết nối thẻ" : null;
        String whyReadReceipt = busy ? BUSY_WHY
                : !uiReady ? "chưa nhận diện thẻ"
                : journalPresent ? "đang có biên nhận chờ đối soát"
                : !gateway.reconciliation().transactionAllowed() ? "cần đối soát giao dịch trước"
                : null;
        String enrollmentWhy = gateway.enrollmentBlockReason();
        String whyTxn = busy ? BUSY_WHY
                : !txnAllowed ? "đang bận hoặc chưa nhận diện thẻ"
                : enrollmentWhy != null ? enrollmentWhy + " — chặn trước khi ghi journal và gửi APDU"
                : !authenticatedState ? "cần PIN học viên đúng để đọc state thẻ"
                : null;
        String whyGrant = whyTxn;
        String whyStart = whyTxn != null ? whyTxn
                : currentStatus.hasActiveVisit() ? "thẻ đang có ca mở"
                : currentStatus.balance() <= 0 ? "số dư bằng 0 — cấp quota trước"
                : null;
        String whyEnd = whyTxn != null ? whyTxn
                : !currentStatus.hasActiveVisit() ? "thẻ không có ca đang mở"
                : null;
        String whyReconcile = busy ? BUSY_WHY
                : !uiReady ? "chưa nhận diện thẻ"
                : !validPending ? "không có giao dịch nào đang chờ đối soát"
                : null;

        btnConnect.setDisable(busy || uiConnected);
        btnIdentify.setDisable(whyIdentify != null);
        btnDisconnect.setDisable(whyDisconnect != null);
        receiptPanel.applyLock(busy, whyReadReceipt, whyReconcile);
        transactionPanel.applyLock(busy, whyGrant, whyStart, whyEnd);

        showReasons(whyConnect, whyIdentify, whyDisconnect, whyReconcile);

        nextStepLabel.setText("Tiếp theo: "
                + nextStep(journalPresent, issued, currentStatus));

        String whyPersonalize = busy ? "Đang xử lý thao tác khác — chờ xong."
                : !uiConnected ? "Chưa kết nối thẻ."
                : journalPresent ? "Đang có biên nhận chờ đối soát — đối soát trước."
                : issued ? "Thẻ đã phát hành — không phát hành lại."
                : null;
        String whyEnroll = busy ? "Đang xử lý thao tác khác — chờ xong."
                : !uiConnected ? "Chưa kết nối thẻ."
                : !issued ? "Thẻ chưa phát hành — phát hành thẻ trước khi đăng ký."
                : null;
        pinPanel.setEnabled(!busy && uiConnected);
        personalizePanel.updateAvailability(whyPersonalize == null, !busy && uiConnected,
                whyPersonalize);
        profilePanel.setEnabled(!busy && uiConnected && issued);
        photoPanel.setEnabled(!busy && uiConnected && issued);
        enrollmentPanel.setEnabled(whyEnroll == null, whyEnroll);
        enrollmentPanel.onCard(currentStatus == null ? null : currentStatus.cardId());
        historyPanel.setEnabled(!busy && uiReady && currentStatus != null
                && currentStatus.cardId() != null && !currentStatus.cardId().isBlank());

        String feedback;
        UiLabels.Tone tone;
        if (busy) {
            feedback = "Đang xử lý: " + (busyLabel == null ? "…" : UiLabels.actionName(busyLabel));
            tone = UiLabels.Tone.PENDING;
        } else if (journalPresent) {
            feedback = validPending
                    ? "Có biên nhận chờ đối soát — đối soát trước khi giao dịch tiếp"
                    : "Pending journal không hợp lệ — cần xử lý thủ công";
            tone = validPending ? UiLabels.Tone.PENDING : UiLabels.Tone.ERROR;
        } else if (!gateway.reconciliation().transactionAllowed()) {
            feedback = "Cần đối soát giao dịch trước khi tiếp tục";
            tone = UiLabels.Tone.ERROR;
        } else {
            feedback = lastFeedback;
            tone = lastTone;
        }
        statusLabel.setText(feedback);
        UiLabels.applyTone(statusLabel, tone);

        int tab = mainTabs.getSelectionModel().getSelectedIndex();
        boolean operations = uiReady && tab == TAB_OPERATIONS;
        UiLabels.markPrimary(btnIdentify, !btnIdentify.isDisable() && !uiReady);
        transactionPanel.markPrimary(operations);
        personalizePanel.setIssuePrimary(uiReady && tab == TAB_CARD);
        photoPanel.setUploadPrimary(uiReady && tab == TAB_RECORDS);
    }

    void fillTransactionInputs(char[] studentPin, char[] adminPin, int delta) {
        transactionPanel.fillInputs(studentPin, adminPin, delta);
    }

    void logTransactionState(String label) {
        transactionPanel.logTransactionState(label, receiptPanel.displayedVisitId());
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
        return receiptPanel.pressReadReceipt();
    }

    boolean pressGrant() {
        return transactionPanel.pressGrant();
    }

    boolean pressStart() {
        return transactionPanel.pressStart();
    }

    boolean pressEnd() {
        return transactionPanel.pressEnd();
    }

    boolean pressReconcile() {
        return receiptPanel.pressReconcile();
    }

    boolean pressLoadHistory() {
        return historyPanel.pressReload();
    }

    int historyRowCount() {
        return historyPanel.rowCount();
    }

    void captureScene(java.nio.file.Path path) throws java.io.IOException {
        javafx.scene.image.WritableImage image = root().snapshot(null, null);
        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                (int) image.getWidth(), (int) image.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        java.nio.file.Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(buffered, "png", path.toFile());
        log("Đã lưu ảnh giao diện " + path);
    }

    private javafx.scene.Parent root() {
        return statusLabel.getScene().getRoot();
    }

    private void rememberHistory() {
        historyReady = false;
        loadedHistory = null;
        historyError = null;
        CardStatus current = gateway.status();
        if (current == null || current.cardId() == null || current.cardId().isBlank()) {
            historyError = new IllegalStateException("chưa có mã thẻ để đọc lịch sử");
            historyReady = true;
            return;
        }
        try {
            loadedHistory = backendClient.historyWithLedger(current.cardId());
            historyReady = true;
        } catch (BackendClient.Failure failure) {
            historyError = failure;
            historyReady = true;
        }
    }

    private void publishHistory() {
        if (!historyReady) {
            return;
        }
        historyReady = false;
        if (historyError != null) {
            historyPanel.showFailure(historyError);
            return;
        }
        historyPanel.show(loadedHistory);
    }

    void logReconcileEvidence(String label) {
        PendingJournal.Loaded loaded = journal.load();
        String journalState = !loaded.present() ? "khong-co"
                : !loaded.valid() ? "KHONG-HOP-LE"
                : loaded.status().name();
        CardStatus current = gateway.status();
        String cardState = current == null || !current.studentStateAvailable()
                ? "state-the-khong-kha-dung"
                : String.format("counter=%d balance=%d caMo=%s", current.counter(), current.balance(),
                        current.hasActiveVisit() ? current.activeVisitId() : "khong");
        log(String.format(
                "SIGN-08 %s | journal=%s | doiSoat=%s | the %s | grant=%s start=%s end=%s"
                        + " | nutDoiSoat=%s | nhan=%s",
                label, journalState, gateway.reconciliation().state(), cardState,
                transactionPanel.grantState(), transactionPanel.startState(),
                transactionPanel.endState(), receiptPanel.reconcileState(),
                statusLabel == null ? "" : statusLabel.getText()));
    }

    void logUi10Evidence(String label) {
        PendingJournal.Loaded loaded = journal.load();
        String journalState = !loaded.present() ? "khong-co"
                : !loaded.valid() ? "KHONG-HOP-LE (" + loaded.problem() + ")"
                : String.format("%s cardId=%s counter=%d operation=%s",
                        loaded.status(), loaded.entry().cardId(),
                        loaded.entry().expectedCounter(),
                        Receipt.operationText(loaded.entry().operation()));
        CardStatus current = gateway.status();
        String cardState = current == null ? "chua-nhan-dien"
                : !current.studentStateAvailable()
                        ? "cardId=" + current.cardId() + " state-student-khong-kha-dung"
                        : String.format("cardId=%s counter=%d balance=%d caMo=%s",
                                current.cardId(), current.counter(), current.balance(),
                                current.hasActiveVisit() ? current.activeVisitId() : "khong");
        log(String.format(
                "UI-10 %s | journal=%s | doiSoat=%s | dangKyThe=%s | the %s"
                        + " | grant=%s start=%s end=%s | nutDoiSoat=%s | hoThoaiDangMo=%s"
                        + " | ketNoi=%s nhanDien=%s | nhan=%s",
                label, journalState, gateway.reconciliation().state(), gateway.enrollmentText(),
                cardState, transactionPanel.grantState(), transactionPanel.startState(),
                transactionPanel.endState(), receiptPanel.reconcileState(),
                reconcileDialogVisible(), uiConnected, uiReady,
                statusLabel == null ? "" : statusLabel.getText()));
    }

    boolean reconcileDialogVisible() {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().getRoot() instanceof javafx.scene.control.DialogPane) {
                return true;
            }
        }
        return false;
    }

    String probeTransactionGate(String label) {
        return transactionPanel.probeTransactionGate(label);
    }

    private boolean snapshotKnown() { return stateSnapshotKnown; }

    private boolean sessionOpen() { return !closing && !busy; }

    private CardOperationResult runCardStateTransaction(CardGateway.TransactionPlan plan,
            CardGateway.CommandSupplier supplier, char[] studentPin)
            throws javax.smartcardio.CardException {
        return gateway.runStateTransaction(journal, plan, supplier, studentPin);
    }

    private CardOperationResult reconcileCardPending(char[] studentPin)
            throws javax.smartcardio.CardException {
        return gateway.reconcilePending(journal, studentPin);
    }

    private PendingJournal.Loaded pendingJournal() { return journal.load(); }

    private void invalidateHistory() { historyReady = false; }

    private char[] studentPinInput() { return transactionPanel.studentPinInput(); }

    private void clearStudentPinInput() { transactionPanel.clearStudentPin(); }

    private void transactionFinished(boolean ok) {
        CardStatus refreshed = gateway.status();
        stateSnapshotKnown = ok && refreshed != null && refreshed.studentStateAvailable();
        if (!stateSnapshotKnown) {
            gateway.clearAuthenticatedStatus();
        }
        receiptPanel.showReceipt();
        publishHistory();
    }

    private void receiptFinished(boolean ok) {
        CardStatus current = gateway.status();
        stateSnapshotKnown = ok && current != null && current.studentStateAvailable();
    }

    private void reconcileFinished(boolean ok) {
        CardStatus refreshed = gateway.status();
        stateSnapshotKnown = ok && !journal.load().present()
                && gateway.reconciliation().transactionAllowed()
                && refreshed != null && refreshed.studentStateAvailable();
    }

    boolean isBusy() {
        return busy;
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

    private void trimLogArea() {
        int overflow = logArea.getLength() - LOG_MAX_CHARS;
        if (overflow < LOG_TRIM_CHARS) {
            return;
        }
        int cut = logArea.getText(0, Math.min(overflow + 200, logArea.getLength())).indexOf('\n');
        logArea.deleteText(0, cut >= 0 ? cut + 1 : overflow);
    }

    private void log(String message) {
        String line = "[" + LocalTime.now().format(TS) + "] " + message;
        System.out.println(line);
        if (Platform.isFxApplicationThread()) {
            showLogLine(message, line);
            return;
        }
        if (logUiClosed) {
            return;
        }
        try {
            Platform.runLater(() -> showLogLine(message, line));
        } catch (IllegalStateException toolkitStopped) {
            logUiClosed = true;
            System.out.println("[" + LocalTime.now().format(TS) + "] ngừng vẽ nhật ký: "
                    + toolkitStopped.getMessage());
        }
    }

    private void showLogLine(String message, String line) {
        if (logArea != null) {
            logArea.appendText(line + System.lineSeparator());
            trimLogArea();
            logArea.setScrollTop(Double.MAX_VALUE);
        }
        boolean failure = message.startsWith("[FAIL]") || message.startsWith("[ERROR]");
        boolean success = message.startsWith("[OK]");
        if (!failure && !success) {
            return;
        }
        logShowsFailure = failure;
        if (logPane != null) {
            boolean narrow = workSplit != null && workSplit.getOrientation() == Orientation.VERTICAL;
            logPane.setExpanded(failure || !narrow);
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

    private static double windowWidth() {
        return Math.min(1180, Screen.getPrimary().getVisualBounds().getWidth() - UiScale.SPACE_5 * 2);
    }

    private static double windowHeight() {
        return Math.min(760, Screen.getPrimary().getVisualBounds().getHeight() - UiScale.SPACE_5 * 2);
    }
}
