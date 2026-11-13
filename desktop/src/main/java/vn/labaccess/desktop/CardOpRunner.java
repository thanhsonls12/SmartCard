package vn.labaccess.desktop;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

interface CardOpRunner {

    void run(String label, Callable<CardOperationResult> operation, Consumer<Boolean> onResult);
}
