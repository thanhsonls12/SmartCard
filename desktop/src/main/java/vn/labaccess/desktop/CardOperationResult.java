package vn.labaccess.desktop;

record CardOperationResult(boolean ok, String text, String warning, SupersededProof superseded) {

    record SupersededProof(String journalContents, CardStatus state, long expectedCounter,
            String lastPayload, String occupiedPayload) {
        boolean fromStudent() {
            return state.studentStateAvailable();
        }

        String describe() {
            return "cardId=" + state.cardId() + " | expectedCounter=" + expectedCounter
                    + " | receipt cuối ECDSA=PASS payload=" + lastPayload
                    + " | payload ledger tại counter chờ=" + occupiedPayload;
        }
    }

    CardOperationResult(boolean ok, String text, String warning) {
        this(ok, text, warning, null);
    }

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

    static CardOperationResult awaitingConfirmation(SupersededProof proof) {
        return new CardOperationResult(false, proof.describe() + " | Chờ nhân viên xác nhận", null, proof);
    }
}
