package vn.labaccess.desktop;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class StaffApp extends Application {

    private final CardTaskRunner tasks = new CardTaskRunner();
    private CardSession session;
    private StaffWorkspace workspace;

    @Override
    public void start(Stage stage) {
        session = CardSession.forRole(CardSession.Role.STAFF, this::log);
        workspace = new StaffWorkspace(session, tasks, this::runCardOperation, this::runBackendOperation);
        Scene scene = new Scene(workspace.node(),
                Math.min(1180, Screen.getPrimary().getVisualBounds().getWidth() - UiScale.SPACE_5 * 2),
                Math.min(800, Screen.getPrimary().getVisualBounds().getHeight() - UiScale.SPACE_5 * 2));
        UiTheme.apply(scene);
        stage.setTitle("LabAccess — Công cụ nhân viên");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume();
            shutdown();
        });
        workspace.initialize();
        stage.show();
        stage.centerOnScreen();
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
                workspace.refresh();
            }
        });
    }

    private <T> boolean submit(String label, Callable<T> operation,
            Consumer<CardTaskRunner.Outcome<T>> observed, Consumer<CardTaskRunner.Outcome<T>> beforeRelease,
            Consumer<CardTaskRunner.Outcome<T>> afterRelease) {
        return tasks.submit(label, operation, new CardTaskRunner.Callbacks<>(
                rejection -> log("[" + label + "] bỏ qua — "
                        + (rejection == CardTaskRunner.Rejection.CLOSING
                                ? "đang đóng cửa sổ" : "đang xử lý thao tác khác")),
                () -> workspace.taskStarted(label), observed, beforeRelease, afterRelease));
    }

    private void shutdown() {
        tasks.close(() -> {
            workspace.closeRequested();
            log("Đóng cửa sổ Staff — khóa thao tác, xếp ngắt kết nối sau worker");
        }, session::disconnect, outcome -> {
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
