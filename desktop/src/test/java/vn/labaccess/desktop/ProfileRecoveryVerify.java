package vn.labaccess.desktop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import vn.labaccess.desktop.backend.BackendClient;
import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.ProfileCodec;
import vn.labaccess.desktop.card.ProfileEditRequest;
import vn.labaccess.desktop.card.Sw;
import vn.labaccess.desktop.ui.profile.ProfileUpdateFlow;
import vn.labaccess.desktop.validation.StudentMajor;

final class ProfileRecoveryVerify {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String SESSION_ID = "00000000-0000-4000-8000-000000000001";
    private static final String OLD_OPERATION = "00000000-0000-4000-8000-000000000002";
    private static final String NEW_OPERATION = "00000000-0000-4000-8000-000000000003";
    private static final String OLD_VERSION = "11".repeat(12);
    private static final String CHANGED_VERSION = "22".repeat(12);
    private static final String SESSION_NONCE = "AA".repeat(32);
    private static final String PROFILE_NONCE = "BB".repeat(32);
    private static final String STUDENT_ID = "CT2026001";
    private static final String DISPLAY_NAME = "Nguyễn Văn An";
    private static final String CLASS_CODE = "CNTT1";
    private static final char[] ADMIN_PIN = RecoveryCardFixture.pin(8);
    private static final char[] STUDENT_PIN = RecoveryCardFixture.pin(6);
    private static int checks;

    public static void main(String[] args) throws Exception {
        nullablePendingClass();
        legacyRecovery(false);
        legacyRecovery(true);
        unrelatedCardVersion();
        nullClassCannotReplaceStale();
        photoClearsReadProfile();
        System.out.println("PROFILE RECOVERY PASS: " + checks + " checks");
    }

    private static void nullablePendingClass() throws Exception {
        for (String classValue : List.of("null", "\"CNTT1\"", "missing")) {
            ObjectNode body = pending();
            if (classValue.equals("missing")) {
                body.remove("classCode");
            } else {
                body.set("classCode", JSON.readTree(classValue));
            }
            try (RecoveryBackendFixture server = new RecoveryBackendFixture((request, payload) ->
                    new RecoveryBackendFixture.Reply(200, body.toString()))) {
                BackendClient backend = new BackendClient(server.url());
                BackendClient.FoundPending byCard = (BackendClient.FoundPending)
                        backend.findProfileUpdateByCard(SESSION_ID, CARD_ID);
                BackendClient.FoundPending byId = (BackendClient.FoundPending)
                        backend.findProfileUpdateById(SESSION_ID, OLD_OPERATION);
                String expected = classValue.equals("\"CNTT1\"") ? CLASS_CODE : null;
                check("HTTP pending class " + classValue,
                        java.util.Objects.equals(expected, byCard.classCode())
                                && java.util.Objects.equals(expected, byId.classCode()));
            }
        }
        for (String invalidClass : List.of("123", "true", "{}", "[]", "\"\"", "\" \"")) {
            ObjectNode body = pending();
            body.set("classCode", JSON.readTree(invalidClass));
            try (RecoveryBackendFixture server = new RecoveryBackendFixture((request, payload) ->
                    new RecoveryBackendFixture.Reply(200, body.toString()))) {
                boolean refused = false;
                try {
                    new BackendClient(server.url()).findProfileUpdateByCard(SESSION_ID, CARD_ID);
                } catch (BackendClient.Failure failure) {
                    refused = failure.kind() == BackendClient.Kind.INVALID_RESPONSE;
                }
                check("HTTP từ chối classCode sai kiểu hoặc rỗng " + invalidClass, refused);
            }
        }
    }

