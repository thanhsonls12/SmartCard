package vn.labaccess.desktop;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.smartcardio.CardException;
import javafx.application.Platform;

public final class VisitStudentPinCleanupVerify {

    private enum Mode {
        INVALID, NULL, CLOSED, FAILED, EXCEPTION, SUCCESS, REFRESH_FAILED
    }

    private static int checks;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        try {
            for (Mode mode : Mode.values()) {
                verify(mode);
            }
            System.out.println("VISIT STUDENT PIN CLEANUP PASS: 7 cases, " + checks + " checks");
        } finally {
            Platform.exit();
        }
    }

    private static void verify(Mode mode) throws Exception {
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        if (mode == Mode.REFRESH_FAILED) {
            card.failTransport = true;
        }
        char[] input = mode == Mode.NULL ? null
                : RecoveryCardFixture.pin(mode == Mode.INVALID ? 5 : PersonalizeRequest.STUDENT_PIN_LENGTH);
        char[] expected = input == null ? null : input.clone();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger transactions = new AtomicInteger();
        AtomicReference<Callable<CardOperationResult>> submitted = new AtomicReference<>();
        try {
            FutureTask<Void> invoke = new FutureTask<>(() -> {
                VisitPanel panel = new VisitPanel(gateway, (label, work, finished) -> {
                    submissions.incrementAndGet();
                    submitted.set(work);
                    return true;
                }, ignored -> { }, new VisitPanel.Actions(() -> true, () -> mode != Mode.CLOSED,
                        (plan, supplier, pin) -> {
                            transactions.incrementAndGet();
                            check(mode + " receives the original Student PIN", pin == input && Arrays.equals(pin, expected));
                            if (mode == Mode.EXCEPTION) {
                                throw new CardException("fixture transaction failure");
                            }
                            return mode == Mode.FAILED ? CardOperationResult.fail("fixture rejected")
                                    : CardOperationResult.ok("fixture confirmed");
                        }, () -> { }, () -> { }, ignored -> { }, () -> { }, () -> { }, () -> { }));
                Method run = VisitPanel.class.getDeclaredMethod("runTransaction", String.class, char[].class,
                        ReceiptExpectation.class, int.class, int.class, CardGateway.CommandSupplier.class);
                run.setAccessible(true);
                ReceiptExpectation expectation = new ReceiptExpectation(HexFormat.of().parseHex(card.cardId),
                        card.counter + 1, Receipt.OPERATION_START, new byte[16]);
                run.invoke(panel, "START_VISIT", input, expectation, -1, 4,
                        (CardGateway.CommandSupplier) () -> { throw new AssertionError("unexpected direct command"); });
                return null;
            });
            Platform.runLater(invoke);
            invoke.get(15, TimeUnit.SECONDS);
            boolean early = mode == Mode.INVALID || mode == Mode.NULL || mode == Mode.CLOSED;
            check(mode + " submission count", submissions.get() == (early ? 0 : 1));
            if (!early) {
                try {
                    CardOperationResult result = submitted.get().call();
                    check(mode + " result preserved", mode != Mode.EXCEPTION
                            && result.ok() == (mode != Mode.FAILED)
                            && (mode != Mode.REFRESH_FAILED || result.warning() != null));
                } catch (CardException failure) {
                    check(mode + " exception propagated", mode == Mode.EXCEPTION);
                }
            }
            check(mode + " Student PIN cleared", input == null || allZero(input));
            check(mode + " transaction count", transactions.get() == (early ? 0 : 1));
        } finally {
            if (input != null) {
                Arrays.fill(input, '\0');
            }
            if (expected != null) {
                Arrays.fill(expected, '\0');
            }
        }
    }

    private static boolean allZero(char[] value) {
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }

    private static void check(String assertion, boolean passed) {
        if (!passed) {
            throw new AssertionError(assertion);
        }
        checks++;
        System.out.println("PASS " + assertion);
    }
}
