package vn.labaccess.desktop;

import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.smartcardio.CardException;
import javax.smartcardio.ResponseAPDU;

final class AdminGrantService {

    private record Prepared(PendingJournal.Entry entry, Receipt receipt, String detail, boolean refreshed) {
    }

    private final CardGateway gateway;
    private final PendingJournal journal;
    private final BackendClient backend;

    AdminGrantService(CardGateway gateway, PendingJournal journal, BackendClient backend) {
        this.gateway = gateway;
        this.journal = journal;
        this.backend = backend;
    }

    @FunctionalInterface
    interface PinSource {

        char[] take();
    }

    CardOperationResult grant(int delta, PinSource pinSource) {
        Prepared prepared;
        boolean pending = false;
        char[] adminPin = null;
        try {
            String problem = preflight(delta);
            if (problem != null) {
                return CardOperationResult.fail("GRANT_QUOTA: " + problem + " | không gửi APDU");
            }
            CardStatus selected = gateway.status();
            String cardId = selected.cardId();
            BackendClient.EnrolledCard enrolled = backend.enrolledCard(cardId);
            BackendClient.LedgerSnapshot ledger = backend.ledgerOf(cardId);
            adminPin = pinSource.take();
            String pinProblem = pinProblem(adminPin);
            if (pinProblem != null) {
                return CardOperationResult.fail("GRANT_QUOTA: " + pinProblem + " | không gửi APDU");
            }
            if (!sameCardSession(selected)) {
                return CardOperationResult.fail("GRANT_QUOTA: phiên thẻ đã đổi sau preflight | không gửi APDU");
            }
            CardOperationResult before = gateway.readAdminStatus(adminPin);
            if (!before.ok()) {
                return before;
            }
            CardStatus state = gateway.status();
            if (!matchesLedger(state, ledger)) {
                gateway.clearAuthenticatedStatus();
                return CardOperationResult.fail("GRANT_QUOTA: " + LedgerAlignment.mismatch(state,
                        ledger.cardId(), ledger.lastCounter(), ledger.lastBalance(), ledger.activeVisitId()));
            }
            if (state.counter() >= Receipt.COUNTER_MASK || delta > Receipt.MAX_BALANCE - state.balance()) {
                return CardOperationResult.fail("GRANT_QUOTA: số lượt hoặc counter vượt giới hạn; không gửi GRANT");
            }
            PendingJournal.Entry entry = new PendingJournal.Entry(cardId, state.counter() + 1,
                    Receipt.OPERATION_GRANT, delta, state.balance() + delta, null, System.currentTimeMillis());
            if (!journal.writePending(entry)) {
                return CardOperationResult.fail("GRANT_QUOTA: ghi journal PENDING thất bại; không gửi GRANT");
            }
            pending = true;
            gateway.markTransactionUnknown("GRANT đang PENDING, chưa có ACK Backend");
            ResponseAPDU response = transmitGrant(delta, adminPin);
            String grantDetail = String.format("GRANT_QUOTA INS=50 SW=%04X dataLen=%d",
                    response.getSW(), response.getData().length);
            if (response.getSW() != Sw.OK || !matchesGrantResponse(response, entry)) {
                return unknown(grantDetail + " | response không chứng minh state kỳ vọng");
            }
            CardOperationResult receiptRead = gateway.readAdminLastReceipt(
                    adminPin, enrolled.cardId(), enrolled.publicKey());
            CardAdminCommands.ReceiptRead read = gateway.lastAdminReceiptRead();
            if (!receiptRead.ok() || read == null || !read.signatureChecked()
                    || !matchesReceipt(read.receipt(), entry)) {
                return unknown(receiptRead.text() + " | receipt Admin không đủ chứng cứ hoặc không khớp kỳ vọng");
            }
            Receipt receipt = read.receipt();
            String detail = before.text() + " | " + grantDetail + " | " + receiptRead.text();
            boolean refreshed = false;
            try {
                CardOperationResult refresh = gateway.readAdminStatus(adminPin);
                refreshed = refresh.ok() && matchesRefreshed(gateway.status(), state, entry);
                detail += " | " + refresh.text();
            } catch (CardException | RuntimeException failure) {
                detail += " | refresh Admin thất bại: " + failure.getClass().getSimpleName();
            }
            if (!refreshed) {
                gateway.clearAuthenticatedStatus();
            }
            prepared = new Prepared(entry, receipt, detail, refreshed);
        } catch (BackendClient.Failure | CardException | RuntimeException failure) {
            String detail = "GRANT_QUOTA: " + failure.getClass().getSimpleName();
            return pending ? unknown(detail) : CardOperationResult.fail(detail + " | chưa gửi GRANT");
        } finally {
            if (adminPin != null) {
                Arrays.fill(adminPin, '\0');
            }
        }
        return record(prepared);
    }

