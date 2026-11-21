package vn.labaccess.desktop;

import static vn.labaccess.desktop.UiSection.tab;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.application.Application;
import javafx.application.Platform;
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

    private final CardSession session = new CardSession(this::log);
    private final CardTaskRunner taskRunner = new CardTaskRunner();

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
    private String lastFeedback = "Chưa có thao tác";
    private String lastFailure;
    private final InputFeedback inputFeedback = new InputFeedback(this::updateButtons);
    private UiLabels.Tone lastTone = UiLabels.Tone.NEUTRAL;
    private boolean logUiClosed;

    private static final double NARROW_WIDTH = 920;
    private static final double SPLIT_BUSINESS = 0.65;
    private double horizontalSplit = SPLIT_BUSINESS;
    private double verticalSplit = 0.68;
    static final String BUSY_WHY = "đang xử lý thao tác khác";
    static final String BUSY_NOTE = "Đang xử lý thao tác khác — chờ xong.";
    private static final String CLOSING_NOTE = "Đang đóng cửa sổ.";

    private ActionNote actionNote;

    private TransactionPanel transactionPanel;
    private ReceiptPanel receiptPanel;
    private PinPanel pinPanel;
    private PersonalizePanel personalizePanel;
    private EnrollmentPanel enrollmentPanel;
    private CardIdentityPanel identityPanel;
    private SystemConnectionPanel systemConnectionPanel;
    private ProfilePanel profilePanel;
    private PhotoPanel photoPanel;
    private DemoScenario demo;

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

        identityPanel = new CardIdentityPanel();
        systemConnectionPanel = new SystemConnectionPanel(session.backend(), session.gateway(),
                this::runEnrollmentOp, (ok, message) -> {
                    lastFeedback = UiLabels.outcome(ok, "CHECK_SYSTEM");
                    lastFailure = ok ? null : message;
                    lastTone = ok ? UiLabels.Tone.OK : UiLabels.Tone.ERROR;
                    log((ok ? "[OK] " : "[FAIL] ") + message);
                    updateButtons();
                });
        transactionPanel = new TransactionPanel(session.gateway(), this::runOp, this::log,
                new TransactionPanel.Session(this::snapshotKnown, this::sessionOpen,
                        session::runStateTransaction,
                        session::grant,
                        this::invalidateHistory,
                        this::rememberHistory, this::transactionFinished, this::studentPinChanged,
                        () -> receiptPanel.pressReadReceipt()));
        receiptPanel = new ReceiptPanel(session.gateway(), this::runOp, this::log,
                new ReceiptPanel.Session(this::sessionOpen, session::pendingJournal, session.journalFile(),
                        this::studentPinInput, this::clearStudentPinInput,
                        session::reconcilePending,
                        new ReceiptPanel.AdminRecovery(() -> {
                            showTab(TAB_OPERATIONS);
                            return transactionPanel.requireAdminPin();
                        }, transactionPanel::takeAdminPin, transactionPanel::clearAdminPin,
                                session::reconcileAdminGrant,
                                session::confirmSuperseded, session::inspectManualReconcile,
                                session::confirmManualReconcile),
                        this::invalidateHistory,
                        this::rememberHistory, this::publishHistory, this::refreshButtons,
                        this::receiptFinished, this::reconcileFinished,
                        () -> {
                            showTab(TAB_OPERATIONS);
                            transactionPanel.requireStudentPin();
                        }));
        pinPanel = new PinPanel(session.gateway(), this::runOp, this::log);
        enrollmentPanel = new EnrollmentPanel(session.gateway(), this::runEnrollmentOp, session.backend(),
                this::log, this::reportEnrollment);
        personalizePanel = new PersonalizePanel(session.gateway(), this::runOp, this::log,
                (ok, issued) -> {
                    uiReady = ok && session.gateway().isReady();
                    session.invalidateSnapshot();
                    showStatus();
                    if (ok && issued != null) {
                        photoPanel.onCard(issued.cardId());
                        enrollmentPanel.prefill(issued.cardId(), issued.studentId());
                    }
                });
        profilePanel = new ProfilePanel(session.gateway(), this::runOp, this::log);
        photoPanel = new PhotoPanel(session.gateway(), this::runOp, this::log);
        demo = new DemoScenario(this, pinPanel, personalizePanel, enrollmentPanel,
                profilePanel, photoPanel, this::log);

        valueConnection = UiLabels.chip();
        valueAid = UiLabels.value();
        valueProtocol = UiLabels.value();
        valueLifecycle = UiLabels.chip();
        valueCardId = UiLabels.value();
        valueCardId.getStyleClass().add("la-card-id");
        valueResult = UiLabels.chip();

        Label brand = new Label("LabAccess");
        brand.getStyleClass().add("la-brand");
        FlowPane summary = UiRows.wrapping(brand,
                UiRows.pair(UiLabels.caption("Kết nối"), valueConnection),
                UiRows.pair(UiLabels.caption("Mã thẻ"),
                        UiRows.pair(valueCardId, UiLabels.copyButton(valueCardId, "mã thẻ"))),
                UiRows.pair(UiLabels.caption("Vòng đời"), valueLifecycle),
                UiRows.pair(UiLabels.caption("Nhận diện"), valueResult),
                UiRows.pair(UiLabels.caption("Đối soát"), receiptPanel.reconcileStatus()),
                UiRows.pair(UiLabels.caption("Sổ giao dịch"), systemConnectionPanel.statusNode()));

        FlowPane sessionActions = UiRows.wrapping(btnConnect, btnIdentify, btnDisconnect,
                receiptPanel.reconcileAction(), systemConnectionPanel.actionNode(), techToggle, logToggle);
        sessionActions.getStyleClass().add("la-header-actions");

        GridPane technical = new GridPane();
        technical.getStyleClass().add("la-info");
        technical.setHgap(UiScale.SPACE_3);
        technical.setVgap(UiScale.SPACE_1);
        technical.setPadding(UiScale.blockInsets());
        technical.add(UiLabels.caption("Mã ứng dụng thẻ"), 0, 0);
        technical.add(valueAid, 1, 0);
        technical.add(UiLabels.caption("Protocol"), 0, 1);
        technical.add(valueProtocol, 1, 1);
        Label valuePid = UiLabels.value();
        valuePid.setText(String.valueOf(pid));
        technical.add(UiLabels.caption("PID"), 0, 2);
        technical.add(valuePid, 1, 2);
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

        VBox header = new VBox(UiScale.SPACE_3, summary, sessionActions, actionNote.node(),
                technical);
        header.getStyleClass().add("la-header");
        header.setPadding(UiScale.headerInsets());

        historyPanel = new HistoryPanel(session.backend(), this::runEnrollmentOp,
                () -> {
                    CardStatus current = session.gateway().status();
                    return current == null ? "" : current.cardId();
                },
                this::log);
        Tab tabOperations = tab("Vận hành",
                identityPanel.node(),
                new UiColumns(UiScale.WORKSPACE_COLUMNS_WIDTH, 64,
                        transactionPanel.node(), receiptPanel.node()),
                historyPanel.node());

        Tab tabRecords = tab("Hồ sơ & ảnh", profilePanel.node(), photoPanel.node());

        Tab tabCard = tab("Cấp thẻ & bảo mật",
                new UiColumns(UiScale.WORKSPACE_COLUMNS_WIDTH, 42,
                        personalizePanel.node(), pinPanel.node()));

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
        business.setMinHeight(260);
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
        root.setCenter(new VBox(statusBar.node(), workSplit));

        Scene scene = new Scene(root, windowWidth(), windowHeight());
        UiTheme.apply(scene);
        scene.widthProperty().addListener((observable, was, now) -> layoutForWidth(now.doubleValue()));
        mainTabs.getSelectionModel().selectedIndexProperty().addListener((observable, was, now) -> {
            inputFeedback.clear();
            updateButtons();
        });
        layoutForWidth(windowWidth());
        String title = "LabAccess — Quản lý & vận hành thẻ";
        stage.setTitle(Boolean.getBoolean("labaccess.demo") ? title + " (PID " + pid + ")" : title);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (taskRunner.isClosing()) {
                return;
            }
            e.consume();
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
        logColumn.setMinHeight(orientation == Orientation.VERTICAL ? UiScale.LOG_MIN_HEIGHT : 0);
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
        CardSession.StartupJournal startup = session.blockOnStartupJournal();
        if (startup.present()) {
            receiptPanel.showReconcile();
        }
        log(startup.message());
    }

    private void doConnect() {
        clearSessionInputs();
        runOp("CONNECT_AND_IDENTIFY", session::connect, ok -> {
            uiConnected = session.gateway().isConnected();
            uiReady = ok && session.gateway().isReady();
            session.invalidateSnapshot();
            clearInfo();
            photoPanel.clearCardImage(ok
                    ? "vừa nhận diện, chưa đọc ảnh từ thẻ"
                    : "kết nối lỗi, không giữ ảnh của phiên trước");
            showStatus();
            systemConnectionPanel.showBackendAccess();
            if (uiReady) {
                pinPanel.resetState("vừa kết nối, chưa đọc trạng thái PIN từ thẻ");
                profilePanel.resetView("vừa kết nối, chưa đọc hồ sơ");
                photoPanel.onCard(session.gateway().status().cardId());
                Platform.runLater(receiptPanel::offerPendingReconciliation);
            }
        });
    }

    private void doIdentify() {
        uiReady = false;
        session.invalidateSnapshot();
        clearSessionInputs();
        invalidateHistory();
        historyPanel.onCard(null);
        receiptPanel.clear();
        profilePanel.resetView("bắt đầu SELECT lại, không giữ hồ sơ của lần đọc trước");
        pinPanel.resetState("bắt đầu SELECT lại, chưa thử PIN");
        photoPanel.clearCardImage("bắt đầu SELECT lại, không giữ ảnh của lần đọc trước");
        runOp("SELECT + GET_STATUS", session::identify, ok -> {
            uiReady = ok;
            session.invalidateSnapshot();
            showStatus();
            systemConnectionPanel.showBackendAccess();
            photoPanel.onCard(ok ? session.gateway().status().cardId() : null);
            if (ok) {
                Platform.runLater(receiptPanel::offerPendingReconciliation);
            }
        });
    }

    private void doDisconnect() {
        clearSessionInputs();
        runOp("DISCONNECT", session::disconnect, ok -> {
            uiConnected = false;
            uiReady = false;
            session.invalidateSnapshot();
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
        CardStatus status = session.gateway().status();
        if (!uiReady || status == null) {
            valueConnection.setText(session.gateway().needsReconnect() ? "Cần kết nối lại"
                    : uiConnected ? "Đã kết nối" : "Chưa kết nối");
            UiLabels.applyChip(valueConnection, uiConnected
                    ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL);
            valueAid.setText(UiLabels.EMPTY_VALUE);
            valueProtocol.setText(UiLabels.EMPTY_VALUE);
            valueLifecycle.setText("Chưa rõ");
            UiLabels.applyChip(valueLifecycle, UiLabels.Tone.NEUTRAL);
            valueCardId.setText(UiLabels.EMPTY_VALUE);
            valueResult.setText("Chưa nhận diện");
            UiLabels.applyChip(valueResult, UiLabels.Tone.PENDING);
            receiptPanel.showReconcile();
            return;
        }
        valueConnection.setText("Đã kết nối");
        UiLabels.applyChip(valueConnection, UiLabels.Tone.NEUTRAL);
        valueAid.setText(status.instanceAid());
        valueProtocol.setText(String.format("%02X", status.protocolVersion()));
        valueLifecycle.setText(CardLifecycle.caption(status.lifecycle()));
        UiLabels.applyChip(valueLifecycle, status.lifecycle() == CardLifecycle.ISSUED
                ? UiLabels.Tone.NEUTRAL : UiLabels.Tone.PENDING);
        valueCardId.setText(status.cardId());
        valueResult.setText("Sẵn sàng");
        UiLabels.applyChip(valueResult, UiLabels.Tone.OK);
        receiptPanel.showReconcile();
        personalizePanel.showPublicKey();
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
        UiLabels.applyChip(valueConnection, UiLabels.Tone.NEUTRAL);
        valueLifecycle.setText("Chưa rõ");
        UiLabels.applyChip(valueLifecycle, UiLabels.Tone.NEUTRAL);
        valueAid.setText(UiLabels.EMPTY_VALUE);
        valueProtocol.setText(UiLabels.EMPTY_VALUE);
        valueCardId.setText(UiLabels.EMPTY_VALUE);
        valueResult.setText("Chưa nhận diện");
        UiLabels.applyChip(valueResult, UiLabels.Tone.NEUTRAL);
        receiptPanel.showReconcile();
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
        submitTask(label, operation, outcome -> { }, outcome -> syncCardConnection(), outcome -> {
            try {
                if (outcome.failure() == null) {
                    onSuccess.accept(outcome.value());
                } else {
                    onFailure.accept(outcome.failure());
                }
            } finally {
                updateButtons();
            }
        });
    }

    private <T> boolean submitTask(String label, Callable<T> operation,
            Consumer<CardTaskRunner.Outcome<T>> observed, Consumer<CardTaskRunner.Outcome<T>> beforeRelease,
            Consumer<CardTaskRunner.Outcome<T>> afterRelease) {
        boolean technical = techToggle.isSelected();
        return taskRunner.submit(label, () -> {
            if (technical) {
                log("   " + label + " chạy trên thread=" + Thread.currentThread().getName());
            }
            return operation.call();
        }, new CardTaskRunner.Callbacks<>(rejection -> logRejected(label, rejection), () -> {
            lastFailure = null;
            inputFeedback.clear();
            updateButtons();
            log("> " + label + " ...");
        }, outcome -> {
            observed.accept(outcome);
            if (technical) {
                log("   " + label + " cập nhật UI trên thread=" + Thread.currentThread().getName());
            }
        }, beforeRelease, afterRelease));
    }

    private void logRejected(String label, CardTaskRunner.Rejection rejection) {
        log("[" + label + "] bỏ qua — " + (rejection == CardTaskRunner.Rejection.CLOSING
                ? "đang đóng cửa sổ" : "đang xử lý thao tác khác"));
    }

    private void reportEnrollment(EnrollmentPanel.OutcomeKind kind, String message) {
        lastFeedback = message;
        lastTone = switch (kind) {
            case SUCCESS -> UiLabels.Tone.OK;
            case ERROR -> UiLabels.Tone.ERROR;
            case UNKNOWN -> UiLabels.Tone.PENDING;
        };
        if (kind == EnrollmentPanel.OutcomeKind.SUCCESS) {
            session.gateway().applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true,
                    "đăng ký thẻ vừa hoàn tất trên Backend"));
            systemConnectionPanel.showBackendAccess();
            updateButtons();
        }
    }

    private boolean runOp(String label, Callable<CardOperationResult> operation,
            Consumer<Boolean> onResult) {
        return submitTask(label, operation, outcome -> logCardOutcome(label, outcome), outcome -> {
            syncCardConnection();
            if (onResult != null) {
                onResult.accept(outcome.failure() == null && outcome.value().ok());
            }
        }, outcome -> showCardOutcome(label, outcome));
    }

    private void logCardOutcome(String label, CardTaskRunner.Outcome<CardOperationResult> outcome) {
        if (outcome.failure() != null) {
            log("[ERROR] " + label + ": " + outcome.failure());
            return;
        }
        CardOperationResult result = outcome.value();
        String level = result.superseded() != null ? "[INFO] " : result.ok() ? "[OK]   " : "[FAIL] ";
        log(level + label + ": " + result.text());
    }

    private void showCardOutcome(String label, CardTaskRunner.Outcome<CardOperationResult> outcome) {
        CardOperationResult result = outcome.value();
        boolean ok = outcome.failure() == null && result.ok();
        String warning = outcome.failure() == null ? result.warning() : null;
        boolean awaitingConfirmation = outcome.failure() == null && result.superseded() != null;
        String failure = ok ? null : awaitingConfirmation ? "Cần nhân viên xác nhận trước khi đóng journal."
                : outcome.failure() == null ? UiLabels.failureMessage(label, result.text(), null)
                : UiLabels.failureMessage(label, "", outcome.failure());
        lastFeedback = awaitingConfirmation ? "Chờ nhân viên xác nhận"
                : warning == null ? UiLabels.outcome(ok, label) : UiLabels.completedAction(label);
        lastFailure = warning == null ? failure : lastFeedback + ". " + warning;
        lastTone = warning != null || awaitingConfirmation ? UiLabels.Tone.PENDING
                : ok ? UiLabels.Tone.OK : UiLabels.Tone.ERROR;
        updateButtons();
        if (!taskRunner.isClosing() && uiReady && mainTabs.getSelectionModel().getSelectedIndex() == TAB_OPERATIONS) {
            transactionPanel.focusStudentPin();
        }
    }

    private void syncCardConnection() {
        CardSession.Connection connection = session.synchronizeConnection();
        uiConnected = connection.connected();
        uiReady = connection.ready();
        showStatus();
    }

    private void updateButtons() {
        boolean busy = taskRunner.isBusy();
        boolean closing = taskRunner.isClosing();
        String busyLabel = taskRunner.busyLabel();
        PendingJournal.Loaded loaded = session.pendingJournal();
        boolean journalPresent = loaded.present();
        boolean validPending = journalPresent && loaded.valid()
                && loaded.status() == PendingJournal.Status.PENDING;
        CardStatus current = session.gateway().status();
        boolean issued = current != null && current.lifecycle() == CardLifecycle.ISSUED;

        if (closing) {
            lockForClosing();
            return;
        }

        session.blockUnusableJournal(loaded);

        SessionGates.State gates = new SessionGates.State(busy, uiConnected, uiReady,
                session.snapshotKnown(), session.gateway().reconciliation().transactionAllowed(), journalPresent,
                validPending, issued, session.gateway().enrollmentBlockReason(), current);
        SessionGates.Reasons reasons = SessionGates.of(gates);

        systemConnectionPanel.setBusy(busy);
        if (!busy) {
            systemConnectionPanel.showBackendAccess();
        }
        boolean reconnect = session.gateway().needsReconnect();
        ReceiptPanel.CardMismatch mismatch = receiptPanel.pendingCardMismatch();
        identityPanel.show(uiReady ? current : null,
                uiReady && session.snapshotKnown() ? session.gateway().lastProfile() : null,
                uiReady && session.snapshotKnown() ? session.gateway().lastPhoto() : null,
                session.gateway().photoAbsent());

        btnConnect.setDisable(busy || uiConnected || reconnect);
        btnIdentify.setDisable(reasons.identify() != null);
        btnDisconnect.setDisable(busy || (!uiConnected && !reconnect));
        receiptPanel.applyLock(busy, reasons.readReceipt(), reasons.reconcile());
        transactionPanel.applyLock(busy, reasons.grant(), reasons.start(), reasons.end(), reasons.readReceipt());
        transactionPanel.showState(current, session.snapshotKnown());
        pinPanel.setEnabled(reasons.pin() == null, SessionGates.panelNote(reasons.pin()));
        personalizePanel.updateAvailability(reasons.personalize() == null, !busy && uiConnected,
                SessionGates.panelNote(reasons.personalize()));
        profilePanel.setEnabled(reasons.profile() == null, SessionGates.panelNote(reasons.profile()));
        photoPanel.setEnabled(reasons.photo() == null, SessionGates.panelNote(reasons.photo()));
        enrollmentPanel.setEnabled(reasons.enroll() == null, SessionGates.panelNote(reasons.enroll()));
        enrollmentPanel.onCard(current == null ? null : current.cardId());
        historyPanel.onCard(uiReady && current != null ? current.cardId() : null);
        historyPanel.setEnabled(reasons.history() == null, SessionGates.panelNote(reasons.history()));

        receiptPanel.showReconcile();
        actionNote.showSession(loaded, reconnect, mismatch == null ? null : mismatch.guidance(),
                session.gateway().reconciliation(), lastFailure, lastTone);

        int activeTab = mainTabs.getSelectionModel().getSelectedIndex();
        SessionGates.NextStep next = SessionGates.nextStep(closing, gates, session.gateway().enrollmentText(),
                inputFeedback.message() != null, activeTab);
        if (!busy && !journalPresent && session.gateway().reconciliation().transactionAllowed() && lastFailure != null
                && inputFeedback.message() == null) {
            next = new SessionGates.NextStep("xem hướng dẫn ở đầu cửa sổ", -1);
        }
        if (!busy && reconnect) {
            next = new SessionGates.NextStep("Ngắt kết nối rồi Kết nối lại; không thực hiện lại giao dịch", -1);
        } else if (!busy && mismatch != null) {
            next = new SessionGates.NextStep("đưa lại thẻ " + mismatch.expectedCardId() + " rồi kết nối lại", -1);
        }
        statusBar.setNextStep(next.text(), next.tab());

        statusBar.showFeedback(busy, busyLabel, loaded, session.gateway().reconciliation(),
                inputFeedback.message(), lastFeedback, lastTone, mismatch == null ? null : mismatch.expectedCardId());

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
        systemConnectionPanel.setBusy(true);
        identityPanel.show(null, null, null, false);
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
        CardSession.History history = session.loadHistory();
        loadedHistory = history.loaded();
        historyError = history.failure();
        historyReady = true;
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
        PendingJournal.Loaded loaded = session.pendingJournal();
        String journalState = !loaded.present() ? "khong-co"
                : !loaded.valid() ? "KHONG-HOP-LE"
                : loaded.status().name();
        CardStatus current = session.gateway().status();
        String cardState = current == null || !current.studentStateAvailable()
                ? "state-the-khong-kha-dung"
                : String.format("counter=%d balance=%d caMo=%s", current.counter(), current.balance(),
                        current.hasActiveVisit() ? current.activeVisitId() : "khong");
        log(String.format(
                "SIGN-08 %s | journal=%s | doiSoat=%s | the %s | grant=%s start=%s end=%s"
                        + " | nutDoiSoat=%s | nhan=%s",
                label, journalState, session.gateway().reconciliation().state(), cardState,
                transactionPanel.grantState(), transactionPanel.startState(),
                transactionPanel.endState(), receiptPanel.reconcileState(),
                statusBar.statusText()));
    }

    void logUi10Evidence(String label) {
        PendingJournal.Loaded loaded = session.pendingJournal();
        String journalState = !loaded.present() ? "khong-co"
                : !loaded.valid() ? "KHONG-HOP-LE (" + loaded.problem() + ")"
                : String.format("%s cardId=%s counter=%d operation=%s",
                        loaded.status(), loaded.entry().cardId(),
                        loaded.entry().expectedCounter(),
                        Receipt.operationText(loaded.entry().operation()));
        CardStatus current = session.gateway().status();
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
                label, journalState, session.gateway().reconciliation().state(), session.gateway().enrollmentText(),
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

    private boolean snapshotKnown() { return session.snapshotKnown(); }

    private boolean sessionOpen() { return !taskRunner.isClosing() && !taskRunner.isBusy(); }

    private void invalidateHistory() { historyReady = false; }

    private char[] studentPinInput() { return transactionPanel.studentPinInput(); }

    private void clearStudentPinInput() { transactionPanel.clearStudentPin(); }

    private void studentPinChanged() { receiptPanel.studentPinChanged(); }

    private void transactionFinished(boolean ok) {
        session.transactionFinished(ok);
        receiptPanel.showReceipt();
        publishHistory();
    }

    private void receiptFinished(boolean ok) {
        session.receiptFinished(ok);
    }

    private void reconcileFinished(boolean ok) {
        session.reconcileFinished(ok);
    }

    boolean isBusy() {
        return taskRunner.isBusy();
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
    }

    private void shutdown() {
        taskRunner.close(() -> {
            clearSessionInputs();
            log("Đóng cửa sổ — dừng demo và ngắt kết nối trên luồng nền");
            demo.stop();
            updateButtons();
            String pending = taskRunner.isBusy() ? "có thao tác đang chạy, sẽ xếp sau khi xong"
                    : "không có thao tác đang chạy";
            log("Đóng cửa sổ — " + pending);
        }, session::disconnect, outcome -> {
            log(outcome.failure() == null ? "[OK]   Đóng kết nối: " + outcome.value().text()
                    : "[ERROR] Lỗi khi đóng kết nối: " + outcome.failure());
            log("Đóng cửa sổ — thoát");
        }, () -> log("Đóng cửa sổ — quá 5s chưa dọn dẹp xong, cưỡng chế thoát"));
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
