package vn.labaccess.desktop.ui.staff;

import static vn.labaccess.desktop.ui.foundation.UiSection.tab;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.Icons;
import vn.labaccess.desktop.ui.foundation.UiColumns;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardLifecycle;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;

import vn.labaccess.desktop.session.CardSession;

import vn.labaccess.desktop.ui.shared.CardDisplayState;
import vn.labaccess.desktop.ui.shared.CardGate;
import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.CardTaskRunner;
import vn.labaccess.desktop.ui.shared.EnrollmentRunner;
import vn.labaccess.desktop.ui.shared.HistoryPanel;
import vn.labaccess.desktop.ui.shared.OperationLog;
import vn.labaccess.desktop.ui.shared.SystemConnectionPanel;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

import vn.labaccess.desktop.ui.enrollment.EnrollmentPanel;
import vn.labaccess.desktop.ui.enrollment.PersonalizePanel;

import vn.labaccess.desktop.ui.photo.PhotoUploadPanel;

import vn.labaccess.desktop.ui.pin.StudentPinUnblockPanel;

import vn.labaccess.desktop.ui.receipt.ReceiptPanel;

import vn.labaccess.desktop.ui.profile.ProfileEditPanel;

import vn.labaccess.desktop.ui.staff.StaffAuditPanel;

import vn.labaccess.desktop.ui.transaction.GrantPanel;

public final class StaffWorkspace {

    private static final int OPERATIONS = 0;
    private static final int UNBLOCK = 1;
    private static final int ISSUANCE = 2;
    private static final int ENROLLMENT = 3;
    private static final int PHOTOS = 4;
    private static final int PROFILE_EDIT = 5;
    private static final int AUDIT = 6;
    private static final String CLOSING = "Đang đóng cửa sổ Staff.";
    private final CardSession session;
    private final CardTaskRunner tasks;
    private final CardOpRunner cardOps;
    private final GrantPanel grants;
    private final StudentPinUnblockPanel unblock;
    private final PersonalizePanel personalize;
    private final EnrollmentPanel enrollment;
    private final PhotoUploadPanel photos;
    private final ProfileEditPanel profileEdit;
    private final ReceiptPanel receipts;
    private final HistoryPanel history;
    private final StaffAuditPanel audit;
    private final SystemConnectionPanel system;
    private final Button connect = new Button("Kết nối", Icons.connect());
    private final Button identify = new Button("Nhận diện thẻ", Icons.identify());
    private final Button disconnect = new Button("Ngắt kết nối", Icons.disconnect());
    private final Button reconcileNow = new Button("Đối soát giao dịch");
    private final ToggleButton technical = new ToggleButton("Chi tiết kỹ thuật");
    private final ToggleButton logToggle = new ToggleButton("Nhật ký");
    private final Label connection = UiLabels.status();
    private final Label connectionDot = new Label("●");
    private final Label cardId = UiLabels.cardId();
    private final Label lifecycle = UiLabels.chip();
    private final Label quota = UiLabels.value();
    private final Label stateOrigin = UiLabels.status();
    private final Label stateOriginDot = new Label("●");
    private final Label feedback = UiLabels.caption("Chưa có thao tác");
    private final Label guidance = UiLabels.caption("Kết nối thẻ để bắt đầu.");
    private final OperationLog operationLog = new OperationLog(OperationLog.View.STAFF);
    private final TabPane tabs;
    private final BorderPane root = new BorderPane();
    private final ScrollPane viewport = UiSection.scroll(root);
    private String shownCard;
    private String awaitingReadCard;
    private boolean active = true;

