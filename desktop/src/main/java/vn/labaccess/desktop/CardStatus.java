package vn.labaccess.desktop;

record CardStatus(int protocolVersion, int lifecycle, String instanceAid, String cardId,
        boolean studentStateAvailable, int balance, long counter, boolean hasActiveVisit,
        String activeVisitId) {

    CardStatus publicView() {
        return new CardStatus(protocolVersion, lifecycle, instanceAid, cardId,
                false, -1, -1, false, "");
    }
}
