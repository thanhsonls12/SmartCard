package vn.labaccess.desktop.ui.transaction;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.FlowPane;

import vn.labaccess.desktop.validation.PinFormat;

import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.Receipt;

import vn.labaccess.desktop.session.AdminGrantService;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.FxPinSource;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class GrantPanel {

    private static final int DELTA_MIN = 1;
    private static final int DELTA_MAX = Receipt.MAX_BALANCE;
    private static final int DELTA_DEFAULT = 5;
    private static final String DELTA_PROBLEM = "Số lượt cần cấp phải từ "
            + DELTA_MIN + " đến " + DELTA_MAX + ".";

    public record Completion(boolean ok, String awaitingReadCardId) {
    }

    public record Actions(BooleanSupplier open, Supplier<CardStatus> status, Transaction transaction,
            Runnable historyInvalidated, Runnable historyRemembered, Consumer<Completion> finished,
            Runnable preparing, Runnable deltaChanged) {
    }

    @FunctionalInterface
    public interface Transaction {

        CardOperationResult run(int delta, AdminGrantService.PinSource adminPin);
    }

    private final CardOpRunner runner;
    private final Consumer<String> log;
    private final Actions actions;
    private final Spinner<Integer> fieldDelta = new Spinner<>(DELTA_MIN, DELTA_MAX, DELTA_DEFAULT);
    private final PasswordField fieldAdminPin = new PasswordField();
    private final Label adminPinError = UiLabels.fieldError();
    private final Label deltaError = UiLabels.fieldError();
    private final Button btnGrant = new Button("Cấp lượt");
    private final Label grantPreview = UiLabels.caption("");
    private final FlowPane node;

    public GrantPanel(CardOpRunner runner, Consumer<String> log, Actions actions) {
        this.runner = runner;
        this.log = log;
        this.actions = actions;
        fieldDelta.setEditable(true);
        fieldDelta.getEditor().setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d{0,4}") ? change : null));
        fieldDelta.getEditor().setOnAction(event -> commitDelta());
        fieldDelta.focusedProperty().addListener((observable, was, now) -> {
            if (!now) {
                commitDelta();
            }
        });
        fieldAdminPin.setPrefColumnCount(10);
        fieldAdminPin.setPromptText(PersonalizeRequest.ADMIN_PIN_LENGTH + " chữ số");
        fieldAdminPin.textProperty().addListener(
                (observable, was, now) -> clearPinProblem());
        fieldDelta.getEditor().textProperty().addListener((observable, was, now) -> {
            clearDeltaProblem();
            actions.deltaChanged().run();
        });
        btnGrant.getStyleClass().add("la-action");
        UiLabels.apduHint(btnGrant,
                "GRANT_QUOTA\nCần nhận diện thẻ, không có biên nhận chờ đối soát");
        btnGrant.setOnAction(event -> grant());
        grantPreview.setId("grant-preview");
        grantPreview.setWrapText(true);
        node = UiRows.wrapping(
                UiRows.field("PIN quản trị · cấp lượt, đối soát cấp lượt", fieldAdminPin, adminPinError),
                UiRows.field("Số lượt cần cấp", fieldDelta, deltaError), btnGrant);
    }

    public Node actionsNode() {
        return node;
    }

    public Node previewNode() {
        return grantPreview;
    }

    public void fillInputs(char[] adminPin, int delta) {
        fieldAdminPin.setText(adminPin == null ? "" : new String(adminPin));
        fieldDelta.getValueFactory().setValue(delta);
    }

    public void clearInputs() {
        fieldAdminPin.clear();
        clearPinProblem();
        clearDeltaProblem();
    }

    public void clearPin() {
        fieldAdminPin.clear();
    }

    void clearPinProblem() {
        UiLabels.clearFieldError(adminPinError);
    }

    void clearDeltaProblem() {
        UiLabels.clearFieldError(deltaError);
    }

    public boolean requirePin() {
        if (PinFormat.isDigits(fieldAdminPin.getText(), PersonalizeRequest.ADMIN_PIN_LENGTH)) {
            return true;
        }
        UiLabels.setFieldError(adminPinError, PersonalizeRequest.adminPinProblem() + ".");
        UiLabels.inputProblem(log, fieldAdminPin, PersonalizeRequest.adminPinProblem() + ".");
        return false;
    }

    public char[] takePin() {
        if (Platform.isFxApplicationThread()) {
            char[] pin = fieldAdminPin.getText().toCharArray();
            fieldAdminPin.clear();
            return pin;
        }
        char[] pin = FxPinSource.take(() -> fieldAdminPin.getText().toCharArray());
        Platform.runLater(fieldAdminPin::clear);
        return pin;
    }

    public void showEffects(CardStatus status, boolean known) {
        boolean available = known && status != null && status.stateAvailable();
        String entered = fieldDelta.getEditor().getText();
        if (!available || entered == null || !entered.matches("\\d{1,4}")
                || Integer.parseInt(entered) < DELTA_MIN) {
            grantPreview.setText(available ? "Nhập số lượt muốn cấp."
                    : "Cấp lượt sẽ đọc trạng thái bằng PIN quản trị trước khi thực hiện.");
            return;
        }
        int delta = Integer.parseInt(entered);
        int after = status.balance() + delta;
        grantPreview.setText(after > Receipt.MAX_BALANCE
                ? "Chỉ có thể cấp thêm " + (Receipt.MAX_BALANCE - status.balance()) + " lượt."
                : "Cấp thêm " + delta + " → còn " + after + " lượt.");
    }

    public void applyLock(boolean busy, String reason) {
        fieldAdminPin.setDisable(busy);
        btnGrant.setDisable(reason != null);
    }

    UiLabels.ActionReason reason(String reason) {
        return UiLabels.actionReason(btnGrant, UiLabels.panelNote(reason));
    }

    void lockAll() {
        btnGrant.setDisable(true);
    }

    public void markPrimary(boolean primary) {
        UiLabels.markPrimary(btnGrant, primary && !btnGrant.isDisable());
    }

    String state() {
        return btnGrant.isDisable() ? "khoa" : "mo";
    }

    public boolean press() {
        if (btnGrant.isDisabled()) {
            return false;
        }
        btnGrant.fire();
        return true;
    }

    private boolean commitDelta() {
        String text = fieldDelta.getEditor().getText();
        boolean numeric = text.matches("\\d{1,4}");
        int typed = numeric ? Integer.parseInt(text) : 0;
        if (!numeric || typed < DELTA_MIN || typed > DELTA_MAX) {
            UiLabels.setFieldError(deltaError, DELTA_PROBLEM);
            return false;
        }
        clearDeltaProblem();
        fieldDelta.getValueFactory().setValue(typed);
        fieldDelta.getEditor().setText(String.valueOf(typed));
        return true;
    }

    private void grant() {
        boolean deltaOk = commitDelta();
        boolean pinOk = PinFormat.isDigits(fieldAdminPin.getText(), PersonalizeRequest.ADMIN_PIN_LENGTH);
        UiLabels.setFieldError(adminPinError, pinOk ? null : PersonalizeRequest.adminPinProblem() + ".");
        if (!deltaOk || !pinOk) {
            UiLabels.inputProblem(log, deltaOk ? fieldAdminPin : fieldDelta.getEditor(),
                    deltaOk ? PersonalizeRequest.adminPinProblem() : DELTA_PROBLEM);
            return;
        }
        if (!actions.open().getAsBoolean()) {
            log.accept("[FAIL] GRANT_QUOTA: đang xử lý thao tác khác hoặc đang đóng");
            return;
        }
        int delta = fieldDelta.getValue();
        actions.preparing().run();
        fieldAdminPin.setDisable(true);
        CardStatus selected = actions.status().get();
        AtomicBoolean needsReread = new AtomicBoolean();
        runner.run("GRANT_QUOTA", () -> {
            actions.historyInvalidated().run();
            CardOperationResult result = actions.transaction().run(delta, this::takePin);
            if (result.ok()) {
                needsReread.set(result.warning() != null);
                actions.historyRemembered().run();
            }
            return result;
        }, ok -> {
            String awaitingRead = ok && needsReread.get() ? selected.cardId() : null;
            fieldAdminPin.clear();
            fieldAdminPin.setDisable(false);
            actions.finished().accept(new Completion(ok, awaitingRead));
        });
    }

}
