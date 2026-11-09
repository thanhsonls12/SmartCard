package vn.labaccess.desktop;

import java.util.List;

final class Reconciliation {

    enum State {
        KNOWN, UNKNOWN
    }

    enum Verdict {
        EXECUTED, NOT_EXECUTED, MISMATCH
    }

    private State state = State.KNOWN;
    private String detail = "";

    State state() {
        return state;
    }

    String detail() {
        return detail;
    }

    boolean transactionAllowed() {
        return state == State.KNOWN;
    }

    void markUnknown(String cause) {
        state = State.UNKNOWN;
        detail = cause;
    }

    void markKnown(String cause) {
        state = State.KNOWN;
        detail = cause == null ? "" : cause;
    }

    Verdict resolve(Receipt receipt, ReceiptExpectation expectation) {
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
            state = State.KNOWN;
            detail = "";
            return Verdict.EXECUTED;
        }

        if (receipt.counterAfter() == expectation.counterAfter() - 1) {
            state = State.KNOWN;
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
