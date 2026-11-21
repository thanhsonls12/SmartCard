package vn.labaccess.desktop;

import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

final class TransactionPanel {

    record Session(BooleanSupplier snapshotKnown, BooleanSupplier open,
            StateTransaction stateTransaction, GrantTransaction grantTransaction, Runnable historyInvalidated,
            Runnable historyRemembered, Consumer<Boolean> finished,
            Runnable studentPinChanged, Runnable stateReader) {
    }

    @FunctionalInterface
    interface GrantTransaction {

        CardOperationResult run(int delta, AdminGrantService.PinSource adminPin);
    }

    @FunctionalInterface
    interface StateTransaction {

        CardOperationResult run(CardGateway.TransactionPlan plan,
                CardGateway.CommandSupplier supplier, char[] studentPin)
                throws javax.smartcardio.CardException;
    }

    private final CardGateway gateway;
    private final Consumer<String> log;
    private final Session session;
    private final GrantPanel grants;
    private final VisitPanel visits;
    private final Label why = UiLabels.whyNote();
    private final VBox node;
    private String grantAwaitingReadCardId;

    TransactionPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log, Session session) {
        this.gateway = gateway;
        this.log = log;
        this.session = session;
        visits = new VisitPanel(gateway, runner, log, new VisitPanel.Actions(
                session.snapshotKnown(), session.open(), session.stateTransaction()::run,
                session.historyInvalidated(), session.historyRemembered(), session.finished(),
                session.studentPinChanged(), session.stateReader(), this::clearAdminPinProblem));
        grants = new GrantPanel(runner, log, new GrantPanel.Actions(
                session.open(), gateway::status, session.grantTransaction()::run,
                session.historyInvalidated(), session.historyRemembered(), this::grantFinished,
                visits::prepareGrant, () -> showEffects(gateway.status(), session.snapshotKnown().getAsBoolean())));
        node = UiSection.of("Vận hành thẻ", visits.stateNode(), visits.noteNode(), visits.actionsNode(),
                visits.previewNode(), grants.actionsNode(), grants.previewNode(), why);
    }

    VBox node() {
        return node;
    }

    void fillInputs(char[] studentPin, char[] adminPin, int delta) {
        visits.fillInput(studentPin);
        grants.fillInputs(adminPin, delta);
    }

    char[] studentPinInput() {
        return visits.pinInput();
    }

    void clearInputs() {
        visits.clearInput();
        grants.clearInputs();
    }

    void clearStudentPin() {
        visits.clearPin();
    }

    void clearAdminPin() {
        grants.clearPin();
    }

    boolean requireStudentPin() {
        return visits.requirePin();
    }

    boolean requireAdminPin() {
        return grants.requirePin();
    }

    char[] takeAdminPin() {
        return grants.takePin();
    }

    void focusStudentPin() {
        visits.focusPin();
    }

    void showState(CardStatus status, boolean known) {
        boolean available = known && status != null && status.stateAvailable();
        if (available || (status != null && !status.cardId().equals(grantAwaitingReadCardId))) {
            grantAwaitingReadCardId = null;
        }
        if (visits.showState(status, known, grantAwaitingReadCardId != null)) {
            grants.clearDeltaProblem();
        }
        showEffects(status, known);
    }

    void applyLock(boolean busy, String whyGrant, String whyStart, String whyEnd, String whyRead) {
        grants.applyLock(busy, whyGrant);
        visits.applyLock(whyRead, whyStart, whyEnd);
        UiLabels.setWhy(why, busy ? null : UiLabels.groupedWhy(
                visits.readReason(whyRead), grants.reason(whyGrant),
                visits.startReason(whyStart), visits.endReason(whyEnd)));
    }

    void lockAll() {
        visits.lockAll();
        grants.lockAll();
    }

    void markPrimary(boolean operations) {
        grants.markPrimary(visits.markPrimary(operations));
    }

    String grantState() {
        return grants.state();
    }

    String startState() {
        return visits.startState();
    }

    String endState() {
        return visits.endState();
    }

    boolean pressGrant() {
        return grants.press();
    }

    boolean pressStart() {
        return visits.pressStart();
    }

    boolean pressEnd() {
        return visits.pressEnd();
    }

    private void showEffects(CardStatus status, boolean known) {
        visits.showEffects(status, known);
        grants.showEffects(status, known);
    }

    private void clearAdminPinProblem() {
        grants.clearPinProblem();
    }

    private void grantFinished(GrantPanel.Completion completion) {
        if (completion.ok()) {
            grantAwaitingReadCardId = completion.awaitingReadCardId();
        }
        session.finished().accept(completion.ok());
    }

    void logTransactionState(String label, String displayedVisitId) {
        CardStatus current = gateway.status();
        if (current == null || !current.studentStateAvailable()) {
            log.accept("UI-06 " + label + " | state unavailable | displayedVisitId="
                    + displayedVisitId);
            return;
        }
        log.accept(String.format("UI-06 %s | balance=%d counter=%d active=%s activeVisitId=%s"
                        + " displayedVisitId=%s receipt=%s",
                label, current.balance(), current.counter(), current.hasActiveVisit(),
                current.activeVisitId(), displayedVisitId,
                gateway.lastReceipt() == null ? "none"
                        : Receipt.operationText(gateway.lastReceipt().operation())));
    }

    String probeTransactionGate(String label) {
        CardStatus current = gateway.status();
        if (current == null || !current.studentStateAvailable()) {
            return label + " | khong-co-state-xac-thuc-de-dung-probe";
        }
        CardGateway.TransactionPlan plan = new CardGateway.TransactionPlan(
                new ReceiptExpectation(HexFormat.of().parseHex(current.cardId()),
                        current.counter() + 1, Receipt.OPERATION_GRANT, null),
                5, current.balance() + 5);
        try {
            CardOperationResult result = session.stateTransaction().run(plan, () -> {
                throw new IllegalStateException("probe: khong duoc gui APDU");
            }, null);
            return label + " | " + (result.ok() ? "MO (khong dat ky vong)" : "CHAN: " + result.text());
        } catch (javax.smartcardio.CardException ex) {
            return label + " | loi truyen: " + ex.getMessage();
        }
    }
}
