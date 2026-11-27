package vn.labaccess.desktop.card;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

final class ReconciliationFlow {

    private static final byte INS_GET_LAST_RECEIPT = (byte) 0x60;
    private static final int RECEIPT_LE = 256;

    interface CardAccess {

        void requireConnected() throws CardException;

        CardStatus status();

        String cardId();

        Receipt lastReceipt();

        void storeReceipt(Receipt receipt);

        void recordSignature(boolean ok, boolean checked);

        ResponseAPDU transmit(CommandAPDU command) throws CardException;

        void recordSw(int sw);

        int lastResponseSw();

        PublicKey cardPublicKey();

        CardOperationResult ensureCardPublicKeyLoaded() throws CardException;

        CardOperationResult verifyStudentPin(char[] pin) throws CardException;

        CardOperationResult readAuthenticatedStatus(char[] pin) throws CardException;

        void receiptVerificationFailed(ReceiptExpectation expectation, String problem);

        CardGateway.ReceiptSave saveReceiptToLedger(Receipt receipt);

        CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry,
                Reconciliation.Verdict verdict, String evidence);
    }

    private final CardAccess card;
    private final Reconciliation reconciliation;
    private final Supplier<CardGateway.ReceiptSink> receiptSink;

    ReconciliationFlow(CardAccess card, Reconciliation reconciliation,
            Supplier<CardGateway.ReceiptSink> receiptSink) {
        this.card = card;
        this.reconciliation = reconciliation;
        this.receiptSink = receiptSink;
    }

    CardOperationResult reconcilePending(PendingJournal journal, char[] studentPin) throws CardException {
        return reconcilePending(journal, studentPin, null);
    }

    CardOperationResult reconcilePending(PendingJournal journal, char[] studentPin,
            Reconciliation.SupersededEvidence superseded) throws CardException {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            return CardOperationResult.fail("không có pending journal");
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            reconciliation.markUnknown("pending journal không hợp lệ hoặc đã RESOLVED");
            return CardOperationResult.fail("pending journal cần xử lý thủ công: " + loaded.problem());
        }
        PendingJournal.Entry entry = loaded.entry();
        if (entry.operation() != Receipt.OPERATION_START && entry.operation() != Receipt.OPERATION_END) {
            reconciliation.markUnknown("GRANT cần đối soát bằng PIN quản trị");
            return CardOperationResult.fail(reconciliation.detail() + " | journal vẫn PENDING");
        }
        if (!entry.cardId().equalsIgnoreCase(card.cardId())) {
            reconciliation.markUnknown("pending journal thuộc cardId khác thẻ đang nhận diện");
            return CardOperationResult.fail("cardId của journal khác cardId hiện tại — vẫn giữ journal");
        }
        reconciliation.markUnknown("Giao dịch Student đang chờ đối soát");
        CardOperationResult keyLoaded = card.ensureCardPublicKeyLoaded();
        if (!keyLoaded.ok()) {
            reconciliation.markUnknown("không nạp được public key để xác minh receipt");
            return CardOperationResult.fail(keyLoaded.text() + " | journal vẫn PENDING");
        }
        CardOperationResult verify = card.verifyStudentPin(studentPin);
        if (!verify.ok()) {
            return CardOperationResult.fail("VERIFY để đối soát thất bại: " + verify.text());
        }
        CardOperationResult receiptRead;
        try {
            receiptRead = readLastReceipt(null);
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất response khi đối soát receipt: " + ex.getMessage());
            return CardOperationResult.fail("không đọc được receipt — journal vẫn PENDING");
        }
        if (!receiptRead.ok()) {
            if (card.lastResponseSw() == Sw.CONDITIONS_NOT_SATISFIED && entry.expectedCounter() == 1) {
                CardOperationResult stateRead = confirmStateUnchanged(entry, studentPin);
                if (!stateRead.ok()) {
                    reconciliation.markUnknown("chưa có receipt nhưng không xác nhận được state chưa đổi");
                    return CardOperationResult.fail(stateRead.text() + " | journal vẫn PENDING | đối soát="
                            + reconciliation.state());
                }
                CardOperationResult closed = card.finishJournal(journal, entry,
                        Reconciliation.Verdict.NOT_EXECUTED, stateRead.text());
                if (closed.ok()) {
                    reconciliation.markKnown(
                            "chưa có receipt; state xác thực xác nhận chưa thực thi (ledger chưa đối chiếu)");
                }
                return closed;
            }
            reconciliation.markUnknown("không chứng minh được giao dịch đã hoặc chưa thực thi");
            return CardOperationResult.fail(receiptRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }

        if (superseded != null && ReceiptCodec.differentReceiptAtOrAfterIntent(entry, card.lastReceipt())) {
            return superseded.read(entry, card.lastReceipt(), studentPin);
        }
        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(entry.cardId()), entry.expectedCounter(),
                entry.operation(), entry.visitId());
        Reconciliation.Verdict verdict = reconciliation.resolve(card.lastReceipt(), expectation);
        if (verdict == Reconciliation.Verdict.MISMATCH) {
            reconciliation.markUnknown("receipt không khớp pending journal");
            return CardOperationResult.fail(receiptRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        if (verdict == Reconciliation.Verdict.EXECUTED) {
            if (!Arrays.equals(card.lastReceipt().payload(), ReceiptCodec.intentPayload(entry))) {
                reconciliation.markUnknown("payload receipt khác intent");
                return CardOperationResult.fail("payload receipt khác intent | journal vẫn PENDING");
            }
            CardGateway.ReceiptSave saved = card.saveReceiptToLedger(card.lastReceipt());
            if (!saved.ok()) {
                reconciliation.markUnknown("giao dịch đã thực thi trên thẻ nhưng chưa lưu được vào ledger");
                return CardOperationResult.fail(receiptRead.text() + " | lưu ledger=FAIL (" + saved.detail()
                        + ") | journal vẫn PENDING | đối soát=" + reconciliation.state());
            }
            String ledgerProblem = StudentLedgerGate.receiptProblem(
                    receiptSink.get(), entry.cardId(), card.lastReceipt());
            if (ledgerProblem != null) {
                reconciliation.markUnknown(ledgerProblem);
                return CardOperationResult.fail(receiptRead.text() + " | " + saved.detail() + " | " + ledgerProblem
                        + " | journal vẫn PENDING | đối soát=" + reconciliation.state());
            }
            CardOperationResult closed = card.finishJournal(journal, entry, verdict,
                    receiptRead.text() + " | " + saved.detail());
            if (closed.ok()) {
                reconciliation.markKnown("đã lưu ledger cho giao dịch đã thực thi");
            }
            return closed;
        }

        if (card.lastReceipt().balanceAfter() != entry.expectedBalance() - entry.delta()) {
            reconciliation.markUnknown("balance receipt trước giao dịch không khớp expected state cũ");
            return CardOperationResult.fail("receipt cũ không chứng minh state chưa đổi — journal vẫn PENDING");
        }
        CardOperationResult stateRead = confirmStateUnchanged(entry, studentPin);
        if (!stateRead.ok()) {
            reconciliation.markUnknown("receipt cho thấy chưa thực thi nhưng state chưa được xác nhận");
            return CardOperationResult.fail(stateRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        CardOperationResult closed = card.finishJournal(journal, entry, verdict,
                receiptRead.text() + " | " + stateRead.text());
        if (closed.ok()) {
            reconciliation.markKnown(
                    "state xác thực xác nhận giao dịch chưa thực thi (ledger chưa đối chiếu)");
        }
        return closed;
    }

    private CardOperationResult confirmStateUnchanged(PendingJournal.Entry entry, char[] studentPin)
            throws CardException {
        CardOperationResult stateRead = card.readAuthenticatedStatus(studentPin);
        if (!stateRead.ok()) {
            return CardOperationResult.fail("không đọc được state xác thực: " + stateRead.text());
        }
        CardStatus current = card.status();
        if (current == null || !current.studentStateAvailable()) {
            return CardOperationResult.fail("state xác thực không khả dụng sau GET_STATUS");
        }
        long wantCounter = entry.expectedCounter() - 1;
        int wantBalance = entry.expectedBalance() - entry.delta();
        if (current.counter() != wantCounter || current.balance() != wantBalance) {
            return CardOperationResult.fail(String.format(
                    "state thẻ counter=%d balance=%d khác mốc trước giao dịch %d/%d",
                    current.counter(), current.balance(), wantCounter, wantBalance));
        }

        CardGateway.ReceiptSink sink = receiptSink.get();
        if (sink == null) {
            return CardOperationResult.fail("chưa cấu hình nguồn ledger Backend để đối chiếu");
        }
        CardGateway.LedgerCheck ledger = sink.ledger(entry.cardId());
        if (!ledger.ok()) {
            return CardOperationResult.fail("không đọc được ledger Backend: " + ledger.detail());
        }
        if (ledger.lastCounter() != current.counter() || ledger.lastBalance() != current.balance()) {
            return CardOperationResult.fail(String.format(
                    "ledger Backend counter=%d balance=%d khác state thẻ counter=%d balance=%d",
                    ledger.lastCounter(), ledger.lastBalance(), current.counter(), current.balance()));
        }
        String cardVisit = current.hasActiveVisit() ? current.activeVisitId() : null;
        String ledgerVisit = ledger.activeVisitId();
        boolean bothClosed = cardVisit == null && ledgerVisit == null;
        boolean sameOpenVisit = cardVisit != null && ledgerVisit != null
                && cardVisit.equalsIgnoreCase(ledgerVisit);
        if (!bothClosed && !sameOpenVisit) {
            return CardOperationResult.fail(String.format("ledger Backend ca mở=%s khác state thẻ ca mở=%s",
                    ledgerVisit == null ? "không" : ledgerVisit,
                    cardVisit == null ? "không" : cardVisit));
        }
        return CardOperationResult.ok(String.format("%s | ledger counter=%d balance=%d ca mở=%s"
                + " | state thẻ và ledger cùng mốc trước giao dịch",
                stateRead.text(), ledger.lastCounter(), ledger.lastBalance(),
                ledgerVisit == null ? "không" : ledgerVisit));
    }

    CardOperationResult readLastReceipt(ReceiptExpectation expectation) throws CardException {
        return readLastReceipt(expectation, RECEIPT_LE);
    }

    CardOperationResult readLastReceipt(ReceiptExpectation expectation, int le) throws CardException {
        card.requireConnected();
        card.storeReceipt(null);
        card.recordSignature(false, false);

        ResponseAPDU response = card.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_GET_LAST_RECEIPT, 0x00, 0x00, le));
        int sw = response.getSW();
        byte[] data = response.getData();
        card.recordSw(sw);
        String apdu = String.format("GET_LAST_RECEIPT INS=60 P1P2=0000 Le=%d SW=%04X dataLen=%d",
                le, sw, data.length);

        if (sw != Sw.OK) {
            card.storeReceipt(null);
            return CardOperationResult.fail(apdu + " | " + receiptStatusText(sw));
        }

        int signatureLength = data.length > ReceiptCodec.PAYLOAD_LENGTH
                ? (data[ReceiptCodec.PAYLOAD_LENGTH] & 0xFF)
                : -1;
        ReceiptCodec.Parse parse = ReceiptCodec.parse(data);
        if (!parse.ok()) {
            card.storeReceipt(null);
            card.receiptVerificationFailed(expectation, "receipt không đọc được: " + parse.problem());
            return CardOperationResult.fail(String.format("%s sigLen=%d parser=FAIL (%s) | đối soát=%s",
                    apdu, signatureLength, parse.problem(), reconciliation.state()));
        }

        Receipt receipt = parse.receipt();
        card.storeReceipt(receipt);
        String detail = String.format(
                "%s sigLen=%d parser=PASS | receipt %s | payload=%s signature=%s",
                apdu, signatureLength, receipt, receipt.payloadHex(), receipt.signatureHex());

        String cardProblem = cardIdProblem(receipt);
        if (cardProblem != null) {
            card.storeReceipt(null);
            card.receiptVerificationFailed(expectation, cardProblem);
            return CardOperationResult.fail(String.format("%s | kết luận=MISMATCH (%s) state=%s",
                    detail, cardProblem, reconciliation.state()));
        }

        CardOperationResult signatureCheck = verifyReceiptSignature(receipt);
        if (!signatureCheck.ok()) {
            card.storeReceipt(null);
            card.receiptVerificationFailed(expectation, "chữ ký receipt không hợp lệ");
            return CardOperationResult.fail(String.format("%s | ECDSA=FAIL (%s) | kết luận=MISMATCH state=%s",
                    detail, signatureCheck.text(), reconciliation.state()));
        }
        detail = detail + " | ECDSA=PASS";

        if (expectation == null) {
            return CardOperationResult.ok(detail + " | chưa đối soát — thiếu kỳ vọng của giao dịch");
        }

        Reconciliation.Verdict verdict = reconciliation.resolve(receipt, expectation);
        if (verdict == Reconciliation.Verdict.MISMATCH) {
            reconciliation.markUnknown(reconciliation.detail());
        }
        String text = String.format("%s | expected %s | kết luận=%s state=%s",
                detail, expectationText(expectation), verdict, reconciliation.state());
        return verdict == Reconciliation.Verdict.MISMATCH
                ? CardOperationResult.fail(text)
                : CardOperationResult.ok(text);
    }

    private String cardIdProblem(Receipt receipt) {
        CardStatus current = card.status();
        if (current == null) {
            return "chưa nhận diện thẻ để đối chiếu cardId";
        }
        if (receipt.cardIdHex().equalsIgnoreCase(current.cardId())) {
            return null;
        }
        return String.format("cardId receipt=%s khác thẻ đang nhận diện=%s",
                receipt.cardIdHex(), current.cardId());
    }

    private CardOperationResult verifyReceiptSignature(Receipt receipt) throws CardException {
        CardOperationResult loaded = card.ensureCardPublicKeyLoaded();
        if (!loaded.ok()) {
            card.recordSignature(false, false);
            return CardOperationResult.fail(loaded.text());
        }
        ReceiptVerifier.Result verified = ReceiptVerifier.verifyReceipt(card.cardPublicKey(), receipt);
        card.recordSignature(verified.ok(), true);
        if (verified.ok()) {
            return CardOperationResult.ok("ECDSA P-256/SHA-256 hợp lệ");
        }
        return CardOperationResult.fail(verified.problem());
    }

    private static String expectationText(ReceiptExpectation expectation) {
        return String.format("counter=%d operation=%s visitId=%s",
                expectation.counterAfter(), Receipt.operationText(expectation.operation()),
                expectation.visitId() == null ? "<khong dung>" : CardGateway.hex(expectation.visitId()));
    }

    private static String receiptStatusText(int sw) {
        if (sw == Sw.SECURITY_NOT_SATISFIED) {
            return "thiếu student authorization — VERIFY_STUDENT_PIN trước khi đọc receipt";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa personalize hoặc chưa có receipt nào được lưu";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Le thiếu so với độ dài response thật";
        }
        return String.format("SW=%04X không đọc được receipt", sw);
    }
}
