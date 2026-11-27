package vn.labaccess.desktop.card;

import java.security.PublicKey;
import java.util.function.Supplier;

import javax.smartcardio.CardException;
import javax.smartcardio.ResponseAPDU;

final class StateTransactionFlow {

    interface CardAccess {

        void requireConnected() throws CardException;

        boolean selected();

        CardStatus status();

        String cardId();

        String enrollmentBlockReason();

        Receipt lastReceipt();

        PublicKey cardPublicKey();

        CardOperationResult readCardPublicKey() throws CardException;

        CardOperationResult verifyStudentPin(char[] pin) throws CardException;

        CardOperationResult readLastReceipt(ReceiptExpectation expectation) throws CardException;

        CardOperationResult beforeJournal(CardGateway.TransactionPlan plan, char[] studentPin) throws CardException;
    }

    interface ResponseTrace {

        void recordSw(int sw);
    }

    private final CardAccess card;
    private final Reconciliation reconciliation;
    private final Supplier<CardGateway.ReceiptSink> receiptSink;
    private final ResponseTrace trace;

    StateTransactionFlow(CardAccess card, Reconciliation reconciliation,
            Supplier<CardGateway.ReceiptSink> receiptSink, ResponseTrace trace) {
        this.card = card;
        this.reconciliation = reconciliation;
        this.receiptSink = receiptSink;
        this.trace = trace;
    }

