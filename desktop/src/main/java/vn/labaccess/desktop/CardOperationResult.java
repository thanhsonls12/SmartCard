package vn.labaccess.desktop;

record CardOperationResult(boolean ok, String text, String warning) {

    CardOperationResult(boolean ok, String text) {
        this(ok, text, null);
    }

    static CardOperationResult ok(String text) {
        return new CardOperationResult(true, text);
    }

    static CardOperationResult fail(String text) {
        return new CardOperationResult(false, text);
    }

    static CardOperationResult warning(String text, String warning) {
        return new CardOperationResult(true, text, warning);
    }
}
