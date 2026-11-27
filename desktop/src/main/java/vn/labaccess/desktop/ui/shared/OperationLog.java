package vn.labaccess.desktop.ui.shared;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;

public final class OperationLog {
    public enum View {
        LEGACY, STAFF, KIOSK
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int LOG_MAX_CHARS = 200_000;
    private static final int LOG_TRIM_CHARS = 20_000;

    private final View view;
    private TextArea logArea;
    private boolean logUiClosed;

    public OperationLog(View view) {
        this.view = view;
    }

    public TitledPane createPane() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        String title = switch (view) {
            case LEGACY -> "Nhật ký thao tác";
            case STAFF -> "Nhật ký thao tác Staff";
            case KIOSK -> "Nhật ký Kiosk";
        };
        TitledPane pane = new TitledPane(title, logArea);
        if (view != View.KIOSK) {
            logArea.getStyleClass().add("la-log");
            pane.getStyleClass().add("la-log-pane");
        }
        if (view == View.LEGACY) {
            pane.setMaxHeight(Double.MAX_VALUE);
        }
        return pane;
    }

    public void append(String message) {
        String line = view == View.KIOSK ? message : timestamped(message);
        System.out.println(line);
        if (Platform.isFxApplicationThread()) {
            showLogLine(line);
            return;
        }
        if (logUiClosed) {
            return;
        }
        try {
            Platform.runLater(() -> showLogLine(line));
        } catch (IllegalStateException toolkitStopped) {
            logUiClosed = view != View.KIOSK;
            if (view == View.LEGACY) {
                System.out.println(timestamped("ngừng vẽ nhật ký: " + toolkitStopped.getMessage()));
            } else if (view == View.KIOSK) {
                System.out.println("Kiosk đã ngừng hiển thị nhật ký.");
            }
        }
    }

    private static String timestamped(String message) {
        return "[" + LocalTime.now().format(TS) + "] " + message;
    }

    private void showLogLine(String line) {
        if (logArea == null) {
            return;
        }
        logArea.appendText(line + System.lineSeparator());
        trimLogArea();
        if (view != View.KIOSK) {
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void trimLogArea() {
        int length = logArea.getLength();
        int trim = switch (view) {
            case LEGACY -> {
                int overflow = length - LOG_MAX_CHARS;
                if (overflow >= LOG_TRIM_CHARS) {
                    int end = logArea.getText(0, Math.min(overflow + 200, length)).indexOf('\n');
                    yield end < 0 ? overflow : end + 1;
                }
                yield 0;
            }
            case STAFF -> {
                if (length > LOG_MAX_CHARS + LOG_TRIM_CHARS) {
                    int end = logArea.getText(0, LOG_TRIM_CHARS).lastIndexOf('\n');
                    yield end < 0 ? LOG_TRIM_CHARS : end + 1;
                }
                yield 0;
            }
            case KIOSK -> length > LOG_MAX_CHARS ? LOG_TRIM_CHARS : 0;
        };
        if (trim > 0) {
            logArea.deleteText(0, trim);
        }
    }
}
