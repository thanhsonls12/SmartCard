package vn.labaccess.desktop.ui.shared;

import java.util.Arrays;
import java.util.concurrent.Callable;

public final class PinOperation<T> implements Callable<T> {
    private final Callable<T> operation;
    private final char[][] pins;

    private PinOperation(Callable<T> operation, char[]... pins) {
        this.operation = operation;
        this.pins = pins;
    }

    public static <T> PinOperation<T> of(Callable<T> operation, char[]... pins) {
        return new PinOperation<>(operation, pins);
    }

    @Override
    public T call() throws Exception {
        try {
            return operation.call();
        } finally {
            clear();
        }
    }

    private void clear() {
        for (char[] pin : pins) {
            Arrays.fill(pin, '\0');
        }
    }

    public static void discard(Callable<?> operation) {
        if (operation instanceof PinOperation<?> sensitive) {
            sensitive.clear();
        }
    }
}