    private boolean sameCardSession(CardStatus selected) {
        return gateway.isReady() && selected.equals(gateway.status()) && !journal.load().present()
                && gateway.reconciliation().transactionAllowed();
    }

    private String preflight(int delta) {
        CardStatus status = gateway.status();
        if (!gateway.isReady() || status == null || status.lifecycle() != CardLifecycle.ISSUED) {
            return "phải SELECT + GET_STATUS thẻ ISSUED trước";
        }
        if (journal.load().present() || !gateway.reconciliation().transactionAllowed()) {
            return "có giao dịch hoặc lỗi xác minh cần đối soát trước";
        }
        String enrollmentProblem = gateway.enrollmentBlockReason();
        if (enrollmentProblem != null) {
            return enrollmentProblem;
        }
        if (delta < 1 || delta > Receipt.MAX_BALANCE) {
            return "số lượt cần cấp phải từ 1 đến " + Receipt.MAX_BALANCE;
        }
        return null;
    }

    static String pinProblem(char[] pin) {
        if (pin == null || pin.length != PersonalizeRequest.ADMIN_PIN_LENGTH) {
            return PersonalizeRequest.adminPinProblem();
        }
        for (char digit : pin) {
            if (digit < '0' || digit > '9') {
                return PersonalizeRequest.adminPinProblem();
            }
        }
        return null;
    }

    private ResponseAPDU transmitGrant(int delta, char[] pin) throws CardException {
        byte[] bytes = new byte[pin.length];
        try {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) pin[index];
            }
            return gateway.grantCommand(bytes, delta).transmit();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static boolean matchesLedger(CardStatus state, BackendClient.LedgerSnapshot ledger) {
        return LedgerAlignment.matches(state, CardStatus.StateSource.ADMIN, ledger.cardId(),
                ledger.lastCounter(), ledger.lastBalance(), ledger.activeVisitId());
    }

    private static boolean matchesGrantResponse(ResponseAPDU response, PendingJournal.Entry entry) {
        byte[] data = response.getData();
        if (data.length != 6) {
            return false;
        }
        ByteBuffer values = ByteBuffer.wrap(data);
        return (values.getShort() & 0xFFFF) == entry.expectedBalance()
                && Integer.toUnsignedLong(values.getInt()) == entry.expectedCounter();
    }

    static boolean matchesReceipt(Receipt receipt, PendingJournal.Entry entry) {
        return receipt != null && receipt.cardIdHex().equalsIgnoreCase(entry.cardId())
                && receipt.counterAfter() == entry.expectedCounter() && receipt.operation() == Receipt.OPERATION_GRANT
                && receipt.delta() == entry.delta() && receipt.balanceAfter() == entry.expectedBalance()
                && Arrays.equals(receipt.visitId(), new byte[Receipt.VISIT_ID_LENGTH]);
    }

    private static boolean matchesRefreshed(CardStatus refreshed, CardStatus before, PendingJournal.Entry entry) {
        return refreshed != null && refreshed.stateSource() == CardStatus.StateSource.ADMIN
                && refreshed.cardId().equalsIgnoreCase(entry.cardId()) && refreshed.counter() == entry.expectedCounter()
                && refreshed.balance() == entry.expectedBalance()
                && refreshed.hasActiveVisit() == before.hasActiveVisit()
                && refreshed.activeVisitId().equalsIgnoreCase(before.activeVisitId());
    }

    private CardOperationResult record(Prepared prepared) {
        try {
            BackendClient.SavedReceipt saved = backend.verifyReceipt(prepared.receipt());
            if (!journal.writeResolved(prepared.entry())) {
                return unknown("Backend đã ACK nhưng ghi RESOLVED thất bại");
            }
            journal.remove();
            gateway.reconciliation().markKnown("GRANT đã xác minh receipt, Backend ACK khớp và journal đã đóng");
            String detail = prepared.detail() + String.format(" | Backend ACK=%s counter=%d HTTP=%d | journal đã đóng",
                    saved.status(), saved.counterAfter(), saved.httpStatus());
            return prepared.refreshed() ? CardOperationResult.ok(detail)
                    : CardOperationResult.warning(detail + " | cần đọc lại trạng thái",
                            "Cấp lượt đã ghi sổ; cần đọc lại trạng thái. Không thực hiện lại GRANT.");
        } catch (BackendClient.Failure | RuntimeException failure) {
            return unknown("GRANT: chưa hoàn tất ghi sổ/journal (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private CardOperationResult unknown(String detail) {
        gateway.markTransactionUnknown(detail);
        gateway.clearAuthenticatedStatus();
        return CardOperationResult.fail(detail + " | journal vẫn được giữ để đối soát; UNKNOWN, không gửi lại GRANT");
    }
}
