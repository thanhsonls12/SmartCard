package vn.labaccess.desktop.card;

import java.util.List;

public final class Reconciliation {

    @FunctionalInterface
    public interface SupersededEvidence {
        CardOperationResult read(PendingJournal.Entry entry, Receipt receipt, char[] studentPin);
    }

    public enum State {
        KNOWN, RECEIPT_UNVERIFIED, UNKNOWN
    }

    public enum Verdict {
        EXECUTED, NOT_EXECUTED, MISMATCH, SUPERSEDED
    }

    private State state = State.KNOWN;
    private String detail = "";

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public boolean transactionAllowed() {
        return state == State.KNOWN;
    }

    public boolean receiptUnverified() {
        return state == State.RECEIPT_UNVERIFIED;
    }

    public void markReceiptUnverified(String cause) {
        if (state != State.UNKNOWN) {
            state = State.RECEIPT_UNVERIFIED;
            detail = cause;
        }
    }

    public void markUnknown(String cause) {
        state = State.UNKNOWN;
        detail = cause;
    }

    public void markKnown(String cause) {
        state = State.KNOWN;
        detail = cause == null ? "" : cause;
    }

    public Verdict resolve(Receipt receipt, ReceiptExpectation expectation) {
        String cardProblem = expectation.cardProblem(receipt);
        if (cardProblem != null) {
            detail = cardProblem;
            return Verdict.MISMATCH;
        }

        if (receipt.counterAfter() == expectation.counterAfter()) {
            List<String> problems = expectation.transactionProblems(receipt);
            if (!problems.isEmpty()) {
                detail = String.join("; ", problems);
                return Verdict.MISMATCH;
            }
            detail = "";
            return Verdict.EXECUTED;
        }

        if (receipt.counterAfter() == expectation.counterAfter() - 1) {
            detail = "";
            return Verdict.NOT_EXECUTED;
        }

        detail = String.format(
                "counterAfter=%d không phải %d (đã xảy ra) cũng không phải %d (chưa xảy ra)",
                receipt.counterAfter(), expectation.counterAfter(),
                expectation.counterAfter() - 1);
        return Verdict.MISMATCH;
    }
}
