package vn.labaccess.desktop.ui.shared;

import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;

import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;

public final class JournalRecoveryView {

    private JournalRecoveryView() {
    }

    public static boolean needed(PendingJournal.Loaded loaded) {
        return loaded.present() && (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING);
    }

    public static String summary(PendingJournal.Loaded loaded) {
        return loaded.recoverySummary();
    }

    public static String instructions(PendingJournal.Loaded loaded, Path file) {
        String details = "Tệp cần kiểm tra: " + file.toAbsolutePath().normalize();
        if (loaded.valid()) {
            PendingJournal.Entry entry = loaded.entry();
            details += "\nTrạng thái: " + loaded.status()
                    + "\nMã thẻ: " + entry.cardId()
                    + "\nSố giao dịch dự kiến: " + entry.expectedCounter()
                    + "\nThao tác: " + Receipt.operationText(entry.operation());
        } else {
            details += "\nNguyên nhân: " + loaded.problem();
        }
        return details + "\n\n1. Giữ nguyên tệp và dừng giao dịch mới."
                + "\n2. Gửi thông tin trên và Nhật ký thao tác cho người quản trị"
                + " để đối chiếu biên nhận trên thẻ và sổ Backend."
                + "\n3. Chỉ xử lý tệp sau khi đã xác nhận kết quả giao dịch; sau đó mở lại ứng dụng và nhận diện thẻ."
                + "\n\nKhông đổi trạng thái hoặc xóa tệp chỉ để gỡ khóa. Hướng dẫn này không tự sửa dữ liệu.";
    }

    public static void show(Node owner, PendingJournal.Loaded loaded, Path file) {
        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("Hướng dẫn khôi phục nhật ký");
        dialog.setHeaderText(summary(loaded));
        TextArea details = new TextArea(instructions(loaded, file));
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefColumnCount(55);
        details.setPrefRowCount(12);
        dialog.getDialogPane().setContent(details);
        dialog.getButtonTypes().setAll(new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setResizable(true);
        UiLabels.attachDialog(dialog, owner);
        dialog.showAndWait();
    }
}
