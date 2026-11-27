package vn.labaccess.desktop;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiTheme;

import vn.labaccess.desktop.session.CardSession;

import vn.labaccess.desktop.virtualreader.VirtualReaderController;
import vn.labaccess.desktop.virtualreader.VirtualReaderView;

public final class VirtualReaderApp extends Application {
    private VirtualReaderController staff;
    private VirtualReaderController kiosk;
    private VirtualReaderView view;
    private boolean busy;

    @Override
    public void start(Stage window) throws Exception {
        String configured = System.getProperty("labaccess.virtualReader");
        if (configured == null) {
            throw new IllegalStateException("Run the Virtual Reader launcher");
        }
        Path rootPath = Path.of(System.getProperty("user.dir"));
        Path directory = Path.of(configured);
        int port = Integer.getInteger("labaccess.port", 9025);
        staff = new VirtualReaderController(rootPath, directory, port, CardSession.Role.STAFF);
        kiosk = new VirtualReaderController(rootPath, directory, port, CardSession.Role.KIOSK);
        view = new VirtualReaderView(staff, kiosk, port, this::run);
        Scene scene = new Scene(view.node(), 960, 780);
        UiTheme.apply(scene);
        window.setTitle("LabAccess — Virtual Card Reader");
        window.setScene(scene);
        window.setMinWidth(460);
        window.setMinHeight(420);
        var bounds = Screen.getPrimary().getVisualBounds();
        window.setWidth(Math.min(980, bounds.getWidth() - UiScale.SPACE_6));
        window.setHeight(Math.min(820, bounds.getHeight() - UiScale.SPACE_6));
        window.setOnCloseRequest(event -> {
            if (busy) {
                event.consume();
                view.message("Chờ thao tác hiện tại hoàn tất.", false);
            }
        });
        reload();
        window.show();
    }

    private void run(String label, Callable<Void> work) {
        if (busy) {
            return;
        }
        busy = true;
        view.message(label, false);
        refresh();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> finish("Hoàn tất.", false));
        task.setOnFailed(event -> finish(task.getException().getMessage(), true));
        Thread worker = new Thread(task, "virtual-reader-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(String detail, boolean failed) {
        busy = false;
        view.message(detail, failed);
        try {
            reload();
        } catch (java.io.IOException failure) {
            view.message("Không đọc được danh sách: " + failure.getMessage(), true);
            refresh();
        }
    }

    private void reload() throws java.io.IOException {
        view.reload();
        refresh();
    }

    private void refresh() {
        if (view != null) {
            view.refresh(busy);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
