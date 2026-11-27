package vn.labaccess.desktop.session;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.OptionalInt;

import javax.smartcardio.CardException;

import vn.labaccess.desktop.card.CardOperationResult.SupersededProof;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptCodec;
import vn.labaccess.desktop.card.ReceiptVerifier;
import vn.labaccess.desktop.card.Reconciliation;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

public final class SupersededRecovery {

    public record Confirmation(CardOperationResult result, OptionalInt adminStatusWord) {
        public static Confirmation withoutAdminResponse(CardOperationResult result) {
            return new Confirmation(result, OptionalInt.empty());
        }
    }

    private final CardGateway gateway;
    private final PendingJournal journal;
    private final BackendClient backend;

    public SupersededRecovery(CardGateway gateway, PendingJournal journal, BackendClient backend) {
        this.gateway = gateway;
        this.journal = journal;
        this.backend = backend;
    }

    public CardOperationResult studentEvidence(PendingJournal.Entry entry, Receipt receipt, char[] studentPin) {
        try {
            CardOperationResult read;
            try {
                read = gateway.readAuthenticatedStatus(studentPin);
            } finally {
                Arrays.fill(studentPin, '\0');
            }
            if (!read.ok()) {
                return unknown(read.text());
            }
            BackendClient.EnrolledCard enrollment = backend.enrolledCard(entry.cardId());
            ReceiptVerifier.ParsedKey key = ReceiptVerifier.parsePublicKey(enrollment.publicKey());
            if (!key.ok() || !ReceiptVerifier.verifyReceipt(key.key(), receipt).ok()) {
                return unknown("Chữ ký receipt cuối không khớp khóa đã đăng ký");
            }
            return verifiedEvidence(entry, receipt, gateway.status());
        } catch (BackendClient.Failure failure) {
            return backendFailure(failure);
        } catch (CardException | RuntimeException failure) {
            return unknown("Không đọc được bằng chứng Student (" + failure.getClass().getSimpleName() + ")");
        }
    }

    CardOperationResult verifiedEvidence(PendingJournal.Entry entry, Receipt receipt, CardStatus state) {
        if (!ReceiptCodec.differentReceiptAtOrAfterIntent(entry, receipt) || state == null || !state.stateAvailable()
                || !entry.cardId().equalsIgnoreCase(state.cardId())
                || !entry.cardId().equalsIgnoreCase(receipt.cardIdHex())
                || state.counter() != receipt.counterAfter() || state.balance() != receipt.balanceAfter()
                || !receiptMatchesVisit(receipt, state)) {
            return unknown("Receipt cuối và trạng thái thẻ chưa đủ bằng chứng");
        }
        try {
            String journalContents = Files.readString(journal.file());
            PendingJournal.Loaded loaded = PendingJournal.parse(journalContents.strip());
            if (loaded.status() != PendingJournal.Status.PENDING
                    || !PendingJournal.formatLine(loaded.entry(), PendingJournal.Status.PENDING)
                            .equals(PendingJournal.formatLine(entry, PendingJournal.Status.PENDING))) {
                return unknown("Journal đã đổi trong lúc đọc bằng chứng");
            }
            if (!matchesLedger(state, backend.ledgerOf(entry.cardId()))) {
                return unknown("Ledger hiện tại khác trạng thái thẻ");
            }
            byte[] occupied = backend.storedReceiptPayload(entry.cardId(), entry.expectedCounter());
            if (Arrays.equals(occupied, ReceiptCodec.intentPayload(entry))) {
                return unknown("Payload ở counter đang chờ trùng intent");
            }
            if (receipt.counterAfter() == entry.expectedCounter() && !Arrays.equals(occupied, receipt.payload())) {
                return unknown("Payload ledger ở counter đang chờ khác receipt đã ký của thẻ");
            }
            SupersededProof proof = new SupersededProof(journalContents, state, entry.expectedCounter(),
                    receipt.payloadHex(), CardGateway.hex(occupied));
            gateway.markTransactionUnknown("Cần nhân viên xác nhận giao dịch khác đã chiếm counter");
            return CardOperationResult.awaitingConfirmation(proof);
        } catch (BackendClient.Failure failure) {
            return backendFailure(failure);
        } catch (IOException failure) {
            return unknown("Không đọc được journal chờ đối soát");
        }
    }

    public Confirmation confirm(SupersededProof proof, char[] adminPin) {
        Confirmation authorization;
        try {
            if (!journalMatches(proof)) {
                return Confirmation.withoutAdminResponse(unknown("Journal đã đổi trước khi xác nhận"));
            }
            if (!gateway.isReady() || !proof.state().equals(gateway.status())) {
                return Confirmation.withoutAdminResponse(unknown("Phiên thẻ đã đổi trước khi xác nhận"));
            }
            authorization = authenticateOperator(proof, adminPin);
        } catch (CardException | IOException | RuntimeException failure) {
            return Confirmation.withoutAdminResponse(unknown("Xác nhận bị gián đoạn ("
                    + failure.getClass().getSimpleName() + ")"));
        } finally {
            if (adminPin != null) {
                Arrays.fill(adminPin, '\0');
            }
            if (proof.fromStudent()) {
                gateway.clearAuthenticatedStatus();
            }
        }
        CardOperationResult result = authorization.result().ok()
                ? closeConfirmed(proof, authorization.result().text()) : unknown(authorization.result().text());
        return new Confirmation(result, authorization.adminStatusWord());
    }