    private static void legacyRecovery(boolean lostWriteResponse) throws Exception {
        try (Scenario scenario = new Scenario(lostWriteResponse ? STUDENT_ID : "CT2026000",
                lostWriteResponse ? DISPLAY_NAME : "Nguyễn Văn Cũ",
                lostWriteResponse ? CHANGED_VERSION : OLD_VERSION)) {
            ProfileUpdateFlow.Result reopened = scenario.flow.reopen(CARD_ID, ADMIN_PIN, STUDENT_PIN);
            check("mở legacy " + lostWriteResponse + " yêu cầu lớp từ JSON null; actual=" + reopened,
                    reopened.state() == ProfileUpdateFlow.State.CLASS_REQUIRED
                            && scenario.flow.hasPending() && scenario.flow.pendingRequest().classCode() == null);
            check("mở legacy không ghi hoặc ký nonce yêu cầu",
                    !scenario.events.contains("write") && !scenario.events.contains("sign-profile")
                            && !scenario.events.contains("replace") && !scenario.events.contains("complete"));
            ProfileUpdateFlow.Result result = scenario.flow.replaceLegacyPending(CARD_ID, CLASS_CODE,
                    ADMIN_PIN, STUDENT_PIN);
            check("phục hồi legacy " + lostWriteResponse + " hoàn tất",
                    result.state() == ProfileUpdateFlow.State.DONE && !scenario.flow.hasPending());
            check("thay yêu cầu trước ghi rồi ký và complete",
                    ordered(scenario.events, "replace", "write", "sign-profile", "complete")
                            && scenario.events.stream().filter("write"::equals).count() == 1);
            check("replace giữ nội dung legacy và phiên bản hiện tại",
                    scenario.replaceBody != null
                            && STUDENT_ID.equals(scenario.replaceBody.path("studentId").asText())
                            && DISPLAY_NAME.equals(scenario.replaceBody.path("displayName").asText())
                            && CLASS_CODE.equals(scenario.replaceBody.path("classCode").asText())
                            && (lostWriteResponse ? CHANGED_VERSION : OLD_VERSION).equals(
                                    scenario.replaceBody.path("expectedVersion").asText()));
            check("APDU ghi schema v2 gồm lớp và ngành suy ra",
                    scenario.card.profile[0] == 2 && ProfileCodec.parse(scenario.card.profile).ok()
                            && CLASS_CODE.equals(ProfileCodec.parse(scenario.card.profile).profile().classCode())
                            && StudentMajor.fromStudentId(STUDENT_ID).equals(
                                    ProfileCodec.parse(scenario.card.profile).profile().major()));
            ProfileEditRequest completed = scenario.flow.lastCompletedRequest();
            check("hoàn tất legacy lưu đúng đích đã xác nhận " + lostWriteResponse,
                    completed != null && STUDENT_ID.equals(completed.studentId())
                            && DISPLAY_NAME.equals(completed.displayName())
                            && CLASS_CODE.equals(completed.classCode()));
            var replacePhoto = ProfileUpdateFlow.class.getDeclaredMethod("replacePhoto",
                    byte[].class, char[].class, char[].class);
            replacePhoto.setAccessible(true);
            replacePhoto.invoke(scenario.flow, new byte[1], ADMIN_PIN, STUDENT_PIN);
            check("vào thay ảnh xóa đích hoàn tất và hồ sơ đọc " + lostWriteResponse,
                    scenario.flow.lastCompletedRequest() == null && scenario.flow.lastReadProfile() == null);
        }
    }

    private static void unrelatedCardVersion() throws Exception {
        try (Scenario scenario = new Scenario("CT2026999", "Người khác", CHANGED_VERSION)) {
            scenario.flow.reopen(CARD_ID, ADMIN_PIN, STUDENT_PIN);
            ProfileUpdateFlow.Result refused = scenario.flow.replaceLegacyPending(CARD_ID, CLASS_CODE,
                    ADMIN_PIN, STUDENT_PIN);
            check("identity khác và nonce khác giữ CLASS_REQUIRED",
                    refused.state() == ProfileUpdateFlow.State.CLASS_REQUIRED && scenario.flow.hasPending()
                            && scenario.flow.pendingRequest().classCode() == null);
            check("identity khác và nonce khác không thay Backend hoặc ghi thẻ",
                    !scenario.events.contains("replace") && !scenario.events.contains("write")
                            && !scenario.events.contains("sign-profile") && !scenario.events.contains("complete"));
        }
    }

