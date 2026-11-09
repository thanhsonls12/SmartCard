package vn.labaccess.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record ReceiptExpectation(byte[] cardId, long counterAfter, int operation, byte[] visitId) {

    String cardProblem(Receipt receipt) {
        if (cardId == null || Arrays.equals(cardId, receipt.cardId())) {
            return null;
        }
        return String.format("cardId=%s khác thẻ đang nhận diện=%s",
                receipt.cardIdHex(), CardGateway.hex(cardId));
    }

    List<String> transactionProblems(Receipt receipt) {
        List<String> problems = new ArrayList<>();
        if (receipt.operation() != operation) {
            problems.add(String.format("operation=%s khác %s",
                    Receipt.operationText(receipt.operation()),
                    Receipt.operationText(operation)));
        }
        if (operation == Receipt.OPERATION_GRANT) {
            return problems;
        }
        if (visitId == null) {
            problems.add("thiếu visitId của ca cần đối soát");
        } else if (!Arrays.equals(visitId, receipt.visitId())) {
            problems.add(String.format("visitId=%s khác ca cần đối soát=%s",
                    receipt.visitIdHex(), CardGateway.hex(visitId)));
        }
        return problems;
    }
}
