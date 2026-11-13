package vn.labaccess.desktop;

record CardOperationResult(boolean ok, String text) {

    static CardOperationResult ok(String text) {
        return new CardOperationResult(true, text);
    }

    static CardOperationResult fail(String text) {
        return new CardOperationResult(false, text);
    }
}