    private CardOperationResult closeConfirmed(SupersededProof proof, String authAudit) {
        String audit = authAudit + " | ";
        try {
            String cardId = proof.state().cardId();
            if (!matchesLedger(proof.state(), backend.ledgerOf(cardId))
                    || !CardGateway.hex(backend.storedReceiptPayload(cardId, proof.expectedCounter()))
                            .equals(proof.occupiedPayload())) {
                return unknown(audit + "Bằng chứng ledger đã đổi trước khi đóng journal");
            }
            if (!journalMatches(proof)) {
                return unknown(audit + "Journal đã đổi trước khi đóng");
            }
            PendingJournal.Entry entry = PendingJournal.parse(proof.journalContents().strip()).entry();
            if (backend.hasStaffSession()) {
                String operation = backend.beginAudit("RECONCILE", cardId, null, entry.auditOperationId());
                backend.finishAudit(operation, "CLIENT_SUPERSEDED", cardId);
            }
            CardOperationResult closed = gateway.finishJournal(journal, entry, Reconciliation.Verdict.SUPERSEDED,
                    proof.describe() + " | " + audit + "Ledger khớp thẻ"
                            + " | Nhân viên xác nhận đóng intent đã bị chiếm counter");
            if (closed.ok()) {
                gateway.reconciliation().markKnown("Nhân viên đã xác nhận SUPERSEDED");
            }
            return closed;
        } catch (BackendClient.Failure failure) {
            return unknown(audit + backendProblem(failure));
        } catch (IOException | RuntimeException failure) {
            return unknown(audit + "Xác nhận bị gián đoạn (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private Confirmation authenticateOperator(SupersededProof proof, char[] adminPin) throws CardException {
        String problem = AdminGrantService.pinProblem(adminPin);
        if (problem != null) {
            return Confirmation.withoutAdminResponse(CardOperationResult.fail(problem));
        }
        CardOperationResult auth = gateway.readAdminStatus(adminPin);
        int sw = gateway.lastResponseSw();
        if (sw < 0) {
            return Confirmation.withoutAdminResponse(auth);
        }
        String audit = String.format("cardId=%s | GET_ADMIN_STATUS SW=%04X", proof.state().cardId(), sw);
        if (!auth.ok()) {
            String detail = (sw & 0xFFF0) == 0x63C0
                    ? audit + " | PIN quản trị sai, còn " + (sw & 0x0F) + " lượt thử"
                    : audit + (sw == Sw.AUTH_BLOCKED ? " | PIN quản trị đã khóa" : " | Xác thực nhân viên thất bại");
            return new Confirmation(CardOperationResult.fail(detail), OptionalInt.of(sw));
        }
        CardStatus state = gateway.status();
        if (!sameState(proof.state(), state)) {
            return new Confirmation(CardOperationResult.fail(audit + " | Trạng thái thẻ đã đổi"), OptionalInt.of(sw));
        }
        return new Confirmation(CardOperationResult.ok(audit), OptionalInt.of(sw));
    }

    private boolean journalMatches(SupersededProof proof) throws IOException {
        return Files.isRegularFile(journal.file()) && Files.readString(journal.file()).equals(proof.journalContents());
    }

    private static boolean matchesLedger(CardStatus state, BackendClient.LedgerSnapshot ledger) {
        String visit = state.hasActiveVisit() ? state.activeVisitId() : null;
        return state.cardId().equalsIgnoreCase(ledger.cardId())
                && state.counter() == ledger.lastCounter() && state.balance() == ledger.lastBalance()
                && (visit == null ? ledger.activeVisitId() == null : visit.equalsIgnoreCase(ledger.activeVisitId()));
    }

    private static boolean sameState(CardStatus expected, CardStatus current) {
        return current != null && current.stateSource() == CardStatus.StateSource.ADMIN
                && expected.publicView().equals(current.publicView())
                && current.counter() == expected.counter() && current.balance() == expected.balance()
                && current.hasActiveVisit() == expected.hasActiveVisit()
                && current.activeVisitId().equalsIgnoreCase(expected.activeVisitId());
    }

    private static boolean receiptMatchesVisit(Receipt receipt, CardStatus state) {
        return switch (receipt.operation()) {
            case Receipt.OPERATION_START -> state.hasActiveVisit()
                    && state.activeVisitId().equalsIgnoreCase(receipt.visitIdHex());
            case Receipt.OPERATION_END -> !state.hasActiveVisit();
            default -> true;
        };
    }

    private CardOperationResult backendFailure(BackendClient.Failure failure) {
        return unknown(backendProblem(failure));
    }

    private static String backendProblem(BackendClient.Failure failure) {
        return "Không đọc được bằng chứng Backend (" + failure.kind() + ", HTTP=" + failure.statusCode() + ")";
    }

    private CardOperationResult unknown(String detail) {
        gateway.markTransactionUnknown(detail);
        return CardOperationResult.fail(detail + " | journal được giữ | UNKNOWN");
    }
}