    public StaffWorkspace(CardSession session, CardTaskRunner tasks, CardOpRunner cardOps,
            EnrollmentRunner backendOps) {
        this.session = session;
        this.tasks = tasks;
        this.cardOps = cardOps;
        CardGateway gateway = session.gateway();
        history = new HistoryPanel(session.backend(), backendOps::run,
                () -> gateway.isReady() && gateway.status() != null ? gateway.status().cardId() : "", this::log);
        audit = new StaffAuditPanel(session.backend(), backendOps);
        grants = new GrantPanel(cardOps, this::log, new GrantPanel.Actions(this::open, gateway::status,
                session::grant, history::discardRefresh,
                () -> history.stageRefresh(session::loadHistory), this::grantFinished,
                () -> { }, () -> showGrantEffects()));
        unblock = new StudentPinUnblockPanel(gateway, cardOps, this::log, outcome -> refresh());
        photos = new PhotoUploadPanel(gateway, cardOps, this::log);
        profileEdit = new ProfileEditPanel(gateway, session.backend(), backendOps, this::log);
        enrollment = new EnrollmentPanel(gateway, backendOps, session.backend(), this::log, this::enrollmentFinished);
        personalize = new PersonalizePanel(gateway, cardOps, session.backend(), this::log, (ok, issued) -> {
            session.invalidateSnapshot();
            if (ok && issued != null) {
                photos.onCard(issued.cardId());
                enrollment.prefill(issued.cardId(), issued.studentId(), issued.classCode());
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
                history::discardRefresh, () -> history.stageRefresh(session::loadHistory),
                history::publishRefresh, this::refresh,
                ok -> {
                    session.reconcileFinished(ok);
                    showGrantEffects();
                }));
        system = new SystemConnectionPanel(session.backend(), gateway, backendOps, (ok, message) -> {
            showFeedback(message, ok ? UiLabels.Tone.OK : UiLabels.Tone.ERROR);
            log((ok ? "[OK] " : "[FAIL] ") + message);
            refresh();
        });
        tabs = new TabPane(
                tab("Vận hành Staff", new UiColumns(UiScale.WORKSPACE_COLUMNS_WIDTH, 64,
                        UiSection.of("Cấp lượt", grants.actionsNode(), grants.previewNode()), receipts.node()),
                        history.node()),
                tab("Mở khóa PIN", UiSection.of("Mở khóa PIN sinh viên", unblock.node(), unblock.feedbackNode())),
                tab("Phát hành thẻ", personalize.node()),
                tab("Đăng ký thẻ", enrollment.node()),
                tab("Ảnh", photos.node()),
                tab("Sửa hồ sơ", profileEdit.node()),
                tab("Nhật ký nhân viên", audit.node()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("la-tabs");
        tabs.setMinHeight(260);
        viewport.setFitToHeight(true);
        root.setMinWidth(0);
        root.setTop(header());
        root.setCenter(tabs);
        BorderPane.setMargin(tabs, UiScale.centerInsets());
        TitledPane logPane = operationLog.createPane();
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
        tabs.getSelectionModel().selectedIndexProperty().addListener((observable, was, now) -> {
            refresh();
            if (now.intValue() == AUDIT) {
                audit.activate();
            }
        });
    }

    public Parent node() {
        return viewport;
    }

    private VBox header() {
        Label brand = new Label("LabAccess · Nhân viên");
        brand.getStyleClass().add("la-brand");
        connectionDot.getStyleClass().add("la-state-dot");
        stateOriginDot.getStyleClass().add("la-state-dot");
        HBox connectionLine = new HBox(UiScale.SPACE_1, connectionDot, connection);
        connectionLine.setAlignment(Pos.CENTER_LEFT);
        HBox cardLine = new HBox(UiScale.SPACE_2, UiLabels.caption("Thẻ"), cardId);
        cardLine.setAlignment(Pos.CENTER_LEFT);
        HBox lifecycleLine = new HBox(UiScale.SPACE_2, UiLabels.caption("Vòng đời"), lifecycle);
        lifecycleLine.setAlignment(Pos.CENTER_LEFT);
        HBox quotaLine = new HBox(UiScale.SPACE_2, UiLabels.caption("Số lượt"), quota);
        quotaLine.setAlignment(Pos.CENTER_LEFT);
        HBox sourceLine = new HBox(UiScale.SPACE_1, stateOriginDot, stateOrigin);
        sourceLine.setAlignment(Pos.CENTER_LEFT);
        HBox reconcileLine = new HBox(UiScale.SPACE_2, UiLabels.caption("Đối soát"), receipts.reconcileStatus());
        reconcileLine.setAlignment(Pos.CENTER_LEFT);
        FlowPane cardSummary = UiRows.wrapping(connectionLine, cardLine, lifecycleLine, quotaLine,
                sourceLine, reconcileLine);
        cardSummary.setVgap(UiScale.SPACE_1);
        for (Button button : new Button[] {connect, identify, disconnect}) {
            button.getStyleClass().add("la-action");
        }
        disconnect.getStyleClass().add("la-action--danger");
        Button more = new Button("Khác");
        more.getStyleClass().add("la-action");
        CheckMenuItem technicalItem = new CheckMenuItem("Chi tiết kỹ thuật");
        technicalItem.selectedProperty().bindBidirectional(technical.selectedProperty());
        CheckMenuItem logItem = new CheckMenuItem("Nhật ký");
        logItem.selectedProperty().bindBidirectional(logToggle.selectedProperty());
        MenuItem reconcileItem = new MenuItem("Đối soát giao dịch");
        Button reconcileButton = (Button) receipts.reconcileAction();
        reconcileItem.disableProperty().bind(reconcileButton.disableProperty());
        reconcileItem.setOnAction(event -> reconcileButton.fire());
        reconcileNow.getStyleClass().add("la-action");
        reconcileNow.disableProperty().bind(reconcileButton.disableProperty());
        reconcileNow.setOnAction(event -> reconcileButton.fire());
        UiLabels.show(reconcileNow, false);
        MenuItem systemItem = new MenuItem("Kiểm tra hệ thống");
        Button systemButton = (Button) system.actionNode();
        systemItem.disableProperty().bind(systemButton.disableProperty());
        systemItem.setOnAction(event -> systemButton.fire());
        ContextMenu moreMenu = new ContextMenu(technicalItem, logItem, new SeparatorMenuItem(), reconcileItem,
                systemItem);
        more.setOnAction(event -> moreMenu.show(more, Side.BOTTOM, 0, 0));
        HBox actions = new HBox(UiScale.SPACE_2, connect, identify, disconnect, reconcileNow, more);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("la-header-actions");
        FlowPane headerActions = UiRows.wrapping(brand, actions);
        headerActions.setHgap(UiScale.SPACE_5);
        headerActions.setVgap(UiScale.SPACE_1);
        feedback.setWrapText(true);
        feedback.setMinWidth(0);
        guidance.setWrapText(true);
        guidance.setMinWidth(0);
        VBox statusLines = new VBox(UiScale.SPACE_1, feedback, guidance);
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
        Label systemValue = (Label) system.statusNode();
        systemValue.setWrapText(true);
        systemValue.setMinWidth(0);
        GridPane.setHgrow(systemValue, Priority.ALWAYS);
        details.addRow(values.length, UiLabels.caption("Hệ thống"), systemValue);
        details.setManaged(false);
        details.setVisible(false);
        technical.selectedProperty().addListener((observable, was, now) -> {
            details.setManaged(now);
            details.setVisible(now);
            personalize.showTechnical(now);
            enrollment.showTechnical(now);
            photos.showTechnical(now);
        });
        VBox header = new VBox(UiScale.SPACE_2, headerActions, cardSummary, statusLines, details);
        header.getStyleClass().add("la-header");
        header.setPadding(UiScale.headerInsets());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return header;
    }

    public void initialize() {
        CardSession.StartupJournal startup = session.blockOnStartupJournal();
        log("Staff PID=" + ProcessHandle.current().pid());
        log(startup.message());
        refresh();
    }

    private boolean open() {
        return active && !tasks.isBusy() && !tasks.isClosing();
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

    public void synchronizeConnection() {
        session.synchronizeConnection();
        CardStatus status = session.gateway().isReady() ? session.gateway().status() : null;
        String current = status == null ? null : status.cardId();
        if (!java.util.Objects.equals(current, shownCard)) {
            if (shownCard != null) {
                clearInputs();
                personalize.resetView();
                receipts.clear();
                history.discardRefresh();
            }
            shownCard = current;
            if (current != null) {
                awaitingReadCard = null;
            }
            photos.onCard(current);
            profileEdit.onCard(current);
            history.onCard(current);
            enrollment.onCard(current);
            unblock.resetFeedback();
        }
    }

    private void resetViews() {
        shownCard = null;
        awaitingReadCard = null;
        history.discardRefresh();
        photos.resetView();
        profileEdit.resetView();
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
        profileEdit.clearInputs();
        personalize.clearInputs();
        enrollment.clearInputs();
    }

    public void taskStarted(String label) {
        showFeedback(pendingText(label), UiLabels.Tone.PENDING);
        log("> " + label + " ...");
        refresh();
    }

    public void backendCompleted(String label, boolean ok) {
        if (feedback.getText().equals(pendingText(label))) {
            showFeedback(UiLabels.outcome(ok, label), ok ? UiLabels.Tone.OK : UiLabels.Tone.ERROR);
        }
    }

    private static String pendingText(String label) {
        return "Đang xử lý: " + UiLabels.actionName(label);
    }

    public void cardCompleted(String label, CardTaskRunner.Outcome<CardOperationResult> outcome) {
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
        history.publishRefresh();
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

    private void showGrantEffects() {
        PendingJournal.Loaded loaded = session.pendingJournal();
        grants.showEffects(session.gateway().status(), session.snapshotKnown()
                && !loaded.present() && session.gateway().reconciliation().transactionAllowed());
    }

    public void refresh() {
        if (!active || tasks.isClosing()) {
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
        profileEdit.setEnabled(mutation == null, mutation);
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
        UiLabels.show(reconcileNow, loaded.present());
        UiLabels.markPrimary(reconcileNow, loaded.present() && !reconcileNow.isDisabled());
        connect.setDisable(CardGate.connectDisabled(gateway, busy));
        identify.setDisable(CardGate.identifyDisabled(gateway, busy));
        disconnect.setDisable(CardGate.disconnectDisabled(gateway, busy));
        system.setBusy(busy);
        audit.setBusy(busy);
        if (!busy) {
            system.showBackendAccess();
        }
        connection.setText(CardGate.connectionText(gateway, "Đã nhận diện"));
        UiLabels.Tone connectionTone = CardGate.connectionTone(gateway);
        connection.getStyleClass().removeAll("la-chip", "la-chip--neutral", "la-chip--ok", "la-chip--pending",
                "la-chip--error");
        if (gateway.needsReconnect()) {
            connection.getStyleClass().addAll("la-chip", "la-chip--error");
        }
        UiLabels.applyTone(connection, connectionTone);
        UiLabels.applyTone(connectionDot, connectionTone);
        cardId.setText(status == null ? UiLabels.EMPTY_VALUE : status.cardId());
        lifecycle.setText(status == null ? "Chưa rõ" : CardLifecycle.caption(status.lifecycle()));
        boolean confirmed = ready && session.snapshotKnown() && !loaded.present()
                && gateway.reconciliation().transactionAllowed() && status.stateAvailable();
        boolean unresolved = CardGate.unresolved(gateway, loaded, legacyProblem);
        stateOrigin.setText(CardDisplayState.of(status, confirmed, unresolved,
                awaitingReadCard != null || gateway.reconciliation().receiptUnverified(), busy));
        UiLabels.Tone sourceTone = confirmed ? UiLabels.Tone.OK
                : unresolved ? UiLabels.Tone.ERROR : UiLabels.Tone.PENDING;
        UiLabels.applyTone(stateOrigin, sourceTone);
        UiLabels.applyTone(stateOriginDot, sourceTone);
        quota.setText(unresolved || busy ? "Chưa rõ kết quả"
                : confirmed ? status.balance() + " lượt"
                : awaitingReadCard != null ? "Đã cấp — cần đọc lại" : "Chưa xác nhận");
        showGrantEffects();
        UiLabels.markPrimary(connect, !busy && !gateway.isConnected() && !gateway.needsReconnect());
        UiLabels.markPrimary(identify, !busy && gateway.isConnected() && !ready && !gateway.needsReconnect());
        boolean primary = tabs.getSelectionModel().getSelectedIndex() == OPERATIONS;
        grants.markPrimary(primary);
        personalize.setIssuePrimary(tabs.getSelectionModel().getSelectedIndex() == ISSUANCE && issuance == null);
        photos.setUploadPrimary(tabs.getSelectionModel().getSelectedIndex() == PHOTOS && mutation == null);
        profileEdit.setSavePrimary(tabs.getSelectionModel().getSelectedIndex() == PROFILE_EDIT
                && mutation == null);
        String next = recoveryProblem != null ? recoveryProblem
                : loaded.present() ? receipts.pendingOperationReason() != null ? receipts.pendingOperationReason()
                : loaded.valid() && loaded.status() == PendingJournal.Status.PENDING
                ? "Đối soát journal trước; không thực hiện lại giao dịch." : loaded.recoverySummary()
                : base != null ? base : !issued ? "Phát hành thẻ tại tab Phát hành thẻ."
                : gateway.enrollmentBlockReason() != null ? "Đăng ký thẻ hoặc kiểm tra hệ thống."
                : "Cấp lượt, mở khóa hoặc ghi ảnh bằng PIN quản trị. Không có thao tác vào/ra ca ở Staff.";
        guidance.setText(next);
    }

    public void closeRequested() {
        active = false;
        audit.close();
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
        profileEdit.setEnabled(false, CLOSING);
        personalize.updateAvailability(false, false, CLOSING);
        enrollment.setEnabled(false, CLOSING);
        history.setEnabled(false, CLOSING);
        audit.setBusy(true);
        receipts.lockAll();
        system.setBusy(true);
        showFeedback(CLOSING, UiLabels.Tone.PENDING);
        guidance.setText("Chờ worker hoàn tất và ngắt kết nối.");
    }

    public void log(String text) {
        operationLog.append(text);
    }
}
