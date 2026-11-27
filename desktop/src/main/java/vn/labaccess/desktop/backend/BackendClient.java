package vn.labaccess.desktop.backend;

import static vn.labaccess.desktop.backend.BackendResponse.CARD_ID_HEX_LENGTH;
import static vn.labaccess.desktop.backend.BackendResponse.EXPECTED_VERSION_HEX_LENGTH;
import static vn.labaccess.desktop.backend.BackendResponse.NONCE_HEX_LENGTH;
import static vn.labaccess.desktop.backend.BackendResponse.optionalString;
import static vn.labaccess.desktop.backend.BackendResponse.requireHex;
import static vn.labaccess.desktop.backend.BackendResponse.requireIntegral;
import static vn.labaccess.desktop.backend.BackendResponse.requireString;
import static vn.labaccess.desktop.backend.BackendResponse.requireUuid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import vn.labaccess.desktop.card.ProfileEditRequest;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptCodec;
import vn.labaccess.desktop.card.ReceiptVerifier;

public final class BackendClient {

    public record AdminSession(String sessionId, String nonceHex, String expiresAt, int httpStatus) {
    }

    public record CardSession(String sessionId, String cardId, int httpStatus) {
    }

    public record Challenge(String challengeId, String nonceHex, String expiresAt, int httpStatus) {
    }

    public record Enrollment(String cardId, String studentId, String enrolledAt, int httpStatus) {
    }

    public record EnrolledCard(String cardId, byte[] publicKey) {
    }

    public EnrolledCard enrolledCard(String cardIdHex) throws Failure {
        Reply reply = get("enrollments/cards/" + cardIdHex);
        JsonNode node = parse(reply);
        String cardId = requireString(node, "cardId", reply.status());
        String keyHex = requireString(node, "publicKey", reply.status());
        if (!cardId.equalsIgnoreCase(cardIdHex) || !keyHex.matches("[0-9A-Fa-f]{130}")) {
            throw invalid(reply, "định danh hoặc khóa đã enroll không hợp lệ");
        }
        byte[] key = java.util.HexFormat.of().parseHex(keyHex);
        if (!ReceiptVerifier.parsePublicKey(key).ok()) {
            throw invalid(reply, "khóa đã enroll không phải điểm P-256 hợp lệ");
        }
        return new EnrolledCard(cardId, key);
    }

    public record SavedReceipt(String status, String cardId, long counterAfter, String operation,
            String visitId, int httpStatus) {
    }

    private record Reply(int status, String body) {
    }

    public enum Kind {

        TRANSPORT,

        HTTP_REJECTED,

        INVALID_RESPONSE
    }

    public static final class Failure extends Exception {

        private final Kind kind;
        private final int statusCode;
        private final boolean requestSent;

        Failure(String message, Kind kind, int statusCode, boolean requestSent) {
            super(message);
            this.kind = kind;
            this.statusCode = statusCode;
            this.requestSent = requestSent;
        }

        public Kind kind() {
            return kind;
        }

        public int statusCode() {
            return statusCode;
        }

        public boolean requestMayHaveReachedServer() {
            return requestSent;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int REQUEST_TIMEOUT_MS = 15_000;
    private static final int ANY_SUCCESS = -1;
    private static final String SIGNATURE_PATTERN = "[0-9A-Fa-f]{16,144}";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Access(java.time.Instant checkedAt, int httpStatus) {
        public boolean available() {
            return httpStatus >= 200 && httpStatus < 500;
        }
    }

    private volatile Access lastAccess;

    public Access lastAccess() {
        return lastAccess;
    }

    private final HttpClient http;
    private final String baseUrl;
    private volatile String staffToken;

    public BackendClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public StaffIdentity loginStaff(String username, char[] password) throws Failure {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("username", username);
        payload.put("password", new String(password));
        Reply reply = post("staff/auth/login", null, payload.toString(), 200);
        JsonNode body = parse(reply);
        String token = requireString(body, "token", reply.status());
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid(reply, "token Staff không hợp lệ");
        }
        StaffIdentity identity = StaffIdentity.decode(body.path("staff"), body, reply.status());
        staffToken = token;
        return identity;
    }

    public StaffIdentity requireStaff() throws Failure {
        if (staffToken == null) {
            throw new Failure("Chưa đăng nhập Staff", Kind.HTTP_REJECTED, 401, false);
        }
        Reply reply = get("staff/auth/me");
        JsonNode body = parse(reply);
        return StaffIdentity.decode(body, body, reply.status());
    }

