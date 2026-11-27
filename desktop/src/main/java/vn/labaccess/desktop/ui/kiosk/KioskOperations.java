package vn.labaccess.desktop.ui.kiosk;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.smartcardio.CardException;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardLifecycle;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.LedgerAlignment;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Profile;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptExpectation;
import vn.labaccess.desktop.card.Reconciliation;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.CardSession;
import vn.labaccess.desktop.session.ConfirmedOperationRefresh;
import vn.labaccess.desktop.session.SupersededRecovery;

public final class KioskOperations {

    public enum Action {
        START("Vào lab"), END("Kết thúc ca"), INFO("Xem thông tin"), CHANGE("Đổi PIN"), RECONCILE("Đối soát giao dịch");

        private final String label;

        Action(String label) { this.label = label; }

        public String label() { return label; }
    }

    public record Outcome(Action action, CardOperationResult result, int pinSw, CardStatus status,
            Profile profile, byte[] photo, boolean photoAbsent) {
    }

    private final CardSession session;
    private String blockedCard;

    public KioskOperations(CardSession session) {
        this.session = session;
    }

    boolean blocked() {
        CardStatus current = session.gateway().status();
        return current != null && current.cardId().equals(blockedCard);
    }

    public void resetSession() {
        blockedCard = null;
        clearStudentData();
    }

    void clearStudentData() {
        session.invalidateSnapshot();
        session.gateway().clearAuthenticatedStatus();
        session.gateway().clearCardIdentity();
    }

    String pendingProblem() {
        String restriction = session.recoveryProblem();
        if (restriction != null) {
            return restriction;
        }
        PendingJournal.Loaded loaded = session.pendingJournal();
        if (!loaded.present()) {
            return null;
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            return loaded.recoverySummary();
        }
        if (loaded.entry().operation() == Receipt.OPERATION_GRANT) {
            return "Nhật ký cấp lượt cần được đối soát tại Staff. Không thực hiện lại giao dịch.";
        }
        CardStatus current = session.gateway().isReady() ? session.gateway().status() : null;
        if (current == null || !loaded.entry().cardId().equalsIgnoreCase(current.cardId())) {
            return "Kết nối và nhận diện đúng thẻ " + loaded.entry().cardId() + " để đối soát.";
        }
        return null;
    }

    public Outcome execute(KioskPinPrompt.Request request) {
        CardGateway gateway = session.gateway();
        gateway.resetPinResult();
        try (request) {
            String problem = guard(request.action());
            if (problem != null) {
                clearStudentData();
                return outcome(request.action(), CardOperationResult.fail(problem), -1);
            }
            String selected = gateway.status().cardId();
            gateway.clearCardIdentity();
            session.invalidateSnapshot();
            CardOperationResult result = switch (request.action()) {
                case START, END -> visit(request.action(), selected, request.pin());
                case INFO -> info(selected, request.pin());
                case CHANGE -> change(selected, request.pin(), request.newPin());
                case RECONCILE -> recover(request.pin());
            };
            int sw = gateway.lastPinSw();
            if (sw == Sw.AUTH_BLOCKED) {
                blockedCard = selected;
            }
            CardStatus current = gateway.isReady() ? gateway.status() : null;
            if (current == null || !selected.equals(current.cardId())) {
                if (!result.ok() || result.warning() == null) {
                    result = CardOperationResult.fail(
                            "Phiên thẻ đã đổi hoặc mất kết nối. Không thực hiện lại giao dịch.");
                }
            }
            if ((!result.ok() && result.superseded() == null) || request.action() == Action.CHANGE) {
                clearStudentData();
            }
            return outcome(request.action(), result, sw);
        } catch (CardException | RuntimeException failure) {
            clearStudentData();
            return outcome(request.action(), CardOperationResult.fail(
                    "Thao tác bị gián đoạn. Không thử lại giao dịch; vui lòng gặp nhân viên."), -1);
        }
    }

    private String guard(Action action) {
        CardGateway gateway = session.gateway();
        if (!gateway.isReady() || gateway.status() == null || gateway.status().lifecycle() != CardLifecycle.ISSUED) {
            return "Cần kết nối và nhận diện thẻ đã phát hành.";
        }
        if (blocked()) {
            return "PIN sinh viên đã khóa. Gặp nhân viên để mở khóa.";
        }
        PendingJournal.Loaded loaded = session.pendingJournal();
        if (action == Action.RECONCILE) {
            if (!loaded.present()) {
                return "Không có giao dịch nào cần đối soát.";
            }
            return pendingProblem();
        }
        String restriction = session.newTransactionProblem();
        if (restriction != null) {
            return restriction;
        }
        if (loaded.present()) {
            return "Giao dịch trước chưa hoàn tất. Đối soát trước khi làm giao dịch mới.";
        }
        return permitsReceiptState(action) ? null : "Cần xác minh hoặc đối soát thẻ trước.";
    }

    boolean permitsReceiptState(Action action) {
        Reconciliation state = session.gateway().reconciliation();
        return state.transactionAllowed() || (action == Action.INFO && state.receiptUnverified());
    }

    private CardOperationResult info(String selected, char[] pin) throws CardException {
        CardGateway gateway = session.gateway();
        CardOperationResult read = gateway.readOperatorCard(pin);
        if (!read.ok()) {
            return read;
        }
        CardStatus state = gateway.status();
        if (state == null || !state.studentStateAvailable() || !selected.equals(state.cardId())) {
            return CardOperationResult.fail("Thẻ đã đổi sau khi xác thực. Số lượt chưa được xác minh.");
        }
        CardOperationResult ledger = checkLedger(state);
        return ledger == null ? read
                : CardOperationResult.fail("Số lượt chưa được xác minh. " + ledger.text());
    }

