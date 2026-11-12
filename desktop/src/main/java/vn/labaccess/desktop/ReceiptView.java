package vn.labaccess.desktop;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * Khối kết quả biên nhận: số dư là giá trị chính, trạng thái là chip, phần còn lại là thông tin phụ.
 *
 * Nhận dữ liệu đã có từ {@link CardGateway} — không đọc thẻ và không tự xác minh lại chữ ký.
 */
final class ReceiptView {

    private final Label valueBalance = new Label("-");
    private final Label captionBalance = UiLabels.caption("Chưa có biên nhận");
    private final Label chipOperation = UiLabels.chip();
    private final Label chipSignature = UiLabels.chip();
    private final Label valueCounter = UiLabels.value();
    private final Label valueDelta = UiLabels.value();
    private final Label valueVisit = UiLabels.value();
    private final VBox node;

    ReceiptView() {
        valueBalance.getStyleClass().add("la-receipt-value");

        VBox headline = new VBox(UiScale.SPACE_1, captionBalance, valueBalance);
        headline.setAlignment(Pos.TOP_LEFT);

        FlowPane chips = UiRows.wrapping(chipOperation, chipSignature);

        FlowPane details = UiRows.wrapping(
                UiRows.pair(UiLabels.caption("Lần ghi"), valueCounter),
                UiRows.pair(UiLabels.caption("Số lượt thay đổi"), valueDelta),
                UiRows.valueBlock("Visit ID", valueVisit));

        node = new VBox(UiScale.SPACE_2, headline, chips, details);
        node.setAlignment(Pos.TOP_LEFT);
        clear("Chưa có biên nhận");
    }

    VBox node() {
        return node;
    }

    void clear(String reason) {
        captionBalance.setText(reason);
        valueBalance.setText("-");
        chipOperation.setText("Chưa có giao dịch");
        UiLabels.applyChip(chipOperation, UiLabels.Tone.NEUTRAL);
        chipSignature.setText("Chưa kiểm tra chữ ký");
        UiLabels.applyChip(chipSignature, UiLabels.Tone.NEUTRAL);
        valueCounter.setText("-");
        valueDelta.setText("-");
        valueVisit.setText("-");
    }

    /**
     * Hiển thị một biên nhận đã đọc.
     *
     * @param signatureChecked gateway đã chạy xác minh chữ ký hay chưa
     * @param signatureOk      kết quả xác minh, chỉ dùng khi đã kiểm tra
     */
    void show(Receipt receipt, boolean signatureChecked, boolean signatureOk) {
        if (receipt == null) {
            clear("Chưa có biên nhận");
            return;
        }
        captionBalance.setText("Số dư sau giao dịch");
        valueBalance.setText(String.valueOf(receipt.balanceAfter()));

        chipOperation.setText(operationLabel(receipt.operation()));
        UiLabels.applyChip(chipOperation, UiLabels.Tone.OK);

        if (!signatureChecked) {
            chipSignature.setText("Chưa kiểm tra chữ ký");
            UiLabels.applyChip(chipSignature, UiLabels.Tone.NEUTRAL);
        } else if (signatureOk) {
            chipSignature.setText("Chữ ký hợp lệ");
            UiLabels.applyChip(chipSignature, UiLabels.Tone.OK);
        } else {
            chipSignature.setText("Chữ ký không hợp lệ");
            UiLabels.applyChip(chipSignature, UiLabels.Tone.ERROR);
        }

        valueCounter.setText(String.valueOf(receipt.counterAfter()));
        valueDelta.setText(String.format("%+d", receipt.delta()));
        valueVisit.setText(receipt.visitIdHex());
    }

    private static String operationLabel(int operation) {
        return switch (operation) {
            case Receipt.OPERATION_GRANT -> "Cấp quota";
            case Receipt.OPERATION_START -> "Mở ca";
            case Receipt.OPERATION_END -> "Đóng ca";
            default -> String.format("Không rõ (%02X)", operation);
        };
    }
}
