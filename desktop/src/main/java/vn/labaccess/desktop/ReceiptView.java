package vn.labaccess.desktop;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

final class ReceiptView {

    private final Label valueBalance = new Label("-");
    private final Label captionBalance = UiLabels.caption("Chưa có biên nhận");
    private final Label chipOperation = UiLabels.chip();
    private final Label chipSignature = UiLabels.chip();
    private final Label chipLedger = UiLabels.chip();
    private final Label valueCounter = UiLabels.value();
    private final Label valueDelta = UiLabels.value();
    private final Label valueVisit = UiLabels.value();
    private final VBox node;
    private final FlowPane details;

    ReceiptView() {
        valueBalance.getStyleClass().add("la-receipt-value");

        FlowPane headline = UiRows.wrapping(captionBalance, valueBalance);

        FlowPane chips = UiRows.wrapping(chipOperation, chipSignature, chipLedger);

        details = UiRows.wrapping(
                UiRows.pair(UiLabels.caption("Số giao dịch"), valueCounter),
                UiRows.pair(UiLabels.caption("Số lượt thay đổi"), valueDelta),
                UiRows.valueBlock("Mã ca trên biên nhận", valueVisit));

        node = new VBox(UiScale.SPACE_2, headline, chips);
        node.setAlignment(Pos.TOP_LEFT);
        clear("Chưa có biên nhận");
    }

    VBox node() {
        return node;
    }

    FlowPane detailsNode() {
        return details;
    }

    void clear(String reason) {
        UiLabels.show(valueBalance, false);
        UiLabels.show(chipOperation, false);
        UiLabels.show(chipSignature, false);
        captionBalance.setText(reason);
        valueBalance.setText("-");
        chipOperation.setText("Chưa có giao dịch");
        UiLabels.applyChip(chipOperation, UiLabels.Tone.NEUTRAL);
        chipSignature.setText("Chưa kiểm tra chữ ký");
        UiLabels.applyChip(chipSignature, UiLabels.Tone.NEUTRAL);
        showLedger(false);
        valueCounter.setText("-");
        valueDelta.setText("-");
        valueVisit.setText("-");
    }

    private void showLedger(boolean pending) {
        chipLedger.setText(pending ? "Chưa lưu vào sổ — đang chờ đối soát" : "");
        chipLedger.setVisible(pending);
        chipLedger.setManaged(pending);
        UiLabels.applyChip(chipLedger, pending ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL);
    }

    /**
     * Hiển thị một biên nhận đã đọc.
     *
     * @param signatureChecked gateway đã chạy xác minh chữ ký hay chưa
     * @param signatureOk      kết quả xác minh, chỉ dùng khi đã kiểm tra
     * @param ledgerPending    giao dịch còn chờ đối soát, chưa ghi vào sổ
     */
    void show(Receipt receipt, boolean signatureChecked, boolean signatureOk, boolean ledgerPending) {
        if (receipt == null) {
            clear("Chưa có biên nhận");
            return;
        }
        UiLabels.show(valueBalance, true);
        UiLabels.show(chipOperation, true);
        UiLabels.show(chipSignature, true);
        captionBalance.setText("Số lượt sau giao dịch");
        valueBalance.setText(receipt.balanceAfter() + " lượt");

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
        showLedger(ledgerPending);
    }

    private static String operationLabel(int operation) {
        return switch (operation) {
            case Receipt.OPERATION_GRANT -> "Cấp hạn mức";
            case Receipt.OPERATION_START -> "Mở ca";
            case Receipt.OPERATION_END -> "Đóng ca";
            default -> String.format("Không rõ (%02X)", operation);
        };
    }
}