    private static void nullClassCannotReplaceStale() throws Exception {
        try (Scenario scenario = new Scenario(STUDENT_ID, DISPLAY_NAME, OLD_VERSION)) {
            scenario.flow.reopen(CARD_ID, ADMIN_PIN, STUDENT_PIN);
            ProfileUpdateFlow.Result result = scenario.flow.replaceStale(CARD_ID,
                    scenario.flow.pendingRequest(), ADMIN_PIN, STUDENT_PIN);
            check("replaceStale không nhận yêu cầu legacy thiếu lớp",
                    result.state() == ProfileUpdateFlow.State.CLASS_REQUIRED && scenario.flow.hasPending()
                            && !scenario.events.contains("replace") && !scenario.events.contains("write"));
        }
    }

    private static void photoClearsReadProfile() throws Exception {
        try (Scenario scenario = new Scenario(STUDENT_ID, DISPLAY_NAME, OLD_VERSION)) {
            scenario.flow.reopen(CARD_ID, ADMIN_PIN, STUDENT_PIN);
            check("đọc hồ sơ lưu cache để hiển thị", scenario.flow.lastReadProfile() != null);
            var replacePhoto = ProfileUpdateFlow.class.getDeclaredMethod("replacePhoto",
                    byte[].class, char[].class, char[].class);
            replacePhoto.setAccessible(true);
            replacePhoto.invoke(scenario.flow, new byte[1], ADMIN_PIN, STUDENT_PIN);
            check("thay ảnh bị từ chối vẫn xóa hồ sơ đọc từ thao tác trước",
                    scenario.flow.lastReadProfile() == null);
        }
    }

    private static ObjectNode pending() {
        ObjectNode body = JSON.createObjectNode();
        body.put("state", "PENDING").put("operationId", OLD_OPERATION).put("cardId", CARD_ID)
                .put("studentId", STUDENT_ID).put("displayName", DISPLAY_NAME)
                .put("expectedVersion", OLD_VERSION).put("nonce", PROFILE_NONCE);
        body.putNull("classCode");
        return body;
    }

    private static boolean ordered(List<String> events, String... expected) {
        int previous = -1;
        for (String event : expected) {
            int next = events.indexOf(event);
            if (next <= previous) {
                return false;
            }
            previous = next;
        }
        return true;
    }

    private static void check(String description, boolean passed) {
        checks++;
        if (!passed) {
            throw new AssertionError("FAIL PR" + checks + " " + description);
        }
        System.out.println("PASS PR" + checks + " " + description);
    }

    private static final class Scenario implements AutoCloseable {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final RecoveryBackendFixture server;
        private final ProfileChannel card;
        private final ProfileUpdateFlow flow;
        private volatile com.fasterxml.jackson.databind.JsonNode replaceBody;

        Scenario(String studentId, String displayName, String version) throws Exception {
            card = new ProfileChannel(events, studentId, displayName, version);
            server = new RecoveryBackendFixture(this::reply);
            CardGateway gateway = new CardGateway(card);
            var identified = gateway.identify();
            if (!identified.ok()) throw new AssertionError("profile fixture identify: " + identified.text());
            flow = new ProfileUpdateFlow(gateway, new BackendClient(server.url()));
        }

