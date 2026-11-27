package vn.labaccess.desktop.ui.shared;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import vn.labaccess.desktop.card.CardOperationResult;

public interface CardOpRunner {

    boolean run(String label, Callable<CardOperationResult> operation, Consumer<Boolean> onResult);
}
