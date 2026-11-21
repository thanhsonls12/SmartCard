package vn.labaccess.desktop;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;

final class CardTaskRunner {

    enum Rejection {
        BUSY, CLOSING
    }

    record Outcome<T>(T value, Exception failure) {
    }

    record Callbacks<T>(Consumer<Rejection> rejected, Runnable accepted, Consumer<Outcome<T>> observed,
            Consumer<Outcome<T>> beforeRelease, Consumer<Outcome<T>> afterRelease) {
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "card-io");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch cleanupDone = new CountDownLatch(1);
    private String busyLabel;
    private boolean closing;

    <T> boolean submit(String label, Callable<T> operation, Callbacks<T> callbacks) {
        if (closing) {
            callbacks.rejected().accept(Rejection.CLOSING);
            return false;
        }
        if (isBusy()) {
            callbacks.rejected().accept(Rejection.BUSY);
            return false;
        }
        busyLabel = label;
        callbacks.accepted().run();
        executor.submit(() -> {
            Outcome<T> outcome = call(operation);
            Platform.runLater(() -> {
                callbacks.observed().accept(outcome);
                if (closing) {
                    return;
                }
                callbacks.beforeRelease().accept(outcome);
                busyLabel = null;
                callbacks.afterRelease().accept(outcome);
            });
        });
        return true;
    }

    <T> void close(Runnable beforeCleanup, Callable<T> cleanup, Consumer<Outcome<T>> cleanupFinished,
            Runnable watchdogElapsed) {
        if (closing) {
            return;
        }
        closing = true;
        beforeCleanup.run();
        executor.submit(() -> {
            Outcome<T> outcome = call(cleanup);
            cleanupDone.countDown();
            Platform.runLater(() -> {
                cleanupFinished.accept(outcome);
                executor.shutdownNow();
                Platform.exit();
            });
        });
        startCloseWatchdog(watchdogElapsed);
    }

    private void startCloseWatchdog(Runnable watchdogElapsed) {
        Thread watchdog = new Thread(() -> {
            try {
                if (!cleanupDone.await(5, TimeUnit.SECONDS)) {
                    Platform.runLater(() -> {
                        watchdogElapsed.run();
                        Platform.exit();
                    });
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "close-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static <T> Outcome<T> call(Callable<T> operation) {
        try {
            return new Outcome<>(operation.call(), null);
        } catch (Exception failure) {
            return new Outcome<>(null, failure);
        }
    }

    boolean isBusy() {
        return busyLabel != null;
    }

    boolean isClosing() {
        return closing;
    }

    String busyLabel() {
        return busyLabel;
    }

    boolean cleanupDone() {
        return cleanupDone.getCount() == 0;
    }
}