        private RecoveryBackendFixture.Reply reply(String request, String payload) throws java.io.IOException {
            String route = request.split(" ")[1];
            ObjectNode response = JSON.createObjectNode();
            int status = 200;
            if (route.equals("/admin/sessions")) {
                response.put("sessionId", SESSION_ID).put("nonce", SESSION_NONCE);
                status = 201;
            } else if (route.equals("/admin/sessions/" + SESSION_ID + "/activate")) {
                response.put("sessionId", SESSION_ID).put("cardId", CARD_ID);
            } else if (route.equals("/profile-updates/pending/" + CARD_ID)
                    || route.equals("/profile-updates/" + OLD_OPERATION)) {
                response = pending();
            } else if (route.equals("/profile-updates/" + OLD_OPERATION + "/replace")) {
                events.add("replace");
                replaceBody = JSON.readTree(payload);
                response.put("operationId", NEW_OPERATION).put("nonce", PROFILE_NONCE)
                        .put("expectedVersion", card.version);
                status = 201;
            } else if (route.equals("/profile-updates/" + NEW_OPERATION + "/complete")) {
                events.add("complete");
                response.put("operationId", NEW_OPERATION).put("cardId", CARD_ID)
                        .put("studentId", STUDENT_ID).put("classCode", CLASS_CODE)
                        .put("major", StudentMajor.fromStudentId(STUDENT_ID))
                        .put("completedAt", "2026-11-27T00:00:00Z").put("alreadyCompleted", false);
            } else {
                return new RecoveryBackendFixture.Reply(404, "{}");
            }
            return new RecoveryBackendFixture.Reply(status, response.toString());
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    private static final class ProfileChannel extends CardChannel {
        private final List<String> events;
        private byte[] profile;
        private String version;

        ProfileChannel(List<String> events, String studentId, String displayName, String version) {
            this.events = events;
            this.version = version;
            byte[] id = studentId.getBytes(StandardCharsets.US_ASCII);
            byte[] name = displayName.getBytes(StandardCharsets.UTF_8);
            profile = ByteBuffer.allocate(3 + id.length + name.length).put((byte) 1)
                    .put((byte) id.length).put(id).put((byte) name.length).put(name).array();
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) {
            return switch (command.getINS()) {
                case 0xA4 -> RecoveryCardFixture.response(new byte[0], Sw.OK);
                case 0x10 -> RecoveryCardFixture.response(ByteBuffer.allocate(27)
                        .put("LABACCESS".getBytes(StandardCharsets.US_ASCII))
                        .put((byte) 1).put((byte) RecoveryCardFixture.AID.length)
                        .put(RecoveryCardFixture.AID).put((byte) 1)
                        .put(HexFormat.of().parseHex(CARD_ID)).array(), Sw.OK);
                case 0x20 -> RecoveryCardFixture.response(new byte[0], Sw.OK);
                case 0x31 -> RecoveryCardFixture.response(profile, Sw.OK);
                case 0x40 -> {
                    byte[] metadata = ByteBuffer.allocate(65).put((byte) 1).put((byte) 1)
                            .put(profile[0]).putShort((short) profile.length)
                            .put(HexFormat.of().parseHex(version)).put(new byte[16])
                            .put(HexFormat.of().parseHex(ProfileCodec.hashHex(
                                    ProfileCodec.parse(profile).profile()))).array();
                    yield RecoveryCardFixture.response(metadata, Sw.OK);
                }
                case 0x64 -> {
                    String nonce = HexFormat.of().withUpperCase().formatHex(command.getData(), 8, 40);
                    events.add(nonce.equals(PROFILE_NONCE) ? "sign-profile" : "sign-session");
                    yield RecoveryCardFixture.response(HexFormat.of().parseHex("3006020101020101"), Sw.OK);
                }
                case 0x42 -> {
                    events.add("write");
                    byte[] data = command.getData();
                    if (!HexFormat.of().formatHex(data, 8, 20).equalsIgnoreCase(version)) {
                        yield RecoveryCardFixture.response(new byte[0], Sw.CONDITIONS_NOT_SATISFIED);
                    }
                    profile = new byte[data.length - 19];
                    profile[0] = 2;
                    System.arraycopy(data, 20, profile, 1, data.length - 20);
                    Arrays.fill(data, (byte) 0);
                    version = "33".repeat(12);
                    yield RecoveryCardFixture.response(new byte[0], Sw.OK);
                }
                default -> throw new AssertionError("INS không mong đợi " + command.getINS());
            };
        }

        @Override public Card getCard() { return null; }
        @Override public int getChannelNumber() { return 0; }
        @Override public int transmit(ByteBuffer command, ByteBuffer response) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}
