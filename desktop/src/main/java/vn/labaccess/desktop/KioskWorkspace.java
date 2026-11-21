package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

final class KioskWorkspace {

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
    private final Label feedback = UiLabels.caption("Kết nối thẻ, chọn thao tác rồi nhập PIN một lần.");
    private final Label quota = UiLabels.value();
    private final Label stateOrigin = UiLabels.chip();
    private boolean needsReread;
    private final Label visit = UiLabels.value();
    private final Label name = UiLabels.caption("");
    private final Label studentId = UiLabels.caption("");
    private final Label photoNote = UiLabels.caption("");
    private final Label pendingNote = UiLabels.caption("");
    private final ImageView photo = new ImageView();
    private final TextArea logArea = new TextArea();
    private final BorderPane root = new BorderPane();
    private final ScrollPane viewport = UiSection.scroll(root);
    private String shownCard;
    private String authenticatedCard;
    private long revision;
    private int adminTries = -1;
    private CardOperationResult.SupersededProof activeProof;

    KioskWorkspace(CardSession session, KioskOperations operations, CardTaskRunner tasks, CardOpRunner cardOps,
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
        }
        for (Button button : new Button[] {start, end, info}) {
            button.getStyleClass().add("la-kiosk-main");
        }
        start.getStyleClass().add("la-primary");
        feedback.setWrapText(true);
        pendingNote.setWrapText(true);
        photo.setFitWidth(120);
        photo.setFitHeight(120);
        photo.setPreserveRatio(true);
        Label brand = new Label("LabAccess · Kiosk học viên");
        brand.getStyleClass().add("la-brand");
        VBox header = new VBox(UiScale.SPACE_3, brand, UiRows.wrapping(connection, cardId, stateOrigin),
                UiRows.wrapping(connect, identify, disconnect), feedback, pendingNote);
        header.setPadding(UiScale.headerInsets());
        header.getStyleClass().add("la-header");
        root.setTop(header);
        TitledPane changeForm = new TitledPane("Đổi PIN học viên", UiRows.wrapping(change));
        changeForm.setExpanded(false);
        VBox content = new VBox(UiScale.SPACE_4,
                UiSection.of("Chọn thao tác", UiRows.wrapping(start, end, info)),
                prompt.node(), changeForm,
                UiSection.of("Kết quả phiên hiện tại", UiRows.wrapping(
                        UiRows.valueBlock("Số lượt còn lại", quota), UiRows.valueBlock("Ca sử dụng", visit)),
                        name, studentId, photo, photoNote, done),
                UiRows.wrapping(reconcile), history.node());
        content.setPadding(UiScale.centerInsets());
        ScrollPane scroll = UiSection.scroll(content);
        scroll.setMinHeight(200);
        viewport.setFitToHeight(true);
        viewport.getStyleClass().add("la-kiosk");
        root.setMinWidth(0);
        root.setCenter(scroll);
        logArea.setEditable(false);
        logArea.setWrapText(true);
        TitledPane log = new TitledPane("Nhật ký Kiosk", logArea);
        log.setExpanded(false);
        log.setMaxHeight(180);
        root.setBottom(log);
        clearView();
    }

    Parent node() { return viewport; }

    KioskOperations operations() { return operations; }

    long revision() { return revision; }

    void initialize() {
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

    void synchronizeConnection() {
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
        feedback.setText(action.label() + ": nhập Student PIN một lần cho thao tác này.");
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
        photo.setImage(null);
        photoNote.setText("");
        history.onCard(null);
        UiLabels.show(history.node(), false);
    }

    void started(String label) {
        feedback.setText("Đang xử lý: " + label);
        refresh();
    }

    void cardCompleted(String label, CardTaskRunner.Outcome<CardOperationResult> outcome) {
        feedback.setText(outcome.failure() != null ? "Lỗi kết nối. Không thực hiện lại giao dịch."
                : outcome.value().ok() ? "Đã " + label.toLowerCase(java.util.Locale.ROOT)
                : outcome.value().text());
        refresh();
    }

    void sessionChanged(CardTaskRunner.Outcome<KioskOperations.Outcome> outcome) {
        clearView();
        String warning = outcome.value() == null ? null : outcome.value().result().warning();
        needsReread = warning != null && outcome.value().result().ok();
        if (needsReread) {
            quota.setText("Cần đọc lại");
        }
        feedback.setText(warning != null ? warning
                : "Phiên thẻ đã đổi hoặc mất kết nối. Không thực hiện lại giao dịch; kết nối và nhận diện lại.");
        refresh();
    }

    void studentCompleted(CardTaskRunner.Outcome<KioskOperations.Outcome> outcome) {
        if (outcome.failure() != null) {
            clearView();
            feedback.setText("Không xác định kết quả. Kiểm tra kết nối và journal; không thực hiện lại giao dịch.");
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
                : result.superseded() != null ? "Cần nhân viên xác nhận bằng chứng SUPERSEDED." : result.text();
        if ((value.pinSw() & 0xFFF0) == 0x63C0) {
            message = "PIN sai, còn " + (value.pinSw() & 0x0F) + " lượt thử. Thao tác đã dừng.";
        } else if (value.pinSw() == Sw.AUTH_BLOCKED) {
            message = "PIN học viên đã khóa. Gặp nhân viên để mở khóa; không thử lại tại Kiosk.";
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
            quota.setText(value.status().balance() + " lượt · STUDENT");
            visit.setText(value.status().hasActiveVisit() ? "Đang mở" : "Chưa mở");
            if (value.profile() != null) {
                name.setText(value.profile().displayName());
                studentId.setText("Mã sinh viên: " + value.profile().studentId());
                showPhoto(value.photo());
                photoNote.setText(value.photoAbsent() ? "Thẻ chưa có ảnh." : "Thông tin vừa đọc sau xác thực Student.");
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

    void logOperatorResponse(String attemptedCardId, SupersededRecovery.Confirmation confirmation) {
        confirmation.adminStatusWord().ifPresent(sw -> {
            int remaining = adminRemaining(sw);
            log(String.format("%s | Xác nhận SUPERSEDED | cardId=%s | GET_ADMIN_STATUS SW=%04X | lượt còn lại=%s",
                    java.time.Instant.now(), attemptedCardId, sw, remaining < 0 ? "chưa biết" : remaining));
        });
    }

    void logOperatorResponse(CardOperationResult.SupersededProof proof,
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

    void operatorCompleted(CardOperationResult.SupersededProof proof, long expectedRevision,
            CardTaskRunner.Outcome<SupersededRecovery.Confirmation> outcome) {
        clearView();
        activeProof = null;
        if (expectedRevision != revision) {
            refresh();
            return;
        }
        if (outcome.failure() != null) {
            feedback.setText("Xác nhận bị gián đoạn. Journal được giữ nguyên.");
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
            feedback.setText(confirmation.result().ok() ? "Đã đối soát SUPERSEDED. Chọn thao tác và nhập PIN mới."
                    : confirmation.result().text()
                            + (adminTries >= 0 ? " | Admin còn " + adminTries + " lượt thử." : ""));
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

    void refresh() {
        CardGateway gateway = session.gateway();
        boolean closing = tasks.isClosing();
        boolean busy = tasks.isBusy() || closing;
        connection.setText(gateway.needsReconnect() ? "Cần kết nối lại"
                : gateway.isReady() ? "Đã nhận diện công khai" : gateway.isConnected() ? "Đã kết nối" : "Chưa kết nối");
        cardId.setText(gateway.isReady() && gateway.status() != null
                ? gateway.status().cardId() : "Chưa nhận diện thẻ");
        start.setDisable(!allowed(KioskOperations.Action.START));
        end.setDisable(!allowed(KioskOperations.Action.END));
        info.setDisable(!allowed(KioskOperations.Action.INFO));
        change.setDisable(!allowed(KioskOperations.Action.CHANGE));
        reconcile.setDisable(!allowed(KioskOperations.Action.RECONCILE));
        done.setDisable(busy);
        connect.setDisable(busy || gateway.isConnected() || gateway.needsReconnect());
        identify.setDisable(busy || !gateway.isConnected() || gateway.needsReconnect() || operations.blocked());
        disconnect.setDisable(busy || !gateway.isConnected() && !gateway.needsReconnect());
        prompt.setBusy(busy);
        history.setEnabled(!busy && authenticatedCard != null && gateway.isReady(), "Cần xác thực thẻ hiện tại.");
        PendingJournal.Loaded loaded = session.pendingJournal();
        String legacyProblem = session.newTransactionProblem();
        boolean unresolved = loaded.present() || legacyProblem != null
                || gateway.reconciliation().state() == Reconciliation.State.UNKNOWN;
        stateOrigin.setText(CardDisplayState.of(gateway.status(), authenticatedCard != null && gateway.isReady(),
                unresolved, needsReread || gateway.reconciliation().receiptUnverified(), busy));
        if (unresolved || busy) {
            quota.setText("Chưa rõ kết quả");
        } else if (needsReread) {
            quota.setText("Cần đọc lại");
        } else if (authenticatedCard != null && gateway.isReady() && gateway.status() != null
                && gateway.status().studentStateAvailable() && authenticatedCard.equals(gateway.status().cardId())) {
            quota.setText(gateway.status().balance() + " lượt · STUDENT");
        }
        pendingNote.setText(loaded.present() ? operations.pendingProblem() != null ? operations.pendingProblem()
                : "Còn PENDING — chỉ đối soát, không thực hiện lại giao dịch."
                : legacyProblem != null ? legacyProblem : operations.blocked() ? "PIN đã khóa — gặp nhân viên." : "");
    }

    void closing() {
        revision++;
        activeProof = null;
        prompt.clear();
        clearView();
        feedback.setText("Đang đóng Kiosk, chờ worker và ngắt kết nối.");
        refresh();
    }

    void log(String text) {
        System.out.println(text);
        if (Platform.isFxApplicationThread()) {
            append(text);
        } else {
            try {
                Platform.runLater(() -> append(text));
            } catch (IllegalStateException stopped) {
                System.out.println("Kiosk đã ngừng hiển thị nhật ký.");
            }
        }
    }

    private void append(String text) {
        logArea.appendText(text + System.lineSeparator());
        if (logArea.getLength() > 200_000) {
            logArea.deleteText(0, 20_000);
        }
    }
}
