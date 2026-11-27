package vn.labaccess.desktop.backend;

import com.fasterxml.jackson.databind.JsonNode;

import vn.labaccess.desktop.backend.BackendClient.Failure;
import vn.labaccess.desktop.backend.BackendClient.FoundCompleted;
import vn.labaccess.desktop.backend.BackendClient.FoundPending;
import vn.labaccess.desktop.backend.BackendClient.FoundProfileUpdate;
import vn.labaccess.desktop.backend.BackendClient.HistoryEntry;
import vn.labaccess.desktop.backend.BackendClient.Kind;
import vn.labaccess.desktop.backend.BackendClient.LedgerSnapshot;
import vn.labaccess.desktop.backend.BackendClient.ProfileUpdate;
import vn.labaccess.desktop.backend.BackendClient.ProfileUpdateCompletion;
import vn.labaccess.desktop.backend.BackendClient.TransactionHistory;

final class BackendResponse {
    static final int NONCE_HEX_LENGTH = 64;
    static final int CARD_ID_HEX_LENGTH = 16;
    static final int EXPECTED_VERSION_HEX_LENGTH = 24;

    private BackendResponse() {
    }

    static LedgerSnapshot decodeLedger(JsonNode node, int status, String cardIdHex) throws Failure {
        String cardId = requireString(node, "cardId", status);
        if (!cardId.equalsIgnoreCase(cardIdHex)) {
            throw invalid(status, "cardId Backend trả khác thẻ đang đối soát: " + cardId);
        }
        long lastCounter = requireIntegral(node, "lastCounter", status);
        if (lastCounter < 0 || lastCounter > 0xFFFFFFFFL) {
            throw invalid(status, "lastCounter ngoài miền 0..4294967295: " + lastCounter);
        }
        long lastBalance = requireIntegral(node, "lastBalance", status);
        if (lastBalance < 0 || lastBalance > 9999L) {
            throw invalid(status, "lastBalance ngoài miền 0..9999: " + lastBalance);
        }
        JsonNode visitNode = node.get("activeVisitId");
        if (visitNode == null) {
            throw invalid(status, "phản hồi thiếu trường activeVisitId");
        }
        String activeVisitId = null;
        if (!visitNode.isNull()) {
            if (!visitNode.isTextual()) {
                throw invalid(status, "activeVisitId phải là null hoặc chuỗi hex");
            }
            String text = visitNode.textValue();
            if (text.length() != 32 || !text.matches("[0-9a-fA-F]{32}") || text.matches("0{32}")) {
                throw invalid(status, "activeVisitId phải là hex 16 byte khác toàn zero: " + text);
            }
            activeVisitId = text;
        }
        return new LedgerSnapshot(cardId, lastCounter, (int) lastBalance, activeVisitId);
    }

