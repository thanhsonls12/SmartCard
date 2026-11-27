package vn.labaccess.desktop.ui.receipt;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import vn.labaccess.desktop.card.CardOperationResult.SupersededProof;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.ui.foundation.Icons;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.Receipt;

import vn.labaccess.desktop.session.AdminGrantService;
import vn.labaccess.desktop.session.StaffManualReconcile;
import vn.labaccess.desktop.session.SupersededRecovery;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.FxPinSource;
import vn.labaccess.desktop.ui.shared.JournalRecoveryView;
import vn.labaccess.desktop.ui.shared.SupersededDialog;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class ReceiptPanel implements ReceiptReconciliationFlow.View {

    @FunctionalInterface
    public interface PendingReconcile {

        CardOperationResult reconcile(char[] studentPin) throws javax.smartcardio.CardException;
    }

    public record AdminRecovery(BooleanSupplier requirePin, AdminGrantService.PinSource pin,
            Runnable clearPin, Function<AdminGrantService.PinSource, CardOperationResult> reconcile,
            BiFunction<SupersededProof, char[], SupersededRecovery.Confirmation> confirmSuperseded,
            Function<AdminGrantService.PinSource, StaffManualReconcile.Decision> inspectManual,
            BiFunction<StaffManualReconcile.Decision, AdminGrantService.PinSource, CardOperationResult> confirmManual) {
    }

    sealed interface SessionContext permits Session, StaffSession {

        BooleanSupplier open();

        Supplier<PendingJournal.Loaded> pendingJournal();

        java.nio.file.Path journalFile();

        AdminRecovery adminRecovery();

        Runnable historyInvalidated();

        Runnable historyRemembered();

        Runnable historyPublished();

        Runnable buttonsRefreshed();

        Consumer<Boolean> reconcileFinished();
    }

    public record Session(BooleanSupplier open, Supplier<PendingJournal.Loaded> pendingJournal,
            java.nio.file.Path journalFile,
            Supplier<char[]> studentPin, Runnable studentPinCleared,
            PendingReconcile reconcilePending, AdminRecovery adminRecovery, Runnable historyInvalidated,
            Runnable historyRemembered, Runnable historyPublished, Runnable buttonsRefreshed,
            Consumer<Boolean> receiptFinished,
            Consumer<Boolean> reconcileFinished, Runnable pinRequired) implements SessionContext {
    }

    public record StaffSession(BooleanSupplier open, Supplier<PendingJournal.Loaded> pendingJournal,
            java.nio.file.Path journalFile, AdminRecovery adminRecovery, Runnable historyInvalidated,
            Runnable historyRemembered, Runnable historyPublished, Runnable buttonsRefreshed,
            Consumer<Boolean> reconcileFinished) implements SessionContext {

        public StaffSession {
            java.util.Objects.requireNonNull(open);
            java.util.Objects.requireNonNull(pendingJournal);
            java.util.Objects.requireNonNull(journalFile);
            java.util.Objects.requireNonNull(adminRecovery);
            java.util.Objects.requireNonNull(historyInvalidated);
            java.util.Objects.requireNonNull(historyRemembered);
            java.util.Objects.requireNonNull(historyPublished);
            java.util.Objects.requireNonNull(buttonsRefreshed);
            java.util.Objects.requireNonNull(reconcileFinished);
        }
    }

    private final CardGateway gateway;
    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final SessionContext session;
    private final ReceiptReconciliationFlow flow;

    private final Label valueVisitId = UiLabels.value();
    private final Label valueReconcile = UiLabels.chip();
    private final ReceiptView receiptView = new ReceiptView();
    private boolean canRead;
    private final Button btnReconcile = new Button("Đối soát giao dịch", Icons.reconcile());
    private final Label why = UiLabels.whyNote();
    private String pinNote;
    private final VBox node;

    public ReceiptPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            Session session) {
        this(gateway, runner, log, (SessionContext) session);
    }

    public ReceiptPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            StaffSession session) {
        this(gateway, runner, log, (SessionContext) session);
    }

    private ReceiptPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log,
            SessionContext session) {
        this.gateway = gateway;
        this.runner = runner;
        this.log = log;
        this.session = session;
        this.flow = new ReceiptReconciliationFlow(gateway, runner, log, session, this);

        valueVisitId.setText("Chưa đọc trạng thái ca");

        btnReconcile.getStyleClass().add("la-action");
        btnReconcile.setOnAction(e -> {
            PendingJournal.Loaded loaded = session.pendingJournal().get();
            if (flow.rejectStaffPending(loaded) || !session.open().getAsBoolean()) {
                return;
            }
            if (JournalRecoveryView.needed(loaded)) {
                JournalRecoveryView.show(btnReconcile, loaded, session.journalFile());
            } else {
                flow.reconcile();
            }
        });

        VBox visitIdField = UiRows.field("Mã ca đang mở", valueVisitId);
        FlowPane controls = UiRows.wrapping(visitIdField,
                UiLabels.copyButton(valueVisitId, "mã ca"));
        TitledPane details = new TitledPane("Chi tiết biên nhận",
                new VBox(UiScale.SPACE_2, controls, receiptView.detailsNode()));
        details.setExpanded(false);
        details.getStyleClass().add("la-receipt-details");
        node = UiSection.of("Biên nhận gần nhất", receiptView.node(), why, details);
    }

    public VBox node() {
        return node;
    }

    public Node reconcileAction() {
        return btnReconcile;
    }

    public Node reconcileStatus() {
        return valueReconcile;
    }

    public void showReconcile() {
        boolean known = gateway.reconciliation().transactionAllowed();
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        boolean recovery = JournalRecoveryView.needed(loaded);
        boolean pending = loaded.present() && loaded.valid()
                && loaded.status() == PendingJournal.Status.PENDING;
        if (recovery) {
            valueReconcile.setText("Cần khôi phục");
        } else if (pending || !known) {
            valueReconcile.setText(gateway.reconciliation().receiptUnverified()
                    ? "Cần xác minh biên nhận" : "Cần đối soát");
        } else {
            valueReconcile.setText("Không có giao dịch chờ");
        }
        UiLabels.Tone tone = recovery || !known || pending
                ? UiLabels.Tone.ERROR : UiLabels.Tone.NEUTRAL;
        UiLabels.applyChip(valueReconcile, tone);
    }

    public String displayedVisitId() {
        return valueVisitId.getText();
    }

    public String reconcileState() {
        return btnReconcile.isDisable() ? "khoa" : "mo";
    }

    public void applyLock(boolean busy, String whyReadReceipt, String whyReconcile) {
        canRead = session instanceof Session && !busy && whyReadReceipt == null;
        ReceiptReconciliationFlow.CardMismatch mismatch = flow.pendingCardMismatch();
        String operationReason = flow.pendingOperationReason();
        if (mismatch != null) {
            whyReconcile = mismatch.guidance();
        } else if (operationReason != null) {
            whyReconcile = operationReason;
        }
        boolean recovery = JournalRecoveryView.needed(session.pendingJournal().get());
        btnReconcile.setText(recovery ? "Hướng dẫn khôi phục" : "Đối soát giao dịch");
        btnReconcile.setTooltip(new javafx.scene.control.Tooltip(recovery
                ? "Xem nguyên nhân và các bước xử lý nhật ký; không thay đổi dữ liệu"
                : whyReconcile == null ? "Đọc biên nhận để đối soát giao dịch" : whyReconcile));
        btnReconcile.setDisable(busy || !session.open().getAsBoolean() || !recovery && whyReconcile != null);
        UiLabels.setWhy(why, mismatch != null ? mismatch.guidance()
                : operationReason != null ? operationReason : pinNote);
    }

    public ReceiptReconciliationFlow.CardMismatch pendingCardMismatch() {
        return flow.pendingCardMismatch();
    }

    public String pendingOperationReason() {
        return flow.pendingOperationReason();
    }

    public boolean reconcileEnabled() {
        return !btnReconcile.isDisable();
    }

    public void setReconcilePrimary(boolean primary) {
        UiLabels.markPrimary(btnReconcile, primary && !btnReconcile.isDisable());
    }

    public void lockAll() {
        canRead = false;
        btnReconcile.setDisable(true);
    }

    public void clear() {
        flow.cardSessionCleared();
        valueVisitId.setText("Chưa đọc trạng thái ca");
        receiptView.clear("Chưa đọc biên nhận từ thẻ");
    }

    private void showPinMissing(Session student) {
        pinNote = "Nhập PIN sinh viên " + PersonalizeRequest.STUDENT_PIN_LENGTH
                + " chữ số trong khối Vận hành thẻ.";
        student.pinRequired().run();
        UiLabels.setWhy(why, pinNote);
    }

    @Override
    public void clearPinNote() {
        if (pinNote == null) {
            return;
        }
        pinNote = null;
        session.buttonsRefreshed().run();
    }

    public void studentPinChanged() {
        clearPinNote();
    }

    private boolean pinEntered(Session student) {
        char[] studentPin = student.studentPin().get();
        boolean ok = PinFormat.isDigits(studentPin, PersonalizeRequest.STUDENT_PIN_LENGTH);
        if (studentPin != null) {
            java.util.Arrays.fill(studentPin, '\0');
        }
        if (!ok) {
            showPinMissing(student);
        }
        return ok;
    }

    @Override
    public boolean studentPinEntered() {
        return session instanceof Session student && pinEntered(student);
    }

    @Override
    public void setPinNote(String note) {
        pinNote = note;
    }

    @Override
    public void showPinNote() {
        UiLabels.setWhy(why, pinNote);
    }

    @Override
    public void blockReconcile(String reason) {
        btnReconcile.setDisable(true);
        UiLabels.setWhy(why, reason);
    }

    @Override
    public void defer(Runnable action) {
        Platform.runLater(action);
    }

    @Override
    public void showReceipt() {
        boolean staff = session instanceof StaffSession;
        Receipt receipt = staff ? gateway.verifiedAdminReceipt() : gateway.lastReceipt();
        CardStatus status = gateway.status();
        boolean authenticated = status != null && (status.studentStateAvailable()
                || session instanceof StaffSession && status.stateSource() == CardStatus.StateSource.ADMIN);
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        boolean ledgerPending = loaded.present()
                && loaded.status() == PendingJournal.Status.PENDING;
        if (receipt == null) {
            receiptView.clear(authenticated ? "Chưa có biên nhận" : "Chưa đọc biên nhận từ thẻ");
        } else {
            receiptView.show(receipt, staff || gateway.lastReceiptSignatureChecked(),
                    staff || gateway.lastReceiptSignatureOk(), ledgerPending);
        }
        valueVisitId.setText(authenticated && status.hasActiveVisit()
                ? status.activeVisitId()
                : authenticated ? "Không có ca đang mở" : "Chưa đọc trạng thái ca");
        showReconcile();
        if (!gateway.reconciliation().detail().isEmpty()) {
            log.accept("Đối soát: " + gateway.reconciliation().detail());
        }
    }

    public boolean pressReadReceipt() {
        if (flow.rejectStaffPending(session.pendingJournal().get())
                || !(session instanceof Session student) || !canRead) {
            return false;
        }
        readReceipt(student);
        return true;
    }

    public boolean pressReconcile() {
        if (flow.rejectStaffPending(session.pendingJournal().get()) || !session.open().getAsBoolean()) {
            return false;
        }
        return press(btnReconcile);
    }

    public void offerPendingReconciliation() {
        flow.offerPendingReconciliation();
    }

    private void readReceipt(Session student) {
        if (!session.open().getAsBoolean()) {
            return;
        }
        PendingJournal.Loaded loaded = session.pendingJournal().get();
        if (loaded.present()) {
            log.accept("[FAIL] GET_LAST_RECEIPT: còn journal "
                    + (loaded.valid() ? loaded.status() : "không hợp lệ")
                    + ", phải đối soát trước");
            session.buttonsRefreshed().run();
            return;
        }
        if (!pinEntered(student)) {
            return;
        }
        clearPinNote();
        char[] studentPin = student.studentPin().get();
        student.studentPinCleared().run();
        runner.run("VERIFY + GET_LAST_RECEIPT + GET_STATUS state", () -> {
            try {
                return gateway.readOperatorCard(studentPin);
            } finally {
                java.util.Arrays.fill(studentPin, '\0');
            }
        }, ok -> {
            student.receiptFinished().accept(ok);
            showReceipt();
        });
    }

    @Override
    public ReceiptReconciliationFlow.PinPrompt requestManualPin(StaffManualReconcile.Decision decision) {
        PasswordField pin = confirmManual(decision);
        return pin == null
                ? null
                : new ReceiptReconciliationFlow.PinPrompt(() -> copyConfirmPin(pin), pin::clear);
    }

    private static char[] copyConfirmPin(PasswordField pin) {
        try {
            return FxPinSource.take(() -> takeConfirmPin(pin));
        } catch (RuntimeException failure) {
            Platform.runLater(pin::clear);
            throw failure;
        }
    }

    private static char[] takeConfirmPin(PasswordField pin) {
        char[] copy = pin.getText().toCharArray();
        pin.clear();
        return copy;
    }

    private PasswordField confirmManual(StaffManualReconcile.Decision decision) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Xác nhận ghi sổ");
        dialog.setHeaderText("Ghi receipt này vào sổ giao dịch?");
        PasswordField pin = new PasswordField();
        pin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        Label evidence = new Label(decision.detail() + "\nKhông gửi lệnh đổi trạng thái thẻ.");
        evidence.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(UiScale.SPACE_2, evidence,
                UiRows.field("PIN quản trị", pin)));
        ButtonType confirm = new ButtonType("Ghi sổ", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(cancel, confirm);
        UiLabels.attachDialog(dialog, node);
        try {
            if (dialog.showAndWait().filter(confirm::equals).isEmpty()) {
                log.accept("[INFO] Đối soát tay: nhân viên hủy, không ghi sổ");
                pin.clear();
                return null;
            }
            char[] probe = pin.getText().toCharArray();
            boolean missing = AdminGrantService.pinProblem(probe) != null;
            Arrays.fill(probe, '\0');
            if (missing) {
                pin.clear();
                log.accept("[INFO] Đối soát tay: thiếu PIN quản trị, không ghi sổ");
                return null;
            }
            return pin;
        } catch (RuntimeException failure) {
            pin.clear();
            throw failure;
        }
    }

    @Override
    public char[] requestSupersededPin(SupersededProof proof, int adminPinRetries) {
        return SupersededDialog.confirm(btnReconcile, proof, adminPinRetries);
    }

    @Override
    public boolean confirmReconcile(PendingJournal.Entry entry) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận đối soát");
        confirmation.setHeaderText("Đọc biên nhận để xử lý nhật ký chờ gửi?");
        confirmation.setContentText(String.format(
                "Mã thẻ: %s%nSố giao dịch: %d%nThao tác: %s%n"
                        + "Hệ thống dùng PIN %s để đọc bằng chứng đối soát."
                        + " Không thực hiện lại giao dịch.",
                entry.cardId(), entry.expectedCounter(), Receipt.operationText(entry.operation()),
                entry.operation() == Receipt.OPERATION_GRANT ? "quản trị" : "sinh viên"));
        return UiLabels.confirm(confirmation, node, "Đối soát giao dịch");
    }

    private static boolean press(Button button) {
        if (button.isDisabled()) {
            return false;
        }
        button.fire();
        return true;
    }
}
