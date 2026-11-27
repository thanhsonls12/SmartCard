package vn.labaccess.desktop;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.Arrays;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiTheme;

import vn.labaccess.desktop.card.CardOperationResult;

import vn.labaccess.desktop.backend.BackendClient;
import vn.labaccess.desktop.backend.StaffIdentity;

import vn.labaccess.desktop.session.CardSession;

import vn.labaccess.desktop.ui.shared.CardTaskRunner;
import vn.labaccess.desktop.ui.shared.PinOperation;
import vn.labaccess.desktop.ui.staff.StaffLoginView;
import vn.labaccess.desktop.ui.staff.StaffSessionBar;

import vn.labaccess.desktop.ui.staff.StaffAudit;

import vn.labaccess.desktop.ui.staff.StaffWorkspace;

public final class StaffApp extends Application {

    private final CardTaskRunner tasks = new CardTaskRunner();
    private CardSession session;
    private StaffWorkspace workspace;
    private StaffLoginView login;
    private StaffSessionBar sessionBar;
    private Scene scene;
    private Stage window;

    @Override
    public void start(Stage stage) {
        window = stage;
        showLogin("");
        scene = new Scene(login.node(),
                Math.min(1180, Screen.getPrimary().getVisualBounds().getWidth() - UiScale.SPACE_5 * 2),
                Math.min(800, Screen.getPrimary().getVisualBounds().getHeight() - UiScale.SPACE_5 * 2));
        UiTheme.apply(scene);
        stage.setTitle("LabAccess — Đăng nhập nhân viên");
        stage.setMinWidth(640);
        stage.setMinHeight(360);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume();
            shutdown();
        });
        stage.show();
        stage.centerOnScreen();
    }

    private void showLogin(String message) {
        workspace = null;
        sessionBar = null;
        session = CardSession.forRole(CardSession.Role.STAFF, this::log);
        login = new StaffLoginView(this::login);
        login.message(message);
        if (scene != null) {
            scene.setRoot(login.node());
            window.setTitle("LabAccess — Đăng nhập nhân viên");
        }
    }

    private void login(String username, char[] password) {
        boolean accepted = tasks.submit("STAFF_LOGIN", () -> {
            try {
                return session.backend().loginStaff(username, password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }, new CardTaskRunner.Callbacks<>(ignored -> { }, () -> login.busy(true),
                ignored -> { }, ignored -> { }, outcome -> {
                    login.busy(false);
                    if (outcome.failure() == null) {
                        showWorkspace(outcome.value());
                    } else {
                        login.message(authMessage(outcome.failure()));
                    }
                }));
        if (!accepted) {
            Arrays.fill(password, '\0');
        }
    }

    private void showWorkspace(StaffIdentity identity) {
        login.clear();
        workspace = new StaffWorkspace(session, tasks, this::runCardOperation, this::runBackendOperation);
        sessionBar = new StaffSessionBar(identity, this::logout);
        BorderPane root = new BorderPane(workspace.node());
        root.setTop(sessionBar.node());
        scene.setRoot(root);
        window.setTitle("LabAccess — Công cụ nhân viên");
        workspace.initialize();
    }

    private void logout() {
        tasks.submit("STAFF_LOGOUT", () -> {
            session.disconnect();
            session.backend().logoutStaff();
            return true;
        }, new CardTaskRunner.Callbacks<>(ignored -> { }, () -> {
            sessionBar.busy(true);
            workspace.taskStarted("STAFF_LOGOUT");
        }, ignored -> { }, ignored -> workspace.synchronizeConnection(), outcome -> {
            sessionBar.busy(false);
            if (outcome.failure() == null) {
                workspace.closeRequested();
                showLogin("Đã đăng xuất.");
            } else {
                workspace.refresh();
                sessionBar.logoutFailed();
                log("Đăng xuất chưa được xác nhận: " + authMessage(outcome.failure()));
            }
        }));
    }

    private static String authMessage(Exception failure) {
        if (failure instanceof BackendClient.Failure rejected) {
            return switch (rejected.statusCode()) {
                case 401 -> "Thông tin đăng nhập không đúng hoặc phiên đã hết hiệu lực.";
                case 429 -> "Quá nhiều lần thử, vui lòng thử lại sau một phút.";
                default -> "Không thể xác thực với Backend (HTTP " + rejected.statusCode() + ").";
            };
        }
        return "Không thể hoàn tất xác thực.";
    }

    private boolean runCardOperation(String label, Callable<CardOperationResult> operation,
            Consumer<Boolean> finished) {
        return submit(label, operation, outcome -> {
            if (outcome.failure() != null) {
                log("[ERROR] " + label + ": " + outcome.failure());
            } else {
                CardOperationResult result = outcome.value();
                log((result.ok() ? "[OK] " : result.superseded() != null ? "[INFO] " : "[FAIL] ")
                        + label + ": " + result.text());
            }
        }, outcome -> {
            workspace.synchronizeConnection();
            if (finished != null) {
                finished.accept(outcome.failure() == null && outcome.value().ok());
            }
        }, outcome -> workspace.cardCompleted(label, outcome));
    }

    private <T> void runBackendOperation(String label, Callable<T> operation,
            Consumer<T> success, Consumer<Exception> failure) {
        submit(label, operation, outcome -> { }, outcome -> workspace.synchronizeConnection(), outcome -> {
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
            Consumer<CardTaskRunner.Outcome<T>> observed, Consumer<CardTaskRunner.Outcome<T>> beforeRelease,
            Consumer<CardTaskRunner.Outcome<T>> afterRelease) {
        Callable<T> authorized = () -> {
            try {
                if (!"DISCONNECT".equals(label)) {
                    session.backend().requireStaff();
                }
                return new StaffAudit(session, this::log).execute(label, operation);
            } finally {
                PinOperation.discard(operation);
            }
        };
        return tasks.submit(label, authorized, new CardTaskRunner.Callbacks<>(
                rejection -> {
                    PinOperation.discard(operation);
                    log("[" + label + "] bỏ qua — "
                        + (rejection == CardTaskRunner.Rejection.CLOSING
                                ? "đang đóng cửa sổ" : "đang xử lý thao tác khác"));
                },
                () -> {
                    sessionBar.busy(true);
                    workspace.taskStarted(label);
                }, observed, beforeRelease, outcome -> {
                    sessionBar.busy(false);
                    if (outcome.failure() instanceof BackendClient.Failure failure && failure.statusCode() == 401) {
                        sessionBar.invalid();
                        workspace.closeRequested();
                        workspace.node().setDisable(true);
                    }
                    afterRelease.accept(outcome);
                }));
    }

    private void shutdown() {
        tasks.close(() -> {
            if (workspace != null) {
                workspace.closeRequested();
                sessionBar.busy(true);
            } else {
                login.clear();
                login.busy(true);
            }
            log("Đóng cửa sổ Staff — khóa thao tác, xếp ngắt kết nối sau worker");
        }, () -> {
            try {
                return session.disconnect();
            } finally {
                session.backend().logoutStaff();
            }
        }, outcome -> {
            log(outcome.failure() == null ? "[OK] Đóng kết nối Staff: " + outcome.value().text()
                    : "[ERROR] Đóng kết nối Staff: " + outcome.failure());
            log("Đóng cửa sổ Staff — thoát");
        }, () -> log("Đóng cửa sổ Staff — quá 5s chưa dọn dẹp xong, cưỡng chế thoát"));
    }

    private void log(String message) {
        if (workspace == null) {
            System.out.println(message);
        } else {
            workspace.log(message);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
