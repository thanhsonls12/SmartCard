package vn.labaccess.desktop.ui.kiosk;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import vn.labaccess.desktop.ui.foundation.Icons;
import vn.labaccess.desktop.ui.foundation.UiColumns;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardLifecycle;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.PhotoImage;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.session.CardSession;
import vn.labaccess.desktop.session.SupersededRecovery;

import vn.labaccess.desktop.ui.shared.CardDisplayState;
import vn.labaccess.desktop.ui.shared.CardGate;
import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.CardTaskRunner;
import vn.labaccess.desktop.ui.shared.EnrollmentRunner;
import vn.labaccess.desktop.ui.shared.HistoryPanel;
import vn.labaccess.desktop.ui.shared.OperationLog;
import vn.labaccess.desktop.ui.shared.SupersededDialog;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class KioskWorkspace {

    private final CardSession session;
    private final KioskOperations operations;
    private final CardTaskRunner tasks;
    private final CardOpRunner cardOps;
    private final BiConsumer<CardOperationResult.SupersededProof, char[]> operatorConfirm;
    private final KioskPinPrompt prompt;
    private final HistoryPanel history;
    private final Button connect = new Button("Kết nối");
    private final Button identify = new Button("Nhận diện lại");
    private final Button disconnect = new Button("Ngắt kết nối");
    private final Button start = new Button("Vào lab");
    private final Button end = new Button("Kết thúc ca");
    private final Button info = new Button("Xem thông tin");
    private final Button change = new Button("Đổi PIN");
    private final Button reconcile = new Button("Đối soát giao dịch");
    private final Button done = new Button("Kết thúc phiên");
    private final Label connection = UiLabels.chip();
    private final Label cardId = UiLabels.caption("Chưa nhận diện thẻ");
    private final Label feedback = UiLabels.caption("Đặt thẻ và bấm Kết nối, sau đó chọn thao tác.");
    private final Label quota = UiLabels.value();
    private final Label stateOrigin = UiLabels.chip();
    private boolean needsReread;
    private final Label visit = UiLabels.value();
    private final Label name = UiLabels.caption("");
    private final Label studentId = UiLabels.caption("");
    private final Label classCode = UiLabels.caption("");
    private final Label major = UiLabels.caption("");
    private final Label photoNote = UiLabels.caption("");
    private final Label pendingNote = UiLabels.caption("");
    private final ImageView photo = new ImageView();
    private final OperationLog operationLog = new OperationLog(OperationLog.View.KIOSK);
    private final BorderPane root = new BorderPane();
    private final ScrollPane viewport = UiSection.scroll(root);
    private String shownCard;
    private String authenticatedCard;
    private long revision;
    private int adminTries = -1;
    private CardOperationResult.SupersededProof activeProof;

    public KioskWorkspace(CardSession session, KioskOperations operations, CardTaskRunner tasks, CardOpRunner cardOps,
            Consumer<KioskPinPrompt.Request> submitted,
            BiConsumer<CardOperationResult.SupersededProof, char[]> operatorConfirm, EnrollmentRunner backend) {
        this.session = session;
        this.operations = operations;
        this.tasks = tasks;
        this.cardOps = cardOps;
        this.operatorConfirm = operatorConfirm;
        prompt = new KioskPinPrompt(submitted, () -> endSession(), this::log);
        history = new HistoryPanel(session.backend(), backend::run,
                () -> authenticatedCard == null ? "" : authenticatedCard, this::log);
        connect.setOnAction(event -> connect());
        identify.setOnAction(event -> identify());
        disconnect.setOnAction(event -> disconnect());
        start.setOnAction(event -> choose(KioskOperations.Action.START));
        end.setOnAction(event -> choose(KioskOperations.Action.END));
        info.setOnAction(event -> choose(KioskOperations.Action.INFO));
        change.setOnAction(event -> choose(KioskOperations.Action.CHANGE));
        reconcile.setOnAction(event -> choose(KioskOperations.Action.RECONCILE));
        done.setOnAction(event -> endSession());
        for (Button button : new Button[] {connect, identify, disconnect, start, end, info, change, reconcile, done}) {
            button.getStyleClass().add("la-action");
            button.setWrapText(true);
            button.setMinWidth(0);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setPrefHeight(Region.USE_COMPUTED_SIZE);
        }
        for (Button button : new Button[] {start, end}) {
            button.getStyleClass().add("la-kiosk-main");
            button.setContentDisplay(ContentDisplay.TOP);
        }
        start.setGraphic(actionIcon(Feather.LOG_IN));
        end.setGraphic(actionIcon(Feather.LOG_OUT));
        info.setGraphic(Icons.info());
        change.setGraphic(actionIcon(Feather.KEY));
        reconcile.setGraphic(Icons.reconcile());
        connect.setGraphic(Icons.connect());
        start.getStyleClass().add("la-primary");
        feedback.setWrapText(true);
        feedback.getStyleClass().add("la-kiosk-feedback");
        pendingNote.setWrapText(true);
        pendingNote.getStyleClass().add("la-kiosk-pending");
        pendingNote.visibleProperty().bind(pendingNote.textProperty().isNotEmpty());
        pendingNote.managedProperty().bind(pendingNote.visibleProperty());
        root.setTop(header());
        ScrollPane scroll = UiSection.scroll(content());
        scroll.setMinHeight(UiScale.SPACE_5 * 8);
        viewport.setFitToHeight(true);
        viewport.getStyleClass().add("la-kiosk");
        root.setMinWidth(0);
        root.setCenter(scroll);
        TitledPane log = operationLog.createPane();
        log.setExpanded(false);
        log.setMaxHeight(UiScale.LOG_MIN_HEIGHT);
        root.setBottom(log);
        clearView();
    }

    private VBox header() {
        Label brand = new Label("LabAccess");
        brand.getStyleClass().add("la-brand");
        Label subtitle = UiLabels.caption("Kiosk sinh viên");
        VBox brandText = new VBox(UiScale.SPACE_1, brand, subtitle);
        StackPane mark = new StackPane(actionIcon(Feather.CREDIT_CARD));
        mark.getStyleClass().add("la-kiosk-brand-mark");
        HBox brandGroup = new HBox(UiScale.SPACE_3, mark, brandText);
        brandGroup.setAlignment(Pos.CENTER_LEFT);
        var controls = UiRows.wrapping(connect, identify, disconnect);
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.getStyleClass().add("la-kiosk-connection-actions");
        UiColumns top = new UiColumns(UiScale.PROFILE_COLUMNS_WIDTH / 2, 45, brandGroup, controls);
        cardId.getStyleClass().add("la-kiosk-card-id");
        VBox header = new VBox(UiScale.SPACE_3, top, UiRows.wrapping(connection, cardId));
        header.getStyleClass().addAll("la-header", "la-kiosk-header");
        return header;
    }

    private VBox content() {
        Label title = new Label("Sử dụng phòng lab");
        title.setWrapText(true);
        title.getStyleClass().add("la-kiosk-heading");
        VBox intro = new VBox(UiScale.SPACE_2, title, feedback);
        Label actionTitle = new Label("Chọn thao tác");
        actionTitle.getStyleClass().add("la-section-title");
        Label actionHint = UiLabels.caption("Nhập PIN sinh viên sau khi chọn thao tác.");
        actionHint.setWrapText(true);
        GridPane mainActions = buttonGrid(start, end);
        GridPane secondaryActions = buttonGrid(info, change);
        for (Region group : new Region[] {mainActions, secondaryActions, actionHint}) {
            group.visibleProperty().bind(prompt.node().visibleProperty().not());
            group.managedProperty().bind(group.visibleProperty());
        }
        VBox actions = new VBox(UiScale.SPACE_4, actionTitle, actionHint, pendingNote, reconcile,
                mainActions, secondaryActions, prompt.node());
        actions.getStyleClass().add("la-kiosk-actions");
        UiColumns columns = new UiColumns(UiScale.WORKSPACE_COLUMNS_WIDTH - UiScale.SPACE_5 * 6,
                54, actions, studentCard());
        history.node().getStyleClass().add("la-kiosk-history");
        VBox content = new VBox(UiScale.SPACE_5, intro, columns, history.node());
        content.getStyleClass().add("la-kiosk-content");
        content.setMinWidth(0);
        return content;
    }

    private VBox studentCard() {
        photo.setFitWidth(UiScale.SPACE_5 * 3);
        photo.setFitHeight(UiScale.SPACE_5 * 3);
        photo.setPreserveRatio(true);
        photo.setSmooth(false);
        FontIcon placeholder = actionIcon(Feather.USER);
        placeholder.visibleProperty().bind(photo.imageProperty().isNull());
        StackPane portrait = new StackPane(placeholder, photo);
        portrait.getStyleClass().add("la-kiosk-portrait");
        name.getStyleClass().add("la-kiosk-student-name");
        name.setWrapText(true);
        studentId.setWrapText(true);
        classCode.setWrapText(true);
        major.setWrapText(true);
        Label empty = UiLabels.caption("Xác thực PIN để xem thông tin sinh viên.");
        empty.setWrapText(true);
        empty.visibleProperty().bind(name.textProperty().isEmpty());
        empty.managedProperty().bind(empty.visibleProperty());
        name.visibleProperty().bind(name.textProperty().isNotEmpty());
        name.managedProperty().bind(name.visibleProperty());
        studentId.visibleProperty().bind(studentId.textProperty().isNotEmpty());
        studentId.managedProperty().bind(studentId.visibleProperty());
        for (Label label : new Label[] {classCode, major}) {
            label.visibleProperty().bind(label.textProperty().isNotEmpty());
            label.managedProperty().bind(label.visibleProperty());
        }
        VBox identity = new VBox(UiScale.SPACE_1, name, studentId, classCode, major, empty);
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);
        HBox profile = new HBox(UiScale.SPACE_4, portrait, identity);
        profile.setAlignment(Pos.CENTER_LEFT);
        quota.getStyleClass().remove("la-value");
        quota.getStyleClass().add("la-kiosk-quota");
        visit.getStyleClass().remove("la-value");
        visit.getStyleClass().add("la-kiosk-visit");
        GridPane metrics = new GridPane();
        metrics.setHgap(UiScale.SPACE_4);
        for (int column = 0; column < 2; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(50);
            constraints.setMinWidth(0);
            metrics.getColumnConstraints().add(constraints);
        }
        metrics.add(UiRows.valueBlock("Số lượt còn lại", quota), 0, 0);
        metrics.add(UiRows.valueBlock("Ca sử dụng", visit), 1, 0);
        metrics.getStyleClass().add("la-kiosk-metrics");
        photoNote.setWrapText(true);
        photoNote.visibleProperty().bind(photoNote.textProperty().isNotEmpty());
        photoNote.managedProperty().bind(photoNote.visibleProperty());
        done.getStyleClass().add("la-kiosk-session-end");
        Label title = new Label("Thẻ sinh viên");
        title.getStyleClass().add("la-section-title");
        VBox card = new VBox(UiScale.SPACE_4, title, profile, stateOrigin, metrics, photoNote, done);
        card.getStyleClass().add("la-kiosk-student-card");
        card.setMinWidth(0);
        return card;
    }

    private static GridPane buttonGrid(Button first, Button second) {
        GridPane grid = new GridPane();
        grid.setHgap(UiScale.SPACE_3);
        for (int column = 0; column < 2; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(50);
            constraints.setMinWidth(0);
            grid.getColumnConstraints().add(constraints);
        }
        grid.add(first, 0, 0);
        grid.add(second, 1, 0);
        return grid;
    }

    private static FontIcon actionIcon(Feather icon) {
        FontIcon node = new FontIcon(icon);
        node.getStyleClass().add("la-icon");
        return node;
    }

    public Parent node() { return viewport; }

    public KioskOperations operations() { return operations; }

    public long revision() { return revision; }

    public void initialize() {
        CardSession.StartupJournal loaded = session.blockOnStartupJournal();
        log(loaded.message());
        refresh();
    }

    private boolean open() { return !tasks.isBusy() && !tasks.isClosing(); }

    private void connect() {
        prepareConnection();
        cardOps.run("Kết nối và nhận diện", session::connect, ok -> { });
    }

    private void identify() {
        prepareConnection();
        cardOps.run("Nhận diện thẻ", session::identify, ok -> { });
    }

    private void disconnect() {
        prepareConnection();
        cardOps.run("Ngắt kết nối", session::disconnect, ok -> { });
    }

    private void prepareConnection() {
        revision++;
        activeProof = null;
        prompt.clear();
        clearView();
        operations.resetSession();
        adminTries = -1;
    }

    public void synchronizeConnection() {
        session.synchronizeConnection();
        CardStatus current = session.gateway().isReady() ? session.gateway().status() : null;
        String next = current == null ? null : current.cardId();
        if (!Objects.equals(shownCard, next)) {
            revision++;
            activeProof = null;
            prompt.clear();
            clearView();
            operations.resetSession();
            adminTries = -1;
            shownCard = next;
            long expectedRevision = revision;
            if (next != null) {
                Platform.runLater(() -> {
                    if (open() && revision == expectedRevision && !prompt.isOpen()) {
                        viewport.setVvalue(0);
                        ((ScrollPane) root.getCenter()).setVvalue(0);
                        Button action = !reconcile.isDisabled() ? reconcile : !start.isDisabled() ? start : info;
                        if (!action.isDisabled()) {
                            action.requestFocus();
                        }
                    }
                });
            }
        }
    }

    private boolean allowed(KioskOperations.Action action) {
        if (!open() || prompt.isOpen() || !session.gateway().isReady() || operations.blocked()) {
            return false;
        }
        CardStatus current = session.gateway().status();
        if (current == null || current.lifecycle() != CardLifecycle.ISSUED) {
            return false;
        }
        if (action == KioskOperations.Action.RECONCILE) {
            return session.pendingJournal().present() && operations.pendingProblem() == null;
        }
        return session.newTransactionProblem() == null && !session.pendingJournal().present()
                && operations.permitsReceiptState(action);
    }

    private void choose(KioskOperations.Action action) {
        if (!allowed(action)) {
            return;
        }
        revision++;
        activeProof = null;
        clearView();
        operations.clearStudentData();
        prompt.open(action);
        feedback.setText(action.label() + ": nhập PIN sinh viên để tiếp tục.");
        refresh();
    }

    private void endSession() {
        if (!open()) {
            return;
        }
        revision++;
        activeProof = null;
        prompt.clear();
        clearView();
        operations.clearStudentData();
        feedback.setText(operations.blocked() ? "PIN đã khóa. Gặp nhân viên để mở khóa."
                : "Phiên đã kết thúc. Chọn thao tác để bắt đầu phiên mới.");
        refresh();
    }

    private void clearView() {
        needsReread = false;
        authenticatedCard = null;
        quota.setText("Chưa xác thực");
        visit.setText("Chưa xác thực");
        name.setText("");
        studentId.setText("");
        classCode.setText("");
        major.setText("");
        photo.setImage(null);
        photoNote.setText("");
        history.onCard(null);
        UiLabels.show(history.node(), false);
    }

    public void started(String label) {
        feedback.setText(pendingText(label));
        refresh();
    }

    public void backendCompleted(String label, boolean ok) {
        if (feedback.getText().equals(pendingText(label))) {
            feedback.setText(UiLabels.outcome(ok, label));
        }
    }

    private static String pendingText(String label) {
        return "Đang xử lý: " + UiLabels.actionName(label);
    }

    public void cardCompleted(String label, CardTaskRunner.Outcome<CardOperationResult> outcome) {
        feedback.setText(outcome.failure() != null ? "Lỗi kết nối thẻ. Hãy kết nối lại thẻ."
                : outcome.value().ok() ? "Đã " + label.toLowerCase(java.util.Locale.ROOT)
                : outcome.value().text());
        refresh();
    }

    public void sessionChanged(CardTaskRunner.Outcome<KioskOperations.Outcome> outcome) {
        clearView();
        String warning = outcome.value() == null ? null : outcome.value().result().warning();
        needsReread = warning != null && outcome.value().result().ok();
        if (needsReread) {
            quota.setText("Cần đọc lại");
        }
        feedback.setText(warning != null ? warning
                : "Thẻ đã được rút hoặc mất kết nối. Không thử lại giao dịch; hãy kết nối lại thẻ.");
        refresh();
    }

    public void studentCompleted(CardTaskRunner.Outcome<KioskOperations.Outcome> outcome) {
        if (outcome.failure() != null) {
            clearView();
            feedback.setText("Chưa rõ kết quả giao dịch. Không thử lại; vui lòng gặp nhân viên.");
            refresh();
            return;
        }
        KioskOperations.Outcome value = outcome.value();
        CardOperationResult result = value.result();
        clearView();
        needsReread = result.ok() && result.warning() != null;
        if (needsReread) {
            quota.setText("Cần đọc lại");
        }
        String message = result.ok() ? value.action().label() + " hoàn tất."
                : result.superseded() != null
                        ? "Giao dịch trước cần nhân viên xác nhận. Vui lòng gặp nhân viên." : result.text();
        if ((value.pinSw() & 0xFFF0) == 0x63C0) {
            message = "PIN sai, còn " + (value.pinSw() & 0x0F) + " lượt thử. Thao tác đã dừng.";
        } else if (value.pinSw() == Sw.AUTH_BLOCKED) {
            message = "PIN sinh viên đã khóa. Gặp nhân viên để mở khóa; không thử lại tại Kiosk.";
        }
        if (result.warning() != null) {
            message += " " + result.warning();
        }
        feedback.setText(message);
        log(value.action().label() + " | Student SW="
                + (value.pinSw() < 0 ? "chưa gửi" : String.format("%04X", value.pinSw())) + " | " + message);
        if (value.status() != null && session.gateway().isReady()
                && value.status().cardId().equals(session.gateway().status().cardId())) {
            session.transactionFinished(result.ok());
            authenticatedCard = value.status().cardId();
            quota.setText(value.status().balance() + " lượt");
            visit.setText(value.status().hasActiveVisit() ? "Đang mở" : "Chưa mở");
            if (value.profile() != null) {
                name.setText(value.profile().displayName());
                studentId.setText("Mã sinh viên: " + value.profile().studentId());
                classCode.setText("Lớp: " + (value.profile().classCode() == null
                        ? "Chưa lưu trên thẻ" : value.profile().classCode()));
                major.setText("Chuyên ngành: " + (value.profile().major() == null ? "Chưa lưu trên thẻ"
                        : value.profile().major().isEmpty() ? "Chưa xác định" : value.profile().major()));
                showPhoto(value.photo());
                photoNote.setText(value.photoAbsent() ? "Thẻ chưa có ảnh."
                        : "Thông tin vừa đọc sau khi xác thực PIN.");
            }
            history.onCard(authenticatedCard);
            UiLabels.show(history.node(), true);
        }
        activeProof = result.superseded();
        if (activeProof != null) {
            CardOperationResult.SupersededProof proof = activeProof;
            long expectedRevision = revision;
            Platform.runLater(() -> confirmSuperseded(proof, expectedRevision));
        }
        refresh();
    }

    private void confirmSuperseded(CardOperationResult.SupersededProof proof, long expectedRevision) {
        if (!open() || expectedRevision != revision || activeProof != proof || adminTries == 0
                || !operations.canConfirm(proof)) {
            return;
        }
        char[] pin = SupersededDialog.confirm(root, proof, adminTries);
        if (pin == null) {
            activeProof = null;
            log("Nhân viên chưa xác nhận; journal giữ nguyên.");
            return;
        }
        if (!open() || expectedRevision != revision || activeProof != proof || !operations.canConfirm(proof)) {
            Arrays.fill(pin, '\0');
            return;
        }
        activeProof = null;
        operatorConfirm.accept(proof, pin);
    }

    public void logOperatorResponse(String attemptedCardId, SupersededRecovery.Confirmation confirmation) {
        confirmation.adminStatusWord().ifPresent(sw -> {
            int remaining = adminRemaining(sw);
            log(String.format("%s | Xác nhận SUPERSEDED | cardId=%s | GET_ADMIN_STATUS SW=%04X | lượt còn lại=%s",
                    java.time.Instant.now(), attemptedCardId, sw, remaining < 0 ? "chưa biết" : remaining));
        });
    }

    public void logOperatorResponse(CardOperationResult.SupersededProof proof,
            SupersededRecovery.Confirmation confirmation) {
        logOperatorResponse(proof.state().cardId(), confirmation);
        if (confirmation.result().ok() && !session.pendingJournal().present()) {
            log(String.format("%s | Đóng journal SUPERSEDED | lý do=Nhân viên xác nhận giao dịch khác đã chiếm counter"
                            + " | cardId=%s | counterChờ=%d | payloadCanonicalChiếm=%s",
                    java.time.Instant.now(), proof.state().cardId(), proof.expectedCounter(), proof.occupiedPayload()));
        }
    }

    private static int adminRemaining(int sw) {
        return (sw & 0xFFF0) == 0x63C0 ? sw & 0x0F : sw == Sw.AUTH_BLOCKED ? 0 : -1;
    }

    public void operatorCompleted(CardOperationResult.SupersededProof proof, long expectedRevision,
            CardTaskRunner.Outcome<SupersededRecovery.Confirmation> outcome) {
        clearView();
        activeProof = null;
        if (expectedRevision != revision) {
            refresh();
            return;
        }
        if (outcome.failure() != null) {
            feedback.setText("Xác nhận bị gián đoạn. Giao dịch vẫn được giữ để đối soát.");
        } else {
            SupersededRecovery.Confirmation confirmation = outcome.value();
            if (session.gateway().isReady() && session.gateway().status() != null
                    && proof.state().cardId().equals(session.gateway().status().cardId())) {
                confirmation.adminStatusWord().ifPresent(sw -> {
                    int remaining = adminRemaining(sw);
                    if (remaining >= 0 || sw == Sw.OK) {
                        adminTries = remaining;
                    }
                });
            }
            feedback.setText(confirmation.result().ok() ? "Đã đối soát xong. Chọn thao tác để tiếp tục."
                    : confirmation.result().text()
                            + (adminTries >= 0 ? " PIN quản trị còn " + adminTries + " lượt thử." : ""));
        }
        operations.clearStudentData();
        refresh();
    }

    private void showPhoto(byte[] bytes) {
        if (bytes == null || bytes.length != PhotoImage.LENGTH) {
            return;
        }
        WritableImage image = new WritableImage(PhotoImage.WIDTH, PhotoImage.HEIGHT);
        for (int row = 0; row < PhotoImage.HEIGHT; row++) {
            for (int column = 0; column < PhotoImage.WIDTH; column++) {
                int gray = bytes[row * PhotoImage.WIDTH + column] & 0xFF;
                image.getPixelWriter().setArgb(column, row, 0xFF000000 | gray << 16 | gray << 8 | gray);
            }
        }
        photo.setImage(image);
    }

    public void refresh() {
        CardGateway gateway = session.gateway();
        boolean closing = tasks.isClosing();
        boolean busy = tasks.isBusy() || closing;
        connection.setText(CardGate.connectionText(gateway, "Đã nhận diện thẻ"));
        UiLabels.applyChip(connection, CardGate.connectionTone(gateway));
        cardId.setText(gateway.isReady() && gateway.status() != null
                ? gateway.status().cardId() : "Chưa nhận diện thẻ");
        start.setDisable(!allowed(KioskOperations.Action.START));
        end.setDisable(!allowed(KioskOperations.Action.END));
        info.setDisable(!allowed(KioskOperations.Action.INFO));
        change.setDisable(!allowed(KioskOperations.Action.CHANGE));
        reconcile.setDisable(!allowed(KioskOperations.Action.RECONCILE));
        done.setDisable(busy);
        connect.setDisable(CardGate.connectDisabled(gateway, busy));
        identify.setDisable(CardGate.identifyDisabled(gateway, busy) || operations.blocked());
        disconnect.setDisable(CardGate.disconnectDisabled(gateway, busy));
        prompt.setBusy(busy);
        history.setEnabled(!busy && authenticatedCard != null && gateway.isReady(), "Cần xác thực thẻ hiện tại.");
        PendingJournal.Loaded loaded = session.pendingJournal();
        String legacyProblem = session.newTransactionProblem();
        boolean unresolved = CardGate.unresolved(gateway, loaded, legacyProblem);
        stateOrigin.setText(CardDisplayState.of(gateway.status(), authenticatedCard != null && gateway.isReady(),
                unresolved, needsReread || gateway.reconciliation().receiptUnverified(), busy));
        UiLabels.applyChip(stateOrigin, unresolved ? UiLabels.Tone.ERROR
                : authenticatedCard != null && gateway.isReady() ? UiLabels.Tone.OK : UiLabels.Tone.PENDING);
        boolean startAllowed = !busy && allowed(KioskOperations.Action.START);
        boolean infoAllowed = !busy && allowed(KioskOperations.Action.INFO);
        boolean reconcileAllowed = !busy && allowed(KioskOperations.Action.RECONCILE);
        UiLabels.markPrimary(connect, !busy && !gateway.isConnected() && !gateway.needsReconnect());
        UiLabels.markPrimary(identify, !busy && gateway.isConnected() && !gateway.isReady()
                && !gateway.needsReconnect() && !operations.blocked());
        UiLabels.markPrimary(start, startAllowed);
        UiLabels.markPrimary(info, !startAllowed && infoAllowed);
        UiLabels.markPrimary(reconcile, !startAllowed && !infoAllowed && reconcileAllowed);
        UiLabels.show(reconcile, loaded.present() && !prompt.isOpen());
        UiLabels.show(connect, !gateway.isConnected() && !gateway.needsReconnect());
        UiLabels.show(identify, gateway.isConnected() && !gateway.needsReconnect());
        UiLabels.show(disconnect, gateway.isConnected() || gateway.needsReconnect());
        if (unresolved || busy) {
            quota.setText("Chưa rõ kết quả");
        } else if (needsReread) {
            quota.setText("Cần đọc lại");
        } else if (authenticatedCard != null && gateway.isReady() && gateway.status() != null
                && gateway.status().studentStateAvailable() && authenticatedCard.equals(gateway.status().cardId())) {
            quota.setText(gateway.status().balance() + " lượt");
        }
        pendingNote.setText(loaded.present() ? operations.pendingProblem() != null ? operations.pendingProblem()
                : "Giao dịch trước chưa hoàn tất. Bấm Đối soát giao dịch để hoàn tất."
                : legacyProblem != null ? legacyProblem : operations.blocked() ? "PIN đã khóa — gặp nhân viên." : "");
    }

    public void closing() {
        revision++;
        activeProof = null;
        prompt.clear();
        clearView();
        feedback.setText("Đang đóng Kiosk và ngắt kết nối thẻ.");
        refresh();
    }

    public void log(String text) {
        operationLog.append(text);
    }
}
