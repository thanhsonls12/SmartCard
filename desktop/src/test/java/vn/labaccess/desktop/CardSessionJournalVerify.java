package vn.labaccess.desktop;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class CardSessionJournalVerify {

    private static int checks;

    public static void main(String[] args) throws Exception {
        verifyStartup("INVALID", "broken", false,
                "Không đọc được nhật ký giao dịch: số trường không hợp lệ.",
                "số trường không hợp lệ",
                "pending journal không hợp lệ — cần xử lý thủ công");
        verifyStartup("RESOLVED", "v1|RESOLVED|A1B2C3D411223344|1|1|5|5||0", true,
                "Nhật ký đã đánh dấu hoàn tất nhưng chưa được dọn khỏi máy.",
                "RESOLVED",
                "journal đã RESOLVED nhưng chưa xóa — cần xử lý thủ công");
        System.out.println("CARD SESSION JOURNAL PASS: 2 cases, " + checks + " checks");
    }

    private static void verifyStartup(String label, String contents, boolean valid, String summary,
            String problem, String refreshedDetail) throws Exception {
        Path directory = Files.createTempDirectory("labaccess-journal-summary-");
        Path file = directory.resolve("pending.txt");
        String previousJournal = System.getProperty("labaccess.journal");
        String previousBackend = System.getProperty("labaccess.backend");
        AtomicInteger http = new AtomicInteger();
        List<Integer> apdus = new ArrayList<>();
        try (RecoveryBackendFixture backend = new RecoveryBackendFixture((request, body) -> {
            http.incrementAndGet();
            return new RecoveryBackendFixture.Reply(503, "{}");
        })) {
            Files.writeString(file, contents);
            System.setProperty("labaccess.journal", file.toString());
            System.setProperty("labaccess.backend", backend.url());
            CardSession session = new CardSession(message -> { });
            CardGateway gateway = session.gateway();
            RecoveryCardFixture card = new RecoveryCardFixture();
            card.failTransport = true;
            Object transport = field(gateway, "transport");
            set(transport, "channel", card);
            set(transport, "connectedFlag", true);
            gateway.traceCommands(apdus::add);
            check(label, "fixture armed before startup", gateway.isConnected()
                    && gateway.reconciliation().transactionAllowed() && apdus.isEmpty() && http.get() == 0);
            PendingJournal.Loaded loaded = session.pendingJournal();
            check(label, "loads expected journal state", loaded.present() && loaded.valid() == valid
                    && (valid ? loaded.status() == PendingJournal.Status.RESOLVED
                            : problem.equals(loaded.problem())));
            CardSession.StartupJournal startup = session.blockOnStartupJournal();
            check(label, "startup notice unchanged", startup.present() && startup.message().equals(
                    "[FAIL] Khởi động: journal không dùng được (" + problem
                            + ") — KHÓA giao dịch, giữ nguyên file, không gửi APDU"));
            check(label, "recovery description unchanged", summary.equals(gateway.reconciliation().detail()));
            check(label, "UNKNOWN blocks transactions before I/O",
                    gateway.reconciliation().state() == Reconciliation.State.UNKNOWN
                            && !gateway.reconciliation().transactionAllowed()
                            && apdus.isEmpty() && http.get() == 0 && session.backend().lastAccess() == null);
            check(label, "startup preserves original file", Files.readString(file).equals(contents));
            session.blockUnusableJournal(session.pendingJournal());
            check(label, "refresh keeps UNKNOWN and original notice",
                    gateway.reconciliation().state() == Reconciliation.State.UNKNOWN
                            && !gateway.reconciliation().transactionAllowed()
                            && refreshedDetail.equals(gateway.reconciliation().detail()));
            check(label, "refresh performs no I/O or journal mutation", apdus.isEmpty() && http.get() == 0
                    && session.backend().lastAccess() == null && Files.readString(file).equals(contents));
            System.out.println("TRACE " + label + ": APDU=" + apdus.size() + " HTTP=" + http.get()
                    + " reconciliation=" + gateway.reconciliation().state()
                    + " transactionAllowed=" + gateway.reconciliation().transactionAllowed()
                    + " journal=unchanged");
        } finally {
            restore("labaccess.journal", previousJournal);
            restore("labaccess.backend", previousBackend);
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void check(String label, String assertion, boolean passed) {
        if (!passed) {
            throw new AssertionError(label + ": " + assertion);
        }
        checks++;
        System.out.println("PASS " + label + ": " + assertion);
    }
}
