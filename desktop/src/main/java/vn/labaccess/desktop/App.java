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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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

    private TextArea logArea;
    private Label statusLabel;
    private Label valueConnection;
    private TabPane mainTabs;

    /** Thứ tự tab cố định: kịch bản demo chọn tab theo chỉ số này. */
    static final int TAB_OPERATIONS = 0;
    static final int TAB_RECORDS = 1;
    static final int TAB_CARD = 2;

    private Label valueAid;
    private Label valueProtocol;
    private Label valueLifecycle;
    private Label valueCardId;
    private Label valueResult;
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
    private ToggleButton techToggle;
    private SplitPane workSplit;
    private TitledPane logPane;
    private ReceiptView receiptView;
    private String busyLabel;
    private String lastFeedback = "Chưa có thao tác";
    private UiLabels.Tone lastTone = UiLabels.Tone.NEUTRAL;
    private boolean logShowsFailure;

    /** Dưới bề ngang này nhật ký chuyển xuống dưới vùng nghiệp vụ. */
    private static final double NARROW_WIDTH = 1000;
    /** Tỉ lệ vùng nghiệp vụ trong SplitPane. */
    private static final double SPLIT_BUSINESS = 0.60;

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

        btnConnect = new Button("Kết nối", Icons.connect());
        btnIdentify = new Button("Nhận diện thẻ", Icons.identify());
        btnDisconnect = new Button("Ngắt kết nối", Icons.disconnect());
        btnReadReceipt = new Button("Đọc biên nhận cuối", Icons.receipt());
        btnGrant = new Button("Cấp quota");
        btnStart = new Button("Mở ca");
        btnEnd = new Button("Đóng ca");
        btnReconcile = new Button("Đối soát giao dịch", Icons.reconcile());
        for (Button action : new Button[] {btnConnect, btnIdentify, btnDisconnect,
                btnReadReceipt, btnGrant, btnStart, btnEnd, btnReconcile}) {
            action.getStyleClass().add("la-action");
        }
        // Nhãn nút dùng ngôn ngữ nghiệp vụ; mã APDU và điều kiện bật nút nằm trong tooltip.
        UiLabels.apduHint(btnConnect, "CONNECT");
        UiLabels.apduHint(btnIdentify, "SELECT + GET_STATUS\nCần kết nối thẻ trước");
        UiLabels.apduHint(btnDisconnect, "DISCONNECT");
        UiLabels.apduHint(btnReadReceipt,
                "VERIFY_STUDENT_PIN + GET_LAST_RECEIPT\nCần nhận diện thẻ và không có biên nhận chờ đối soát");
        UiLabels.apduHint(btnGrant,
                "GRANT_QUOTA\nCần nhận diện thẻ, không có biên nhận chờ đối soát");
        UiLabels.apduHint(btnStart,
                "START_VISIT\nCần nhận diện thẻ, chưa mở ca, số dư lớn hơn 0");
        UiLabels.apduHint(btnEnd, "END_VISIT\nCần nhận diện thẻ và đang có ca mở");
        UiLabels.apduHint(btnReconcile, "RECONCILE_PENDING\nCần có biên nhận chờ đối soát");
        techToggle = new ToggleButton("Chi tiết kỹ thuật");
        techToggle.getStyleClass().add("la-technical-toggle");

        fieldVisitId = new TextField();
        fieldVisitId.setPrefColumnCount(34);
        fieldVisitId.setPromptText("32 ký tự hex của ca đang mở");
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

        FlowPane receiptControls = UiRows.wrapping(
                UiRows.field("Visit ID đang mở", fieldVisitId), btnReadReceipt);

        FlowPane txnControls = UiRows.wrapping(
                UiRows.field("PIN học viên", fieldStudentPin),
                UiRows.field("PIN quản trị", fieldAdminPin),
                UiRows.field("Số lượt thay đổi", fieldDelta),
                btnGrant, btnStart, btnEnd);

        valueConnection = UiLabels.chip();
        valueAid = UiLabels.value();
        valueProtocol = UiLabels.value();
        valueLifecycle = UiLabels.chip();
        valueCardId = UiLabels.value();
        valueCardId.getStyleClass().add("la-card-id");
        valueResult = UiLabels.chip();
        valueReconcile = UiLabels.chip();

        // Header: trạng thái phiên lâu dài. Chip chỉ dành cho trạng thái, Card ID là giá trị nổi bật.
        FlowPane summary = UiRows.wrapping(
                UiRows.pair(UiLabels.caption("Kết nối"), valueConnection),
                UiRows.pair(UiLabels.caption("Mã thẻ"), valueCardId),
                UiRows.pair(UiLabels.caption("Vòng đời"), valueLifecycle),
                UiRows.pair(UiLabels.caption("Nhận diện"), valueResult),
                UiRows.pair(UiLabels.caption("Đối soát"), valueReconcile));

        FlowPane sessionActions = UiRows.wrapping(btnConnect, btnIdentify, btnDisconnect,
                btnReconcile, techToggle);

        // Chi tiết kỹ thuật tách khỏi trạng thái nghiệp vụ; mặc định ẩn để không chiếm chỗ.
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

        VBox header = new VBox(UiScale.SPACE_2, summary, sessionActions, technical);
        header.getStyleClass().add("la-header");
        header.setPadding(UiScale.headerInsets());

        // Vận hành: cấp quota, mở ca, đóng ca, đọc biên nhận.
        receiptView = new ReceiptView();
        Tab tabOperations = tab("Vận hành",
                UiSection.of("Giao dịch thẻ", txnControls),
                UiSection.of("Biên nhận", receiptControls, receiptView.node()));

        Tab tabRecords = tab("Hồ sơ & ảnh", profilePanel.node(), photoPanel.node());

        Tab tabCard = tab("Cấp thẻ & bảo mật", personalizePanel.node(), pinPanel.node());

        mainTabs = new TabPane(tabOperations, tabRecords, tabCard);
        mainTabs.getStyleClass().add("la-tabs");
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.getSelectionModel().selectFirst();
        mainTabs.setMinHeight(0);
        VBox.setVgrow(mainTabs, Priority.ALWAYS);

        statusLabel = new Label("Chưa kết nối");
        statusLabel.setId("status");
        statusLabel.getStyleClass().add("la-status");
        HBox statusBar = new HBox(statusLabel);
        statusBar.getStyleClass().add("la-status-bar");
        statusBar.setPadding(UiScale.statusBarInsets());

        logArea = new TextArea();
        logArea.setEditable(false);
        // Cột log hẹp: xuống dòng để đọc trọn dòng APDU thay vì cuộn ngang trong ô log.
        logArea.setWrapText(true);
        logArea.getStyleClass().add("la-log");
        TitledPane logPane = new TitledPane("Nhật ký thao tác", logArea);
        logPane.setExpanded(true);
        logPane.setMinWidth(200);
        // TitledPane mặc định co theo nội dung; mở maxHeight để nó giãn hết cột phải.
        logPane.setMaxHeight(Double.MAX_VALUE);
        logPane.getStyleClass().add("la-log-pane");
        this.logPane = logPane;

        // Nghiệp vụ bên trái, nhật ký bên phải: thao tác và quan sát log cùng lúc.
        VBox business = new VBox(mainTabs);
        business.setMinHeight(0);
        business.setPadding(UiScale.centerInsets());

        VBox logColumn = new VBox(logPane);
        logColumn.setMinWidth(200);
        logColumn.setPadding(UiScale.centerInsets());
        // Nhật ký giãn hết cột phải: không để khoảng trống cố định dưới khung log.
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
        // Hẹp thì nhật ký xuống dưới để vùng nghiệp vụ giữ đủ bề ngang, không sinh cuộn ngang.
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
        updateButtons();

        if (Boolean.getBoolean("labaccess.demo")) {
            stage.setAlwaysOnTop(true);
            demo.start();
        }
    }

    /** Một tab: nội dung cuộn dọc, không bao giờ cuộn ngang để control không bị cắt. */
    private static Tab tab(String title, Node... content) {
        VBox body = new VBox(UiScale.SPACE_4, content);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("la-scroll");
        // Cho phép co lại: cửa sổ thấp thì tab cuộn, không đè lên nhật ký.
        scroll.setMinHeight(0);
        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
    }

    /** Kịch bản demo chọn tab trước mỗi bước; panel không tự biết tab (Q-34). */
    void showTab(int index) {
        if (mainTabs != null) {
            mainTabs.getSelectionModel().select(index);
        }
    }

    /**
     * Bố cục theo bề ngang cửa sổ: đủ rộng thì nghiệp vụ trái — nhật ký phải,
     * hẹp thì nhật ký xuống dưới. Tỉ lệ 70/30 ở cả hai chiều.
     */
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
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, null);
            log("[FAIL] đọc biên nhận: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldStudentPin.clear();
        runOp("VERIFY + GET_LAST_RECEIPT + GET_STATUS state", () -> {
            try {
                gateway.clearAuthenticatedStatus();
                CardGateway.Result keyReady = gateway.prepareReceiptVerification();
                if (!keyReady.ok()) {
                    return CardGateway.Result.fail("public key: " + keyReady.text());
                }
                CardGateway.Result verify = gateway.verifyStudentPin(studentPin);
                if (!verify.ok()) {
                    return CardGateway.Result.fail("VERIFY: " + verify.text());
                }
                CardGateway.Result read = gateway.readLastReceipt(null);
                String receiptEvidence;
                if (!read.ok() && gateway.lastResponseSw() == Sw.CONDITIONS_NOT_SATISFIED) {
                    receiptEvidence = "thẻ ISSUED chưa có receipt";
                } else if (!read.ok()) {
                    return read;
                } else {
                    receiptEvidence = read.text();
                }
                CardGateway.Result state = gateway.readAuthenticatedStatus(studentPin);
                if (!state.ok()) {
                    return CardGateway.Result.fail(receiptEvidence
                            + " | không nạp được state ca: " + state.text());
                }
                return CardGateway.Result.ok(receiptEvidence + " | " + state.text());
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            CardGateway.Status current = gateway.status();
            stateSnapshotKnown = ok && current != null && current.studentStateAvailable();
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
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, null);
            log("[FAIL] đối soát giao dịch: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldStudentPin.clear();
        runOp("RECONCILE_PENDING", () -> {
            try {
                CardGateway.Result reconciled = gateway.reconcilePending(journal, studentPin);
                if (!reconciled.ok()) {
                    return reconciled;
                }
                CardGateway.Result refreshed = gateway.readAuthenticatedStatus(studentPin);
                return refreshed.ok()
                        ? CardGateway.Result.ok(reconciled.text() + " | " + refreshed.text())
                        : CardGateway.Result.fail(reconciled.text()
                                + " | state ca chưa được làm mới: " + refreshed.text());
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            CardGateway.Status refreshed = gateway.status();
            stateSnapshotKnown = ok && !journal.load().present()
                    && gateway.reconciliation().transactionAllowed()
                    && refreshed != null && refreshed.studentStateAvailable();
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
        if (status == null || !status.studentStateAvailable()) {
            log("[FAIL] GRANT_QUOTA: chưa có snapshot state student đã xác thực");
            return;
        }
        long counter = status.counter();
        if (counter >= Receipt.COUNTER_MASK) {
            log("[FAIL] GRANT_QUOTA: counter đã đạt giới hạn");
            return;
        }
        int expectedBalance = status.balance() + delta;
        if (expectedBalance > Receipt.MAX_BALANCE) {
            log("[FAIL] GRANT_QUOTA: balance dự kiến vượt 9999");
            return;
        }
        byte[] adminPin = pinToBytes(fieldAdminPin);
        if (adminPin == null) {
            log("[FAIL] cấp quota: PIN quản trị phải đúng 8 chữ số");
            return;
        }
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, adminPin);
            log("[FAIL] cấp quota: PIN học viên phải đúng 6 chữ số");
            return;
        }
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
        if (!stateSnapshotKnown) {
            log("[FAIL] START_VISIT: đọc state hiện tại bằng VERIFY + GET_STATUS trước");
            return;
        }
        if (status == null || !status.studentStateAvailable()) {
            log("[FAIL] START_VISIT: chưa có snapshot state student đã xác thực");
            return;
        }
        if (status.hasActiveVisit()) {
            log("[FAIL] START_VISIT: đã có ca đang mở");
            return;
        }
        if (status.balance() <= 0) {
            log("[FAIL] START_VISIT: balance hiện tại không còn lượt");
            return;
        }
        if (status.counter() >= Receipt.COUNTER_MASK) {
            log("[FAIL] START_VISIT: counter đã đạt giới hạn");
            return;
        }
        byte[] visitId = newVisitId();
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, null);
            log("[FAIL] mở ca: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldStudentPin.clear();
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), status.counter() + 1,
                Receipt.OPERATION_START, visitId);
        runTransaction("START_VISIT", studentPin, null, expectation, -1,
                status.balance() - 1, gateway.visitCommand((byte) 0x51, visitId));
    }

    private void doEnd() {
        CardGateway.Status status = gateway.status();
        if (!stateSnapshotKnown) {
            log("[FAIL] END_VISIT: đọc state hiện tại bằng VERIFY + GET_STATUS trước");
            return;
        }
        if (status == null || !status.studentStateAvailable()) {
            log("[FAIL] END_VISIT: chưa có snapshot state student đã xác thực");
            return;
        }
        if (!status.hasActiveVisit()) {
            log("[FAIL] END_VISIT: thẻ không có ca đang mở");
            return;
        }
        if (status.counter() >= Receipt.COUNTER_MASK) {
            log("[FAIL] END_VISIT: counter đã đạt giới hạn");
            return;
        }
        byte[] visitId = HexFormat.of().parseHex(status.activeVisitId());
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(status.cardId()), status.counter() + 1,
                Receipt.OPERATION_END, visitId);
        char[] studentPin = fieldStudentPin.getText().toCharArray();
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, null);
            log("[FAIL] đóng ca: PIN học viên phải đúng 6 chữ số");
            return;
        }
        fieldStudentPin.clear();
        runTransaction("END_VISIT", studentPin, null, expectation, 0,
                status.balance(), gateway.visitCommand((byte) 0x52, visitId));
    }

    private void runTransaction(String label, char[] studentPin, byte[] adminPin,
            ReceiptExpectation expectation, int delta, int expectedBalance,
            CardGateway.CommandSupplier supplier) {
        if (!validPin(studentPin, 6)) {
            clearBuffers(studentPin, adminPin);
            log("[FAIL] " + UiLabels.actionName(label) + ": PIN học viên phải đúng 6 chữ số");
            return;
        }
        if (closing || busy) {
            clearBuffers(studentPin, adminPin);
            log("[FAIL] " + label + ": đang xử lý thao tác khác hoặc đang đóng");
            return;
        }
        runOp(label, () -> {
            try {
                gateway.clearAuthenticatedStatus();
                CardGateway.Result verify = gateway.verifyStudentPin(studentPin);
                if (!verify.ok()) {
                    return CardGateway.Result.fail(label + " không chạy — VERIFY: " + verify.text());
                }
                CardGateway.Result transaction = gateway.runStateTransaction(journal,
                        new CardGateway.TransactionPlan(expectation, delta, expectedBalance),
                        supplier, studentPin);
                if (!transaction.ok()) {
                    return transaction;
                }
                CardGateway.Result refreshed = gateway.readAuthenticatedStatus(studentPin);
                if (!refreshed.ok()) {
                    gateway.clearAuthenticatedStatus();
                    return CardGateway.Result.fail(transaction.text()
                            + " | state ca chưa được làm mới: " + refreshed.text());
                }
                return CardGateway.Result.ok(transaction.text() + " | " + refreshed.text());
            } finally {
                clearBuffers(studentPin, adminPin);
            }
        }, ok -> {
            CardGateway.Status refreshed = gateway.status();
            stateSnapshotKnown = ok && refreshed != null && refreshed.studentStateAvailable();
            if (!stateSnapshotKnown) {
                gateway.clearAuthenticatedStatus();
            }
            showReceipt();
        });
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
        CardGateway.Status status = gateway.status();
        boolean authenticated = status != null && status.studentStateAvailable();
        if (receipt == null) {
            receiptView.clear(authenticated ? "Chưa có biên nhận" : "Chưa đọc biên nhận từ thẻ");
        } else {
            receiptView.show(receipt, gateway.lastReceiptSignatureChecked(),
                    gateway.lastReceiptSignatureOk());
        }
        fieldVisitId.setText(authenticated && status.hasActiveVisit()
                ? status.activeVisitId() : "");
        showReconcile();
        if (!gateway.reconciliation().detail().isEmpty()) {
            log("Đối soát: " + gateway.reconciliation().detail());
        }
    }

    private void showStatus() {
        CardGateway.Status status = gateway.status();
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
        valueLifecycle.setText(CardGateway.lifecycleCaption(status.lifecycle()));
        UiLabels.applyChip(valueLifecycle, status.lifecycle() == 0x01
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
        fieldVisitId.clear();
        receiptView.clear("Chưa đọc biên nhận từ thẻ");
        personalizePanel.resetView();
        pinPanel.clearState();
        profilePanel.clearView();
        photoPanel.resetView();
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
        busyLabel = label;
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
                busyLabel = null;
                lastFeedback = UiLabels.outcome(okFinal, label);
                lastTone = okFinal ? UiLabels.Tone.OK : UiLabels.Tone.ERROR;
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
            statusLabel.setText("Đang đóng cửa sổ");
            UiLabels.applyTone(statusLabel, UiLabels.Tone.PENDING);
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
        CardGateway.Status currentStatus = gateway.status();
        boolean authenticatedState = currentStatus != null && currentStatus.studentStateAvailable();
        btnGrant.setDisable(!txnAllowed || !authenticatedState);
        btnStart.setDisable(!txnAllowed || !authenticatedState || currentStatus.hasActiveVisit()
                || currentStatus.balance() <= 0);
        btnEnd.setDisable(!txnAllowed || !authenticatedState || !currentStatus.hasActiveVisit());
        btnReconcile.setDisable(busy || !uiReady || !validPending);
        pinPanel.setEnabled(!busy && uiConnected);
        personalizePanel.updateAvailability(
                !busy && uiConnected && !journalPresent && !issued,
                !busy && uiConnected);
        profilePanel.setEnabled(!busy && uiConnected && issued);
        photoPanel.setEnabled(!busy && uiConnected && issued);

        // Thanh trạng thái: phản hồi thao tác cuối, không lặp lại trạng thái phiên ở header.
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
        boolean canEnd = operations && !btnEnd.isDisable();
        boolean canStart = operations && !btnStart.isDisable();
        UiLabels.markPrimary(btnEnd, canEnd);
        UiLabels.markPrimary(btnStart, !canEnd && canStart);
        UiLabels.markPrimary(btnGrant, operations && !canEnd && !canStart && !btnGrant.isDisable());
        personalizePanel.setIssuePrimary(uiReady && tab == TAB_CARD);
        photoPanel.setUploadPrimary(uiReady && tab == TAB_RECORDS);
    }

    void fillTransactionInputs(char[] studentPin, char[] adminPin, int delta) {
        fieldStudentPin.setText(new String(studentPin));
        fieldAdminPin.setText(adminPin == null ? "" : new String(adminPin));
        fieldDelta.setText(Integer.toString(delta));
    }

    void logTransactionState(String label) {
        CardGateway.Status current = gateway.status();
        if (current == null || !current.studentStateAvailable()) {
            log("UI-06 " + label + " | state unavailable | displayedVisitId="
                    + fieldVisitId.getText());
            return;
        }
        log(String.format("UI-06 %s | balance=%d counter=%d active=%s activeVisitId=%s"
                        + " displayedVisitId=%s receipt=%s",
                label, current.balance(), current.counter(), current.hasActiveVisit(),
                current.activeVisitId(), fieldVisitId.getText(),
                gateway.lastReceipt() == null ? "none"
                        : Receipt.operationText(gateway.lastReceipt().operation())));
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

    boolean pressStart() {
        return press(btnStart);
    }

    boolean pressEnd() {
        return press(btnEnd);
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

    private void log(String message) {
        System.out.println("[" + LocalTime.now().format(TS) + "] " + message);
        if (logArea != null) {
            logArea.appendText("[" + LocalTime.now().format(TS) + "] " + message
                    + System.lineSeparator());
            logArea.setScrollTop(Double.MAX_VALUE);
        }
        boolean failure = message.startsWith("[FAIL]") || message.startsWith("[ERROR]");
        boolean success = message.startsWith("[OK]");
        if (!failure && !success) {
            return;
        }
        logShowsFailure = failure;
        if (failure) {
            int end = message.indexOf(']');
            String detail = end >= 0 ? message.substring(end + 1).strip() : message;
            lastFeedback = detail;
            lastTone = UiLabels.Tone.ERROR;
        }
        if (logPane != null) {
            boolean narrow = workSplit != null && workSplit.getOrientation() == Orientation.VERTICAL;
            logPane.setExpanded(failure || !narrow);
        }
        if (failure && !busy && statusLabel != null) {
            updateButtons();
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

    /** Cửa sổ vừa màn hình đang dùng; màn hình nhỏ hoặc scaling cao thì thu lại, không tràn ra ngoài. */
    private static double windowWidth() {
        return Math.min(1180, Screen.getPrimary().getVisualBounds().getWidth() - UiScale.SPACE_5 * 2);
    }

    private static double windowHeight() {
        return Math.min(760, Screen.getPrimary().getVisualBounds().getHeight() - UiScale.SPACE_5 * 2);
    }
}