    public void logoutStaff() throws Failure {
        if (staffToken == null) {
            return;
        }
        try {
            post("staff/auth/logout", null, "{}", 204);
        } catch (Failure failure) {
            if (failure.statusCode() != 401) {
                throw failure;
            }
        }
        staffToken = null;
    }

    public boolean hasStaffSession() {
        return staffToken != null;
    }

    public void forgetStaffSession() {
        staffToken = null;
    }

    public String beginAudit(String action, String cardId, String expectedPayload, String originId) throws Failure {
        ObjectNode body = JSON.createObjectNode().put("action", action);
        if (cardId != null) { body.put("cardId", cardId); }
        if (expectedPayload != null) { body.put("expectedPayload", expectedPayload); }
        if (originId != null) { body.put("originId", originId); }
        Reply reply = post("staff/audit", null, body.toString());
        return requireUuid(parse(reply), "operationId", reply.status());
    }

    public void finishAudit(String operationId, String outcome, String cardId) throws Failure {
        ObjectNode body = JSON.createObjectNode().put("outcome", outcome);
        if (cardId != null) { body.put("cardId", cardId); }
        post("staff/audit/" + operationId + "/outcome", null, body.toString(), 204);
    }

    public java.util.List<StaffAuditEntry> staffAudit(String staffCode, String cardId, java.time.Instant from,
            java.time.Instant to) throws Failure {
        var query = new java.util.StringJoiner("&");
        auditParameter(query, "staffCode", staffCode);
        auditParameter(query, "cardId", cardId);
        auditParameter(query, "from", from == null ? null : from.toString());
        auditParameter(query, "to", to == null ? null : to.toString());
        Reply reply = get("staff/audit" + (query.length() == 0 ? "" : "?" + query));
        try {
            return StaffAuditEntry.decode(JSON.readTree(reply.body()), reply.status());
        } catch (JsonProcessingException broken) {
            throw invalid(reply, "Phản hồi Audit không phải JSON hợp lệ");
        }
    }

