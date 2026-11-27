package vn.labaccess.desktop.ui.shared;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javafx.application.Platform;

public final class FxPinSource {

    private static final int PIN_READ_TIMEOUT_SECONDS = 10;

    private FxPinSource() {
    }

    public static char[] take(Supplier<char[]> copy) {
        if (Platform.isFxApplicationThread()) {
            return copy.get();
        }
        CompletableFuture<char[]> request = new CompletableFuture<>();
        Platform.runLater(() -> supply(request, copy));
        try {
            return request.get(PIN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            discard(request);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bị ngắt khi lấy PIN quản trị", interrupted);
        } catch (TimeoutException timeout) {
            discard(request);
            throw new IllegalStateException("quá hạn lấy PIN quản trị", timeout);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("không lấy được PIN quản trị", failure);
        } catch (RuntimeException failure) {
            discard(request);
            throw failure;
        }
    }

    private static void supply(CompletableFuture<char[]> request, Supplier<char[]> copy) {
        if (request.isDone()) {
            return;
        }
        try {
            char[] pin = copy.get();
            if (!request.complete(pin) && pin != null) {
                Arrays.fill(pin, '\0');
            }
        } catch (RuntimeException failure) {
            request.completeExceptionally(failure);
        }
    }

    private static void discard(CompletableFuture<char[]> request) {
        if (!request.complete(null) && !request.isCompletedExceptionally()) {
            char[] pin = request.getNow(null);
            if (pin != null) {
                Arrays.fill(pin, '\0');
            }
        }
    }
}