    CardOperationResult runStateTransaction(PendingJournal journal, CardGateway.TransactionPlan plan,
            CardGateway.CommandSupplier command, char[] studentPin) throws CardException {
        card.requireConnected();
        if (!card.selected() || card.status() == null
                || card.status().lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail("giao dịch bị từ chối: phải SELECT + GET_STATUS thẻ ISSUED trước");
        }
        if (!reconciliation.transactionAllowed()) {
            return CardOperationResult.fail("giao dịch bị khóa: đối soát=" + reconciliation.state()
                    + " (" + reconciliation.detail() + ")");
        }
        if (journal.load().present()) {
            return CardOperationResult.fail("đã có pending journal — phải đối soát trước giao dịch mới");
        }
        if (receiptSink.get() == null) {
            return CardOperationResult.fail(
                    "chưa cấu hình ledger Backend — không gửi APDU đổi state, không ghi journal");
        }
        String enrollmentBlock = card.enrollmentBlockReason();
        if (enrollmentBlock != null) {
            return CardOperationResult.fail("giao dịch bị chặn vì đăng ký thẻ: " + enrollmentBlock
                    + " | không ghi journal, không gửi APDU đổi state");
        }
        CardOperationResult ledgerBlock = card.beforeJournal(plan, studentPin);
        if (ledgerBlock != null) {
            return ledgerBlock;
        }
        PendingJournal.Entry entry = new PendingJournal.Entry(
                card.cardId(), plan.expectation().counterAfter(),
                plan.expectation().operation(), plan.delta(), plan.expectedBalance(),
                plan.expectation().visitId(), System.currentTimeMillis());
        if (!journal.writePending(entry)) {
            return CardOperationResult.fail("ghi pending journal thất bại — không gửi APDU");
        }

        ResponseAPDU response;
        try {
            response = command.transmit();
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất kết nối khi chờ response: " + ex.getMessage());
            return CardOperationResult.fail("APDU không có response — journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }

        int sw = response.getSW();
        byte[] data = response.getData();
        trace.recordSw(sw);
        String apdu = String.format("APDU đổi state SW=%04X dataLen=%d", sw, data.length);

        if (sw == Sw.INTERNAL_ERROR) {
            reconciliation.markUnknown("Applet trả 6F00 — có thể đã commit, có thể chưa");
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (sw != Sw.OK) {
            reconciliation.markUnknown("APDU đổi state bị từ chối; cần xác nhận state trước khi xóa journal");
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (data.length != CardGateway.STATE_RESPONSE_LENGTH) {
            reconciliation.markUnknown("response " + data.length + " byte khác 6 — không đọc được state");
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }

        CardOperationResult receiptRead;
        try {
            receiptRead = verifyAndReadReceipt(plan.expectation(), studentPin);
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất response khi đọc receipt: " + ex.getMessage());
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (!receiptRead.ok()) {
            reconciliation.markUnknown("đọc receipt sau 9000 thất bại");
            return CardOperationResult.fail(apdu + " | " + receiptRead.text() + " | journal vẫn PENDING"
                    + " | đối soát=" + reconciliation.state());
        }
        Receipt receipt = card.lastReceipt();
        if (receipt == null
                || receipt.counterAfter() != plan.expectation().counterAfter()) {
            reconciliation.markUnknown("APDU trả 9000 nhưng receipt không chứng minh counter giao dịch vừa gửi");
            return CardOperationResult.fail(apdu + " | receipt không khớp counter sau 9000"
                    + " | journal vẫn PENDING | đối soát=" + reconciliation.state());
        }
        String valueProblem = transactionValueProblem(receipt, plan);
        if (valueProblem != null) {
            reconciliation.markUnknown(valueProblem);
            return CardOperationResult.fail(apdu + " | receipt không khớp expected delta/balance: "
                    + valueProblem + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        CardGateway.ReceiptSave saved = saveReceiptToLedger(receipt);
        if (!saved.ok()) {
            reconciliation.markUnknown("receipt đã xác minh trên thẻ nhưng chưa lưu được vào ledger");
            return CardOperationResult.fail(apdu + " | " + receiptRead.text() + " | lưu ledger=FAIL ("
                    + saved.detail() + ") | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        CardOperationResult journalResult = finishJournal(journal, entry);
        if (!journalResult.ok()) {
            return CardOperationResult.fail(apdu + " | " + receiptRead.text() + " | " + saved.detail()
                    + " | " + journalResult.text());
        }
        reconciliation.markKnown("receipt khớp, ledger đã lưu, journal đã đóng");
        return CardOperationResult.ok(apdu + " | " + receiptRead.text() + " | " + saved.detail()
                + " | " + journalResult.text());
    }

    CardGateway.ReceiptSave saveReceiptToLedger(Receipt receipt) {
        CardGateway.ReceiptSink sink = receiptSink.get();
        if (sink == null) {
            return new CardGateway.ReceiptSave(false, "chưa cấu hình ledger Backend");
        }
        return sink.save(receipt);
    }

    CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry) {
        return finishJournal(journal, entry, null, null);
    }

    CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry,
            Reconciliation.Verdict verdict, String evidence) {
        if (!journal.writeResolved(entry)) {
            reconciliation.markUnknown("ghi trạng thái RESOLVED thất bại; giữ journal");
            return CardOperationResult.fail("ghi RESOLVED thất bại — journal vẫn được giữ lại");
        }
        try {
            journal.remove();
        } catch (RuntimeException ex) {
            reconciliation.markUnknown("xóa journal RESOLVED thất bại; giữ file để xử lý thủ công");
            return CardOperationResult.fail("journal đã RESOLVED nhưng chưa xóa được — cần xử lý thủ công");
        }
        if (verdict == null) {
            return CardOperationResult.ok("journal đã RESOLVED và bị xóa");
        }
        return CardOperationResult.ok(String.format(
                "%s | kết luận=%s | journal đã RESOLVED và bị xóa",
                evidence, verdict));
    }

    private static String transactionValueProblem(Receipt receipt, CardGateway.TransactionPlan plan) {
        if (receipt == null) {
            return "chưa có receipt để đối chiếu delta/balance";
        }
        if (receipt.delta() != plan.delta()) {
            return String.format("delta receipt=%d expected=%d", receipt.delta(), plan.delta());
        }
        if (receipt.balanceAfter() != plan.expectedBalance()) {
            return String.format("balance receipt=%d expected=%d",
                    receipt.balanceAfter(), plan.expectedBalance());
        }
        return null;
    }

    CardOperationResult verifyAndReadReceipt(ReceiptExpectation expectation, char[] studentPin)
            throws CardException {
        CardOperationResult keyLoaded = ensureCardPublicKeyLoaded();
        if (!keyLoaded.ok()) {
            return CardOperationResult.fail("không nạp được public key của thẻ: " + keyLoaded.text());
        }
        if (studentPin != null) {
            CardOperationResult verify = card.verifyStudentPin(studentPin);
            if (!verify.ok()) {
                return CardOperationResult.fail("VERIFY lần hai sau giao dịch thất bại: " + verify.text());
            }
        }
        return card.readLastReceipt(expectation);
    }

    CardOperationResult ensureCardPublicKeyLoaded() throws CardException {
        if (card.cardPublicKey() != null) {
            return CardOperationResult.ok("public key đã nạp");
        }
        return card.readCardPublicKey();
    }

    void receiptVerificationFailed(ReceiptExpectation expectation, String problem) {
        if (expectation == null) {
            reconciliation.markReceiptUnverified(problem);
        } else {
            reconciliation.markUnknown(problem);
        }
    }

    void markTransactionUnknown(String cause) {
        reconciliation.markUnknown(cause);
    }
}
