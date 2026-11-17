package vn.labaccess.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client HTTP thuần Java cho ba endpoint enrollment. Không phụ thuộc JavaFX.
 *
 * <p>Mọi lỗi đi qua {@link Failure}, mang theo loại lỗi và mã HTTP: sau
 * {@code /enrollments/complete}, "Backend từ chối" và "đã gửi nhưng không đọc được kết quả" là hai
 * kết luận khác nhau, và chỉ loại thứ hai mới buộc người vận hành đi kiểm tra.
 */
final class BackendClient {

    record AdminSession(String sessionId, String expiresAt, int httpStatus) {
    }

    record Challenge(String challengeId, String nonceHex, String expiresAt, int httpStatus) {
    }

    record Enrollment(String cardId, String studentId, String enrolledAt, int httpStatus) {
    }

    record SavedReceipt(String status, String cardId, long counterAfter, String operation,
            String visitId, int httpStatus) {
    }

    private record Reply(int status, String body) {
    }

    /** Loại lỗi, để nơi gọi kết luận đúng thay vì suy từ mã HTTP. */
    enum Kind {
        /** Không có phản hồi HTTP: timeout, đứt kết nối, bị ngắt. */
        TRANSPORT,
        /** Backend đã trả lời bằng mã lỗi. */
        HTTP_REJECTED,
        /** Backend trả 2xx nhưng thân phản hồi thiếu trường hoặc sai định dạng. */
        INVALID_RESPONSE
    }

    static final class Failure extends Exception {

        private final Kind kind;
        private final int statusCode;
        private final boolean requestSent;

        Failure(String message, Kind kind, int statusCode, boolean requestSent) {
            super(message);
            this.kind = kind;
            this.statusCode = statusCode;
            this.requestSent = requestSent;
        }

        Kind kind() {
            return kind;
        }

        int statusCode() {
            return statusCode;
        }

