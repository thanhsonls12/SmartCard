package vn.labaccess.desktop.backend;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import vn.labaccess.desktop.card.Receipt;

public record StaffAuditEntry(String operationId, String action, String cardId, Instant createdAt,
        String originId, Actor staff, Actor originStaff, String outcome, List<Event> events) {

    public record Actor(String code, String name) {
        public String display() {
            return name + " · " + code;
        }
    }

    public record Evidence(String cardId, long counter, String operation) { }

    public record Event(String outcome, Instant createdAt, Evidence receipt) { }

    static List<StaffAuditEntry> decode(JsonNode rows, int status) throws BackendClient.Failure {
        if (rows == null || !rows.isArray() || rows.size() > 100) {
            throw BackendResponse.invalid(status, "Danh sách Audit không hợp lệ");
        }
        List<StaffAuditEntry> result = new ArrayList<>();
        for (JsonNode row : rows) {
            result.add(decodeEntry(row, status));
        }
        return List.copyOf(result);
    }

    private static StaffAuditEntry decodeEntry(JsonNode row, int status) throws BackendClient.Failure {
        String id = BackendResponse.requireUuid(row, "operationId", status);
        String originId = row.path("originId").isNull() ? null : BackendResponse.requireUuid(row, "originId", status);
        Actor origin = originId == null ? null : actor(row.path("originStaff"), status);
        String card = row.path("cardId").isNull() ? null : cardId(row, status);
        JsonNode events = row.path("events");
        if (!events.isArray()) {
            throw BackendResponse.invalid(status, "Thiếu danh sách sự kiện Audit");
        }
        List<Event> decoded = new ArrayList<>();
        for (JsonNode event : events) {
            JsonNode evidence = event.path("receipt");
            Evidence receipt = evidence.isNull() ? null : evidence(evidence, status);
            decoded.add(new Event(BackendResponse.requireString(event, "outcome", status),
                    instant(event, status), receipt));
        }
        return new StaffAuditEntry(id, BackendResponse.requireString(row, "action", status), card,
                instant(row, status), originId, actor(row.path("staff"), status), origin,
                BackendResponse.requireString(row, "outcome", status), List.copyOf(decoded));
    }

    private static Actor actor(JsonNode node, int status) throws BackendClient.Failure {
        return new Actor(BackendResponse.requireString(node, "staffCode", status),
                BackendResponse.requireString(node, "displayName", status));
    }

    private static Evidence evidence(JsonNode node, int status) throws BackendClient.Failure {
        long counter = BackendResponse.requireIntegral(node, "counterAfter", status);
        if (counter < 1 || counter > Receipt.COUNTER_MASK) {
            throw BackendResponse.invalid(status, "Counter Audit không hợp lệ");
        }
        return new Evidence(cardId(node, status), counter, BackendResponse.requireString(node, "operation", status));
    }

    private static String cardId(JsonNode node, int status) throws BackendClient.Failure {
        return BackendResponse.requireHex(node, "cardId", status, BackendResponse.CARD_ID_HEX_LENGTH,
                "Mã thẻ Audit không hợp lệ");
    }

    private static Instant instant(JsonNode node, int status) throws BackendClient.Failure {
        try {
            return Instant.parse(BackendResponse.requireString(node, "createdAt", status));
        } catch (DateTimeParseException failure) {
            throw BackendResponse.invalid(status, "Thời gian Audit không hợp lệ");
        }
    }
}
