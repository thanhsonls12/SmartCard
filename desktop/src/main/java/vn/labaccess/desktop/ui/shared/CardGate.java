package vn.labaccess.desktop.ui.shared;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Reconciliation;

public final class CardGate {

    private CardGate() {
    }

    public static String connectionText(CardGateway gateway, String readyText) {
        if (gateway.needsReconnect()) {
            return "Cần kết nối lại";
        }
        if (gateway.isReady()) {
            return readyText;
        }
        return gateway.isConnected() ? "Đã kết nối" : "Chưa kết nối";
    }

    public static UiLabels.Tone connectionTone(CardGateway gateway) {
        if (gateway.needsReconnect()) {
            return UiLabels.Tone.ERROR;
        }
        if (gateway.isReady()) {
            return UiLabels.Tone.OK;
        }
        return gateway.isConnected() ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL;
    }

    public static boolean connectDisabled(CardGateway gateway, boolean busy) {
        return busy || gateway.isConnected() || gateway.needsReconnect();
    }

    public static boolean identifyDisabled(CardGateway gateway, boolean busy) {
        return busy || !gateway.isConnected() || gateway.needsReconnect();
    }

    public static boolean disconnectDisabled(CardGateway gateway, boolean busy) {
        return busy || (!gateway.isConnected() && !gateway.needsReconnect());
    }

    public static boolean unresolved(CardGateway gateway, PendingJournal.Loaded loaded, String legacyProblem) {
        return loaded.present() || legacyProblem != null
                || gateway.reconciliation().state() == Reconciliation.State.UNKNOWN;
    }
}
