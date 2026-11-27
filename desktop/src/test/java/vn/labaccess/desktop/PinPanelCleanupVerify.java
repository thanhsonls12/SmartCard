package vn.labaccess.desktop;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.smartcardio.CardException;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;

import vn.labaccess.desktop.ui.pin.PinPanel;
import vn.labaccess.desktop.ui.pin.PinState;

public final class PinPanelCleanupVerify {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        try {
            for (PinState.Operation operation : PinState.Operation.values()) {
                verify(operation);
                verifyRejectedSubmission(operation, false);
                verifyRejectedSubmission(operation, true);
            }
            System.out.println("PIN PANEL CLEANUP PASS: 3 operations, " + checks + " checks");
        } finally {
            Platform.exit();
        }
    }

    private static void verify(PinState.Operation operation) throws Exception {
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        card.failTransport = true;
        char[] pin = RecoveryCardFixture.pin(6);
        char[] changed = RecoveryCardFixture.pin(6);
        char[] admin = RecoveryCardFixture.pin(8);
        AtomicReference<Callable<CardOperationResult>> work = new AtomicReference<>();
        AtomicReference<Consumer<Boolean>> finished = new AtomicReference<>();
        List<char[]> copies = new ArrayList<>();
        AtomicReference<PinPanel> panel = new AtomicReference<>();
        try {
            fx(() -> {
                PinPanel value = new PinPanel(gateway, (label, callable, callback) -> {
                    try {
                        for (Field field : callable.getClass().getDeclaredFields()) {
                            if (field.getType() == char[].class) {
                                field.setAccessible(true);
                                copies.add((char[]) field.get(callable));
                            } else if (field.getType() == char[][].class) {
                                field.setAccessible(true);
                                copies.addAll(Arrays.asList((char[][]) field.get(callable)));
                            }
                        }
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                    work.set(callable);
                    finished.set(callback);
                    return true;
                }, ignored -> { });
                panel.set(value);
                value.fillInputs(pin, changed, admin);
                boolean accepted = switch (operation) {
                    case VERIFY -> value.pressVerify();
                    case CHANGE -> value.pressChange();
                    case UNBLOCK -> value.pressUnblock();
                };
                check(operation + " accepts the button action", accepted && work.get() != null);
                check(operation + " captures only owned PIN arrays", copies.size() == (operation == PinState.Operation.VERIFY ? 1 : 2)
                        && copies.stream().noneMatch(PinPanelCleanupVerify::allZero));
                List<PasswordField> fields = fields(value.node());
                List<PasswordField> submitted = switch (operation) {
                    case VERIFY -> fields.subList(0, 1);
                    case CHANGE -> fields.subList(1, 4);
                    case UNBLOCK -> fields.subList(4, 7);
                };
                check(operation + " clears submitted input fields before worker", submitted.stream().allMatch(field -> field.getText().isEmpty()));
                return null;
            });
            try {
                work.get().call();
                throw new AssertionError("expected transport failure");
            } catch (CardException expected) {
                check(operation + " propagates transport failure", true);
            }
            check(operation + " wipes copies even when transport throws", copies.stream().allMatch(PinPanelCleanupVerify::allZero));
            fx(() -> {
                finished.get().accept(false);
                check(operation + " reports unchanged not-sent state", labels(panel.get().node()).stream()
                        .anyMatch(text -> text.startsWith("Chưa gửi lệnh PIN")));
                panel.get().clearInputs();
                check(operation + " clears all form inputs on session cleanup", fields(panel.get().node()).stream()
                        .allMatch(field -> field.getText().isEmpty()));
                return null;
            });
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(changed, '\0');
            Arrays.fill(admin, '\0');
            for (char[] copy : copies) Arrays.fill(copy, '\0');
        }
    }

    private static void verifyRejectedSubmission(PinState.Operation operation, boolean throwOnSubmit)
            throws Exception {
        RecoveryCardFixture card = new RecoveryCardFixture();
        CardGateway gateway = card.gateway();
        char[] pin = RecoveryCardFixture.pin(6);
        char[] changed = RecoveryCardFixture.pin(6);
        char[] admin = RecoveryCardFixture.pin(8);
        List<char[]> copies = new ArrayList<>();
        AtomicBoolean runnerCalled = new AtomicBoolean();
        AtomicBoolean workerCalled = new AtomicBoolean();
        AtomicBoolean callbackCalled = new AtomicBoolean();
        AtomicBoolean submitThrown = new AtomicBoolean();
        AtomicBoolean exceptionCaught = new AtomicBoolean();
        AtomicBoolean actionFired = new AtomicBoolean();
        AtomicReference<Callable<CardOperationResult>> work = new AtomicReference<>();
        AtomicReference<Consumer<Boolean>> finished = new AtomicReference<>();
        String scenario = operation + " runner " + (throwOnSubmit ? "throws" : "returns false");
        try {
            fx(() -> {
                PinPanel panel = new PinPanel(gateway, (label, callable, callback) -> {
                    try {
                        for (Field field : callable.getClass().getDeclaredFields()) {
                            if (field.getType() == char[].class) {
                                field.setAccessible(true);
                                copies.add((char[]) field.get(callable));
                            } else if (field.getType() == char[][].class) {
                                field.setAccessible(true);
                                copies.addAll(Arrays.asList((char[][]) field.get(callable)));
                            }
                        }
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                    check(scenario + " captures the expected live PIN arrays at submit",
                            copies.size() == (operation == PinState.Operation.VERIFY ? 1 : 2)
                                    && copies.stream().noneMatch(PinPanelCleanupVerify::allZero));
                    runnerCalled.set(true);
                    work.set(() -> {
                        workerCalled.set(true);
                        return callable.call();
                    });
                    finished.set(ok -> {
                        callbackCalled.set(true);
                        callback.accept(ok);
                    });
                    if (throwOnSubmit) {
                        submitThrown.set(true);
                        throw new IllegalStateException("fixture rejected submission");
                    }
                    return false;
                }, ignored -> { });
                panel.fillInputs(pin, changed, admin);
                try {
                    actionFired.set(switch (operation) {
                        case VERIFY -> panel.pressVerify();
                        case CHANGE -> panel.pressChange();
                        case UNBLOCK -> panel.pressUnblock();
                    });
                } catch (IllegalStateException failure) {
                    if (!"fixture rejected submission".equals(failure.getMessage())) {
                        throw failure;
                    }
                    exceptionCaught.set(true);
                }
                List<PasswordField> fields = fields(panel.node());
                List<PasswordField> submitted = switch (operation) {
                    case VERIFY -> fields.subList(0, 1);
                    case CHANGE -> fields.subList(1, 4);
                    case UNBLOCK -> fields.subList(4, 7);
                };
                check(scenario + " clears submitted fields", submitted.stream()
                        .allMatch(field -> field.getText().isEmpty()));
                return null;
            });
            check(scenario + " does not run worker or callback",
                    runnerCalled.get() && work.get() != null && finished.get() != null
                            && !workerCalled.get() && !callbackCalled.get()
                            && (throwOnSubmit ? submitThrown.get() && exceptionCaught.get()
                                    : !submitThrown.get() && actionFired.get()));
            check(scenario + " wipes captured PIN arrays after return/catch",
                    copies.stream().allMatch(PinPanelCleanupVerify::allZero));
        } finally {
            Arrays.fill(pin, '\0');
            Arrays.fill(changed, '\0');
            Arrays.fill(admin, '\0');
            for (char[] copy : copies) Arrays.fill(copy, '\0');
        }
    }

    private static List<PasswordField> fields(Parent parent) {
        List<PasswordField> fields = new ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof PasswordField field) fields.add(field);
            else if (child instanceof Parent nested) fields.addAll(fields(nested));
        }
        return fields;
    }

    private static List<String> labels(Parent parent) {
        List<String> result = new ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Label label) result.add(label.getText());
            else if (child instanceof Parent nested) result.addAll(labels(nested));
        }
        return result;
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
}