    private static void auditParameter(java.util.StringJoiner query, String key, String value) {
        if (value != null && !value.isBlank()) {
            query.add(key + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    public void checkDatabase() throws Failure {
        Reply reply = get("health/database");
        JsonNode health = parse(reply);
        if (!"ok".equals(requireString(health, "status", reply.status()))
                || !"postgresql".equals(requireString(health, "provider", reply.status()))) {
            throw invalid(reply, "hệ thống chưa xác nhận kết nối sổ giao dịch");
        }
    }

    public AdminSession issueAdminSession() throws Failure {
        Reply reply = post("admin/sessions", null, "{}");
        JsonNode body = parse(reply);
        String nonce = requireHex(body, "nonce", reply.status(), NONCE_HEX_LENGTH,
                "nonce phiên quản trị không phải 32 byte hex");
        return new AdminSession(requireUuid(body, "sessionId", reply.status()), nonce,
                optionalString(body, "expiresAt"), reply.status());
    }

    public CardSession activateAdminSession(String sessionId, String cardIdHex, String profileHashHex,
            String signatureHex) throws Failure {
        if (signatureHex == null || !signatureHex.matches(SIGNATURE_PATTERN)) {
            throw new Failure("chữ ký không phải chuỗi hex hợp lệ", Kind.INVALID_RESPONSE, 0, false);
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("cardId", cardIdHex);
        payload.put("profileHash", profileHashHex);
        payload.put("signature", signatureHex);
        Reply reply = post("admin/sessions/" + sessionId + "/activate", sessionId, payload.toString(),
                ANY_SUCCESS);
        JsonNode body = parse(reply);
        String cardId = requireHex(body, "cardId", reply.status(), CARD_ID_HEX_LENGTH,
                "cardId không phải 16 ký tự hex");
        return new CardSession(requireUuid(body, "sessionId", reply.status()), cardId, reply.status());
    }

    public Challenge createChallenge(String sessionId, String cardIdHex, String studentId, String publicKeyHex)
            throws Failure {
        return createChallenge(sessionId, cardIdHex, studentId, publicKeyHex, null);
    }

    public Challenge createChallenge(String sessionId, String cardIdHex, String studentId, String publicKeyHex,
            String classCode) throws Failure {
        ObjectNode request = JSON.createObjectNode();
        request.put("cardId", cardIdHex);
        request.put("studentId", studentId);
        request.put("publicKey", publicKeyHex);
        if (classCode != null) {
            request.put("classCode", classCode);
        }
        Reply reply = post("enrollments/challenges", sessionId, request.toString());
        JsonNode body = parse(reply);
        String nonce = requireHex(body, "nonce", reply.status(), NONCE_HEX_LENGTH,
                "nonce không phải 32 byte hex");
        return new Challenge(requireUuid(body, "challengeId", reply.status()), nonce,
                optionalString(body, "expiresAt"), reply.status());
    }

    public Enrollment completeEnrollment(String sessionId, String challengeId, String signatureHex)
            throws Failure {
        String payload = "{\"challengeId\":\"" + challengeId + "\",\"signature\":\""
                + signatureHex + "\"}";
        Reply reply = post("enrollments/complete", sessionId, payload);
        JsonNode body = parse(reply);
        String cardId = requireHex(body, "cardId", reply.status(), CARD_ID_HEX_LENGTH,
                "cardId không phải 16 ký tự hex");
        return new Enrollment(cardId, requireString(body, "studentId", reply.status()),
                requireString(body, "enrolledAt", reply.status()), reply.status());
    }

    public SavedReceipt verifyReceipt(Receipt receipt) throws Failure {
        return verifyReceipt(receipt, null);
    }

    public SavedReceipt verifyReceipt(Receipt receipt, String auditOperationId) throws Failure {
        String body = "{\"payload\":\"" + receipt.payloadHex() + "\",\"signature\":\""
                + receipt.signatureHex() + "\"}";
        String path = auditOperationId == null ? "receipts/verify" : "receipts/staff/" + auditOperationId + "/verify";
        Reply reply = post(path, null, body);
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

    private Reply post(String path, String adminSessionId, String payload) throws Failure {
        return post(path, adminSessionId, payload, 201);
    }

    private Reply post(String path, String adminSessionId, String payload, int expectedStatus)
            throws Failure {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (adminSessionId != null) {
            builder.header("x-admin-session", adminSessionId);
        }
        authorizeStaff(builder);
        return send(builder.build(), expectedStatus);
    }

    private Reply get(String path) throws Failure {
        return get(path, 200, null);
    }

    private Reply get(String path, int expectedStatus, String adminSessionId) throws Failure {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .GET();
        if (adminSessionId != null) {
            builder.header("x-admin-session", adminSessionId);
        }
        authorizeStaff(builder);
        return send(builder.build(), expectedStatus);
    }

    private void authorizeStaff(HttpRequest.Builder builder) {
        String token = staffToken;
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
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
        if (status != expectedStatus && expectedStatus != ANY_SUCCESS) {
            throw new Failure(String.format(
                    "HTTP %d | Backend trả mã không đúng hợp đồng (cần %d)", status, expectedStatus),
                    Kind.INVALID_RESPONSE, status, true);
        }
        return new Reply(status, response.body());
    }

    public record LedgerSnapshot(String cardId, long lastCounter, int lastBalance, String activeVisitId) {
    }

    public byte[] storedReceiptPayload(String cardId, long counter) throws Failure {
        Reply reply = get("cards/" + cardId + "/transactions/" + counter);
        JsonNode node = parse(reply);
        String hex = requireString(node, "payload", reply.status());
        if (!requireString(node, "cardId", reply.status()).equalsIgnoreCase(cardId)
                || requireIntegral(node, "counterAfter", reply.status()) != counter
                || !hex.matches("[0-9A-Fa-f]{" + (ReceiptCodec.PAYLOAD_LENGTH * 2) + "}")) {
            throw invalid(reply, "payload ledger không đúng định danh hoặc độ dài canonical");
        }
        byte[] payload = java.util.HexFormat.of().parseHex(hex);
        ReceiptCodec.Parse parsed = ReceiptCodec.parsePayload(payload, new byte[0]);
        if (!parsed.ok() || !parsed.receipt().cardIdHex().equalsIgnoreCase(cardId)
                || parsed.receipt().counterAfter() != counter) {
            throw invalid(reply, "payload ledger không khớp cardId/counter yêu cầu");
        }
        return payload;
    }

    public record HistoryEntry(String id, long counterAfter, String operation, int delta, int balanceAfter,
            String verifyStatus, java.time.Instant recordedAt) {
    }

    public record TransactionHistory(String cardId, java.util.List<HistoryEntry> entries) {
    }

    public record HistoryLoad(TransactionHistory history, LedgerSnapshot ledger) {
    }

    public LedgerSnapshot ledgerOf(String cardIdHex) throws Failure {
        Reply reply = get("receipts/ledger/" + cardIdHex);
        return BackendResponse.decodeLedger(parse(reply), reply.status(), cardIdHex);
    }

    TransactionHistory transactionsOf(String cardIdHex) throws Failure {
        Reply reply = get("cards/" + cardIdHex + "/transactions");
        return BackendResponse.decodeHistory(parse(reply), reply.status(), cardIdHex);
    }

    public HistoryLoad historyWithLedger(String cardIdHex) throws Failure {
        return new HistoryLoad(transactionsOf(cardIdHex), ledgerOf(cardIdHex));
    }

    public boolean cardEnrolled(String cardIdHex) throws Failure {
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

    public record ProfileUpdate(String operationId, String nonceHex, String expectedVersionHex, String expiresAt,
            int httpStatus) {
    }

    public ProfileUpdate createProfileUpdate(String sessionId, String cardIdHex, ProfileEditRequest request,
            String expectedVersionHex) throws Failure {
        if (cardIdHex == null || !cardIdHex.matches("[0-9A-Fa-f]{" + CARD_ID_HEX_LENGTH + "}")) {
            throw new Failure("mã thẻ phải đúng " + (CARD_ID_HEX_LENGTH * 2) + " ký tự hex",
                    Kind.INVALID_RESPONSE, 0, false);
        }
        String payload = profileUpdatePayload(JSON.createObjectNode().put("cardId", cardIdHex),
                request, expectedVersionHex);
        Reply reply = post("profile-updates", sessionId, payload, ANY_SUCCESS);
        return BackendResponse.decodeProfileRequest(parse(reply), reply.status());
    }

    public ProfileUpdate replaceProfileUpdate(String sessionId, String operationId, ProfileEditRequest request,
            String expectedVersionHex) throws Failure {
        String payload = profileUpdatePayload(JSON.createObjectNode(), request, expectedVersionHex);
        Reply reply = post("profile-updates/" + operationId + "/replace", sessionId, payload, ANY_SUCCESS);
        return BackendResponse.decodeProfileRequest(parse(reply), reply.status());
    }

    private static String profileUpdatePayload(ObjectNode payload, ProfileEditRequest request,
            String expectedVersionHex) throws Failure {
        if (expectedVersionHex == null
                || !expectedVersionHex.matches("[0-9A-Fa-f]{" + EXPECTED_VERSION_HEX_LENGTH + "}")) {
            throw new Failure("expectedVersion phải đúng 12 byte hex", Kind.INVALID_RESPONSE, 0, false);
        }
        payload.put("studentId", request.studentId());
        payload.put("displayName", request.displayName());
        payload.put("classCode", request.classCode());
        payload.put("expectedVersion", expectedVersionHex);
        return payload.toString();
    }

    public sealed interface FoundProfileUpdate permits FoundPending, FoundCompleted {

        String operationId();

        String cardId();

        String studentId();

        String classCode();
    }

    public record FoundPending(String operationId, String cardId, String studentId, String displayName,
            String classCode, String expectedVersionHex, String nonceHex, String expiresAt, int httpStatus)
            implements FoundProfileUpdate {
    }

    public record FoundCompleted(String operationId, String cardId, String studentId, String classCode,
            String major, String completedAt, int httpStatus) implements FoundProfileUpdate {
    }

    public FoundProfileUpdate findProfileUpdateByCard(String sessionId, String cardIdHex) throws Failure {
        Reply reply = get("profile-updates/pending/" + cardIdHex, ANY_SUCCESS, sessionId);
        return BackendResponse.decodeFoundProfile(parse(reply), reply.status());
    }

    public FoundProfileUpdate findProfileUpdateById(String sessionId, String operationId) throws Failure {
        Reply reply = get("profile-updates/" + operationId, ANY_SUCCESS, sessionId);
        return BackendResponse.decodeFoundProfile(parse(reply), reply.status());
    }

    public record ProfileUpdateCompletion(String operationId, String cardId, String studentId, String major,
            String classCode, String completedAt, boolean alreadyCompleted, int httpStatus) {
    }

    public ProfileUpdateCompletion completeProfileUpdate(String sessionId, String operationId,
            String signatureHex) throws Failure {
        if (signatureHex == null || !signatureHex.matches(SIGNATURE_PATTERN)) {
            throw new Failure("chữ ký không phải chuỗi hex hợp lệ", Kind.INVALID_RESPONSE, 0, false);
        }
        String payload = "{\"signature\":\"" + signatureHex + "\"}";
        Reply reply = post("profile-updates/" + operationId + "/complete", sessionId, payload, ANY_SUCCESS);
        return BackendResponse.decodeProfileCompletion(parse(reply), reply.status());
    }

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
        return BackendResponse.invalid(reply.status(), detail);
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
