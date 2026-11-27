package vn.labaccess.desktop.card;

public record CardOperationResult(boolean ok, String text, String warning, SupersededProof superseded) {

    public record SupersededProof(String journalContents, CardStatus state, long expectedCounter,
            String lastPayload, String occupiedPayload) {
        public boolean fromStudent() {
            return state.studentStateAvailable();
        }

        public String describe() {
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

    public static CardOperationResult ok(String text) {
        return new CardOperationResult(true, text);
    }

    public static CardOperationResult fail(String text) {
        return new CardOperationResult(false, text);
    }

    public static CardOperationResult warning(String text, String warning) {
        return new CardOperationResult(true, text, warning);
    }

    public static CardOperationResult awaitingConfirmation(SupersededProof proof) {
        return new CardOperationResult(false, proof.describe() + " | Chờ nhân viên xác nhận", null, proof);
    }
}