    static TransactionHistory decodeHistory(JsonNode node, int status, String cardIdHex) throws Failure {
        String cardId = requireString(node, "cardId", status);
        if (!cardId.equalsIgnoreCase(cardIdHex)) {
            throw invalid(status, "cardId Backend trả khác thẻ đang xem: " + cardId);
        }
        JsonNode rows = node.get("transactions");
        if (rows == null || !rows.isArray()) {
            throw invalid(status, "phản hồi thiếu mảng transactions");
        }
        java.util.ArrayList<HistoryEntry> entries = new java.util.ArrayList<>();
        long previousCounter = 0;
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) {
                throw invalid(status, "một dòng lịch sử không phải object");
            }
            String id = requireUuid(row, "id", status);
            long counter = requireIntegral(row, "counterAfter", status);
            if (counter < 1 || counter > 0xFFFFFFFFL || counter <= previousCounter) {
                throw invalid(status, "counterAfter không tăng dần trong 1..4294967295: " + counter);
            }
            previousCounter = counter;
            String operation = requireString(row, "operation", status);
            if (!operation.equals("GRANT") && !operation.equals("START") && !operation.equals("END")) {
                throw invalid(status, "operation ngoài GRANT/START/END: " + operation);
            }
            long delta = requireIntegral(row, "delta", status);
            if (delta < -1 || delta > 9999) {
                throw invalid(status, "delta ngoài miền -1..9999: " + delta);
            }
            long balance = requireIntegral(row, "balanceAfter", status);
            if (balance < 0 || balance > 9999) {
                throw invalid(status, "balanceAfter ngoài miền 0..9999: " + balance);
            }
            String verifyStatus = requireString(row, "verifyStatus", status);
            if (!verifyStatus.equals("verified")) {
                throw invalid(status, "verifyStatus phải là verified, nhận " + verifyStatus);
            }
            java.time.Instant recordedAt;
            try {
                recordedAt = java.time.Instant.parse(requireString(row, "recordedAt", status));
            } catch (java.time.format.DateTimeParseException exception) {
                throw invalid(status, "thời gian ghi nhận giao dịch không hợp lệ");
            }
            entries.add(new HistoryEntry(id, counter, operation, (int) delta, (int) balance,
                    verifyStatus, recordedAt));
        }
        return new TransactionHistory(cardId, java.util.List.copyOf(entries));
    }

    static FoundProfileUpdate decodeFoundProfile(JsonNode body, int status) throws Failure {
        String state = requireString(body, "state", status);
        String cardId = requireHex(body, "cardId", status, CARD_ID_HEX_LENGTH,
                "cardId không phải 16 ký tự hex");
        if (state.equals("COMPLETED")) {
            return new FoundCompleted(requireUuid(body, "operationId", status), cardId,
                    requireString(body, "studentId", status), optionalString(body, "classCode"),
                    optionalString(body, "major"), optionalString(body, "completedAt"), status);
        }
        if (!state.equals("PENDING")) {
            throw invalid(status, "state phải là PENDING hoặc COMPLETED, nhận " + state);
        }
        String expectedVersion = requireHex(body, "expectedVersion", status, EXPECTED_VERSION_HEX_LENGTH,
                "expectedVersion không phải 12 byte hex");
        String nonce = requireHex(body, "nonce", status, NONCE_HEX_LENGTH,
                "nonce không phải 32 byte hex");
        return new FoundPending(requireUuid(body, "operationId", status), cardId,
                requireString(body, "studentId", status),
                requireString(body, "displayName", status), optionalString(body, "classCode"),
                expectedVersion, nonce, optionalString(body, "expiresAt"), status);
    }

    static ProfileUpdate decodeProfileRequest(JsonNode body, int status) throws Failure {
        String expectedVersion = requireHex(body, "expectedVersion", status, EXPECTED_VERSION_HEX_LENGTH,
                "expectedVersion không phải 12 byte hex");
        String nonce = requireHex(body, "nonce", status, NONCE_HEX_LENGTH,
                "nonce không phải 32 byte hex");
        return new ProfileUpdate(requireString(body, "operationId", status), nonce,
                expectedVersion, optionalString(body, "expiresAt"), status);
    }

    static ProfileUpdateCompletion decodeProfileCompletion(JsonNode body, int status) throws Failure {
        String cardId = requireHex(body, "cardId", status, CARD_ID_HEX_LENGTH,
                "cardId không phải 16 ký tự hex");
        JsonNode alreadyCompleted = body.get("alreadyCompleted");
        if (alreadyCompleted == null || !alreadyCompleted.isBoolean()) {
            throw invalid(status, "phản hồi thiếu alreadyCompleted");
        }
        return new ProfileUpdateCompletion(requireString(body, "operationId", status), cardId,
                requireString(body, "studentId", status), optionalString(body, "major"),
                optionalString(body, "classCode"), optionalString(body, "completedAt"),
                alreadyCompleted.booleanValue(), status);
    }

    static String requireHex(JsonNode body, String key, int status, int length, String detail)
            throws Failure {
        String value = requireString(body, key, status);
        if (!value.matches("[0-9A-Fa-f]{" + length + "}")) {
            throw invalid(status, detail);
        }
        return value;
    }

    static String optionalString(JsonNode body, String key) {
        JsonNode value = body.get(key);
        return value == null || !value.isTextual() ? "" : value.asText();
    }

    static Failure invalid(int status, String detail) {
        return new Failure(String.format("HTTP %d | %s", status, detail),
                Kind.INVALID_RESPONSE, status, true);
    }

    static long requireIntegral(JsonNode body, String key, int status) throws Failure {
        JsonNode value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new Failure(String.format("HTTP %d | phản hồi thiếu số nguyên hợp lệ ở %s", status, key),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return value.longValue();
    }

    static String requireString(JsonNode body, String key, int status) throws Failure {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new Failure(String.format("HTTP %d | phản hồi thiếu trường %s", status, key),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return value.asText();
    }

    static String requireUuid(JsonNode body, String key, int status) throws Failure {
        String value = requireString(body, key, status);
        if (!value.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) {
            throw new Failure(String.format("HTTP %d | %s không phải UUID", status, key),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return value;
    }
}