    private CardOperationResult visit(Action action, String selected, char[] pin) throws CardException {
        CardGateway gateway = session.gateway();
        CardOperationResult read = gateway.readAuthenticatedStatus(pin);
        if (!read.ok()) {
            return read;
        }
        CardStatus state = gateway.status();
        if (state == null || !state.studentStateAvailable() || !selected.equals(state.cardId())) {
            return CardOperationResult.fail(
                    "Thẻ đã đổi sau khi xác thực. Chưa thực hiện giao dịch.");
        }
        CardOperationResult ledger = checkLedger(state);
        if (ledger != null) {
            return ledger;
        }
        boolean start = action == Action.START;
        if (state.counter() >= Receipt.COUNTER_MASK) {
            return CardOperationResult.fail("Số giao dịch đã đạt giới hạn.");
        }
        if (start && state.hasActiveVisit()) {
            return CardOperationResult.fail("Đang có ca mở. Chọn Kết thúc ca.");
        }
        if (start && state.balance() <= 0) {
            return CardOperationResult.fail("Đã hết lượt. Gặp nhân viên để cấp lượt.");
        }
        if (!start && !state.hasActiveVisit()) {
            return CardOperationResult.fail("Không có ca đang mở để kết thúc.");
        }
        byte[] visit = start ? newVisitId() : HexFormat.of().parseHex(state.activeVisitId());
        CardGateway.TransactionPlan plan = new CardGateway.TransactionPlan(
                new ReceiptExpectation(HexFormat.of().parseHex(selected), state.counter() + 1,
                        start ? Receipt.OPERATION_START : Receipt.OPERATION_END, visit),
                start ? -1 : 0, start ? state.balance() - 1 : state.balance());
        CardOperationResult result = session.runStateTransaction(plan,
                gateway.visitCommand((byte) (start ? 0x51 : 0x52), visit), pin);
        return result.ok() ? ConfirmedOperationRefresh.read(gateway, result, pin) : result;
    }

    private CardOperationResult checkLedger(CardStatus state) {
        try {
            BackendClient.LedgerSnapshot ledger = session.backend().ledgerOf(state.cardId());
            if (!LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, ledger.cardId(),
                    ledger.lastCounter(), ledger.lastBalance(), ledger.activeVisitId())) {
                return CardOperationResult.fail(LedgerAlignment.mismatch(state, ledger.cardId(),
                        ledger.lastCounter(), ledger.lastBalance(), ledger.activeVisitId()));
            }
            return null;
        } catch (BackendClient.Failure failure) {
            return CardOperationResult.fail("Không kết nối được máy chủ."
                    + " Chưa thực hiện giao dịch; vui lòng thử lại sau.");
        }
    }

    private CardOperationResult change(String selected, char[] pin, char[] newPin) throws CardException {
        CardGateway gateway = session.gateway();
        CardOperationResult read = gateway.readAuthenticatedStatus(pin);
        if (!read.ok()) {
            return read;
        }
        if (!selected.equals(gateway.status().cardId())) {
            return CardOperationResult.fail("Thẻ đã đổi; không gửi lệnh đổi PIN.");
        }
        if (session.pendingJournal().present()) {
            return CardOperationResult.fail("Có giao dịch chưa hoàn tất; cần đối soát trước.");
        }
        return gateway.changeStudentPin(pin, newPin);
    }

    private CardOperationResult recover(char[] pin) throws CardException {
        CardOperationResult result = session.reconcilePending(pin);
        return result.ok() ? ConfirmedOperationRefresh.read(session.gateway(), result, pin) : result;
    }

    public boolean canConfirm(CardOperationResult.SupersededProof proof) {
        if (proof == null || pendingProblem() != null || !session.pendingJournal().present()) {
            return false;
        }
        PendingJournal.Loaded loaded = session.pendingJournal();
        return loaded.valid() && loaded.status() == PendingJournal.Status.PENDING
                && loaded.entry().operation() != Receipt.OPERATION_GRANT
                && session.gateway().isReady() && proof.state().equals(session.gateway().status())
                && proof.fromStudent() && journalMatches(proof);
    }

    private boolean journalMatches(CardOperationResult.SupersededProof proof) {
        try {
            return java.nio.file.Files.readString(session.journalFile()).equals(proof.journalContents());
        } catch (java.io.IOException failure) {
            return false;
        }
    }

    public SupersededRecovery.Confirmation confirm(CardOperationResult.SupersededProof proof, char[] pin) {
        try {
            if (!canConfirm(proof)) {
                return SupersededRecovery.Confirmation.withoutAdminResponse(
                        CardOperationResult.fail("Dữ liệu thẻ đã thay đổi. Giao dịch được giữ;"
                                + " đọc lại thẻ bằng PIN sinh viên."));
            }
            return session.confirmSuperseded(proof, pin);
        } finally {
            if (pin != null) {
                Arrays.fill(pin, '\0');
            }
        }
    }

    private Outcome outcome(Action action, CardOperationResult result, int sw) {
        CardGateway gateway = session.gateway();
        CardStatus status = gateway.isReady() && result.ok() && gateway.status() != null
                && gateway.status().studentStateAvailable() ? gateway.status() : null;
        return new Outcome(action, result, sw, status, status == null ? null : gateway.lastProfile(),
                status == null ? null : gateway.lastPhoto(), status != null && gateway.photoAbsent());
    }

    private static byte[] newVisitId() {
        SecureRandom random = new SecureRandom();
        byte[] value = new byte[Receipt.VISIT_ID_LENGTH];
        do {
            random.nextBytes(value);
        } while (allZero(value));
        return value;
    }

    private static boolean allZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }
}
