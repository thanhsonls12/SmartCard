package vn.labaccess.desktop.backend;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public record StaffIdentity(String staffCode, String username, String displayName, Instant expiresAt) {
    static StaffIdentity decode(JsonNode staff, JsonNode body, int status) throws BackendClient.Failure {
        String code = BackendResponse.requireString(staff, "staffCode", status);
        String username = BackendResponse.requireString(staff, "username", status);
        String name = BackendResponse.requireString(staff, "displayName", status);
        try {
            return new StaffIdentity(code, username, name,
                    Instant.parse(BackendResponse.requireString(body, "expiresAt", status)));
        } catch (DateTimeParseException invalid) {
            throw BackendResponse.invalid(status, "thời hạn phiên Staff không hợp lệ");
        }
    }
}
