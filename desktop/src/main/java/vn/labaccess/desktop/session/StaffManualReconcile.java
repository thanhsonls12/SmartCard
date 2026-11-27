package vn.labaccess.desktop.session;

import java.util.Arrays;

import javax.smartcardio.CardException;

import vn.labaccess.desktop.card.CardAdminCommands;
import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.Receipt;

import vn.labaccess.desktop.backend.BackendClient;

public final class StaffManualReconcile {

    public enum Kind {
        ALREADY_RECORDED, NEEDS_CONFIRMATION, REJECTED
    }

    public record Decision(Kind kind, Receipt receipt, CardStatus state, String detail) {
    }

    private final CardGateway gateway;
    private final BackendClient backend;

    public StaffManualReconcile(CardGateway gateway, BackendClient backend) {
        this.gateway = gateway;
        this.backend = backend;
    }

    public Decision inspect(AdminGrantService.PinSource pinSource) {
        Read read = readCard(pinSource);
        if (read.failure() != null) {
            return new Decision(Kind.REJECTED, read.receipt(), read.state(), read.failure());
        }
        return decide(read.receipt(), read.state(), false);
    }

    public CardOperationResult confirm(Decision preview, AdminGrantService.PinSource pinSource) {
        if (preview == null || preview.kind() != Kind.NEEDS_CONFIRMATION || preview.receipt() == null) {
            return CardOperationResult.fail("Chưa có giao dịch cần xác nhận | không ghi sổ");
        }
        Read read = readCard(pinSource);
        if (read.failure() != null) {
            return CardOperationResult.fail(read.failure() + " | không ghi sổ");
        }
        if (!sameEvidence(preview, read)) {
            return CardOperationResult.fail("Thẻ đã đổi sau khi xem giao dịch | không ghi sổ");
        }
        Decision again = decide(read.receipt(), read.state(), true);
        if (again.kind() == Kind.ALREADY_RECORDED) {
            return CardOperationResult.ok(again.detail());
        }
        if (again.kind() != Kind.NEEDS_CONFIRMATION) {
            return CardOperationResult.fail(again.detail());
        }
        try {
            String auditId = backend.hasStaffSession()
                    ? backend.beginAudit("RECONCILE", read.state().cardId(), null, null) : null;
            BackendClient.SavedReceipt saved = backend.verifyReceipt(read.receipt(), auditId);
            BackendClient.LedgerSnapshot ledger = backend.ledgerOf(read.state().cardId());
            if (!AdminGrantService.matchesLedger(read.state(), ledger)) {
                return CardOperationResult.fail("Backend ACK=" + saved.status() + " HTTP=" + saved.httpStatus()
                        + " | ledger chưa khớp thẻ sau khi ghi");
            }
            return CardOperationResult.ok(String.format(
                    "Đã ghi sổ %s counter=%d số lượt=%d | Backend ACK=%s HTTP=%d | ledger khớp thẻ",
                    Receipt.operationText(read.receipt().operation()), read.receipt().counterAfter(),
                    read.receipt().balanceAfter(), saved.status(), saved.httpStatus()));
        } catch (BackendClient.Failure failure) {
            return CardOperationResult.fail("Không ghi được sổ (" + failure.kind() + ", HTTP="
                    + failure.statusCode() + ")");
        }
    }

    private Decision decide(Receipt receipt, CardStatus state, boolean confirming) {
        if (!cardMatchesReceipt(state, receipt)) {
            return rejected("Receipt, thẻ và ca đang mở mâu thuẫn");
        }
        try {
            BackendClient.LedgerSnapshot ledger = backend.ledgerOf(state.cardId());
            if (sameRecorded(receipt, state, ledger)) {
                return new Decision(Kind.ALREADY_RECORDED, receipt, state,
                        "Đã ghi sổ " + Receipt.operationText(receipt.operation())
                                + " counter=" + receipt.counterAfter() + " | không gửi lại");
            }
            if (ledger.lastCounter() + 1 == receipt.counterAfter()
                    && ledger.lastBalance() == receipt.balanceAfter() - receipt.delta()
                    && visitFollows(receipt, state, ledger)) {
                String detail = String.format(
                        "Receipt mới hơn sổ đúng một giao dịch: %s counter=%d số lượt=%d ca=%s",
                        Receipt.operationText(receipt.operation()), receipt.counterAfter(),
                        receipt.balanceAfter(), state.hasActiveVisit() ? state.activeVisitId() : "không");
                return new Decision(Kind.NEEDS_CONFIRMATION, receipt, state, detail);
            }
            if (receipt.counterAfter() > ledger.lastCounter() + 1) {
                return rejected("Counter nhảy cóc từ " + ledger.lastCounter() + " tới " + receipt.counterAfter());
            }
            return rejected("Receipt, thẻ và sổ mâu thuẫn");
        } catch (BackendClient.Failure failure) {
            return rejected("Không đọc được sổ (" + failure.kind() + ", HTTP=" + failure.statusCode() + ")");
        } finally {
            if (confirming) {
                gateway.clearAuthenticatedStatus();
            }
        }
    }

