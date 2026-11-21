package vn.labaccess.desktop;

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import vn.labaccess.desktop.CardOperationResult.SupersededProof;

final class SupersededDialog {

    private SupersededDialog() {
    }

    static char[] confirm(Node owner, SupersededProof proof, int remainingTries) {
        String evidence = String.format("Mã thẻ %s%nGiao dịch khác đã được ghi ở counter %d.",
                proof.state().cardId(), proof.expectedCounter());
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Xác nhận của nhân viên");
        dialog.setHeaderText("Giao dịch chờ đã bị giao dịch khác vượt qua");
        PasswordField pin = new PasswordField();
        pin.setPromptText("PIN quản trị (8 chữ số)");
        Label tries = new Label(remainingTries >= 0
                ? "Số lượt thử PIN quản trị còn lại theo lần đọc gần nhất: " + remainingTries
                : "PIN quản trị có tối đa 5 lượt thử. Hết lượt phải tái cấp thẻ.");
        tries.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(UiScale.SPACE_2, new Label(evidence), tries,
                UiRows.field("PIN quản trị", pin, UiLabels.fieldError())));
        ButtonType confirm = new ButtonType("Xác thực và đóng journal", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, confirm);
        dialog.getDialogPane().lookupButton(confirm).disableProperty().bind(Bindings.createBooleanBinding(
                () -> !pin.getText().matches("[0-9]{" + PersonalizeRequest.ADMIN_PIN_LENGTH + "}"),
                pin.textProperty()));
        UiLabels.attachDialog(dialog, owner);
        try {
            return dialog.showAndWait().filter(confirm::equals).isPresent() ? pin.getText().toCharArray() : null;
        } finally {
            pin.clear();
        }
    }
}
