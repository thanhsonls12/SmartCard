package vn.labaccess.desktop;

import javax.smartcardio.CardException;

final class StudentLedgerGate {

    private StudentLedgerGate() {
    }

    static CardOperationResult beforeJournal(CardGateway gateway, CardGateway.ReceiptSink sink,
            CardGateway.TransactionPlan plan, char[] studentPin) throws CardException {
        int operation = plan.expectation().operation();
        if (operation != Receipt.OPERATION_START && operation != Receipt.OPERATION_END) {
            return null;
        }
        CardOperationResult read = gateway.readAuthenticatedStatus(studentPin);
        if (!read.ok()) {
            return stopped(read.text());
        }
        CardStatus state = gateway.status();
        if (state == null || !state.studentStateAvailable()) {
            return stopped("không đọc được state Student sau VERIFY");
        }
        if (sink == null) {
            return CardOperationResult.fail(
                    "chưa cấu hình ledger Backend — không gửi APDU đổi state, không ghi journal");
        }
        CardGateway.LedgerCheck ledger = sink.ledger(state.cardId());
        if (!ledger.ok()) {
            return stopped("không đọc được sổ giao dịch: " + ledger.detail());
        }
        if (!LedgerAlignment.matches(state, CardStatus.StateSource.STUDENT, ledger.cardId(),
                ledger.lastCounter(), ledger.lastBalance(), ledger.activeVisitId())) {
            return CardOperationResult.fail(String.format(
                    "Thẻ và sổ giao dịch chưa khớp (cardId %s/%s, số giao dịch %d/%d,"
                            + " số lượt %d/%d, ca %s/%s). Quay lại máy đang giữ nhật ký để đối soát"
                            + " | không ghi journal, không gửi APDU đổi state",
                    state.cardId(), ledger.cardId(), state.counter(), ledger.lastCounter(),
                    state.balance(), ledger.lastBalance(), visitText(state),
                    visitText(ledger.activeVisitId())));
        }
        String stale = stalePlan(state, plan);
        if (stale != null) {
            return stopped(stale);
        }
        CardOperationResult authorized = gateway.verifyStudentPin(studentPin);
        if (!authorized.ok()) {
            return stopped("VERIFY trước lệnh đổi state thất bại: " + authorized.text());
        }
        return null;
    }

    private static CardOperationResult stopped(String detail) {
        return CardOperationResult.fail(detail + " | không ghi journal, không gửi APDU đổi state");
    }

    private static String stalePlan(CardStatus state, CardGateway.TransactionPlan plan) {
        ReceiptExpectation expectation = plan.expectation();
        boolean start = expectation.operation() == Receipt.OPERATION_START;
        long counter = state.counter() + 1;
        int balance = start ? state.balance() - 1 : state.balance();
        int delta = start ? -1 : 0;
        boolean visit = start
                ? !state.hasActiveVisit() && assignedVisit(expectation.visitId())
                : state.hasActiveVisit() && sameVisit(state.activeVisitId(), expectation.visitId());
        boolean aligned = expectation.cardId() != null
                && state.cardId().equalsIgnoreCase(CardGateway.hex(expectation.cardId()))
                && expectation.counterAfter() == counter
                && plan.expectedBalance() == balance
                && plan.delta() == delta
                && visit;
        if (aligned) {
            return null;
        }
        return "Kỳ vọng giao dịch không khớp trạng thái vừa đọc."
                + " Đọc lại trạng thái trước khi mở hoặc đóng ca";
    }

    private static boolean assignedVisit(byte[] visitId) {
        return visitId != null && visitId.length == Receipt.VISIT_ID_LENGTH && !isAllZero(visitId);
    }

    private static boolean sameVisit(String cardVisit, byte[] planned) {
        return planned != null && cardVisit != null && cardVisit.equalsIgnoreCase(CardGateway.hex(planned));
    }

    private static boolean isAllZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    static String receiptProblem(CardGateway.ReceiptSink sink, String cardIdHex, Receipt receipt) {
        if (sink == null) {
            return "chưa cấu hình nguồn ledger Backend để đối chiếu";
        }
        CardGateway.LedgerCheck ledger = sink.ledger(cardIdHex);
        if (!ledger.ok()) {
            return "không đọc được ledger Backend sau khi lưu: " + ledger.detail();
        }
        if (ledger.lastCounter() != receipt.counterAfter()
                || ledger.lastBalance() != receipt.balanceAfter()) {
            return String.format(
                    "ledger Backend counter=%d balance=%d khác receipt counter=%d balance=%d",
                    ledger.lastCounter(), ledger.lastBalance(),
                    receipt.counterAfter(), receipt.balanceAfter());
        }
        return null;
    }

    private static String visitText(CardStatus state) {
        return state.hasActiveVisit() ? state.activeVisitId() : "không";
    }

    private static String visitText(String visitId) {
        return visitId == null ? "không" : visitId;
    }
}
