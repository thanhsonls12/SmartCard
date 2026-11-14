package vn.labaccess.desktop;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
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
    private StatusBar statusBar;
    private Label valueConnection;
    private TabPane mainTabs;

    static final int TAB_CARD = 0;
    static final int TAB_ENROLL = 1;
    static final int TAB_OPERATIONS = 2;
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
    private ToggleButton logToggle;
    private SplitPane workSplit;
    private TitledPane logPane;
    private VBox logColumn;
    private HistoryPanel historyPanel;
    private BackendClient.HistoryLoad loadedHistory;
    private Exception historyError;
    private boolean historyReady;
    private String busyLabel;
    private String lastFeedback = "Chưa có thao tác";
    private String lastFailure;
    private final InputFeedback inputFeedback = new InputFeedback(this::updateButtons);
    private UiLabels.Tone lastTone = UiLabels.Tone.NEUTRAL;
    private boolean logUiClosed;

    private static final double NARROW_WIDTH = 1000;
    private static final double SPLIT_BUSINESS = 0.65;
    private double horizontalSplit = SPLIT_BUSINESS;
    private double verticalSplit = 0.60;
    static final String BUSY_WHY = "đang xử lý thao tác khác";
    static final String BUSY_NOTE = "Đang xử lý thao tác khác — chờ xong.";
    private static final String CLOSING_NOTE = "Đang đóng cửa sổ.";

    private ActionNote actionNote;

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
        techToggle.getStyleClass().add("la-header-toggle");
        logToggle = new ToggleButton("Nhật ký");
        logToggle.getStyleClass().add("la-header-toggle");
        logToggle.setTooltip(new Tooltip("Bật/tắt khung nhật ký thao tác"));

        actionNote = new ActionNote(() -> logToggle.setSelected(true));

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
                        this::rememberHistory, this::transactionFinished, this::studentPinChanged,
                        () -> receiptPanel.pressReadReceipt()));
        receiptPanel = new ReceiptPanel(gateway, this::runOp, this::log,
                new ReceiptPanel.Session(this::sessionOpen, this::pendingJournal, journal.file(),
                        this::studentPinInput, this::clearStudentPinInput,
                        this::reconcileCardPending, this::invalidateHistory,
                        this::rememberHistory, this::publishHistory, this::refreshButtons,
                        this::showReconcile, this::receiptFinished, this::reconcileFinished,
                        () -> {
                            showTab(TAB_OPERATIONS);
                            transactionPanel.requireStudentPin();
                        }));
        pinPanel = new PinPanel(gateway, this::runOp, this::log);
        enrollmentPanel = new EnrollmentPanel(gateway, this::runEnrollmentOp, backendClient,
                this::log, this::reportEnrollment);
        personalizePanel = new PersonalizePanel(gateway, this::runOp, this::log,
                (ok, issued) -> {
                    uiReady = ok && gateway.isReady();
                    stateSnapshotKnown = false;
                    showStatus();
                    if (ok && issued != null) {
                        photoPanel.onCard(issued.cardId());
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
                UiRows.pair(UiLabels.caption("Mã thẻ"),
                        UiRows.pair(valueCardId, UiLabels.copyButton(valueCardId, "mã thẻ"))),
                UiRows.pair(UiLabels.caption("Vòng đời"), valueLifecycle),
                UiRows.pair(UiLabels.caption("Nhận diện"), valueResult),
                UiRows.pair(UiLabels.caption("Đối soát"), valueReconcile));

        FlowPane sessionActions = UiRows.wrapping(btnConnect, btnIdentify, btnDisconnect,
                receiptPanel.reconcileAction(), techToggle, logToggle);

        GridPane technical = new GridPane();
        technical.getStyleClass().add("la-info");
        technical.setHgap(UiScale.SPACE_3);
        technical.setVgap(UiScale.SPACE_1);
        technical.setPadding(UiScale.blockInsets());
        technical.add(UiLabels.caption("Mã ứng dụng thẻ"), 0, 0);
        technical.add(valueAid, 1, 0);
        technical.add(UiLabels.caption("Protocol"), 0, 1);
        technical.add(valueProtocol, 1, 1);
        technical.setManaged(false);
        technical.setVisible(false);
        techToggle.selectedProperty().addListener((observable, was, now) -> {
            technical.setManaged(now);
            technical.setVisible(now);
            personalizePanel.showTechnical(now);
            profilePanel.showTechnical(now);
            photoPanel.showTechnical(now);
            enrollmentPanel.showTechnical(now);
        });

        VBox header = new VBox(UiScale.SPACE_2, summary, sessionActions, actionNote.node(),
                technical);
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

        mainTabs = new TabPane(tabCard, tabEnroll, tabOperations, tabRecords);
        mainTabs.getStyleClass().add("la-tabs");
        mainTabs.addEventHandler(InputFeedback.Problem.TYPE, inputFeedback::show);
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.getSelectionModel().select(TAB_OPERATIONS);
        mainTabs.setMinHeight(0);
        VBox.setVgrow(mainTabs, Priority.ALWAYS);

        statusBar = new StatusBar(this::showTab);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("la-log");
        TitledPane logPane = new TitledPane("Nhật ký thao tác", logArea);
        logPane.setMaxHeight(Double.MAX_VALUE);
        logPane.getStyleClass().add("la-log-pane");
        this.logPane = logPane;
        logToggle.selectedProperty().bindBidirectional(logPane.expandedProperty());
        logPane.setExpanded(false);

        VBox business = new VBox(mainTabs);
        business.setMinHeight(0);
        business.setPadding(UiScale.centerInsets());

        logColumn = new VBox(logPane);
        logColumn.setMinWidth(320);
        business.setMinWidth(0);
        logColumn.setPadding(UiScale.centerInsets());
        VBox.setVgrow(logPane, Priority.ALWAYS);

        workSplit = new SplitPane(business);
        workSplit.getStyleClass().add("la-split");
        SplitPane.setResizableWithParent(logColumn, false);
        VBox.setVgrow(workSplit, Priority.ALWAYS);
        logPane.expandedProperty().addListener((observable, was, now) -> showLogColumn(now));

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(workSplit);
        root.setBottom(statusBar.node());

        Scene scene = new Scene(root, windowWidth(), windowHeight());
        UiTheme.apply(scene);
        scene.widthProperty().addListener((observable, was, now) -> layoutForWidth(now.doubleValue()));
        mainTabs.getSelectionModel().selectedIndexProperty().addListener((observable, was, now) -> {
            inputFeedback.clear();
            updateButtons();
        });
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
        if (mainTabs == null || index < 0 || index >= mainTabs.getTabs().size()) {
            return;
        }
        mainTabs.getSelectionModel().select(index);
        if (mainTabs.getTabs().get(index).getContent() instanceof ScrollPane scroll) {
            scroll.setVvalue(0);
        }
    }

    private void layoutForWidth(double width) {
        if (workSplit == null) {
            return;
        }
        Orientation orientation = width < NARROW_WIDTH ? Orientation.VERTICAL : Orientation.HORIZONTAL;
        if (orientation == workSplit.getOrientation()) {
            return;
        }
        rememberLogSplit();
        workSplit.setOrientation(orientation);
        restoreLogSplit();
    }

    private void showLogColumn(boolean expanded) {
        boolean shown = workSplit.getItems().contains(logColumn);
        if (expanded == shown) {
            return;
        }
        if (expanded) {
            workSplit.getItems().add(logColumn);
            restoreLogSplit();
            return;
        }
        rememberLogSplit();
        workSplit.getItems().remove(logColumn);
        workSplit.setDividerPositions(1);
    }

    private void rememberLogSplit() {
        if (workSplit.getDividers().isEmpty()) {
            return;
        }
        double position = workSplit.getDividerPositions()[0];
        if (workSplit.getOrientation() == Orientation.HORIZONTAL) {
            horizontalSplit = position;
        } else {
            verticalSplit = position;
        }
    }

    private void restoreLogSplit() {
        workSplit.setDividerPositions(workSplit.getOrientation() == Orientation.HORIZONTAL
                ? horizontalSplit : verticalSplit);
    }

    private void blockOnStartupJournal() {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            log("Khởi động: không có pending journal");
            return;
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            gateway.markTransactionUnknown(JournalRecoveryView.summary(loaded));
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
        clearSessionInputs();
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
        uiReady = false;
        stateSnapshotKnown = false;
        clearSessionInputs();
        invalidateHistory();
        historyPanel.onCard(null);
        receiptPanel.clear();
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
            photoPanel.onCard(ok ? gateway.status().cardId() : null);
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
                    enrolled ? "sổ Backend có mã thẻ này"
                            : "sổ Backend không có mã thẻ này"));
            return enrolled
                    ? "đã đăng ký (sổ Backend có mã thẻ này)"
                    : "CHƯA đăng ký (sổ Backend không có mã thẻ) — GRANT/START/END bị chặn";
        } catch (BackendClient.Failure failure) {
            gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(false, false,
                    failure.getMessage()));
            return "chưa xác nhận được (" + failure.getMessage()
                    + ") — GRANT/START/END bị chặn";
        }
    }

    private void doDisconnect() {
        clearSessionInputs();
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
            valueResult.setText("Chưa nhận diện");
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
        boolean recovery = JournalRecoveryView.needed(journal.load());
        valueReconcile.setText(recovery ? "Cần khôi phục" : known ? "Không có giao dịch chờ" : "Cần đối soát");
        UiLabels.Tone tone = recovery || !known
                ? UiLabels.Tone.ERROR
                : (uiConnected ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
        UiLabels.applyChip(valueReconcile, tone);
    }

    private void clearSessionInputs() {
        inputFeedback.clear();
        transactionPanel.clearInputs();
        personalizePanel.clearInputs();
        pinPanel.clearInputs();
        profilePanel.clearInputs();
        photoPanel.clearInputs();
        enrollmentPanel.clearInputs();
    }

    private void clearInfo() {
        clearSessionInputs();
        invalidateHistory();
        valueConnection.setText(uiConnected ? "Đã kết nối" : "Chưa kết nối");
        UiLabels.applyChip(valueConnection, uiConnected ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
        valueLifecycle.setText("Chưa rõ");
        UiLabels.applyChip(valueLifecycle, UiLabels.Tone.NEUTRAL);
        valueAid.setText("-");
        valueProtocol.setText("-");
        valueCardId.setText("-");
        valueResult.setText("Chưa nhận diện");
        UiLabels.applyChip(valueResult, UiLabels.Tone.NEUTRAL);
        showReconcile();
        receiptPanel.clear();
        historyPanel.onCard(null);
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
        lastFailure = null;
        inputFeedback.clear();
        busyLabel = label;
        updateButtons();
        log("> " + label + " ...");
        boolean technical = techToggle.isSelected();

        worker.submit(() -> {
            T value = null;
            Exception failure = null;
            try {
                if (technical) {
                    log("   " + label + " chạy trên thread=" + Thread.currentThread().getName());
                }
                value = operation.call();
            } catch (Exception ex) {
                failure = ex;
            }
            final T resultValue = value;
            final Exception resultFailure = failure;
            Platform.runLater(() -> {
                if (technical) {
                    log("   " + label + " cập nhật UI trên thread="
                            + Thread.currentThread().getName());
                }
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
        lastFailure = null;
        inputFeedback.clear();
        busyLabel = label;
        updateButtons();
        log("> " + label + " ...");
        boolean technical = techToggle.isSelected();

        worker.submit(() -> {
            String line;
            String problem = null;
            boolean ok = false;
            try {
                if (technical) {
                    log("   " + label + " chạy trên thread=" + Thread.currentThread().getName());
                }
                CardOperationResult r = op.call();
                ok = r.ok();
                line = (ok ? "[OK]   " : "[FAIL] ") + label + ": " + r.text();
                if (!ok) {
                    problem = UiLabels.failureMessage(label, r.text(), null);
                }
            } catch (Exception ex) {
                line = "[ERROR] " + label + ": " + ex;
                problem = UiLabels.failureMessage(label, "", ex);
            }
            final boolean okFinal = ok;
            final String lineFinal = line;
            final String failureFinal = problem;
            Platform.runLater(() -> {
                log(lineFinal);
                if (technical) {
                    log("   " + label + " cập nhật UI trên thread="
                            + Thread.currentThread().getName());
                }
                if (onResult != null) {
                    onResult.accept(okFinal);
                }
                busy = false;
                busyLabel = null;
                lastFeedback = UiLabels.outcome(okFinal, label);
                lastFailure = failureFinal;
                lastTone = okFinal ? UiLabels.Tone.OK : UiLabels.Tone.ERROR;
                updateButtons();
            });
        });
    }

    private void updateButtons() {
        PendingJournal.Loaded loaded = journal.load();
        boolean journalPresent = loaded.present();
        boolean validPending = journalPresent && loaded.valid()
                && loaded.status() == PendingJournal.Status.PENDING;
        CardStatus current = gateway.status();
        boolean issued = current != null && current.lifecycle() == CardLifecycle.ISSUED;

        if (closing) {
            lockForClosing();
            return;
        }

        if (journalPresent && !loaded.valid()) {
            gateway.markTransactionUnknown("pending journal không hợp lệ — cần xử lý thủ công");
        } else if (journalPresent && loaded.status() == PendingJournal.Status.RESOLVED) {
            gateway.markTransactionUnknown("journal đã RESOLVED nhưng chưa xóa — cần xử lý thủ công");
        }

        SessionGates.State gates = new SessionGates.State(busy, uiConnected, uiReady,
                stateSnapshotKnown, gateway.reconciliation().transactionAllowed(), journalPresent,
                validPending, issued, gateway.enrollmentBlockReason(), current);
        SessionGates.Reasons reasons = SessionGates.of(gates);

        btnConnect.setDisable(busy || uiConnected);
        btnIdentify.setDisable(reasons.identify() != null);
        btnDisconnect.setDisable(reasons.disconnect() != null);
        receiptPanel.applyLock(busy, reasons.readReceipt(), reasons.reconcile());
        transactionPanel.applyLock(busy, reasons.grant(), reasons.start(), reasons.end(), reasons.readReceipt());
        transactionPanel.showState(current, stateSnapshotKnown);
        pinPanel.setEnabled(reasons.pin() == null, reasons.pin());
        personalizePanel.updateAvailability(reasons.personalize() == null, !busy && uiConnected,
                reasons.personalize());
        profilePanel.setEnabled(reasons.profile() == null, reasons.profile());
        photoPanel.setEnabled(reasons.photo() == null, reasons.photo());
        enrollmentPanel.setEnabled(reasons.enroll() == null, reasons.enroll());
        enrollmentPanel.onCard(current == null ? null : current.cardId());
        historyPanel.onCard(uiReady && current != null ? current.cardId() : null);
        historyPanel.setEnabled(reasons.history() == null, reasons.history());

        if (busy) {
            actionNote.show(BUSY_NOTE, UiLabels.Tone.NEUTRAL, false);
        } else if (JournalRecoveryView.needed(loaded)) {
            actionNote.show(JournalRecoveryView.summary(loaded) + " Mở Hướng dẫn khôi phục.",
                    UiLabels.Tone.ERROR, false);
        } else if (journalPresent || !gateway.reconciliation().transactionAllowed()) {
            actionNote.show("Có giao dịch chưa rõ kết quả. Không thực hiện lại; kiểm tra và đối soát trước.",
                    UiLabels.Tone.PENDING, true);
        } else if (lastFailure != null) {
            actionNote.show(lastFailure, UiLabels.Tone.ERROR, true);
        } else if (!uiConnected) {
            actionNote.show("Kết nối thẻ để bắt đầu.", UiLabels.Tone.NEUTRAL, false);
        } else if (!uiReady) {
            actionNote.show("Đã kết nối. Bấm Nhận diện thẻ để tiếp tục.", UiLabels.Tone.NEUTRAL, false);
        } else {
            actionNote.show(null, UiLabels.Tone.NEUTRAL, false);
        }

        int activeTab = mainTabs.getSelectionModel().getSelectedIndex();
        SessionGates.NextStep next = SessionGates.nextStep(closing, gates, gateway.enrollmentText(),
                inputFeedback.message() != null, activeTab);
        if (!busy && !journalPresent && gateway.reconciliation().transactionAllowed() && lastFailure != null
                && inputFeedback.message() == null) {
            next = new SessionGates.NextStep("xem hướng dẫn lỗi ở đầu cửa sổ", -1);
        }
        statusBar.setNextStep(next.text(), next.tab());

        String feedback;
        UiLabels.Tone tone;
        if (busy) {
            feedback = "Đang xử lý: " + (busyLabel == null ? "…" : UiLabels.actionName(busyLabel));
            tone = UiLabels.Tone.PENDING;
        } else if (journalPresent) {
            feedback = validPending
                    ? "Có biên nhận chờ đối soát — đối soát trước khi giao dịch tiếp"
                    : "Giao dịch đang khóa. Xem Hướng dẫn khôi phục.";
            tone = validPending ? UiLabels.Tone.PENDING : UiLabels.Tone.ERROR;
        } else if (!gateway.reconciliation().transactionAllowed()) {
            feedback = "Cần đối soát giao dịch trước khi tiếp tục";
            tone = UiLabels.Tone.ERROR;
        } else if (inputFeedback.message() != null) {
            feedback = inputFeedback.message();
            tone = UiLabels.Tone.ERROR;
        } else {
            feedback = lastFeedback;
            tone = lastTone;
        }
        statusBar.setStatus(feedback, tone, busy);

        boolean reconcilePrimary = !busy && validPending && receiptPanel.reconcileEnabled();
        receiptPanel.setReconcilePrimary(reconcilePrimary);
        int tab = mainTabs.getSelectionModel().getSelectedIndex();
        boolean operations = uiReady && tab == TAB_OPERATIONS;
        boolean connectPrimary = !reconcilePrimary && !uiConnected && !btnConnect.isDisable();
        UiLabels.markPrimary(btnConnect, connectPrimary);
        UiLabels.markPrimary(btnIdentify, !reconcilePrimary && !connectPrimary && uiConnected
                && !uiReady && !btnIdentify.isDisable());
        transactionPanel.markPrimary(!reconcilePrimary && operations);
        personalizePanel.setIssuePrimary(!reconcilePrimary && uiReady && tab == TAB_CARD);
        photoPanel.setUploadPrimary(!reconcilePrimary && uiReady && tab == TAB_RECORDS);
    }

    private void lockForClosing() {
        btnConnect.setDisable(true);
        btnIdentify.setDisable(true);
        btnDisconnect.setDisable(true);
        receiptPanel.lockAll();
        transactionPanel.lockAll();
        pinPanel.setEnabled(false, CLOSING_NOTE);
        personalizePanel.updateAvailability(false, false, CLOSING_NOTE);
        profilePanel.setEnabled(false, CLOSING_NOTE);
        photoPanel.setEnabled(false, CLOSING_NOTE);
        enrollmentPanel.setEnabled(false, CLOSING_NOTE);
        historyPanel.setEnabled(false, CLOSING_NOTE);
        statusBar.setStatus("Đang đóng cửa sổ", UiLabels.Tone.PENDING, false);
        statusBar.setNextStep("chờ cửa sổ đóng", -1);
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
        return statusBar.node().getScene().getRoot();
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
                statusBar.statusText()));
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
                statusBar.statusText()));
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

    private void studentPinChanged() { receiptPanel.studentPinChanged(); }

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
        if (failure && logPane != null) {
            logPane.setExpanded(true);
        }
    }

    private void shutdown() {
        clearSessionInputs();
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
