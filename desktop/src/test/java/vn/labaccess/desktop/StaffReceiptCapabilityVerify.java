package vn.labaccess.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Window;

public final class StaffReceiptCapabilityVerify {

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String VISIT_ID = "11".repeat(16);
    private static int checks;

    private StaffReceiptCapabilityVerify() {
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        FutureTask<Void> verify = new FutureTask<>(() -> {
            staffCannotReadStudentReceipt();
            nonGrantPendingNeverUsesRecoveryCapabilities();
            grantPendingStillRequiresAdminPin();
            wrongCardAndDamagedJournalRemainBlocked();
            adminStateIsVisibleOnlyToStaff();
            legacyStudentRecoveryRemainsAvailable();
            closedAndRejectedTasksDoNotRetainPin();
            System.out.println("STAFF RECEIPT CAPABILITY PASS: " + checks + " checks");
            return null;
        });
        try {
            Platform.runLater(verify);
            verify.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
    }

    private static void staffCannotReadStudentReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ReceiptPanel panel = fixture.staffPanel();
            panel.applyLock(false, null, null);
            require(!panel.pressReadReceipt(), "Staff không có đường đọc bằng Student PIN");
            require(fixture.pinRequests.get() == 0 && fixture.tasks.get() == 0,
                    "Đọc receipt Staff không hỏi PIN và không chạy tác vụ");
            require(Arrays.stream(ReceiptPanel.StaffSession.class.getRecordComponents())
                    .noneMatch(component -> component.getName().toLowerCase(java.util.Locale.ROOT).contains("student")),
                    "StaffSession không chứa capability Student");
        }
    }

    private static void nonGrantPendingNeverUsesRecoveryCapabilities() throws Exception {
        for (int operation : new int[] {Receipt.OPERATION_START, Receipt.OPERATION_END}) {
            try (Fixture fixture = new Fixture()) {
                require(fixture.journal.writePending(entry(operation, CARD_ID)), "Tạo pending học viên");
                byte[] before = Files.readAllBytes(fixture.journal.file());
                ReceiptPanel panel = fixture.staffPanel();
                require(panel.pendingOperationReason().contains("Kiosk"), "Gate Staff hướng dẫn Kiosk");
                panel.offerPendingReconciliation();
                require(fixture.gateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                        "Offer giữ UNKNOWN cho pending học viên");
                panel.applyLock(false, null, null);
                require(!panel.reconcileEnabled() && !panel.pressReconcile(), "Không bật đối soát Staff cho START/END");
                require(!panel.pressReadReceipt(), "Không đi vòng qua đọc Student receipt");
                Button direct = (Button) panel.reconcileAction();
                direct.setDisable(false);
                direct.fire();
                require(fixture.gateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                        "Handler trực tiếp cũng giữ UNKNOWN");
                require(fixture.pinRequests.get() == 0 && fixture.tasks.get() == 0
                        && fixture.historyChanges.get() == 0 && fixture.finished.get() == 0,
                        "Không hỏi PIN, chạy APDU/HTTP hay publish kết quả giả");
                require(hasText(panel.node(), "Kiosk"), "Panel tự hiển thị hướng dẫn Kiosk");
                require(Window.getWindows().stream().noneMatch(Window::isShowing), "Không mở hộp thoại cho START/END");
                require(Arrays.equals(before, Files.readAllBytes(fixture.journal.file()))
                        && fixture.journal.load().status() == PendingJournal.Status.PENDING,
                        "Giữ nguyên bytes và trạng thái PENDING");
            }
        }
    }

    private static void grantPendingStillRequiresAdminPin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            require(fixture.journal.writePending(entry(Receipt.OPERATION_GRANT, CARD_ID)), "Tạo pending GRANT");
            ReceiptPanel panel = fixture.staffPanel();
            panel.offerPendingReconciliation();
            require(fixture.pinRequests.get() == 0, "Offer GRANT chỉ hướng dẫn, không hỏi PIN");
            require(panel.pendingOperationReason() == null, "GRANT thuộc capability Staff");
            panel.applyLock(false, null, null);
            require(panel.pressReconcile() && fixture.pinRequests.get() == 1,
                    "GRANT vẫn dùng AdminRecovery.requirePin khi bấm");
            require(hasText(panel.node(), "Cấp lượt") && !hasText(panel.node(), "Vận hành thẻ"),
                    "Staff hướng dẫn đúng khối Cấp lượt");
            require(fixture.tasks.get() == 0, "Thiếu PIN không bắt đầu GRANT recovery");
        }
    }

    private static void wrongCardAndDamagedJournalRemainBlocked() throws Exception {
        try (Fixture fixture = new Fixture()) {
            require(fixture.journal.writePending(entry(Receipt.OPERATION_GRANT, "0011223344556677")),
                    "Tạo journal thẻ khác");
            ReceiptPanel panel = fixture.staffPanel();
            panel.offerPendingReconciliation();
            panel.applyLock(false, null, null);
            require(panel.pendingCardMismatch() != null && !panel.pressReconcile(), "Khóa pending khác thẻ");
            require(fixture.pinRequests.get() == 0 && fixture.tasks.get() == 0, "Không dùng PIN thẻ sai");
        }
        for (String contents : new String[] {"broken", "v1|RESOLVED|" + CARD_ID + "|2|1|5|10||1"}) {
            try (Fixture fixture = new Fixture()) {
                Files.writeString(fixture.journal.file(), contents);
                ReceiptPanel panel = fixture.staffPanel();
                panel.offerPendingReconciliation();
                require(fixture.gateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                        "Journal hỏng/RESOLVED không được coi là đã xử lý");
                require(fixture.pinRequests.get() == 0 && fixture.tasks.get() == 0
                        && Files.readString(fixture.journal.file()).equals(contents),
                        "Không sửa journal hỏng/RESOLVED, không dùng PIN/APDU/HTTP");
            }
        }
    }

    private static void adminStateIsVisibleOnlyToStaff() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ReceiptPanel staff = fixture.staffPanel();
            ReceiptPanel legacy = fixture.legacyPanel(new AtomicInteger());
            staff.showReceipt();
            legacy.showReceipt();
            require(VISIT_ID.equals(staff.displayedVisitId()), "Staff hiển thị ca từ ADMIN status");
            require("Chưa đọc trạng thái ca".equals(legacy.displayedVisitId()), "Legacy không nhận ADMIN status");
            setField(fixture.gateway, "status", status(CardStatus.StateSource.PUBLIC));
            staff.showReceipt();
            require("Chưa đọc trạng thái ca".equals(staff.displayedVisitId()), "Staff không lộ ca từ PUBLIC status");
            setField(fixture.gateway, "status", status(CardStatus.StateSource.STUDENT));
            legacy.showReceipt();
            require(VISIT_ID.equals(legacy.displayedVisitId()), "Legacy vẫn hiển thị Student snapshot");
        }
    }

    private static void legacyStudentRecoveryRemainsAvailable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            require(fixture.journal.writePending(entry(Receipt.OPERATION_START, CARD_ID)), "Tạo legacy START");
            AtomicInteger studentReads = new AtomicInteger();
            ReceiptPanel legacy = fixture.legacyPanel(studentReads);
            legacy.applyLock(false, null, null);
            require(legacy.pendingOperationReason() == null, "Không áp gate Staff vào legacy");
            require(legacy.pressReconcile() && studentReads.get() == 1, "Legacy vẫn yêu cầu Student PIN");
            require(fixture.pinRequests.get() == 0 && fixture.tasks.get() == 0,
                    "Legacy không dùng Admin PIN cho START thiếu Student PIN");
        }
    }

    private static void closedAndRejectedTasksDoNotRetainPin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ReceiptPanel panel = fixture.staffPanel();
            fixture.open.set(false);
            panel.applyLock(false, null, null);
            require(!panel.pressReconcile(), "Phiên đóng không chạy đối soát");
            Button direct = (Button) panel.reconcileAction();
            direct.setDisable(false);
            direct.fire();
            require(fixture.pinRequests.get() == 0 && fixture.tasks.get() == 0, "Handler cũng kiểm phiên đóng");
            fixture.open.set(true);
            panel.applyLock(true, null, null);
            require(!panel.pressReconcile(), "Busy khóa nút đối soát");
            fixture.pinAvailable.set(true);
            panel.applyLock(false, null, null);
            require(panel.pressReconcile(), "Có PIN thì thử submit manual recovery cũ");
            require(fixture.tasks.get() == 1 && fixture.pinClears.get() == 1,
                    "Runner từ chối tác vụ thì xóa Admin PIN");
        }
    }

    private static PendingJournal.Entry entry(int operation, String cardId) {
        return new PendingJournal.Entry(cardId, 2, operation,
                operation == Receipt.OPERATION_GRANT ? 5 : operation == Receipt.OPERATION_START ? -1 : 0,
                operation == Receipt.OPERATION_GRANT ? 10 : 4,
                operation == Receipt.OPERATION_GRANT ? null
                        : new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 1);
    }

    private static CardStatus status(CardStatus.StateSource source) {
        return new CardStatus(1, CardLifecycle.ISSUED, "F04C4142010101", CARD_ID, source, 5, 1, true, VISIT_ID);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean hasText(Parent parent, String text) {
        for (var child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Label label && label.getText() != null && label.getText().contains(text)) {
                return true;
            }
            if (child instanceof Parent nested && hasText(nested, text)) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
        System.out.println("PASS " + message);
    }

    private static final class Fixture implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("staff-receipt-capability-");
        private final PendingJournal journal = new PendingJournal(directory.resolve("pending-journal.txt"));
        private final CardGateway gateway = new CardGateway("127.0.0.1", 1);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean pinAvailable = new AtomicBoolean();
        private final AtomicInteger pinRequests = new AtomicInteger();
        private final AtomicInteger pinClears = new AtomicInteger();
        private final AtomicInteger tasks = new AtomicInteger();
        private final AtomicInteger historyChanges = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        Fixture() throws Exception {
            setField(gateway, "ready", true);
            var transport = CardGateway.class.getDeclaredField("transport");
            transport.setAccessible(true);
            setField(transport.get(gateway), "connectedFlag", true);
            setField(gateway, "status", status(CardStatus.StateSource.ADMIN));
        }

        private ReceiptPanel.AdminRecovery recovery() {
            return new ReceiptPanel.AdminRecovery(() -> {
                pinRequests.incrementAndGet();
                return pinAvailable.get();
            }, () -> { throw new AssertionError("Không lấy PIN khi chưa chấp nhận tác vụ"); },
                    () -> pinClears.incrementAndGet(),
                    source -> { throw new AssertionError("Không thực thi GRANT trong ca gate"); },
                    (proof, pin) -> { throw new AssertionError("Không thực thi SUPERSEDED trong ca gate"); },
                    source -> { throw new AssertionError("Không inspect trong runner từ chối"); },
                    (decision, source) -> { throw new AssertionError("Không ghi HTTP trong ca gate"); });
        }

        private CardOpRunner runner() {
            return (label, work, callback) -> {
                tasks.incrementAndGet();
                return false;
            };
        }

        private ReceiptPanel staffPanel() {
            return new ReceiptPanel(gateway, runner(), ignored -> { }, new ReceiptPanel.StaffSession(
                    open::get, journal::load, journal.file(), recovery(),
                    () -> historyChanges.incrementAndGet(), () -> historyChanges.incrementAndGet(),
                    () -> historyChanges.incrementAndGet(), () -> { }, ignored -> finished.incrementAndGet()));
        }

        private ReceiptPanel legacyPanel(AtomicInteger studentReads) {
            return new ReceiptPanel(gateway, runner(), ignored -> { }, new ReceiptPanel.Session(
                    open::get, journal::load, journal.file(), () -> {
                        studentReads.incrementAndGet();
                        return new char[0];
                    }, () -> { }, pin -> { throw new AssertionError("Thiếu Student PIN thì không đối soát"); },
                    recovery(), () -> historyChanges.incrementAndGet(), () -> historyChanges.incrementAndGet(),
                    () -> historyChanges.incrementAndGet(), () -> { }, ignored -> { },
                    ignored -> finished.incrementAndGet(), () -> { }));
        }

        @Override
        public void close() throws Exception {
            Files.deleteIfExists(journal.file());
            Files.deleteIfExists(directory);
        }
    }
}