        boolean requestMayHaveReachedServer() {
            return requestSent;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int REQUEST_TIMEOUT_MS = 15_000;
    private static final int NONCE_HEX_LENGTH = 64;
    private static final int CARD_ID_HEX_LENGTH = 16;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    record Access(java.time.Instant checkedAt, int httpStatus) {
        boolean available() {
            return httpStatus >= 200 && httpStatus < 500;
        }
    }

    private volatile Access lastAccess;

    Access lastAccess() {
        return lastAccess;
    }

    private final HttpClient http;
    private final String baseUrl;

    BackendClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    String baseUrl() {
        return baseUrl;
    }

    void checkDatabase() throws Failure {
        Reply reply = get("health/database");
        JsonNode health = parse(reply);
        if (!"ok".equals(requireString(health, "status", reply.status()))
                || !"postgresql".equals(requireString(health, "provider", reply.status()))) {
            throw invalid(reply, "hệ thống chưa xác nhận kết nối sổ giao dịch");
        }
    }

    AdminSession issueAdminSession() throws Failure {
        Reply reply = post("admin/sessions", null, "{}");
        JsonNode body = parse(reply);
        return new AdminSession(requireUuid(body, "sessionId", reply.status()),
                optionalString(body, "expiresAt"), reply.status());
    }

    Challenge createChallenge(String sessionId, String cardIdHex, String studentId, String publicKeyHex)
            throws Failure {
        String payload = "{\"cardId\":\"" + cardIdHex + "\",\"studentId\":\"" + studentId
                + "\",\"publicKey\":\"" + publicKeyHex + "\"}";
        Reply reply = post("enrollments/challenges", sessionId, payload);
        JsonNode body = parse(reply);
        String nonce = requireString(body, "nonce", reply.status());
        if (!nonce.matches("[0-9A-Fa-f]{" + NONCE_HEX_LENGTH + "}")) {
            throw invalid(reply, "nonce không phải 32 byte hex");
        }
        return new Challenge(requireUuid(body, "challengeId", reply.status()), nonce,
                optionalString(body, "expiresAt"), reply.status());
    }

    Enrollment completeEnrollment(String sessionId, String challengeId, String signatureHex)
            throws Failure {
        String payload = "{\"challengeId\":\"" + challengeId + "\",\"signature\":\""
                + signatureHex + "\"}";
        Reply reply = post("enrollments/complete", sessionId, payload);
        JsonNode body = parse(reply);
        String cardId = requireString(body, "cardId", reply.status());
        if (!cardId.matches("[0-9A-Fa-f]{" + CARD_ID_HEX_LENGTH + "}")) {
            throw invalid(reply, "cardId không phải 16 ký tự hex");
        }
        return new Enrollment(cardId, requireString(body, "studentId", reply.status()),
                requireString(body, "enrolledAt", reply.status()), reply.status());
    }

    /**
     * Gửi receipt đã ký lên Backend và đối chiếu phản hồi với chính receipt đó: 201 không đủ để coi
     * là đã lưu, phải đúng định danh và đúng giá trị thì ledger mới được coi là khớp.
     */
    SavedReceipt verifyReceipt(Receipt receipt) throws Failure {
        String body = "{\"payload\":\"" + receipt.payloadHex() + "\",\"signature\":\""
                + receipt.signatureHex() + "\"}";
        Reply reply = post("receipts/verify", null, body);
        JsonNode node = parse(reply);

        String status = requireString(node, "status", reply.status());
        if (!status.equals("recorded") && !status.equals("duplicate")) {
            throw invalid(reply, "status phải là recorded hoặc duplicate, nhận " + status);
        }
        String cardId = requireString(node, "cardId", reply.status());
        String operation = requireString(node, "operation", reply.status());
        long counter = requireIntegral(node, "counterAfter", reply.status());
        long delta = requireIntegral(node, "delta", reply.status());
        long balance = requireIntegral(node, "balanceAfter", reply.status());
        JsonNode visitNode = node.get("visitId");
        if (visitNode == null) {
            throw invalid(reply, "phản hồi thiếu trường visitId");
        }
        String visitId;
        if (receipt.operation() == Receipt.OPERATION_GRANT) {
            if (!visitNode.isNull()) {
                throw invalid(reply, "GRANT phải trả visitId là null, nhận " + visitNode);
            }
            visitId = "";
        } else {
            if (!visitNode.isTextual()) {
                throw invalid(reply, "START/END phải trả visitId dạng chuỗi hex");
            }
            visitId = visitNode.textValue();
            if (visitId.isEmpty() || !visitId.equalsIgnoreCase(receipt.visitIdHex())) {
                throw invalid(reply, "visitId Backend trả khác receipt đã gửi: " + visitId);
            }
        }

        if (!cardId.equalsIgnoreCase(receipt.cardIdHex())) {
            throw invalid(reply, "cardId Backend trả khác receipt đã gửi: " + cardId);
        }
        if (counter != receipt.counterAfter()) {
            throw invalid(reply, "counterAfter Backend trả khác receipt đã gửi: " + counter);
        }
        if (!operation.equals(expectedOperationText(receipt.operation()))) {
            throw invalid(reply, "operation Backend trả khác receipt đã gửi: " + operation);
        }
        if (delta != receipt.delta()) {
            throw invalid(reply, "delta Backend trả khác receipt đã gửi: " + delta);
        }
        if (balance != receipt.balanceAfter()) {
            throw invalid(reply, "balanceAfter Backend trả khác receipt đã gửi: " + balance);
        }
        return new SavedReceipt(status, cardId, counter, operation, visitId, reply.status());
    }

    private static String expectedOperationText(int operation) {
        return switch (operation) {
            case Receipt.OPERATION_GRANT -> "GRANT";
            case Receipt.OPERATION_START -> "START";
            case Receipt.OPERATION_END -> "END";
            default -> "";
        };
    }

    /** Số nguyên phải nằm gọn trong long trước khi dùng; không cắt bớt giá trị để so sánh. */
    private static long requireIntegral(JsonNode body, String key, int status) throws Failure {
        JsonNode value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new Failure(String.format("HTTP %d | phản hồi thiếu số nguyên hợp lệ ở %s", status, key),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return value.longValue();
    }

    private Reply post(String path, String adminSessionId, String payload) throws Failure {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (adminSessionId != null) {
            builder.header("x-admin-session", adminSessionId);
        }
        return send(builder.build(), 201);
    }

    private Reply get(String path) throws Failure {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .GET()
                .build();
        return send(request, 200);
    }

    private Reply send(HttpRequest request, int expectedStatus) throws Failure {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException timeout) {
            lastAccess = new Access(java.time.Instant.now(), 0);
            throw new Failure("không kết nối được Backend trong " + CONNECT_TIMEOUT_MS
                    + " ms", Kind.TRANSPORT, 0, false);
        } catch (HttpTimeoutException timeout) {
            lastAccess = new Access(java.time.Instant.now(), 0);
            throw new Failure("Backend không phản hồi trong " + REQUEST_TIMEOUT_MS + " ms",
                    Kind.TRANSPORT, 0, true);
        } catch (ConnectException refused) {
            lastAccess = new Access(java.time.Instant.now(), 0);
            throw new Failure("không mở được kết nối tới Backend: " + refused.getMessage(),
                    Kind.TRANSPORT, 0, false);
        } catch (IOException failure) {
            lastAccess = new Access(java.time.Instant.now(), 0);
            throw new Failure("đứt kết nối khi đang chờ phản hồi: " + failure.getMessage(),
                    Kind.TRANSPORT, 0, true);
        } catch (InterruptedException interrupted) {
            lastAccess = new Access(java.time.Instant.now(), 0);
            Thread.currentThread().interrupt();
            throw new Failure("bị ngắt khi đang gọi Backend", Kind.TRANSPORT, 0, true);
        }
        int status = response.statusCode();
        lastAccess = new Access(java.time.Instant.now(), status);
        if (status >= 400) {
            throw new Failure(describeError(status, response.body()), Kind.HTTP_REJECTED, status, true);
        }
        if (status != expectedStatus) {
            throw new Failure(String.format(
                    "HTTP %d | Backend trả mã không đúng hợp đồng (cần %d)", status, expectedStatus),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return new Reply(status, response.body());
    }

    record LedgerSnapshot(String cardId, long lastCounter, int lastBalance, String activeVisitId) {
    }

    record HistoryEntry(String id, long counterAfter, String operation, int delta, int balanceAfter,
            String verifyStatus, java.time.Instant recordedAt) {
    }

    record TransactionHistory(String cardId, java.util.List<HistoryEntry> entries) {
    }

    record HistoryLoad(TransactionHistory history, LedgerSnapshot ledger) {
    }

    /** Đọc mốc ledger của thẻ; sai schema hoặc trả định danh khác thẻ đang đối soát đều là lỗi. */
    LedgerSnapshot ledgerOf(String cardIdHex) throws Failure {
        Reply reply = get("receipts/ledger/" + cardIdHex);
        JsonNode node = parse(reply);

        String cardId = requireString(node, "cardId", reply.status());
        if (!cardId.equalsIgnoreCase(cardIdHex)) {
            throw invalid(reply, "cardId Backend trả khác thẻ đang đối soát: " + cardId);
        }
        long lastCounter = requireIntegral(node, "lastCounter", reply.status());
        if (lastCounter < 0 || lastCounter > 0xFFFFFFFFL) {
            throw invalid(reply, "lastCounter ngoài miền 0..4294967295: " + lastCounter);
        }
        long lastBalance = requireIntegral(node, "lastBalance", reply.status());
        if (lastBalance < 0 || lastBalance > 9999L) {
            throw invalid(reply, "lastBalance ngoài miền 0..9999: " + lastBalance);
        }
        JsonNode visitNode = node.get("activeVisitId");
        if (visitNode == null) {
            throw invalid(reply, "phản hồi thiếu trường activeVisitId");
        }
        String activeVisitId = null;
        if (!visitNode.isNull()) {
            if (!visitNode.isTextual()) {
                throw invalid(reply, "activeVisitId phải là null hoặc chuỗi hex");
            }
            String text = visitNode.textValue();
            if (text.length() != 32 || !text.matches("[0-9a-fA-F]{32}") || text.matches("0{32}")) {
                throw invalid(reply, "activeVisitId phải là hex 16 byte khác toàn zero: " + text);
            }
            activeVisitId = text;
        }
        return new LedgerSnapshot(cardId, lastCounter, (int) lastBalance, activeVisitId);
    }

    TransactionHistory transactionsOf(String cardIdHex) throws Failure {
        Reply reply = get("cards/" + cardIdHex + "/transactions");
        JsonNode node = parse(reply);
        String cardId = requireString(node, "cardId", reply.status());
        if (!cardId.equalsIgnoreCase(cardIdHex)) {
            throw invalid(reply, "cardId Backend trả khác thẻ đang xem: " + cardId);
        }
        JsonNode rows = node.get("transactions");
        if (rows == null || !rows.isArray()) {
            throw invalid(reply, "phản hồi thiếu mảng transactions");
        }
        java.util.ArrayList<HistoryEntry> entries = new java.util.ArrayList<>();
        long previousCounter = 0;
        for (JsonNode row : rows) {
            if (row == null || !row.isObject()) {
                throw invalid(reply, "một dòng lịch sử không phải object");
            }
            String id = requireUuid(row, "id", reply.status());
            long counter = requireIntegral(row, "counterAfter", reply.status());
            if (counter < 1 || counter > 0xFFFFFFFFL || counter <= previousCounter) {
                throw invalid(reply, "counterAfter không tăng dần trong 1..4294967295: " + counter);
            }
            previousCounter = counter;
            String operation = requireString(row, "operation", reply.status());
            if (!operation.equals("GRANT") && !operation.equals("START") && !operation.equals("END")) {
                throw invalid(reply, "operation ngoài GRANT/START/END: " + operation);
            }
            long delta = requireIntegral(row, "delta", reply.status());
            if (delta < -1 || delta > 9999) {
                throw invalid(reply, "delta ngoài miền -1..9999: " + delta);
            }
            long balance = requireIntegral(row, "balanceAfter", reply.status());
            if (balance < 0 || balance > 9999) {
                throw invalid(reply, "balanceAfter ngoài miền 0..9999: " + balance);
            }
            String verifyStatus = requireString(row, "verifyStatus", reply.status());
            if (!verifyStatus.equals("verified")) {
                throw invalid(reply, "verifyStatus phải là verified, nhận " + verifyStatus);
            }
            java.time.Instant recordedAt;
            try {
                recordedAt = java.time.Instant.parse(requireString(row, "recordedAt", reply.status()));
            } catch (java.time.format.DateTimeParseException exception) {
                throw invalid(reply, "thời gian ghi nhận giao dịch không hợp lệ");
            }
            entries.add(new HistoryEntry(id, counter, operation, (int) delta, (int) balance,
                    verifyStatus, recordedAt));
        }
        return new TransactionHistory(cardId, java.util.List.copyOf(entries));
    }

    HistoryLoad historyWithLedger(String cardIdHex) throws Failure {
        return new HistoryLoad(transactionsOf(cardIdHex), ledgerOf(cardIdHex));
    }

    boolean cardEnrolled(String cardIdHex) throws Failure {
        try {
            ledgerOf(cardIdHex);
            return true;
        } catch (Failure failure) {
            if (failure.statusCode() == 404) {
                return false;
            }
            throw failure;
        }
    }

    /** Parse thân phản hồi thành object JSON; hỏng hoặc không phải object đều là lỗi phản hồi. */
    private static JsonNode parse(Reply reply) throws Failure {
        String body = reply.body();
        if (body == null || body.isBlank()) {
            throw invalid(reply, "thân phản hồi rỗng");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JsonProcessingException broken) {
            throw invalid(reply, "thân phản hồi không phải JSON hợp lệ");
        }
        if (node == null || !node.isObject()) {
            throw invalid(reply, "thân phản hồi không phải JSON object");
        }
        return node;
    }

    private static Failure invalid(Reply reply, String detail) {
        return new Failure(String.format("HTTP %d | %s", reply.status(), detail),
                Kind.INVALID_RESPONSE, reply.status(), true);
    }

    private static String requireString(JsonNode body, String key, int status) throws Failure {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new Failure(String.format("HTTP %d | phản hồi thiếu trường %s", status, key),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return value.asText();
    }

    private static String requireUuid(JsonNode body, String key, int status) throws Failure {
        String value = requireString(body, key, status);
        if (!value.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) {
            throw new Failure(String.format("HTTP %d | %s không phải UUID", status, key),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return value;
    }

    private static String optionalString(JsonNode body, String key) {
        JsonNode value = body.get(key);
        return value == null || !value.isTextual() ? "" : value.asText();
    }

    private static String describeError(int status, String body) {
        String detail = body == null ? "" : body.strip();
        try {
            JsonNode node = JSON.readTree(detail);
            JsonNode message = node == null ? null : node.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                detail = message.asText();
            }
        } catch (JsonProcessingException notJson) {
            return String.format("HTTP %d | %s", status, detail);
        }
        return String.format("HTTP %d | %s", status, detail);
    }
}
