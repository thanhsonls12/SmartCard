package vn.labaccess.desktop;

record CardStatus(int protocolVersion, int lifecycle, String instanceAid, String cardId,
        StateSource stateSource, int balance, long counter, boolean hasActiveVisit,
        String activeVisitId) {

    enum StateSource {
        PUBLIC, STUDENT, ADMIN
    }

    boolean stateAvailable() {
        return stateSource != StateSource.PUBLIC;
    }

    boolean studentStateAvailable() {
        return stateSource == StateSource.STUDENT;
    }

    CardStatus publicView() {
        return new CardStatus(protocolVersion, lifecycle, instanceAid, cardId,
                StateSource.PUBLIC, -1, -1, false, "");
    }
}
