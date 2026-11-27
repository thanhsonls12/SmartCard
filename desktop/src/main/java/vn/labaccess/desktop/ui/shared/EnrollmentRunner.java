package vn.labaccess.desktop.ui.shared;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

public interface EnrollmentRunner {

    <T> void run(String label, Callable<T> operation, Consumer<T> onSuccess, Consumer<Exception> onFailure);
}