    private Read readCard(AdminGrantService.PinSource pinSource) {
        char[] pin = null;
        try {
            if (!gateway.isReady() || gateway.status() == null) {
                return Read.failed("Chưa nhận diện thẻ");
            }
            String cardId = gateway.status().cardId();
            BackendClient.EnrolledCard enrolled = backend.enrolledCard(cardId);
            pin = pinSource.take();
            String pinProblem = AdminGrantService.pinProblem(pin);
            if (pinProblem != null) {
                return Read.failed(pinProblem);
            }
            CardOperationResult receiptRead = gateway.readAdminLastReceipt(
                    pin, enrolled.cardId(), enrolled.publicKey());
            CardAdminCommands.ReceiptRead proof = gateway.lastAdminReceiptRead();
            if (!receiptRead.ok() || proof == null || !proof.signatureChecked() || proof.receipt() == null) {
                return Read.failed("Chữ ký receipt không khớp khóa đã enroll | " + receiptRead.text());
            }
            CardOperationResult statusRead = gateway.readAdminStatus(pin);
            CardStatus state = gateway.status();
            if (!statusRead.ok() || state == null || state.stateSource() != CardStatus.StateSource.ADMIN) {
                return Read.failed(statusRead.text());
            }
            return new Read(proof.receipt(), state, null);
        } catch (BackendClient.Failure failure) {
            return Read.failed("Không đọc được khóa đã enroll (" + failure.kind() + ")");
        } catch (CardException | RuntimeException failure) {
            return Read.failed("Đọc thẻ bị gián đoạn (" + failure.getClass().getSimpleName() + ")");
        } finally {
            if (pin != null) {
                Arrays.fill(pin, '\0');
            }
        }
    }

    private boolean sameRecorded(Receipt receipt, CardStatus state, BackendClient.LedgerSnapshot ledger)
            throws BackendClient.Failure {
        if (!AdminGrantService.matchesLedger(state, ledger)
                || ledger.lastCounter() != receipt.counterAfter()
                || ledger.lastBalance() != receipt.balanceAfter()) {
            return false;
        }
        try {
            byte[] stored = backend.storedReceiptPayload(state.cardId(), receipt.counterAfter());
            return Arrays.equals(stored, receipt.payload());
        } catch (BackendClient.Failure missing) {
            if (missing.statusCode() == 404) {
                return false;
            }
            throw missing;
        }
    }

    private static boolean cardMatchesReceipt(CardStatus state, Receipt receipt) {
        if (state == null || receipt == null || !state.cardId().equalsIgnoreCase(receipt.cardIdHex())
                || state.counter() != receipt.counterAfter() || state.balance() != receipt.balanceAfter()) {
            return false;
        }
        return switch (receipt.operation()) {
            case Receipt.OPERATION_START -> state.hasActiveVisit()
                    && state.activeVisitId().equalsIgnoreCase(receipt.visitIdHex());
            case Receipt.OPERATION_END -> !state.hasActiveVisit();
            case Receipt.OPERATION_GRANT -> !receiptHasVisit(receipt);
            default -> false;
        };
    }

    private static boolean visitFollows(Receipt receipt, CardStatus state, BackendClient.LedgerSnapshot ledger) {
        return switch (receipt.operation()) {
            case Receipt.OPERATION_START -> ledger.activeVisitId() == null;
            case Receipt.OPERATION_END -> ledger.activeVisitId() != null
                    && receipt.visitIdHex().equalsIgnoreCase(ledger.activeVisitId());
            case Receipt.OPERATION_GRANT -> sameVisit(state, ledger.activeVisitId());
            default -> false;
        };
    }

    private static boolean sameVisit(CardStatus state, String ledgerVisit) {
        if (!state.hasActiveVisit()) {
            return ledgerVisit == null;
        }
        return state.activeVisitId().equalsIgnoreCase(ledgerVisit);
    }

    private static boolean receiptHasVisit(Receipt receipt) {
        String visit = receipt.visitIdHex();
        return visit != null && !visit.matches("0{32}");
    }

    private static boolean sameEvidence(Decision preview, Read read) {
        return read.receipt() != null && read.state() != null
                && Arrays.equals(preview.receipt().payload(), read.receipt().payload())
                && preview.state().equals(read.state());
    }

    private static Decision rejected(String detail) {
        return new Decision(Kind.REJECTED, null, null, detail + " | không ghi sổ");
    }

    private record Read(Receipt receipt, CardStatus state, String failure) {
        static Read failed(String failure) {
            return new Read(null, null, failure + " | không ghi sổ");
        }
    }
}
