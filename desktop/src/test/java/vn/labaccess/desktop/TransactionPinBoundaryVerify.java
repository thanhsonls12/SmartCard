package vn.labaccess.desktop;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;

public final class TransactionPinBoundaryVerify {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        try {
            onFx(() -> {
                verifyPinCallbacks();
                verifyAdminSnapshot();
                return null;
            });
            System.out.println("TRANSACTION PIN BOUNDARY PASS: " + checks + " checks");
        } finally {
            Platform.exit();
        }
    }

    private static TransactionPanel panel(CardGateway gateway, AtomicInteger submissions, List<String> log) {
        return new TransactionPanel(gateway, (label, work, finished) -> {
            submissions.incrementAndGet();
            return false;
        }, log::add, new TransactionPanel.Session(() -> true, () -> true,
                (plan, supplier, pin) -> { throw new AssertionError("unexpected Student transaction"); },
                (delta, source) -> { throw new AssertionError("unexpected GRANT"); },
                () -> { }, () -> { }, ignored -> { }, () -> { }, () -> { }));
    }

    private static void verifyPinCallbacks() {
        AtomicInteger submissions = new AtomicInteger();
        TransactionPanel panel = panel(new CardGateway("127.0.0.1", 1), submissions, new ArrayList<>());
        char[] student = RecoveryCardFixture.pin(PersonalizeRequest.STUDENT_PIN_LENGTH);
        char[] admin = RecoveryCardFixture.pin(PersonalizeRequest.ADMIN_PIN_LENGTH);
        try {
            panel.fillInputs(student, admin, 5);
            check("both PIN fields validate independently", panel.requireStudentPin() && panel.requireAdminPin());
            char[] taken = panel.takeAdminPin();
            try {
                check("Admin recovery callback takes only Admin PIN", Arrays.equals(taken, admin));
            } finally {
                Arrays.fill(taken, '\0');
            }
            checkStudent(panel, student, "taking Admin PIN preserves Student PIN");
            check("taking Admin PIN clears only the Admin field", !panel.requireAdminPin() && panel.requireStudentPin());
            panel.fillInputs(student, admin, 5);
            panel.clearAdminPin();
            checkStudent(panel, student, "Admin clear callback preserves Student PIN");
            check("Admin clear callback cannot accept Student PIN", !panel.requireAdminPin());
            panel.fillInputs(student, admin, 5);
            panel.clearStudentPin();
            check("Student clear callback preserves Admin PIN", !panel.requireStudentPin() && panel.requireAdminPin());
            panel.fillInputs(new char[0], admin, 5);
            check("Admin-only input does not satisfy Student validation", panel.requireAdminPin() && !panel.requireStudentPin());
            panel.clearInputs();
            check("session cleanup clears both owners", panel.node().lookupAll(".password-field").stream()
                    .map(node -> (PasswordField) node).allMatch(field -> field.getText().isEmpty()));
            check("PIN callbacks alone never submit work", submissions.get() == 0);
        } finally {
            Arrays.fill(student, '\0');
            Arrays.fill(admin, '\0');
            panel.clearInputs();
        }
    }

    private static void verifyAdminSnapshot() throws Exception {
        CardGateway gateway = new CardGateway("127.0.0.1", 1);
        AtomicInteger submissions = new AtomicInteger();
        List<String> lines = new ArrayList<>();
        TransactionPanel panel = panel(gateway, submissions, lines);
        char[] student = RecoveryCardFixture.pin(PersonalizeRequest.STUDENT_PIN_LENGTH);
        char[] admin = RecoveryCardFixture.pin(PersonalizeRequest.ADMIN_PIN_LENGTH);
        try {
            CardStatus status = new CardStatus(1, CardLifecycle.ISSUED, "F04C4142010101",
                    RecoveryCardFixture.CARD_ID, CardStatus.StateSource.ADMIN, 5, 1, false, "");
            Field current = CardGateway.class.getDeclaredField("status");
            current.setAccessible(true);
            current.set(gateway, status);
            panel.fillInputs(student, admin, 5);
            panel.showState(status, true);
            SessionGates.Reasons reasons = SessionGates.of(new SessionGates.State(false, true, true, true,
                    true, false, false, true, null, status));
            panel.applyLock(false, reasons.grant(), reasons.start(), reasons.end(), reasons.readReceipt());
            check("Admin snapshot permits GRANT without enabling visits", panel.grantState().equals("mo")
                    && panel.startState().equals("khoa") && panel.endState().equals("khoa"));
            check("START and END buttons reject Admin-only snapshot", !panel.pressStart() && !panel.pressEnd());
            for (var node : panel.node().lookupAll(".button")) {
                if (node instanceof Button button && (button.getText().startsWith("Mở ca")
                        || button.getText().equals("Đóng ca"))) {
                    button.setDisable(false);
                }
            }
            check("forced START click still checks Student snapshot", panel.pressStart() && submissions.get() == 0
                    && lines.stream().anyMatch(line -> line.contains("START_VISIT: chưa có snapshot state student")));
            check("forced END click still checks Student snapshot", panel.pressEnd() && submissions.get() == 0
                    && lines.stream().anyMatch(line -> line.contains("END_VISIT: chưa có snapshot state student")));
            panel.applyLock(true, App.BUSY_WHY, App.BUSY_WHY, App.BUSY_WHY, App.BUSY_WHY);
            check("busy locks all three transaction actions", !panel.pressGrant() && !panel.pressStart() && !panel.pressEnd());
            panel.applyLock(false, null, null, null, null);
            panel.lockAll();
            check("closing locks both component owners", !panel.pressGrant() && !panel.pressStart() && !panel.pressEnd());
            check("guard and locking checks never submit work", submissions.get() == 0);
        } finally {
            Arrays.fill(student, '\0');
            Arrays.fill(admin, '\0');
            panel.clearInputs();
        }
    }

    private static void checkStudent(TransactionPanel panel, char[] expected, String assertion) {
        char[] student = panel.studentPinInput();
        try {
            check(assertion, Arrays.equals(student, expected));
        } finally {
            Arrays.fill(student, '\0');
        }
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void check(String assertion, boolean passed) {
        if (!passed) {
            throw new AssertionError(assertion);
        }
        checks++;
        System.out.println("PASS " + assertion);
    }
}
