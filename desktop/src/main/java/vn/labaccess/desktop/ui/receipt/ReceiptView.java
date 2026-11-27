package vn.labaccess.desktop.ui.receipt;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.Icons;
import vn.labaccess.desktop.ui.foundation.UiScale;

import vn.labaccess.desktop.card.Receipt;

import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class ReceiptView {

    private final Label valueBalance = new Label(UiLabels.EMPTY_VALUE);
    private final Label captionBalance = UiLabels.caption("Chưa có biên nhận");
    private final Label chipOperation = UiLabels.chip();
    private final Label chipSignature = UiLabels.chip();
    private final Label chipLedger = UiLabels.chip();
    private final Label valueCounter = UiLabels.value();
    private final Label valueDelta = UiLabels.value();
    private final Label valueVisit = UiLabels.value();
    private final Label emptyHint = UiLabels.caption("Đọc lại thẻ để xem biên nhận.");
    private final VBox node;
    private final FlowPane details;

    public ReceiptView() {
        valueBalance.getStyleClass().add("la-receipt-value");

        FlowPane headline = UiRows.wrapping(captionBalance, valueBalance);

        FlowPane chips = UiRows.wrapping(chipOperation, chipSignature, chipLedger);

        details = UiRows.wrapping(
                UiRows.pair(UiLabels.caption("Số giao dịch"), valueCounter),
                UiRows.pair(UiLabels.caption("Số lượt thay đổi"), valueDelta),
                UiRows.valueBlock("Mã ca trên biên nhận", valueVisit));

        emptyHint.setGraphic(Icons.info());
        node = new VBox(UiScale.SPACE_2, headline, chips, emptyHint);
        node.setAlignment(Pos.TOP_LEFT);
        clear("Chưa có biên nhận");
    }

    public VBox node() {
        return node;
    }

    public FlowPane detailsNode() {
        return details;
    }

    public void clear(String reason) {
        UiLabels.show(valueBalance, false);
        UiLabels.show(chipOperation, false);
        UiLabels.show(chipSignature, false);
        UiLabels.show(emptyHint, true);
        captionBalance.setText(reason);
        valueBalance.setText(UiLabels.EMPTY_VALUE);
        chipOperation.setText("Chưa có giao dịch");
        UiLabels.applyChip(chipOperation, UiLabels.Tone.NEUTRAL);
        chipSignature.setText("Chưa kiểm tra chữ ký");
        UiLabels.applyChip(chipSignature, UiLabels.Tone.NEUTRAL);
        showLedger(false);
        valueCounter.setText(UiLabels.EMPTY_VALUE);
        valueDelta.setText(UiLabels.EMPTY_VALUE);
        valueVisit.setText(UiLabels.EMPTY_VALUE);
    }

    private void showLedger(boolean pending) {
        chipLedger.setText(pending ? "Chưa lưu vào sổ — đang chờ đối soát" : "");
        chipLedger.setVisible(pending);
        chipLedger.setManaged(pending);
        UiLabels.applyChip(chipLedger, pending ? UiLabels.Tone.PENDING : UiLabels.Tone.NEUTRAL);
    }

    public void show(Receipt receipt, boolean signatureChecked, boolean signatureOk, boolean ledgerPending) {
        if (receipt == null) {
            clear("Chưa có biên nhận");
            return;
        }
        UiLabels.show(valueBalance, true);
        UiLabels.show(chipOperation, true);
        UiLabels.show(chipSignature, true);
        UiLabels.show(emptyHint, false);
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
            case Receipt.OPERATION_GRANT -> "Cấp lượt";
            case Receipt.OPERATION_START -> "Mở ca";
            case Receipt.OPERATION_END -> "Đóng ca";
            default -> String.format("Không rõ (%02X)", operation);
        };
    }
}
