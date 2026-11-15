package vn.labaccess.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

public final class BeforeHttpScenario extends App {
    private final Path signals = Path.of(System.getProperty("labaccess.demo.signalDir"));

    @Override
    public void start(Stage stage) {
        super.start(stage);
        Thread driver = new Thread(() -> {
            char[] admin = null;
            char[] student = null;
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                admin = input.readLine().toCharArray();
                student = input.readLine().toCharArray();
                if (!App.validPin(admin, 8) || !App.validPin(student, 6)) {
                    throw new IllegalArgumentException("Invalid QA PIN input");
                }
                if (System.getProperty("labaccess.qa.mode").equals("first")) {
                    prepareAndStart(admin, student);
                } else {
                    recover(student);
                }
            } catch (Exception failure) {
                System.err.println("SCENARIO_FAILED " + failure);
                failure.printStackTrace(System.err);
                Platform.runLater(() -> stage.fireEvent(new javafx.stage.WindowEvent(
                        stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
            } finally {
                if (admin != null) Arrays.fill(admin, '\0');
                if (student != null) Arrays.fill(student, '\0');
            }
        }, "qa-ui-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private void prepareAndStart(char[] admin, char[] student) throws Exception {
        step("CONNECT", this::pressConnect);
        step("IDENTIFY", this::pressIdentify);
        PersonalizePanel issue = component("personalizePanel", PersonalizePanel.class);
        EnrollmentPanel enroll = component("enrollmentPanel", EnrollmentPanel.class);
        fx(() -> {
            showTab(TAB_CARD);
            issue.fillInputs(System.getProperty("labaccess.demo.cardId"),
                    System.getProperty("labaccess.demo.studentId"), "QA Before HTTP", admin, student);
            Platform.runLater(this::confirmIssueDialog);
            require(issue.pressPersonalize(), "Issue button is disabled");
            return null;
        });
        idle();
        fx(() -> {
            showTab(TAB_ENROLL);
            enroll.fillInputs(System.getProperty("labaccess.demo.studentId"), student);
            require(enroll.pressEnroll(), "Enroll button is disabled");
            return null;
        });
        idle();
        require(fx(enroll::enrolled), "Card enrollment failed");
        fx(() -> {
            showTab(TAB_OPERATIONS);
            fillTransactionInputs(student, null, 5);
            require(pressReadReceipt(), "Read-state button is disabled");
            return null;
        });
        idle();
        fx(() -> {
            fillTransactionInputs(student, admin, 5);
            require(pressGrant(), "Grant button is disabled");
            return null;
        });
        idle();
        fx(() -> {
            logUi10Evidence("QA_BASELINE_BEFORE_START");
            logTransactionState("QA_BASELINE_BEFORE_START");
            return null;
        });
        System.out.println(Instant.now() + " SCENARIO_BASELINE_READY");
        signal("go-start.flag");
        fx(() -> {
            fillTransactionInputs(student, null, 5);
            require(pressStart(), "Start button is disabled");
            return null;
        });
        System.out.println(Instant.now() + " SCENARIO_START_PRESSED_ONCE");
        Path evidence = Path.of(System.getProperty("labaccess.qa.evidence"));
        awaitFile(evidence.resolve("gate-held.txt"));
        fx(() -> {
            captureScene(evidence.resolve("before-close-gated.png"));
            return null;
        });
        System.out.println(Instant.now() + " SCENARIO_GATED_CAPTURED");
        closeWhenSignalled();
    }

    private void confirmIssueDialog() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof Stage stage
                    && stage.getTitle().equals("Xác nhận phát hành thẻ")
                    && window.getScene().getRoot() instanceof DialogPane pane) {
                for (ButtonType type : pane.getButtonTypes()) {
                    if (type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE
                            && pane.lookupButton(type) instanceof Button button) {
                        button.fire();
                        System.out.println(Instant.now() + " SCENARIO_ISSUE_DIALOG_CONFIRMED");
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("Expected issue dialog not found");
    }

    private void closeWhenSignalled() throws Exception {
        signal("go-close-" + System.getProperty("labaccess.qa.mode") + ".flag");
        fx(() -> {
            Stage owner = (Stage) Window.getWindows().stream()
                    .filter(window -> window instanceof Stage stage && stage.getTitle().startsWith("LabAccess"))
                    .findFirst().orElseThrow();
            System.out.println(Instant.now() + " SCENARIO_CLOSE_WINDOW_REQUESTED");
            owner.fireEvent(new javafx.stage.WindowEvent(owner, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });
    }

    private void recover(char[] student) throws Exception {
        fx(() -> {
            logUi10Evidence("QA_REOPENED_BEFORE_CONNECT");
            captureScene(Path.of(System.getProperty("labaccess.qa.evidence"), "reopened-pending-locked.png"));
            return null;
        });
        System.out.println(Instant.now() + " SCENARIO_REOPENED_CAPTURED");
        signal("go-connect.flag");
        step("CONNECT", this::pressConnect);
        step("IDENTIFY", this::pressIdentify);
        CompletableFuture<Boolean> reconcileClick = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                fillTransactionInputs(student, null, 5);
                reconcileClick.complete(pressReconcile());
            } catch (Exception failure) {
                reconcileClick.completeExceptionally(failure);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!fx(this::reconcileDialogVisible)) {
            require(!reconcileClick.isDone(), "Reconcile button did not open its dialog");
            require(System.nanoTime() < deadline, "Reconciliation dialog did not open");
            Thread.sleep(100);
        }
        System.out.println(Instant.now() + " SCENARIO_WAIT_GO_OK");
        signal("go-ok.flag");
        fx(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window instanceof Stage stage
                        && stage.getTitle().equals("Xác nhận đối soát")
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    for (ButtonType type : pane.getButtonTypes()) {
                        if (type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE
                                && pane.lookupButton(type) instanceof Button button && !button.isDisabled()) {
                            button.fire();
                            System.out.println(Instant.now() + " SCENARIO_RECONCILE_CONFIRMED_ONCE");
                            return null;
                        }
                    }
                }
            }
            throw new IllegalStateException("Enabled reconciliation confirmation button not found");
        });
        require(reconcileClick.get(20, TimeUnit.SECONDS), "Reconcile button was disabled");
        idle();
        fx(() -> {
            logUi10Evidence("QA_RECOVERED");
            logTransactionState("QA_RECOVERED");
            captureScene(Path.of(System.getProperty("labaccess.qa.evidence"), "recovered-card-ledger.png"));
            return null;
        });
        System.out.println(Instant.now() + " SCENARIO_RECOVERY_COMPLETE");
        closeWhenSignalled();
    }

    private void signal(String name) throws Exception {
        awaitFile(signals.resolve(name));
    }

    private void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (!Files.exists(file)) {
            require(System.nanoTime() < deadline, "Signal timed out: " + file.getFileName());
            Thread.sleep(100);
        }
    }

    private void step(String name, Callable<Boolean> press) throws Exception {
        require(fx(press), "Disabled button: " + name);
        idle();
    }

    private void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (fx(this::isBusy)) {
            require(System.nanoTime() < deadline, "Card operation did not finish");
            Thread.sleep(100);
        }
    }

    private <T> T fx(Callable<T> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(operation.call());
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(90, TimeUnit.SECONDS);
    }

    private <T> T component(String name, Class<T> type) throws Exception {
        Field field = App.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(this));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static void main(String[] args) {
        Application.launch(BeforeHttpScenario.class, args);
    }
}
