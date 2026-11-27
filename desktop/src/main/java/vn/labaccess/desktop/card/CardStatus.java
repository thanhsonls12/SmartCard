package vn.labaccess.desktop.card;

public record CardStatus(int protocolVersion, int lifecycle, String instanceAid, String cardId,
        StateSource stateSource, int balance, long counter, boolean hasActiveVisit,
        String activeVisitId) {

    public enum StateSource {
        PUBLIC, STUDENT, ADMIN
    }

    public boolean stateAvailable() {
        return stateSource != StateSource.PUBLIC;
    }

    public boolean studentStateAvailable() {
        return stateSource == StateSource.STUDENT;
    }

    public CardStatus publicView() {
        return new CardStatus(protocolVersion, lifecycle, instanceAid, cardId,
                StateSource.PUBLIC, -1, -1, false, "");
    }
}
