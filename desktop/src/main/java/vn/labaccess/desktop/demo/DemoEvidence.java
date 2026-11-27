package vn.labaccess.desktop.demo;

import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.Reconciliation;

public record DemoEvidence(
        PendingJournal.Loaded loaded,
        CardStatus current,
        Reconciliation.State reconciliation,
        String enrollment,
        String grant,
        String start,
        String end,
        String reconcileButton,
        String statusText) {
    public String reconcile(String label) {
        String journalState = !loaded.present() ? "khong-co"
                : !loaded.valid() ? "KHONG-HOP-LE"
                : loaded.status().name();
        String cardState = current == null || !current.studentStateAvailable()
                ? "state-the-khong-kha-dung"
                : String.format("counter=%d balance=%d caMo=%s", current.counter(), current.balance(),
                        current.hasActiveVisit() ? current.activeVisitId() : "khong");
        return String.format(
                "SIGN-08 %s | journal=%s | doiSoat=%s | the %s | grant=%s start=%s end=%s"
                        + " | nutDoiSoat=%s | nhan=%s",
                label, journalState, reconciliation, cardState,
                grant, start,
                end, reconcileButton,
                statusText);
    }

    public String ui10(String label, boolean dialogVisible, boolean connected, boolean ready) {
        String journalState = !loaded.present() ? "khong-co"
                : !loaded.valid() ? "KHONG-HOP-LE (" + loaded.problem() + ")"
                : String.format("%s cardId=%s counter=%d operation=%s",
                        loaded.status(), loaded.entry().cardId(),
                        loaded.entry().expectedCounter(),
                        Receipt.operationText(loaded.entry().operation()));
        String cardState = current == null ? "chua-nhan-dien"
                : !current.studentStateAvailable()
                        ? "cardId=" + current.cardId() + " state-student-khong-kha-dung"
                        : String.format("cardId=%s counter=%d balance=%d caMo=%s",
                                current.cardId(), current.counter(), current.balance(),
                                current.hasActiveVisit() ? current.activeVisitId() : "khong");
        return String.format(
                "UI-10 %s | journal=%s | doiSoat=%s | dangKyThe=%s | the %s"
                        + " | grant=%s start=%s end=%s | nutDoiSoat=%s | hoThoaiDangMo=%s"
                        + " | ketNoi=%s nhanDien=%s | nhan=%s",
                label, journalState, reconciliation, enrollment,
                cardState, grant, start,
                end, reconcileButton,
                dialogVisible, connected, ready,
                statusText);
    }


    public static boolean dialogVisible() {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().getRoot() instanceof javafx.scene.control.DialogPane) {
                return true;
            }
        }
        return false;
    }

    public static void captureScene(javafx.scene.Parent root, java.nio.file.Path path) throws java.io.IOException {
        javafx.scene.image.WritableImage image = root.snapshot(null, null);
        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                (int) image.getWidth(), (int) image.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        java.nio.file.Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(buffered, "png", path.toFile());
    }
}
