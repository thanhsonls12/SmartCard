package vn.labaccess.desktop;

import static vn.labaccess.desktop.UiSection.tab;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class StaffWorkspace {

    private static final int OPERATIONS = 0;
    private static final int ISSUANCE = 1;
    private static final int ENROLLMENT = 2;
    private static final int PHOTOS = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String CLOSING = "Đang đóng cửa sổ Staff.";
    private final CardSession session;
    private final CardTaskRunner tasks;
    private final CardOpRunner cardOps;
    private final GrantPanel grants;
    private final StudentPinUnblockPanel unblock;
    private final PersonalizePanel personalize;
    private final EnrollmentPanel enrollment;
    private final PhotoUploadPanel photos;
    private final ReceiptPanel receipts;
    private final HistoryPanel history;
    private final SystemConnectionPanel system;
    private final Button connect = new Button("Kết nối", Icons.connect());
    private final Button identify = new Button("Nhận diện thẻ", Icons.identify());
    private final Button disconnect = new Button("Ngắt kết nối", Icons.disconnect());
    private final ToggleButton technical = new ToggleButton("Chi tiết kỹ thuật");
    private final ToggleButton logToggle = new ToggleButton("Nhật ký");
    private final Label connection = UiLabels.chip();
    private final Label cardId = UiLabels.value();
    private final Label lifecycle = UiLabels.chip();
    private final Label quota = UiLabels.value();
    private final Label stateOrigin = UiLabels.chip();
    private final Label feedback = UiLabels.caption("Chưa có thao tác");
    private final Label guidance = UiLabels.caption("Kết nối thẻ để bắt đầu.");
    private final TextArea logArea = new TextArea();
    private final TabPane tabs;
    private final BorderPane root = new BorderPane();
    private final ScrollPane viewport = UiSection.scroll(root);
    private String shownCard;
    private String awaitingReadCard;
    private CardSession.History stagedHistory;
    private boolean historyReady;
    private boolean logStopped;

    StaffWorkspace(CardSession session, CardTaskRunner tasks, CardOpRunner cardOps, EnrollmentRunner backendOps) {
        this.session = session;
        this.tasks = tasks;
        this.cardOps = cardOps;
        CardGateway gateway = session.gateway();
        grants = new GrantPanel(cardOps, this::log, new GrantPanel.Actions(this::open, gateway::status,
                session::grant, this::invalidateHistory, this::rememberHistory, this::grantFinished,
                () -> { }, () -> showGrantEffects()));
        unblock = new StudentPinUnblockPanel(gateway, cardOps, this::log, outcome -> refresh());
        photos = new PhotoUploadPanel(gateway, cardOps, this::log);
        enrollment = new EnrollmentPanel(gateway, backendOps, session.backend(), this::log, this::enrollmentFinished);
        personalize = new PersonalizePanel(gateway, cardOps, this::log, (ok, issued) -> {
            session.invalidateSnapshot();
            if (ok && issued != null) {
                photos.onCard(issued.cardId());
                enrollment.prefill(issued.cardId(), issued.studentId());
            }
        });
        receipts = new ReceiptPanel(gateway, cardOps, this::log, new ReceiptPanel.StaffSession(
                () -> open() && session.recoveryProblem() == null, session::pendingJournal, session.journalFile(),
                new ReceiptPanel.AdminRecovery(() -> {
                    tabsSelect(OPERATIONS);
                    return grants.requirePin();
                }, grants::takePin, grants::clearPin, session::reconcileAdminGrant,
                        session::confirmSuperseded, session::inspectManualReconcile,
                        session::confirmManualReconcile),
                this::invalidateHistory, this::rememberHistory, this::publishHistory, this::refresh,
                ok -> {
                    session.reconcileFinished(ok);
                    showGrantEffects();
                }));
        history = new HistoryPanel(session.backend(), backendOps::run,
                () -> gateway.isReady() && gateway.status() != null ? gateway.status().cardId() : "", this::log);
        system = new SystemConnectionPanel(session.backend(), gateway, backendOps, (ok, message) -> {
            showFeedback(message, ok ? UiLabels.Tone.OK : UiLabels.Tone.ERROR);
            log((ok ? "[OK] " : "[FAIL] ") + message);
            refresh();
        });
        tabs = new TabPane(
                tab("Vận hành Staff", new UiColumns(UiScale.WORKSPACE_COLUMNS_WIDTH, 64,
                        UiSection.of("Cấp lượt", grants.actionsNode(), grants.previewNode()), receipts.node()),
                        history.node()),
                tab("Phát hành & mở khóa", personalize.node(),
                        UiSection.of("Mở khóa PIN học viên", unblock.node(), unblock.feedbackNode())),
                tab("Đăng ký thẻ", enrollment.node()),
                tab("Ảnh", photos.node()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(260);
        viewport.setFitToHeight(true);
        root.setMinWidth(0);
        root.setTop(header());
        root.setCenter(tabs);
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("la-log");
        TitledPane logPane = new TitledPane("Nhật ký thao tác Staff", logArea);
        logPane.getStyleClass().add("la-log-pane");
        logPane.setExpanded(false);
        logPane.setMaxHeight(220);
        logPane.setManaged(false);
        logPane.setVisible(false);
        logToggle.selectedProperty().addListener((observable, was, now) -> {
            logPane.setManaged(now);
            logPane.setVisible(now);
            logPane.setExpanded(now);
        });
        root.setBottom(logPane);
        connect.setOnAction(event -> connect());
        identify.setOnAction(event -> identify());
        disconnect.setOnAction(event -> disconnect());
        tabs.getSelectionModel().selectedIndexProperty().addListener((observable, was, now) -> refresh());
    }

    Parent node() {
        return viewport;
    }

    private VBox header() {
        Label brand = new Label("LabAccess · Nhân viên");
        brand.getStyleClass().add("la-brand");
        FlowPane summary = UiRows.wrapping(brand, UiRows.pair(UiLabels.caption("Kết nối"), connection),
                UiRows.pair(UiLabels.caption("Mã thẻ"), UiRows.pair(cardId, UiLabels.copyButton(cardId, "mã thẻ"))),
                UiRows.pair(UiLabels.caption("Vòng đời"), lifecycle),
                UiRows.pair(UiLabels.caption("Số lượt"), quota), stateOrigin,
                UiRows.pair(UiLabels.caption("Đối soát"), receipts.reconcileStatus()), system.statusNode());
        for (Button button : new Button[] {connect, identify, disconnect}) {
            button.getStyleClass().add("la-action");
        }
        FlowPane actions = UiRows.wrapping(connect, identify, disconnect, receipts.reconcileAction(),
                system.actionNode(), technical, logToggle);
        GridPane details = new GridPane();
        details.setHgap(UiScale.SPACE_3);
        details.setVgap(UiScale.SPACE_1);
        String[][] values = {{"Backend", session.backend().baseUrl()},
                {"Journal Staff", session.journalFile().toString()},
                {"PID", Long.toString(ProcessHandle.current().pid())}};
        for (int index = 0; index < values.length; index++) {
            Label value = UiLabels.caption(values[index][1]);
            value.setWrapText(true);
            value.setMinWidth(0);
            GridPane.setHgrow(value, Priority.ALWAYS);
            details.addRow(index, UiLabels.caption(values[index][0]), value);
        }
        details.setManaged(false);
        details.setVisible(false);
        technical.selectedProperty().addListener((observable, was, now) -> {
            details.setManaged(now);
            details.setVisible(now);
            personalize.showTechnical(now);
            enrollment.showTechnical(now);
            photos.showTechnical(now);
        });
        feedback.setWrapText(true);
        guidance.setWrapText(true);
        VBox header = new VBox(UiScale.SPACE_3, summary, actions, feedback, guidance, details);
        header.getStyleClass().add("la-header");
        header.setPadding(UiScale.headerInsets());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return header;
    }

    void initialize() {
        CardSession.StartupJournal startup = session.blockOnStartupJournal();
        log("Staff PID=" + ProcessHandle.current().pid());
        log(startup.message());
        refresh();
    }

    private boolean open() {
        return !tasks.isBusy() && !tasks.isClosing();
    }

    private void tabsSelect(int index) {
        tabs.getSelectionModel().select(index);
    }

    private void connect() {
        clearInputs();
        cardOps.run("CONNECT_AND_IDENTIFY", session::connect, ok -> {
            session.invalidateSnapshot();
            resetViews();
            if (ok && session.gateway().isReady()) {
                synchronizeConnection();
                Platform.runLater(this::offerPendingReconciliation);
            }
        });
    }

    private void identify() {
        clearInputs();
        session.invalidateSnapshot();
        resetViews();
        cardOps.run("SELECT + GET_STATUS", session::identify, ok -> {
            session.invalidateSnapshot();
            if (ok) {
                synchronizeConnection();
                Platform.runLater(this::offerPendingReconciliation);
            }
        });
    }

    private void offerPendingReconciliation() {
        if (session.recoveryProblem() == null) {
            receipts.offerPendingReconciliation();
        }
    }

    private void disconnect() {
        clearInputs();
        cardOps.run("DISCONNECT", session::disconnect, ok -> {
            session.invalidateSnapshot();
            resetViews();
        });
    }

    void synchronizeConnection() {
        session.synchronizeConnection();
        CardStatus status = session.gateway().isReady() ? session.gateway().status() : null;
        String current = status == null ? null : status.cardId();
        if (!java.util.Objects.equals(current, shownCard)) {
            if (shownCard != null) {
                clearInputs();
                personalize.resetView();
                receipts.clear();
                invalidateHistory();
            }
            shownCard = current;
            if (current != null) {
                awaitingReadCard = null;
            }
            photos.onCard(current);
            history.onCard(current);
            enrollment.onCard(current);
            unblock.resetFeedback();
        }
    }

    private void resetViews() {
        shownCard = null;
        awaitingReadCard = null;
        invalidateHistory();
        photos.resetView();
        history.onCard(null);
        enrollment.resetView();
        personalize.resetView();
        unblock.resetFeedback();
        receipts.clear();
    }

    private void clearInputs() {
        grants.clearInputs();
        unblock.clearInputs();
        photos.clearInputs();
        personalize.clearInputs();
        enrollment.clearInputs();
    }

    void taskStarted(String label) {
        showFeedback("Đang xử lý: " + UiLabels.actionName(label), UiLabels.Tone.PENDING);
        log("> " + label + " ...");
        refresh();
    }

    void cardCompleted(String label, CardTaskRunner.Outcome<CardOperationResult> outcome) {
        if (outcome.failure() != null) {
            showFeedback(UiLabels.failureMessage(label, "", outcome.failure()), UiLabels.Tone.ERROR);
        } else {
            CardOperationResult result = outcome.value();
            boolean confirmation = result.superseded() != null;
            showFeedback(confirmation ? "Chờ nhân viên xác nhận." : result.warning() != null
                    ? UiLabels.completedAction(label) + ". " + result.warning()
                    : result.ok() ? UiLabels.outcome(true, label) : "GRANT_QUOTA".equals(label)
                            ? result.text() : UiLabels.failureMessage(label, result.text(), null),
                    result.warning() != null || confirmation ? UiLabels.Tone.PENDING
                            : result.ok() ? UiLabels.Tone.OK : UiLabels.Tone.ERROR);
        }
        refresh();
    }

    private void showFeedback(String text, UiLabels.Tone tone) {
        feedback.setText(text);
        UiLabels.applyNoteTone(feedback, tone);
    }

    private void grantFinished(GrantPanel.Completion completion) {
        session.transactionFinished(completion.ok());
        if (completion.ok()) {
            awaitingReadCard = completion.awaitingReadCardId();
        }
        receipts.showReceipt();
        publishHistory();
    }

    private void enrollmentFinished(EnrollmentPanel.OutcomeKind kind, String message) {
        showFeedback(message, kind == EnrollmentPanel.OutcomeKind.SUCCESS ? UiLabels.Tone.OK
                : kind == EnrollmentPanel.OutcomeKind.UNKNOWN ? UiLabels.Tone.PENDING : UiLabels.Tone.ERROR);
        if (kind == EnrollmentPanel.OutcomeKind.SUCCESS) {
            session.gateway().applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true,
                    "đăng ký thẻ vừa hoàn tất trên Backend"));
        }
        refresh();
    }

    private void invalidateHistory() {
        historyReady = false;
    }

    private void rememberHistory() {
        historyReady = false;
        stagedHistory = session.loadHistory();
        historyReady = true;
    }

    private void publishHistory() {
        if (!historyReady) {
            return;
        }
        historyReady = false;
        if (stagedHistory.failure() != null) {
            history.showFailure(stagedHistory.failure());
        } else {
            history.show(stagedHistory.loaded());
        }
    }

    private void showGrantEffects() {
        PendingJournal.Loaded loaded = session.pendingJournal();
        grants.showEffects(session.gateway().status(), session.snapshotKnown()
                && !loaded.present() && session.gateway().reconciliation().transactionAllowed());
    }

    void refresh() {
        if (tasks.isClosing()) {
            lockForClosing();
            return;
        }
        CardGateway gateway = session.gateway();
        PendingJournal.Loaded loaded = session.pendingJournal();
        session.blockUnusableJournal(loaded);
        boolean busy = tasks.isBusy();
        boolean ready = gateway.isReady();
        CardStatus status = ready ? gateway.status() : null;
        boolean issued = status != null && status.lifecycle() == CardLifecycle.ISSUED;
        String base = busy ? "đang xử lý thao tác khác" : gateway.needsReconnect() ? "cần ngắt và kết nối lại thẻ"
                : !gateway.isConnected() ? "chưa kết nối thẻ" : !ready ? "chưa nhận diện thẻ" : null;
        String legacyProblem = session.newTransactionProblem();
        String recoveryProblem = session.recoveryProblem();
        String journalWhy = legacyProblem != null ? legacyProblem : !loaded.present() ? null : !loaded.valid()
                || loaded.status() != PendingJournal.Status.PENDING ? "nhật ký cần xử lý thủ công"
                : "có giao dịch đang chờ đối soát";
        String mutation = base != null ? base : journalWhy != null ? journalWhy
                : !issued ? "thẻ chưa phát hành" : !gateway.reconciliation().transactionAllowed()
                ? "cần đối soát giao dịch" : null;
        String grantWhy = mutation != null ? mutation : gateway.enrollmentBlockReason();
        grants.applyLock(busy, grantWhy);
        unblock.setEnabled(mutation == null);
        photos.setEnabled(mutation == null, mutation);
        String issuance = base != null ? base : journalWhy != null ? journalWhy
                : issued ? "thẻ đã phát hành — không phát hành lại" : null;
        personalize.updateAvailability(issuance == null, !busy && gateway.isConnected(), issuance);
        String enrollWhy = base != null ? base : journalWhy != null ? journalWhy
                : !issued ? "thẻ chưa phát hành" : null;
        enrollment.setEnabled(enrollWhy == null, enrollWhy);
        enrollment.onCard(status == null ? null : status.cardId());
        String historyWhy = base != null ? base : !issued ? "thẻ chưa phát hành" : gateway.enrollmentBlockReason();
        history.onCard(status == null ? null : status.cardId());
        history.setEnabled(historyWhy == null, historyWhy);
        String reconcileWhy = base != null ? base : !issued ? "thẻ chưa phát hành" : receipts.pendingOperationReason();
        if (loaded.present() && (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING)) {
            reconcileWhy = busy ? "đang xử lý thao tác khác" : null;
        }
        receipts.applyLock(busy, "Staff đối soát bằng PIN quản trị", reconcileWhy);
        receipts.showReconcile();
        connect.setDisable(busy || gateway.isConnected() || gateway.needsReconnect());
        identify.setDisable(busy || !gateway.isConnected() || gateway.needsReconnect());
        disconnect.setDisable(busy || (!gateway.isConnected() && !gateway.needsReconnect()));
        system.setBusy(busy);
        if (!busy) {
            system.showBackendAccess();
        }
        connection.setText(gateway.needsReconnect() ? "Cần kết nối lại"
                : ready ? "Đã nhận diện" : gateway.isConnected() ? "Đã kết nối" : "Chưa kết nối");
        UiLabels.applyChip(connection, ready ? UiLabels.Tone.OK : UiLabels.Tone.NEUTRAL);
        cardId.setText(status == null ? UiLabels.EMPTY_VALUE : status.cardId());
        lifecycle.setText(status == null ? "Chưa rõ" : CardLifecycle.caption(status.lifecycle()));
        boolean confirmed = ready && session.snapshotKnown() && !loaded.present()
                && gateway.reconciliation().transactionAllowed() && status.stateAvailable();
        boolean unresolved = loaded.present() || legacyProblem != null
                || gateway.reconciliation().state() == Reconciliation.State.UNKNOWN;
        stateOrigin.setText(CardDisplayState.of(status, confirmed, unresolved,
                awaitingReadCard != null || gateway.reconciliation().receiptUnverified(), busy));
        quota.setText(unresolved || busy ? "Chưa rõ kết quả"
                : confirmed ? status.balance() + " lượt · " + status.stateSource()
                : awaitingReadCard != null ? "Đã cấp — cần đọc lại" : "Chưa xác nhận");
        showGrantEffects();
        boolean primary = tabs.getSelectionModel().getSelectedIndex() == OPERATIONS;
        grants.markPrimary(primary);
        personalize.setIssuePrimary(tabs.getSelectionModel().getSelectedIndex() == ISSUANCE && issuance == null);
        photos.setUploadPrimary(tabs.getSelectionModel().getSelectedIndex() == PHOTOS && mutation == null);
        String next = recoveryProblem != null ? recoveryProblem
                : loaded.present() ? receipts.pendingOperationReason() != null ? receipts.pendingOperationReason()
                : loaded.valid() && loaded.status() == PendingJournal.Status.PENDING
                ? "Đối soát journal trước; không thực hiện lại giao dịch." : loaded.recoverySummary()
                : base != null ? base : !issued ? "Phát hành thẻ tại Phát hành & mở khóa."
                : gateway.enrollmentBlockReason() != null ? "Đăng ký thẻ hoặc kiểm tra hệ thống."
                : "Cấp lượt, mở khóa hoặc ghi ảnh bằng PIN quản trị. Không có thao tác vào/ra ca ở Staff.";
        guidance.setText(next);
    }

    void closeRequested() {
        clearInputs();
        lockForClosing();
    }

    private void lockForClosing() {
        connect.setDisable(true);
        identify.setDisable(true);
        disconnect.setDisable(true);
        grants.applyLock(true, CLOSING);
        unblock.setEnabled(false);
        photos.setEnabled(false, CLOSING);
        personalize.updateAvailability(false, false, CLOSING);
        enrollment.setEnabled(false, CLOSING);
        history.setEnabled(false, CLOSING);
        receipts.lockAll();
        system.setBusy(true);
        showFeedback(CLOSING, UiLabels.Tone.PENDING);
        guidance.setText("Chờ worker hoàn tất và ngắt kết nối.");
    }

    void log(String text) {
        String line = "[" + LocalTime.now().format(TIME) + "] " + text;
        System.out.println(line);
        if (Platform.isFxApplicationThread()) {
            appendLog(line);
        } else if (!logStopped) {
            try {
                Platform.runLater(() -> appendLog(line));
            } catch (IllegalStateException stopped) {
                logStopped = true;
            }
        }
    }

    private void appendLog(String line) {
        logArea.appendText(line + System.lineSeparator());
        if (logArea.getLength() > 220_000) {
            int end = logArea.getText(0, 20_000).lastIndexOf('\n');
            logArea.deleteText(0, end < 0 ? 20_000 : end + 1);
        }
        logArea.setScrollTop(Double.MAX_VALUE);
    }
}
