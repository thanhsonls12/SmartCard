package vn.labaccess.desktop;

import java.util.Arrays;

import javax.smartcardio.CardException;

final class AdminGrantRecovery {

    private final CardGateway gateway;
    private final PendingJournal journal;
    private final BackendClient backend;
    private final SupersededRecovery superseded;

    AdminGrantRecovery(CardGateway gateway, PendingJournal journal, BackendClient backend) {
        this.gateway = gateway;
        this.journal = journal;
        this.backend = backend;
        superseded = new SupersededRecovery(gateway, journal, backend);
    }

    CardOperationResult reconcile(AdminGrantService.PinSource pinSource) {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present() || !loaded.valid() || loaded.status() != PendingJournal.Status.PENDING
                || loaded.entry().operation() != Receipt.OPERATION_GRANT) {
            return CardOperationResult.fail("Cần nhật ký GRANT ở trạng thái PENDING hợp lệ");
        }
        PendingJournal.Entry entry = loaded.entry();
        CardStatus selected = gateway.status();
        if (!gateway.isReady() || selected == null || selected.lifecycle() != CardLifecycle.ISSUED
                || !entry.cardId().equalsIgnoreCase(selected.cardId())) {
            return unknown("Nhận diện đúng thẻ " + entry.cardId() + " để đối soát");
        }
        gateway.markTransactionUnknown("GRANT đang chờ đối soát");

        Receipt receipt;
        CardStatus state;
        String detail = "";
        try {
            BackendClient.EnrolledCard enrolled = backend.enrolledCard(entry.cardId());
            char[] pin = pinSource.take();
            try {
                String pinProblem = AdminGrantService.pinProblem(pin);
                if (pinProblem != null) {
                    return unknown(pinProblem);
                }
                if (!gateway.isReady() || !selected.equals(gateway.status())) {
                    return unknown("Phiên thẻ đã đổi trước khi đọc Admin");
                }
                CardOperationResult receiptRead = gateway.readAdminLastReceipt(
                        pin, enrolled.cardId(), enrolled.publicKey());
                CardAdminCommands.ReceiptRead proof = gateway.lastAdminReceiptRead();
                boolean noReceipt = gateway.lastResponseSw() == Sw.CONDITIONS_NOT_SATISFIED
                        && entry.expectedCounter() == 1;
                if (receiptRead.ok() && proof != null && proof.signatureChecked() && proof.receipt() != null) {
                    receipt = proof.receipt();
                } else if (noReceipt) {
                    receipt = null;
                } else {
                    return unknown(receiptRead.text());
                }
                CardOperationResult statusRead = gateway.readAdminStatus(pin);
                state = gateway.status();
                detail = receiptRead.text() + " | " + statusRead.text();
                if (!statusRead.ok() || state == null || state.stateSource() != CardStatus.StateSource.ADMIN
                        || !entry.cardId().equalsIgnoreCase(state.cardId())) {
                    return unknown(detail);
                }
            } finally {
                if (pin != null) {
                    Arrays.fill(pin, '\0');
                }
            }

            if (receipt != null && Arrays.equals(receipt.payload(), ReceiptCodec.intentPayload(entry))
                    && state.counter() == entry.expectedCounter() && state.balance() == entry.expectedBalance()) {
                BackendClient.SavedReceipt saved = backend.verifyReceipt(receipt);
                detail += " | Backend ACK=" + saved.status() + " HTTP=" + saved.httpStatus();
                if (!AdminGrantService.matchesLedger(state, backend.ledgerOf(entry.cardId()))) {
                    return unknown(detail + " | Ledger hiện tại không khớp trạng thái thẻ sau GRANT");
                }
                return close(entry, Reconciliation.Verdict.EXECUTED,
                        detail + " | Ledger khớp trạng thái thẻ sau GRANT");
            }
            if (!matchesPreviousState(entry, receipt, state)) {
                if (ReceiptCodec.differentReceiptAtOrAfterIntent(entry, receipt)) {
                    return superseded.verifiedEvidence(entry, receipt, state);
                }
                return unknown(detail + " | Biên nhận và trạng thái thẻ không chứng minh được intent GRANT");
            }
            BackendClient.LedgerSnapshot ledger = backend.ledgerOf(entry.cardId());
            if (!AdminGrantService.matchesLedger(state, ledger)) {
                return unknown(detail + " | Trạng thái trước GRANT không khớp ledger");
            }
            return close(entry, Reconciliation.Verdict.NOT_EXECUTED,
                    detail + " | Ledger khớp trạng thái trước GRANT");
        } catch (BackendClient.Failure failure) {
            String evidence = detail.isEmpty() ? "" : detail + " | ";
            return unknown(evidence + "Backend không cung cấp đủ bằng chứng (" + failure.kind()
                    + ", HTTP=" + failure.statusCode() + ")");
        } catch (CardException | RuntimeException failure) {
            return unknown("Đối soát GRANT bị gián đoạn (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static boolean matchesPreviousState(PendingJournal.Entry entry, Receipt receipt, CardStatus state) {
        if (state.counter() != entry.expectedCounter() - 1
                || state.balance() != entry.expectedBalance() - entry.delta()) {
            return false;
        }
        return receipt == null || (receipt.counterAfter() == state.counter()
                && receipt.balanceAfter() == state.balance());
    }

    private CardOperationResult close(PendingJournal.Entry entry, Reconciliation.Verdict verdict, String detail) {
        CardOperationResult closed = gateway.finishJournal(journal, entry, verdict, detail);
        if (closed.ok()) {
            gateway.reconciliation().markKnown("Đối soát GRANT: " + verdict);
        } else {
            gateway.clearAuthenticatedStatus();
        }
        return closed;
    }

    private CardOperationResult unknown(String detail) {
        gateway.markTransactionUnknown(detail);
        gateway.clearAuthenticatedStatus();
        return CardOperationResult.fail(detail + " | journal vẫn PENDING | UNKNOWN");
    }
}
