package vn.labaccess.desktop;

import javafx.beans.value.ChangeListener;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.control.TextInputControl;

final class InputFeedback {

    static final class Problem extends Event {
        static final EventType<Problem> TYPE = new EventType<>(Event.ANY, "INPUT_PROBLEM");
        private final TextInputControl field;
        private final String message;

        Problem(TextInputControl field, String message) {
            super(TYPE);
            this.field = field;
            this.message = message;
        }
    }

    private final Runnable changed;
    private final ChangeListener<String> edited = (observable, was, now) -> clear();
    private TextInputControl field;
    private String message;

    InputFeedback(Runnable changed) {
        this.changed = changed;
    }

    void show(Problem problem) {
        detach();
        field = problem.field;
        message = problem.message;
        field.textProperty().addListener(edited);
        changed.run();
    }

    String message() {
        return message;
    }

    void clear() {
        if (message == null) {
            return;
        }
        detach();
        message = null;
        changed.run();
    }

    private void detach() {
        if (field != null) {
            field.textProperty().removeListener(edited);
            field = null;
        }
    }
}
