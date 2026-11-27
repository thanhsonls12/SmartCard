package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import vn.labaccess.desktop.ui.foundation.UiTheme;

import vn.labaccess.desktop.card.CardOperationResult;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.CardSession;
import vn.labaccess.desktop.session.SupersededRecovery;

import vn.labaccess.desktop.ui.shared.CardTaskRunner;
import vn.labaccess.desktop.ui.shared.StaffCredentialsDialog;

import vn.labaccess.desktop.ui.kiosk.KioskOperations;
import vn.labaccess.desktop.ui.kiosk.KioskPinPrompt;
import vn.labaccess.desktop.ui.kiosk.KioskWorkspace;

public final class KioskApp extends Application {

    private final CardTaskRunner tasks = new CardTaskRunner();
    private CardSession session;
    private KioskWorkspace workspace;

    @Override
    public void start(Stage stage) {
        session = CardSession.forRole(CardSession.Role.KIOSK, this::log);
        KioskOperations operations = new KioskOperations(session);
        workspace = new KioskWorkspace(session, operations, tasks, this::runCard, this::runStudent,
                this::confirmOperator, this::runBackend);
        Scene scene = new Scene(workspace.node(),
                Math.min(1060, Screen.getPrimary().getVisualBounds().getWidth() - 40),
                Math.min(800, Screen.getPrimary().getVisualBounds().getHeight() - 80));
        UiTheme.apply(scene);
        stage.setTitle("LabAccess — Kiosk sinh viên");
        stage.setMinWidth(480);
        stage.setMinHeight(360);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume();
            tasks.close(() -> {
                workspace.closing();
                log("Đóng cửa sổ Kiosk — khóa thao tác, xếp ngắt kết nối sau worker");
            }, session::disconnect, result -> {
                log(result.failure() == null ? "[OK] Kiosk ngắt kết nối" : "[ERROR] Kiosk lỗi ngắt kết nối");
                log("Kiosk — thoát");
            }, () -> log("Kiosk — watchdog 5s; chưa xác nhận cleanup hoàn tất"));
        });
        workspace.initialize();
        stage.show();
        stage.centerOnScreen();
    }

    private boolean runCard(String label, Callable<CardOperationResult> operation, Consumer<Boolean> completed) {
        return submit(label, operation, outcome -> {
            workspace.synchronizeConnection();
            completed.accept(outcome.failure() == null && outcome.value().ok());
        }, outcome -> workspace.cardCompleted(label, outcome));
    }

    private void runStudent(KioskPinPrompt.Request request) {
        long revision = workspace.revision();
        boolean accepted;
        try {
            accepted = submit(request.action().label(), () -> workspace.operations().execute(request),
                    outcome -> workspace.synchronizeConnection(), outcome -> {
                        if (workspace.revision() == revision) {
                            workspace.studentCompleted(outcome);
                        } else {
                            workspace.sessionChanged(outcome);
                        }
                    });
        } catch (RuntimeException failure) {
            request.close();
            throw failure;
        }
        if (!accepted) {
            request.close();
        }
    }

    private void confirmOperator(CardOperationResult.SupersededProof proof, char[] pin) {
        long revision = workspace.revision();
        StaffCredentialsDialog.Credentials credentials = StaffCredentialsDialog.ask(workspace.node());
        if (credentials == null) {
            Arrays.fill(pin, '\0');
            return;
        }
        if (tasks.isBusy() || tasks.isClosing() || workspace.revision() != revision
                || !workspace.operations().canConfirm(proof)) {
            credentials.close();
            Arrays.fill(pin, '\0');
            return;
        }
        boolean accepted;
        try {
            accepted = submit("Xác nhận SUPERSEDED", () -> {
                try (credentials) {
                    session.backend().loginStaff(credentials.username(), credentials.password());
                    SupersededRecovery.Confirmation confirmation = workspace.operations().confirm(proof, pin);
                    workspace.logOperatorResponse(proof, confirmation);
                    return confirmation;
                } finally {
                    Arrays.fill(pin, '\0');
                    try {
                        session.backend().logoutStaff();
                    } catch (BackendClient.Failure failure) {
                        log("Không xác nhận được thu hồi phiên nhân viên trên Backend.");
                    } finally {
                        session.backend().forgetStaffSession();
                    }
                }
            }, outcome -> workspace.synchronizeConnection(),
                    outcome -> workspace.operatorCompleted(proof, revision, outcome));
        } catch (RuntimeException failure) {
            credentials.close();
            Arrays.fill(pin, '\0');
            throw failure;
        }
        if (!accepted) {
            credentials.close();
            Arrays.fill(pin, '\0');
        }
    }

    private <T> void runBackend(String label, Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        submit(label, operation, outcome -> workspace.synchronizeConnection(), outcome -> {
            try {
                if (outcome.failure() == null) {
                    success.accept(outcome.value());
                } else {
                    failure.accept(outcome.failure());
                }
            } finally {
                workspace.backendCompleted(label, outcome.failure() == null);
                workspace.refresh();
            }
        });
    }

    private <T> boolean submit(String label, Callable<T> operation,
            Consumer<CardTaskRunner.Outcome<T>> beforeRelease, Consumer<CardTaskRunner.Outcome<T>> afterRelease) {
        return tasks.submit(label, operation, new CardTaskRunner.Callbacks<>(
                rejection -> log("[" + label + "] từ chối: " + rejection),
                () -> workspace.started(label), outcome -> {
                    if (outcome.failure() != null) {
                        log("[ERROR] " + label + ": " + outcome.failure().getClass().getSimpleName());
                    }
                }, beforeRelease, afterRelease));
    }

    private void log(String text) {
        if (workspace == null) {
            System.out.println(text);
        } else {
            workspace.log(text);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
