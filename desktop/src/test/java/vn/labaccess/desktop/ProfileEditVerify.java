package vn.labaccess.desktop;

import vn.labaccess.desktop.validation.ClassCode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.transport.CardTransport;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardPhotoCommands;
import vn.labaccess.desktop.card.CardProfileUpdateCommands;
import vn.labaccess.desktop.card.CardProtocol;
import vn.labaccess.desktop.card.PhotoImage;
import vn.labaccess.desktop.card.ProfileEditRequest;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.ui.shared.UiLabels;

import vn.labaccess.desktop.ui.profile.ProfileUpdateFlow;

final class ProfileEditVerify {

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final char[] ADMIN_PIN = "12345678".toCharArray();
    private static final char[] STUDENT_PIN = "654321".toCharArray();
    private static final byte[] VERSION = HexFormat.of().parseHex("000102030405060708090A0B");
    private static final String NONCE = "AB".repeat(32);
    private static final byte[] DER_SIGNATURE = HexFormat.of().parseHex("3006020101020101");

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        classCode();
        payload();
        identity();
        signature();
        photoReplace();
        pendingState();
        httpPayloads();
        System.out.println("PROFILE EDIT " + (failures == 0 ? "PASS" : "FAIL") + ": " + checks
                + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void httpPayloads() throws Exception {
        String cardId = "A1B2C3D411223344";
        String version = "ab".repeat(12);
        String response = "{\"operationId\":\"profile-op\",\"nonce\":\"" + "cd".repeat(32)
                + "\",\"expectedVersion\":\"" + version + "\"}";
        var requests = new java.util.concurrent.ConcurrentHashMap<String, String>();
        try (var fixture = new RecoveryBackendFixture((request, body) -> {
            requests.put(request.split(" ")[1], body);
            return new RecoveryBackendFixture.Reply(201, response);
        })) {
            var client = new BackendClient(fixture.url());
            var change = new ProfileEditRequest("SV2024001", "Nguyen Van A", "AT19A");
            client.createProfileUpdate("admin", cardId, change, version);
            client.replaceProfileUpdate("admin", "profile-op", change, version);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var create = (com.fasterxml.jackson.databind.node.ObjectNode)
                    json.readTree(requests.get("/profile-updates"));
            var replace = json.readTree(requests.get("/profile-updates/profile-op/replace"));
            check("PE33", "chỉ payload tạo yêu cầu chứa cardId",
                    cardId.equals(create.remove("cardId").asText()) && !replace.has("cardId"));
            check("PE34", "payload tạo và thay thế giữ cùng nội dung cập nhật",
                    create.equals(replace) && "SV2024001".equals(replace.path("studentId").asText())
                            && "Nguyen Van A".equals(replace.path("displayName").asText())
                            && "AT19A".equals(replace.path("classCode").asText())
                            && version.equals(replace.path("expectedVersion").asText()));
        }
    }

    private static void classCode() {
        check("PE01", "lớp được chuẩn hóa bỏ khoảng trắng và viết hoa",
                "AT19A".equals(ClassCode.normalize(" at19a ")));
        check("PE02", "lớp hợp lệ 5–7 ký tự chữ hoặc số không báo lỗi",
                ClassCode.problem("AT19A") == null && ClassCode.problem("AT19A1B") == null);
        check("PE03", "lớp ngắn hơn 5 hoặc dài hơn 7 bị từ chối trước Backend",
                ClassCode.problem("AT19") != null && ClassCode.problem("AT19A1B2C") != null);
        check("PE04", "thông báo lỗi lớp giữ nguyên câu của màn Đăng ký thẻ",
                "Lớp phải 5–7 ký tự chữ hoặc số, ví dụ AT19A.".equals(ClassCode.problem("A")));
        check("PE05", "prompt lớp dùng chung một định nghĩa",
                "5–7 chữ/số, ví dụ AT19A".equals(ClassCode.PROMPT));
    }

    private static void payload() {
        ProfileEditRequest request = ProfileEditRequest.normalized("sv12345678", "Nguyễn Văn Á",
                " at19a ");
        byte[] payload = request.payload(ADMIN_PIN, VERSION);
        byte[] studentId = "SV12345678".getBytes(StandardCharsets.US_ASCII);
        byte[] name = "Nguyễn Văn Á".getBytes(StandardCharsets.UTF_8);
        check("PE06", "mã sinh viên viết hoa và lớp chuẩn hóa khi gửi",
                "SV12345678".equals(request.studentId()) && "AT19A".equals(request.classCode()));
        check("PE07", "Lc bằng 22 cộng studentIdLen cộng nameLen đúng hợp đồng Case 3S",
                payload.length == 22 + studentId.length + name.length
                        && request.payloadLength() == payload.length);
        check("PE08", "8 byte đầu là Admin PIN ASCII",
                Arrays.equals(Arrays.copyOfRange(payload, 0, 8),
                        "12345678".getBytes(StandardCharsets.US_ASCII)));
        check("PE09", "12 byte expectedVersion nằm ngay sau Admin PIN",
                Arrays.equals(Arrays.copyOfRange(payload, 8, 20), VERSION));
        check("PE10", "studentIdLength một byte rồi tới studentId ASCII",
                payload[20] == (byte) studentId.length
                        && Arrays.equals(Arrays.copyOfRange(payload, 21, 21 + studentId.length),
                                studentId));
        int nameOffset = 21 + studentId.length;
        check("PE11", "nameLength là số byte UTF-8 rồi tới name",
                payload[nameOffset] == (byte) name.length
                        && Arrays.equals(Arrays.copyOfRange(payload, nameOffset + 1, payload.length),
                                name));
        check("PE12", "tên tiếng Việt dài hơn số ký tự nhưng vẫn đúng giới hạn byte",
                request.displayNameLength() == name.length
                        && request.displayNameLength() > request.displayName().length());
    }

    private static void identity() throws Exception {
        FakeChannel channel = new FakeChannel();
        CardProfileUpdateCommands commands = new CardProfileUpdateCommands(
                new CardTransport(channel), sw -> { });
        ProfileEditRequest request = ProfileEditRequest.normalized("SV12345678", "Nguyễn Văn Á",
                "AT19A");
        CardProfileUpdateCommands.IdentityWrite write = commands.updateIdentity(request, ADMIN_PIN,
                VERSION);
        CommandAPDU sent = channel.last();
        int lc = sent.getData().length;
        check("PE13", "UPDATE_IDENTITY là Case 3S INS=42 P1P2=0000 không có Le",
                write.result().ok() && write.sw() == Sw.OK
                        && (sent.getINS() & 0xFF) == 0x42 && sent.getP1() == 0
                        && sent.getP2() == 0 && sent.getBytes().length == 5 + lc
                        && sent.getBytes()[0] == (byte) CardProtocol.CLA_APPLICATION);
        check("PE14", "SW 6985 được trả về cho tầng luồng để phân loại expectedVersion",
                rejectedIdentity(channel, Sw.CONDITIONS_NOT_SATISFIED) == Sw.CONDITIONS_NOT_SATISFIED);
        CardProfileUpdateCommands.IdentityWrite tooShort = new CardProfileUpdateCommands(
                new CardTransport(new FakeChannel()), sw -> { })
                .updateIdentity(request, ADMIN_PIN, new byte[11]);
        check("PE15", "expectedVersion sai độ dài bị chặn trước khi gửi APDU",
                !tooShort.result().ok() && tooShort.sw() == -1);
        check("PE16", "thông báo nêu rõ expectedVersion phải đúng 12 byte",
                tooShort.result().text().contains("12 byte"));
    }

    private static int rejectedIdentity(FakeChannel channel, int sw) throws Exception {
        channel.rejectIns = 0x42;
        channel.rejectSw = sw;
        CardProfileUpdateCommands commands = new CardProfileUpdateCommands(
                new CardTransport(channel), value -> { });
        return commands.updateIdentity(ProfileEditRequest.normalized("SV12345678", "Nguyễn Văn Á",
                "AT19A"), ADMIN_PIN, VERSION).sw();
    }

    private static void signature() throws Exception {
        FakeChannel channel = new FakeChannel();
        channel.response = DER_SIGNATURE;
        CardProfileUpdateCommands commands = new CardProfileUpdateCommands(
                new CardTransport(channel), sw -> { });
        CardOperationResult signed = commands.signProfileChallenge(ADMIN_PIN, NONCE);
        CommandAPDU sent = channel.last();
        check("PE17", "SIGN_PROFILE_CHALLENGE là Case 4S Lc=40 Le=256",
                signed.ok() && (sent.getINS() & 0xFF) == 0x64 && sent.getData().length == 40
                        && sent.getBytes().length == 5 + 40 + 1 && sent.getBytes()[45] == 0x00);
        check("PE18", "8 byte đầu là Admin PIN rồi tới 32 byte nonce",
                Arrays.equals(Arrays.copyOfRange(sent.getData(), 0, 8),
                        "12345678".getBytes(StandardCharsets.US_ASCII))
                        && Arrays.equals(Arrays.copyOfRange(sent.getData(), 8, 40),
                                HexFormat.of().parseHex(NONCE)));
        check("PE19", "chữ ký DER được giữ dạng hex để gửi Backend",
                "3006020101020101".equals(commands.lastSignatureHex()));
        check("PE20", "nonce sai định dạng bị chặn trước khi gửi APDU",
                !commands.signProfileChallenge(ADMIN_PIN, "AB").ok());
        FakeChannel pinChannel = new FakeChannel();
        pinChannel.rejectIns = 0x64;
        pinChannel.rejectSw = 0x63C3;
        CardOperationResult blocked = new CardProfileUpdateCommands(new CardTransport(pinChannel),
                sw -> { }).signProfileChallenge(ADMIN_PIN, NONCE);
        check("PE21", "63C3 báo còn số lượt thử Admin PIN",
                !blocked.ok() && blocked.text().contains("còn 3 lượt"));
        FakeChannel shortDer = new FakeChannel();
        shortDer.response = HexFormat.of().parseHex("3001");
        check("PE22", "chữ ký quá ngắn bị coi là thất bại",
                !new CardProfileUpdateCommands(new CardTransport(shortDer), sw -> { })
                        .signProfileChallenge(ADMIN_PIN, NONCE).ok());
    }

    private static void photoReplace() throws Exception {
        FakeChannel channel = new FakeChannel();
        CardPhotoCommands commands = new CardPhotoCommands(new CardTransport(channel),
                pin -> CardOperationResult.ok("VERIFY"), sw -> { });
        byte[] image = new byte[PhotoImage.LENGTH];
        for (int index = 0; index < image.length; index++) {
            image[index] = (byte) index;
        }
        CardOperationResult replaced = commands.replacePhoto(image, ADMIN_PIN);
        List<Integer> ins = channel.insSequence();
        check("PE23", "PHOTO_REPLACE_BEGIN rồi 32 chunk rồi PHOTO_FINISH",
                replaced.ok() && ins.size() == 34 && ins.get(0) == 0x36 && ins.get(33) == 0x35
                        && ins.subList(1, 33).stream().allMatch(value -> value == 0x33));
        check("PE24", "chunk ảnh thay thế vẫn tối đa 128 byte",
                channel.commands.get(1).getData().length == PhotoImage.CHUNK_MAX_LENGTH);
        FakeChannel noPhoto = new FakeChannel();
        noPhoto.rejectIns = 0x36;
        noPhoto.rejectSw = Sw.CONDITIONS_NOT_SATISFIED;
        CardOperationResult refused = new CardPhotoCommands(new CardTransport(noPhoto),
                pin -> CardOperationResult.ok("VERIFY"), sw -> { }).replacePhoto(image, ADMIN_PIN);
        check("PE25", "thẻ chưa có ảnh bị từ chối kèm hướng dẫn ghi ảnh lần đầu",
                !refused.ok() && refused.text().contains("chưa có ảnh")
                        && noPhoto.insSequence().size() == 1);
        FakeChannel shortImage = new FakeChannel();
        CardOperationResult rejected = new CardPhotoCommands(new CardTransport(shortImage),
                pin -> CardOperationResult.ok("VERIFY"), sw -> { })
                .replacePhoto(new byte[1024], ADMIN_PIN);
        check("PE26", "ảnh không đủ 4096 byte bị chặn trước khi gửi APDU",
                !rejected.ok() && shortImage.insSequence().isEmpty());
    }

    private static void pendingState() throws Exception {
        FakeChannel channel = new FakeChannel();
        ProfileUpdateFlow flow = new ProfileUpdateFlow(new CardGateway(channel),
                new BackendClient("http://127.0.0.1:1"));
        check("PE27", "phiên mới không giữ yêu cầu chờ nào",
                !flow.hasPending() && flow.pendingCardId().isEmpty());
        ProfileUpdateFlow.Result reopened = flow.reopen(CARD_ID, ADMIN_PIN, STUDENT_PIN);
        List<Integer> ins = channel.insSequence();
        check("PE28", "thẻ không đọc được hồ sơ thì Mở lại bị từ chối, không gửi lệnh 42 hay 64",
                reopened.state() == ProfileUpdateFlow.State.REJECTED
                        && reopened.message().contains("Chưa đọc được hồ sơ")
                        && !ins.contains(0x42) && !ins.contains(0x64) && !flow.hasPending());
        check("PE29", "kết quả chưa xác minh dùng đúng câu Chờ xác minh — đọc lại thẻ",
                ProfileUpdateFlow.Result.unverified("chi tiết").message()
                        .equals("Chờ xác minh — đọc lại thẻ")
                        && ProfileUpdateFlow.Result.unverified("chi tiết")
                                .state() == ProfileUpdateFlow.State.UNVERIFIED);
        check("PE30", "ba nhãn hành động của màn Sửa hồ sơ nằm trong UiLabels",
                "Lưu hồ sơ".equals(UiLabels.SAVE_PROFILE)
                        && "Thay ảnh".equals(UiLabels.REPLACE_PHOTO)
                        && "Mở lại yêu cầu".equals(UiLabels.REOPEN_PROFILE_REQUEST));

        FakeChannel staleChannel = new FakeChannel();
        ProfileUpdateFlow staleFlow = new ProfileUpdateFlow(new CardGateway(staleChannel),
                new BackendClient("http://127.0.0.1:1"));
        ProfileUpdateFlow.Result replaced = staleFlow.replaceStale(CARD_ID,
                ProfileEditRequest.normalized("SV2024001", "Nguyễn Văn Á", "AT19A"), ADMIN_PIN,
                STUDENT_PIN);
        List<Integer> replaceIns = staleChannel.insSequence();
        check("PE31", "thẻ không đọc được hồ sơ thì Thay yêu cầu cũ bị từ chối, không gửi lệnh 42/64, không tạo yêu cầu",
                replaced.state() == ProfileUpdateFlow.State.REJECTED
                        && replaced.message().contains("Chưa đọc được hồ sơ")
                        && !replaceIns.contains(0x42) && !replaceIns.contains(0x64)
                        && !staleFlow.hasPending() && staleFlow.pendingRequest() == null);
        check("PE32", "kết quả lệch phiên bản là STALE kèm nhãn xác nhận hai bước",
                ProfileUpdateFlow.Result.stale("Hồ sơ trên thẻ đã thay đổi so với lúc tạo yêu cầu",
                        "chi tiết").state() == ProfileUpdateFlow.State.STALE
                        && "Thay yêu cầu cũ".equals(UiLabels.REPLACE_STALE_REQUEST)
                        && "Xác nhận thay yêu cầu".equals(UiLabels.REPLACE_STALE_CONFIRM)
                        && !UiLabels.REPLACE_STALE_CONFIRM.equals(UiLabels.REPLACE_STALE_REQUEST));
    }

    private static void check(String code, String name, boolean passed) {
        checks++;
        if (!passed) {
            failures++;
        }
        System.out.println((passed ? "PASS " : "FAIL ") + code + " " + name);
    }

    private static final class FakeChannel extends CardChannel {

        private final List<CommandAPDU> commands = new ArrayList<>();
        private byte[] response = new byte[0];
        private int rejectIns = -1;
        private int rejectSw = Sw.OK;

        @Override
        public Card getCard() {
            return null;
        }

        @Override
        public int getChannelNumber() {
            return 0;
        }

        @Override
        public int transmit(ByteBuffer command, ByteBuffer target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) {
            commands.add(command);
            int sw = (command.getINS() & 0xFF) == rejectIns ? rejectSw : Sw.OK;
            byte[] data = (command.getINS() & 0xFF) == rejectIns ? new byte[0] : response;
            byte[] out = Arrays.copyOf(data, data.length + 2);
            out[data.length] = (byte) ((sw >> 8) & 0xFF);
            out[data.length + 1] = (byte) (sw & 0xFF);
            return new ResponseAPDU(out);
        }

        @Override
        public void close() {
        }

        CommandAPDU last() {
            return commands.get(commands.size() - 1);
        }

        List<Integer> insSequence() {
            return commands.stream().map(command -> command.getINS() & 0xFF).toList();
        }
    }
}
