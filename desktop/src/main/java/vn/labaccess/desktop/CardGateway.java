package vn.labaccess.desktop;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

final class CardGateway {

    record Result(boolean ok, String text) {
        static Result ok(String text) {
            return new Result(true, text);
        }

        static Result fail(String text) {
            return new Result(false, text);
        }
    }

    record Status(int protocolVersion, int lifecycle, String instanceAid, String cardId) {
    }

    private static final byte[] INSTANCE_AID = HexFormat.of().parseHex("F04C4142010101");

    private static final byte CLA_APPLICATION = (byte) 0x80;
    private static final byte INS_GET_STATUS = (byte) 0x10;
    private static final byte INS_PERSONALIZE = (byte) 0x30;
    private static final byte INS_VERIFY_STUDENT_PIN = (byte) 0x20;
    private static final byte INS_CHANGE_STUDENT_PIN = (byte) 0x21;
    private static final byte INS_UNBLOCK_STUDENT_PIN = (byte) 0x23;
    private static final byte INS_GRANT_QUOTA = (byte) 0x50;
    private static final byte INS_START_VISIT = (byte) 0x51;
    private static final byte INS_END_VISIT = (byte) 0x52;
    private static final byte INS_GET_LAST_RECEIPT = (byte) 0x60;
    private static final byte INS_GET_CARD_PUBLIC_KEY = (byte) 0x61;
    private static final byte INS_GET_PROFILE = (byte) 0x31;
    private static final byte INS_GET_PROFILE_CRYPTO_META = (byte) 0x40;
    private static final byte INS_PHOTO_BEGIN = (byte) 0x32;
    private static final byte INS_PHOTO_WRITE_CHUNK = (byte) 0x33;
    private static final byte INS_PHOTO_READ_CHUNK = (byte) 0x34;
    private static final byte INS_PHOTO_FINISH = (byte) 0x35;
    private static final int PUBLIC_KEY_LE = 256;
    private static final int PROFILE_LE = 256;
    private static final int RECEIPT_LE = 256;
    private static final int STATE_RESPONSE_LENGTH = 6;
    private static final int PIN_LENGTH = 6;
    private static final int ADMIN_PIN_LENGTH = 8;
    private static final int GRANT_DATA_LENGTH = 10;
    private static final int VISIT_ID_LENGTH = 16;
    private static final int PROTOCOL_VERSION = 0x01;

    private static final int LIFECYCLE_UNPERSONALIZED = 0x00;
    private static final int LIFECYCLE_ISSUED = 0x01;

    private static final byte[] MAGIC = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S'
    };

    private static final int OFFSET_PROTOCOL_VERSION = 9;
    private static final int OFFSET_AID_LENGTH = 10;
    private static final int OFFSET_INSTANCE_AID = 11;
    private static final int CARD_ID_LENGTH = 8;


    private final String host;
    private final int port;

    private TerminalFactory factory;
    private CardTerminal terminal;
    private Card card;
    private CardChannel channel;

    private volatile boolean ready;
    private volatile boolean connectedFlag;
    private volatile Status status;

    private final Reconciliation reconciliation = new Reconciliation();
    private Receipt lastReceipt;
    private java.security.PublicKey cardPublicKey;
    private Profile lastProfile;
    private ProfileCryptoMeta lastProfileMeta;
    private byte[] lastPhoto;
    private int lastResponseSw = -1;
    private volatile int lastPinSw = -1;

    CardGateway(String host, int port) {
        this.host = host;
        this.port = port;
    }

    CardGateway(CardChannel channel) {
        this.host = null;
        this.port = 0;
        this.channel = channel;
        this.connectedFlag = channel != null;
        this.ready = channel != null;
    }

    Result connect() throws CardException, java.security.NoSuchAlgorithmException,
            java.security.NoSuchProviderException {

        if (card != null) {
            return Result.fail("Đã kết nối rồi, phải Disconnect trước");
        }

        factory = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(host, port)),
                "SocketCardTerminalProvider");

        List<CardTerminal> terminals = factory.terminals().list();
        if (terminals.isEmpty()) {
            throw new CardException("Không có terminal nào từ socket provider");
        }
        terminal = terminals.get(0);

        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new CardException("Không có thẻ trên terminal " + terminal.getName());
        }

        card = terminal.connect("*");
        channel = card.getBasicChannel();
        ready = false;
        status = null;
        lastReceipt = null;
        lastProfile = null;
        lastProfileMeta = null;
        lastPhoto = null;
        lastPinSw = -1;
        connectedFlag = true;

        ATR atr = card.getATR();
        return Result.ok("terminal=" + terminal.getName() + " | ATR=" + hex(atr.getBytes()));
    }

    Result identify() throws CardException {
        return identifyWith(INSTANCE_AID);
    }

    Result identifyWith(byte[] instanceAid) throws CardException {
        requireConnected();
        ready = false;
        status = null;
        lastProfile = null;
        lastProfileMeta = null;
        lastPhoto = null;
        lastPinSw = -1;

        ResponseAPDU selectResponse = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, instanceAid, 256));
        if (selectResponse.getSW() != Sw.OK) {
            return Result.fail(String.format(
                    "SELECT AID=%s SW=%04X | kết luận: CHƯA SẴN SÀNG",
                    hex(instanceAid), selectResponse.getSW()));
        }
        String selectPrefix = String.format("SELECT AID=%s SW=9000", hex(instanceAid));

        ResponseAPDU response = channel.transmit(
                new CommandAPDU(CLA_APPLICATION, INS_GET_STATUS, 0x00, 0x00, 256));
        Result checked = checkStatus(response, instanceAid);
        if (!checked.ok()) {
            return Result.fail(selectPrefix + " | " + checked.text());
        }
        ready = true;
        return Result.ok(String.format(
                "%s | GET_STATUS SW=9000 | %s",
                selectPrefix, checked.text()));
    }

    Result checkStatus(ResponseAPDU response, byte[] requestedAid) {
        byte[] data = response.getData();
        int sw = response.getSW();

        if (sw != Sw.OK) {
            return Result.fail(String.format(
                    "GET_STATUS SW=%04X | kết luận: CHƯA SẴN SÀNG", sw));
        }
        if (data.length < OFFSET_INSTANCE_AID) {
            return Result.fail(String.format(
                    "GET_STATUS SW=%04X nhưng chỉ có %d byte | kết luận: CHƯA SẴN SÀNG",
                    sw, data.length));
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return Result.fail("GET_STATUS sai magic: " + hex(Arrays.copyOf(data, MAGIC.length))
                        + " | kết luận: CHƯA SẴN SÀNG");
            }
        }

        int version = data[OFFSET_PROTOCOL_VERSION] & 0xFF;
        if (version != PROTOCOL_VERSION) {
            return Result.fail(String.format(
                    "GET_STATUS protocol=%02X không hỗ trợ | kết luận: CHƯA SẴN SÀNG", version));
        }

        int aidLength = data[OFFSET_AID_LENGTH] & 0xFF;
        int expectedLength = OFFSET_INSTANCE_AID + aidLength + 1 + CARD_ID_LENGTH;
        if (data.length != expectedLength) {
            return Result.fail(String.format(
                    "GET_STATUS %d byte, theo aidLen=%d phải là %d | kết luận: CHƯA SẴN SÀNG",
                    data.length, aidLength, expectedLength));
        }

        byte[] returnedAid = Arrays.copyOfRange(data, OFFSET_INSTANCE_AID,
                OFFSET_INSTANCE_AID + aidLength);
        if (!Arrays.equals(returnedAid, requestedAid)) {
            return Result.fail(String.format(
                    "GET_STATUS trả AID=%s khác AID đã yêu cầu=%s | kết luận: CHƯA SẴN SÀNG",
                    hex(returnedAid), hex(requestedAid)));
        }

        int lifecycle = data[OFFSET_INSTANCE_AID + aidLength] & 0xFF;
        byte[] cardId = Arrays.copyOfRange(data, OFFSET_INSTANCE_AID + aidLength + 1,
                expectedLength);

        Result lifecycleProblem = checkLifecycle(lifecycle, cardId);
        if (lifecycleProblem != null) {
            return lifecycleProblem;
        }

        Status current = new Status(version, lifecycle, hex(returnedAid), hex(cardId));
        status = current;

        return Result.ok(String.format(
                "AID=%s protocol=%02X lifecycle=%s cardId=%s | kết luận: SẴN SÀNG",
                current.instanceAid(), current.protocolVersion(),
                lifecycleText(current.lifecycle()), current.cardId()));
    }

    private Result checkLifecycle(int lifecycle, byte[] cardId) {
        if (lifecycle != LIFECYCLE_UNPERSONALIZED && lifecycle != LIFECYCLE_ISSUED) {
            return Result.fail(String.format(
                    "GET_STATUS lifecycle=%02X chưa được giao thức định nghĩa | kết luận: CHƯA SẴN SÀNG",
                    lifecycle));
        }
        boolean cardIdAssigned = !isAllZero(cardId);
        if (lifecycle == LIFECYCLE_UNPERSONALIZED && cardIdAssigned) {
            return Result.fail(String.format(
                    "lifecycle=00 UNPERSONALIZED nhưng cardId=%s đã gán | kết luận: CHƯA SẴN SÀNG",
                    hex(cardId)));
        }
        if (lifecycle == LIFECYCLE_ISSUED && !cardIdAssigned) {
            return Result.fail(String.format(
                    "lifecycle=01 ISSUED nhưng cardId toàn 00 | kết luận: CHƯA SẴN SÀNG"));
        }
        return null;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
    static String lifecycleText(int lifecycle) {
        if (lifecycle == 0x00) {
            return "00 UNPERSONALIZED";
        }
        if (lifecycle == 0x01) {
            return "01 ISSUED";
        }
        return String.format("%02X KHÔNG RÕ", lifecycle);
    }

    Result readLastReceipt(ReceiptExpectation expectation) throws CardException {
        return readLastReceipt(expectation, RECEIPT_LE);
    }

    Result readCardPublicKey() throws CardException {
        requireConnected();
        ResponseAPDU response = channel.transmit(new CommandAPDU(
                CLA_APPLICATION, INS_GET_CARD_PUBLIC_KEY, 0x00, 0x00, PUBLIC_KEY_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        if (sw != Sw.OK) {
            cardPublicKey = null;
            return Result.fail(String.format(
                    "GET_CARD_PUBLIC_KEY INS=61 P1P2=0000 Le=%d SW=%04X | %s | kết luận: CHƯA SẴN SÀNG",
                    PUBLIC_KEY_LE, sw, publicKeyStatusText(sw)));
        }
        ReceiptVerifier.ParsedKey parsed = ReceiptVerifier.parsePublicKey(data);
        if (!parsed.ok()) {
            cardPublicKey = null;
            return Result.fail(String.format(
                    "GET_CARD_PUBLIC_KEY INS=61 SW=9000 dataLen=%d | %s | kết luận: CHƯA SẴN SÀNG",
                    data.length, parsed.problem()));
        }
        cardPublicKey = parsed.key();
        String cardId = status == null ? "chưa nhận diện" : status.cardId();
        return Result.ok(String.format(
                "GET_CARD_PUBLIC_KEY INS=61 SW=9000 dataLen=%d | cardId=%s | P-256 point=%s",
                data.length, cardId, hex(data)));
    }

    private static String publicKeyStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED — GET_CARD_PUBLIC_KEY chỉ đọc được sau PERSONALIZE";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Le nhỏ hơn 65 byte của public point";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        return "không đọc được public key";
    }

    Result readProfile() throws CardException {
        return readProfile(0x00, 0x00);
    }

    Result readProfile(int p1, int p2) throws CardException {
        requireConnected();
        lastProfile = null;
        ResponseAPDU response = channel.transmit(new CommandAPDU(
                CLA_APPLICATION, INS_GET_PROFILE, p1, p2, PROFILE_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        String apdu = String.format(
                "GET_PROFILE INS=31 P1P2=%02X%02X Le=00 SW=%04X dataLen=%d",
                p1, p2, sw, data.length);

        if (sw != Sw.OK) {
            return Result.fail(apdu + " | " + profileStatusText(sw));
        }
        ProfileCodec.Parse parse = ProfileCodec.parse(data);
        if (!parse.ok()) {
            return Result.fail(apdu + " | parser=FAIL (" + parse.problem() + ")");
        }
        lastProfile = parse.profile();
        return Result.ok(apdu + " | parser=PASS | " + lastProfile.evidence()
                + " | UTF-8 nghiêm ngặt=PASS");
    }

    Result readProfileCryptoMeta() throws CardException {
        return readProfileCryptoMeta(0x00, 0x00);
    }

    Result readProfileCryptoMeta(int p1, int p2) throws CardException {
        requireConnected();
        lastProfileMeta = null;
        ResponseAPDU response = channel.transmit(new CommandAPDU(
                CLA_APPLICATION, INS_GET_PROFILE_CRYPTO_META, p1, p2, PROFILE_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        String apdu = String.format(
                "GET_PROFILE_CRYPTO_META INS=40 P1P2=%02X%02X Le=00 SW=%04X dataLen=%d",
                p1, p2, sw, data.length);

        if (sw != Sw.OK) {
            return Result.fail(apdu + " | " + profileStatusText(sw));
        }
        ProfileMetaCodec.Parse parse = ProfileMetaCodec.parse(data);
        if (!parse.ok()) {
            return Result.fail(apdu + " | parser=FAIL (" + parse.problem() + ")");
        }
        lastProfileMeta = parse.meta();
        return Result.ok(apdu + " | parser=PASS | " + lastProfileMeta.evidence());
    }

    Result readProfileWithPin(char[] pin) throws CardException {
        return withFreshAuthorization("GET_PROFILE", pin, this::readProfile);
    }

    Result readProfileCryptoMetaWithPin(char[] pin) throws CardException {
        return withFreshAuthorization("GET_PROFILE_CRYPTO_META", pin,
                this::readProfileCryptoMeta);
    }

    private Result withFreshAuthorization(String label, char[] pin, ReadOperation read)
            throws CardException {
        lastProfile = null;
        lastProfileMeta = null;
        Result verified = verifyStudentPin(pin);
        if (!verified.ok()) {
            return Result.fail(label + " không chạy — VERIFY: " + verified.text());
        }
        Result result = read.run();
        if (!result.ok()) {
            return result;
        }
        return Result.ok("VERIFY SW=9000 (quyền chỉ dùng cho lệnh vừa gửi) | " + result.text());
    }

    interface ReadOperation {
        Result run() throws CardException;
    }

    ResponseAPDU transmitPhotoBegin(byte[] data) throws CardException {
        requireConnected();
        ResponseAPDU begin = channel.transmit(new CommandAPDU(
                CLA_APPLICATION, INS_PHOTO_BEGIN, 0x00, 0x00, data));
        lastResponseSw = begin.getSW();
        return begin;
    }

    ResponseAPDU transmitPhotoWriteChunk(int offset, byte[] chunk) throws CardException {
        requireConnected();
        ResponseAPDU write = channel.transmit(new CommandAPDU(CLA_APPLICATION,
                INS_PHOTO_WRITE_CHUNK, (offset >> 8) & 0xFF, offset & 0xFF, chunk));
        lastResponseSw = write.getSW();
        return write;
    }

    ResponseAPDU transmitPhotoFinish() throws CardException {
        requireConnected();
        ResponseAPDU finish = channel.transmit(new CommandAPDU(
                CLA_APPLICATION, INS_PHOTO_FINISH, 0x00, 0x00));
        lastResponseSw = finish.getSW();
        return finish;
    }

    Result uploadPhoto(byte[] image, char[] adminPin) throws CardException {
        requireConnected();
        lastPhoto = null;
        if (image == null || image.length != PhotoImage.LENGTH) {
            return Result.fail("ảnh phải đúng " + PhotoImage.LENGTH
                    + " byte grayscale raw 8-bit (chưa gửi APDU)");
        }
        if (adminPin == null || adminPin.length != PhotoImage.ADMIN_PIN_LENGTH) {
            return Result.fail("admin PIN phải đúng " + PhotoImage.ADMIN_PIN_LENGTH
                    + " ký tự (chưa gửi APDU)");
        }
        byte[] beginData = PhotoImage.beginPayload(adminPin);
        try {
            ResponseAPDU begin = transmitPhotoBegin(beginData);
            if (begin.getSW() != Sw.OK) {
                return Result.fail(String.format(
                        "PHOTO_BEGIN Case 3S Lc=%d SW=%04X | %s",
                        beginData.length, begin.getSW(), photoStatusText(begin.getSW())));
            }

            int offset = 0;
            int chunks = 0;
            while (offset < PhotoImage.LENGTH) {
                int length = PhotoImage.chunkLength(offset);
                ResponseAPDU write = transmitPhotoWriteChunk(offset,
                        PhotoImage.chunk(image, offset, length));
                if (write.getSW() != Sw.OK) {
                    return Result.fail(String.format(
                            "PHOTO_WRITE_CHUNK offset=%d Lc=%d SW=%04X | %s",
                            offset, length, write.getSW(), photoStatusText(write.getSW())));
                }
                offset += length;
                chunks++;
            }

            ResponseAPDU finish = transmitPhotoFinish();
            if (finish.getSW() != Sw.OK) {
                return Result.fail(String.format(
                        "PHOTO_FINISH Case 1 SW=%04X | %s",
                        finish.getSW(), photoStatusText(finish.getSW())));
            }
            return Result.ok(String.format(
                    "PHOTO_BEGIN SW=9000 | PHOTO_WRITE_CHUNK x%d tổng=%d byte SW=9000"
                            + " | PHOTO_FINISH SW=9000 | photoReady=true | sha256 nguồn=%s",
                    chunks, offset, PhotoImage.fingerprint(image)));
        } finally {
            Arrays.fill(beginData, (byte) 0);
        }
    }

    ResponseAPDU transmitReadChunk(int offset, int le) throws CardException {
        requireConnected();
        ResponseAPDU chunk = channel.transmit(new CommandAPDU(CLA_APPLICATION,
                INS_PHOTO_READ_CHUNK, (offset >> 8) & 0xFF, offset & 0xFF, le));
        lastResponseSw = chunk.getSW();
        return chunk;
    }

    Result readPhoto(char[] pin) throws CardException {
        requireConnected();
        lastPhoto = null;
        Result verified = verifyStudentPin(pin);
        if (!verified.ok()) {
            return Result.fail("đọc ảnh không chạy — VERIFY: " + verified.text());
        }
        byte[] image = new byte[PhotoImage.LENGTH];
        int offset = 0;
        int chunks = 0;
        while (offset < PhotoImage.LENGTH) {
            int length = PhotoImage.chunkLength(offset);
            ResponseAPDU chunk = transmitReadChunk(offset, length);
            if (chunk.getSW() != Sw.OK) {
                return Result.fail(String.format(
                        "PHOTO_READ_CHUNK offset=%d Le=%d SW=%04X | %s",
                        offset, length, chunk.getSW(), photoStatusText(chunk.getSW())));
            }
            byte[] data = chunk.getData();
            if (data.length == 0 || data.length > length) {
                return Result.fail(String.format(
                        "PHOTO_READ_CHUNK offset=%d trả %d byte, vượt Le=%d",
                        offset, data.length, length));
            }
            System.arraycopy(data, 0, image, offset, data.length);
            offset += data.length;
            chunks++;
        }
        lastPhoto = image;
        return Result.ok(String.format(
                "VERIFY SW=9000 (quyền tiêu thụ khi hết chuỗi) | PHOTO_READ_CHUNK x%d"
                        + " tổng=%d byte SW=9000 | sha256 đọc lại=%s",
                chunks, offset, PhotoImage.fingerprint(image)));
    }

    byte[] lastPhoto() {
        return lastPhoto;
    }

    private static String photoStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "photoReady/phiên không cho phép: chưa BEGIN, đã ready, hoặc thiếu dữ liệu";
        }
        if (sw == Sw.SECURITY_NOT_SATISFIED) {
            return "thiếu quyền student — VERIFY ngay trước chuỗi đọc";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "offset không liền mạch hoặc vượt tổng ảnh";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc/Le sai (chunk 1..128, BEGIN Lc=13, FINISH không data/Le)";
        }
        if (sw == Sw.WRONG_DATA) {
            return "admin PIN sai định dạng hoặc format/kích thước sai";
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "admin PIN đã bị khóa";
        }
        return String.format("SW=%04X không đọc được ảnh", sw);
    }

    private static String profileStatusText(int sw) {
        if (sw == Sw.SECURITY_NOT_SATISFIED) {
            return "thiếu quyền student — VERIFY_STUDENT_PIN ngay trước lệnh đọc,"
                    + " quyền chỉ dùng cho một lệnh";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED — chưa có hồ sơ để đọc";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Le nhỏ hơn độ dài response";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "record hồ sơ hỏng (tag/version/schema) — không trả dữ liệu";
        }
        return String.format("SW=%04X không đọc được hồ sơ", sw);
    }

    Profile lastProfile() {
        return lastProfile;
    }

    ProfileCryptoMeta lastProfileMeta() {
        return lastProfileMeta;
    }

    int lastPinSw() {
        return lastPinSw;
    }

    java.security.PublicKey cardPublicKey() {
        return cardPublicKey;
    }

    Result personalize(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) throws CardException {
        requireConnected();

        String problem = PersonalizeRequest.problem(
                adminPin, cardIdHex, studentId, studentPin, displayName);
        if (problem != null) {
            return Result.fail("PERSONALIZE: " + problem + " | không gửi APDU");
        }

        String expectedCardId = cardIdHex.strip().toUpperCase();
        byte[] data = PersonalizeRequest.payload(
                adminPin, cardIdHex, studentId, studentPin, displayName);
        int lc = data.length;
        ResponseAPDU response;
        try {
            response = channel.transmit(new CommandAPDU(
                    CLA_APPLICATION, INS_PERSONALIZE, 0x00, 0x00, data, 256));
        } finally {
            Arrays.fill(data, (byte) 0);
        }

        int sw = response.getSW();
        lastResponseSw = sw;
        String apdu = String.format(
                "PERSONALIZE INS=30 P1P2=0000 case=4S Le=00 Lc=%d SW=%04X", lc, sw);
        if (sw != Sw.OK) {
            return Result.fail(apdu + " | " + personalizeStatusText(sw)
                    + " | kết luận: CHƯA SẴN SÀNG");
        }

        Result reidentified = identify();
        if (!reidentified.ok()) {
            return Result.fail(apdu + " | " + reidentified.text());
        }
        Status current = status;
        if (current == null || current.lifecycle() != LIFECYCLE_ISSUED) {
            return Result.fail(apdu + " | sau PERSONALIZE lifecycle không phải ISSUED"
                    + " | kết luận: CHƯA SẴN SÀNG");
        }
        if (!current.cardId().equalsIgnoreCase(expectedCardId)) {
            return Result.fail(String.format("%s | cardId trên thẻ=%s khác yêu cầu=%s"
                    + " | kết luận: CHƯA SẴN SÀNG", apdu, current.cardId(), expectedCardId));
        }
        return Result.ok(apdu + " | " + reidentified.text());
    }

    private static String personalizeStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ đã ISSUED — PERSONALIZE chỉ chạy một lần";
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "admin PIN đã bị khóa do hết lượt thử";
        }
        if ((sw & 0xFF00) == 0x6300) {
            return String.format("admin PIN sai, còn %d lượt thử", sw & 0x0F);
        }
        if (sw == Sw.WRONG_DATA) {
            return "payload bị từ chối: cấu trúc/định dạng/giới hạn sai";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc không khớp schema hoặc Le khác 00";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "lỗi nội bộ khi sinh khóa/mã hóa; thẻ vẫn UNPERSONALIZED";
        }
        return "không xác định được kết quả";
    }

    Result readLastReceipt(ReceiptExpectation expectation, int le) throws CardException {
        requireConnected();

        ResponseAPDU response = channel.transmit(new CommandAPDU(
                CLA_APPLICATION, INS_GET_LAST_RECEIPT, 0x00, 0x00, le));
        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        String apdu = String.format("GET_LAST_RECEIPT INS=60 P1P2=0000 Le=%d SW=%04X dataLen=%d",
                le, sw, data.length);

        if (sw != Sw.OK) {
            lastReceipt = null;
            return Result.fail(apdu + " | " + receiptStatusText(sw));
        }

        int signatureLength = data.length > ReceiptCodec.PAYLOAD_LENGTH
                ? (data[ReceiptCodec.PAYLOAD_LENGTH] & 0xFF)
                : -1;
        ReceiptCodec.Parse parse = ReceiptCodec.parse(data);
        if (!parse.ok()) {
            lastReceipt = null;
            reconciliation.markUnknown("receipt không đọc được: " + parse.problem());
            return Result.fail(String.format("%s sigLen=%d parser=FAIL (%s) | đối soát=%s",
                    apdu, signatureLength, parse.problem(), reconciliation.state()));
        }

        Receipt receipt = parse.receipt();
        lastReceipt = receipt;
        String detail = String.format(
                "%s sigLen=%d parser=PASS | receipt %s | payload=%s signature=%s",
                apdu, signatureLength, receipt, receipt.payloadHex(), receipt.signatureHex());

        String cardProblem = cardIdProblem(receipt);
        if (cardProblem != null) {
            lastReceipt = null;
            reconciliation.markUnknown(cardProblem);
            return Result.fail(String.format("%s | kết luận=MISMATCH (%s) state=%s",
                    detail, cardProblem, reconciliation.state()));
        }

        Result signatureCheck = verifyReceiptSignature(receipt);
        if (!signatureCheck.ok()) {
            lastReceipt = null;
            reconciliation.markUnknown("chữ ký receipt không hợp lệ");
            return Result.fail(String.format("%s | ECDSA=FAIL (%s) | kết luận=MISMATCH state=%s",
                    detail, signatureCheck.text(), reconciliation.state()));
        }
        detail = detail + " | ECDSA=PASS";

        if (expectation == null) {
            return Result.ok(detail + " | chưa đối soát — thiếu kỳ vọng của giao dịch");
        }

        Reconciliation.Verdict verdict = reconciliation.resolve(receipt, expectation);
        String text = String.format("%s | expected %s | kết luận=%s state=%s",
                detail, expectationText(expectation), verdict, reconciliation.state());
        return verdict == Reconciliation.Verdict.MISMATCH ? Result.fail(text) : Result.ok(text);
    }

    private String cardIdProblem(Receipt receipt) {
        Status current = status();
        if (current == null) {
            return "chưa nhận diện thẻ để đối chiếu cardId";
        }
        if (receipt.cardIdHex().equalsIgnoreCase(current.cardId())) {
            return null;
        }
        return String.format("cardId receipt=%s khác thẻ đang nhận diện=%s",
                receipt.cardIdHex(), current.cardId());
    }

    Result prepareReceiptVerification() throws CardException {
        return ensureCardPublicKeyLoaded();
    }

    private Result verifyReceiptSignature(Receipt receipt) throws CardException {
        Result loaded = ensureCardPublicKeyLoaded();
        if (!loaded.ok()) {
            return Result.fail(loaded.text());
        }
        ReceiptVerifier.Result verified = ReceiptVerifier.verifyReceipt(cardPublicKey, receipt);
        if (verified.ok()) {
            return Result.ok("ECDSA P-256/SHA-256 hợp lệ");
        }
        return Result.fail(verified.problem());
    }

    void markTransactionUnknown(String cause) {
        reconciliation.markUnknown(cause);
    }

    private record PinField(char[] value, int length, String name) {
    }

    Result verifyStudentPin(char[] pin) throws CardException {
        return pinCommand("VERIFY_STUDENT_PIN", INS_VERIFY_STUDENT_PIN,
                "quyền student đã mở cho APDU kế tiếp",
                new PinField(pin, PIN_LENGTH, "PIN"));
    }

    Result changeStudentPin(char[] oldPin, char[] newPin) throws CardException {
        return pinCommand("CHANGE_STUDENT_PIN", INS_CHANGE_STUDENT_PIN,
                "PIN đã đổi, retry reset về 3",
                new PinField(oldPin, PIN_LENGTH, "PIN cũ"),
                new PinField(newPin, PIN_LENGTH, "PIN mới"));
    }

    Result unblockStudentPin(char[] adminPin, char[] newPin) throws CardException {
        return pinCommand("UNBLOCK_STUDENT_PIN", INS_UNBLOCK_STUDENT_PIN,
                "PIN đã mở khóa, retry reset về 3",
                new PinField(adminPin, ADMIN_PIN_LENGTH, "admin PIN"),
                new PinField(newPin, PIN_LENGTH, "PIN mới"));
    }

    private Result pinCommand(String label, byte ins, String successText, PinField... fields)
            throws CardException {
        requireConnected();
        lastPinSw = -1;
        if (!ready || status == null) {
            return Result.fail(label + ": phải SELECT + GET_STATUS thẻ ISSUED trước | không gửi APDU");
        }
        if (status.lifecycle() != LIFECYCLE_ISSUED) {
            return Result.fail(String.format("%s: lifecycle=%d %s | không gửi APDU",
                    label, status.lifecycle(), lifecycleText(status.lifecycle())));
        }
        int total = 0;
        for (PinField field : fields) {
            total += field.length();
        }
        byte[] data = new byte[total];
        try {
            int offset = 0;
            for (PinField field : fields) {
                String problem = copyDigits(field, data, offset);
                if (problem != null) {
                    return Result.fail(label + ": " + problem + " | không gửi APDU");
                }
                offset += field.length();
            }
            ResponseAPDU response = channel.transmit(new CommandAPDU(
                    CLA_APPLICATION, ins, 0x00, 0x00, data));
            int sw = response.getSW();
            lastResponseSw = sw;
            lastPinSw = sw;
            if (sw == Sw.OK) {
                return Result.ok(String.format(
                        "%s Case 3S Lc=%d SW=9000 | %s", label, total, successText));
            }
            return Result.fail(String.format("%s Case 3S Lc=%d SW=%04X | %s",
                    label, total, sw, pinStatusText(sw)));
        } finally {
            java.util.Arrays.fill(data, (byte) 0);
        }
    }

    private static String copyDigits(PinField field, byte[] target, int offset) {
        if (field.value() == null) {
            return field.name() + " không được để trống";
        }
        if (field.value().length != field.length()) {
            return String.format("%s phải đúng %d chữ số", field.name(), field.length());
        }
        for (int index = 0; index < field.length(); index++) {
            char digit = field.value()[index];
            if (digit < '0' || digit > '9') {
                return field.name() + " chỉ gồm chữ số 0-9";
            }
            target[offset + index] = (byte) digit;
        }
        return null;
    }

    private static String pinStatusText(int sw) {
        if (sw == Sw.AUTH_BLOCKED) {
            return "PIN đã bị khóa, cần UNBLOCK";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa personalize hoặc PIN chưa gán";
        }
        if ((sw & 0xFF00) == 0x6300) {
            return String.format("PIN sai, còn %d lần thử", sw & 0x0F);
        }
        if (sw == Sw.WRONG_DATA) {
            return "định dạng PIN sai";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc không khớp, Case 3S không Le";
        }
        return String.format("SW=%04X không xác thực được", sw);
    }

    record TransactionPlan(ReceiptExpectation expectation, int delta, int expectedBalance) {
    }

    Result runStateTransaction(PendingJournal journal, TransactionPlan plan,
            CommandSupplier command, char[] studentPin) throws CardException {
        requireConnected();
        if (!ready || status() == null || status().lifecycle() != LIFECYCLE_ISSUED) {
            return Result.fail("giao dịch bị từ chối: phải SELECT + GET_STATUS thẻ ISSUED trước");
        }
        if (!reconciliation.transactionAllowed()) {
            return Result.fail("giao dịch bị khóa: đối soát=" + reconciliation.state()
                    + " (" + reconciliation.detail() + ")");
        }
        if (journal.load().present()) {
            return Result.fail("đã có pending journal — phải đối soát trước giao dịch mới");
        }
        PendingJournal.Entry entry = new PendingJournal.Entry(
                gatewayCardId(), plan.expectation().counterAfter(),
                plan.expectation().operation(), plan.delta(), plan.expectedBalance(),
                plan.expectation().visitId(), System.currentTimeMillis());
        if (!journal.writePending(entry)) {
            return Result.fail("ghi pending journal thất bại — không gửi APDU");
        }

        ResponseAPDU response;
        try {
            response = command.transmit();
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất kết nối khi chờ response: " + ex.getMessage());
            return Result.fail("APDU không có response — journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }

        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        String apdu = String.format("APDU đổi state SW=%04X dataLen=%d", sw, data.length);

        if (sw == Sw.INTERNAL_ERROR) {
            reconciliation.markUnknown("Applet trả 6F00 — có thể đã commit, có thể chưa");
            return Result.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (sw != Sw.OK) {
            reconciliation.markUnknown("APDU đổi state bị từ chối; cần xác nhận state trước khi xóa journal");
            return Result.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (data.length != STATE_RESPONSE_LENGTH) {
            reconciliation.markUnknown("response " + data.length + " byte khác 6 — không đọc được state");
            return Result.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }

        Result receiptRead;
        try {
            receiptRead = verifyAndReadReceipt(plan.expectation(), studentPin);
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất response khi đọc receipt: " + ex.getMessage());
            return Result.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (!receiptRead.ok()) {
            reconciliation.markUnknown("đọc receipt sau 9000 thất bại");
            return Result.fail(apdu + " | " + receiptRead.text() + " | journal vẫn PENDING"
                    + " | đối soát=" + reconciliation.state());
        }
        if (lastReceipt == null
                || lastReceipt.counterAfter() != plan.expectation().counterAfter()) {
            reconciliation.markUnknown("APDU trả 9000 nhưng receipt không chứng minh counter giao dịch vừa gửi");
            return Result.fail(apdu + " | receipt không khớp counter sau 9000"
                    + " | journal vẫn PENDING | đối soát=" + reconciliation.state());
        }
        String valueProblem = transactionValueProblem(lastReceipt, plan);
        if (valueProblem != null) {
            reconciliation.markUnknown(valueProblem);
            return Result.fail(apdu + " | receipt không khớp expected delta/balance: "
                    + valueProblem + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        Result journalResult = finishJournal(journal, entry);
        if (!journalResult.ok()) {
            return Result.fail(apdu + " | " + receiptRead.text() + " | " + journalResult.text());
        }
        return Result.ok(apdu + " | " + receiptRead.text() + " | " + journalResult.text());
    }

    Result reconcilePending(PendingJournal journal, char[] studentPin) throws CardException {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            return Result.fail("không có pending journal");
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            reconciliation.markUnknown("pending journal không hợp lệ hoặc đã RESOLVED");
            return Result.fail("pending journal cần xử lý thủ công: " + loaded.problem());
        }
        PendingJournal.Entry entry = loaded.entry();
        if (!entry.cardId().equalsIgnoreCase(gatewayCardId())) {
            reconciliation.markUnknown("pending journal thuộc cardId khác thẻ đang nhận diện");
            return Result.fail("cardId của journal khác cardId hiện tại — vẫn giữ journal");
        }

        Result keyLoaded = ensureCardPublicKeyLoaded();
        if (!keyLoaded.ok()) {
            reconciliation.markUnknown("không nạp được public key để xác minh receipt");
            return Result.fail(keyLoaded.text() + " | journal vẫn PENDING");
        }
        Result verify = verifyStudentPin(studentPin);
        if (!verify.ok()) {
            return Result.fail("VERIFY để đối soát thất bại: " + verify.text());
        }
        Result receiptRead;
        try {
            receiptRead = readLastReceipt(null);
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất response khi đối soát receipt: " + ex.getMessage());
            return Result.fail("không đọc được receipt — journal vẫn PENDING");
        }
        if (!receiptRead.ok()) {
            if (lastResponseSw == Sw.CONDITIONS_NOT_SATISFIED && entry.expectedCounter() == 1) {
                reconciliation.markKnown("chưa có receipt, giao dịch đầu tiên chưa thực thi");
                return finishJournal(journal, entry);
            }
            reconciliation.markUnknown("không chứng minh được giao dịch đã hoặc chưa thực thi");
            return Result.fail(receiptRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }

        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(entry.cardId()), entry.expectedCounter(),
                entry.operation(), entry.visitId());
        Reconciliation.Verdict verdict = reconciliation.resolve(lastReceipt, expectation);
        if (verdict == Reconciliation.Verdict.MISMATCH) {
            reconciliation.markUnknown("receipt không khớp pending journal");
            return Result.fail(receiptRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        if (verdict == Reconciliation.Verdict.EXECUTED) {
            String valueProblem = transactionValueProblem(lastReceipt, entry);
            if (valueProblem != null) {
                reconciliation.markUnknown(valueProblem);
                return Result.fail("receipt cùng counter nhưng sai delta/balance: " + valueProblem
                        + " | journal vẫn PENDING");
            }
        } else if (lastReceipt.balanceAfter() != entry.expectedBalance() - entry.delta()) {
            reconciliation.markUnknown("balance receipt trước giao dịch không khớp expected state cũ");
            return Result.fail("receipt cũ không chứng minh state chưa đổi — journal vẫn PENDING");
        }
        return finishJournal(journal, entry, verdict, receiptRead.text());
    }

    private Result finishJournal(PendingJournal journal, PendingJournal.Entry entry) {
        return finishJournal(journal, entry, null, null);
    }

    private Result finishJournal(PendingJournal journal, PendingJournal.Entry entry,
            Reconciliation.Verdict verdict, String evidence) {
        if (!journal.writeResolved(entry)) {
            reconciliation.markUnknown("ghi trạng thái RESOLVED thất bại; giữ journal");
            return Result.fail("ghi RESOLVED thất bại — journal vẫn được giữ lại");
        }
        try {
            journal.remove();
        } catch (RuntimeException ex) {
            reconciliation.markUnknown("xóa journal RESOLVED thất bại; giữ file để xử lý thủ công");
            return Result.fail("journal đã RESOLVED nhưng chưa xóa được — cần xử lý thủ công");
        }
        if (verdict == null) {
            return Result.ok("journal đã RESOLVED và bị xóa");
        }
        return Result.ok(String.format(
                "%s | kết luận=%s | đối soát=%s | journal đã RESOLVED và bị xóa",
                evidence, verdict, reconciliation.state()));
    }

    private static String transactionValueProblem(Receipt receipt, TransactionPlan plan) {
        if (receipt == null) {
            return "chưa có receipt để đối chiếu delta/balance";
        }
        if (receipt.delta() != plan.delta()) {
            return String.format("delta receipt=%d expected=%d", receipt.delta(), plan.delta());
        }
        if (receipt.balanceAfter() != plan.expectedBalance()) {
            return String.format("balance receipt=%d expected=%d",
                    receipt.balanceAfter(), plan.expectedBalance());
        }
        return null;
    }

    private static String transactionValueProblem(Receipt receipt, PendingJournal.Entry entry) {
        return transactionValueProblem(receipt,
                new TransactionPlan(new ReceiptExpectation(null, entry.expectedCounter(),
                        entry.operation(), entry.visitId()), entry.delta(), entry.expectedBalance()));
    }

    Result verifyAndReadReceipt(ReceiptExpectation expectation, char[] studentPin)
            throws CardException {
        Result keyLoaded = ensureCardPublicKeyLoaded();
        if (!keyLoaded.ok()) {
            return Result.fail("không nạp được public key của thẻ: " + keyLoaded.text());
        }
        if (studentPin != null) {
            Result verify = verifyStudentPin(studentPin);
            if (!verify.ok()) {
                return Result.fail("VERIFY lần hai sau giao dịch thất bại: " + verify.text());
            }
        }
        return readLastReceipt(expectation);
    }

    private Result ensureCardPublicKeyLoaded() throws CardException {
        if (cardPublicKey != null) {
            return Result.ok("public key đã nạp");
        }
        return readCardPublicKey();
    }

    interface CommandSupplier {
        ResponseAPDU transmit() throws CardException;
    }

    CommandSupplier grantCommand(byte[] adminPin, int delta) {
        return () -> {
            byte[] data = new byte[GRANT_DATA_LENGTH];
            try {
                if (adminPin == null || adminPin.length != 8) {
                    throw new IllegalArgumentException("admin PIN phải đúng 8 byte");
                }
                System.arraycopy(adminPin, 0, data, 0, 8);
                data[8] = (byte) (delta >> 8);
                data[9] = (byte) delta;
                return channel.transmit(new CommandAPDU(
                        CLA_APPLICATION, INS_GRANT_QUOTA, 0x00, 0x00, data, STATE_RESPONSE_LENGTH));
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        };
    }

    CommandSupplier visitCommand(byte ins, byte[] visitId) {
        return () -> channel.transmit(new CommandAPDU(
                CLA_APPLICATION, ins, 0x00, 0x00, visitId, STATE_RESPONSE_LENGTH));
    }

    private String gatewayCardId() {
        CardGateway.Status status = status();
        return status == null ? "0000000000000000" : status.cardId();
    }

    Reconciliation reconciliation() {
        return reconciliation;
    }

    Receipt lastReceipt() {
        return lastReceipt;
    }

    int lastResponseSw() {
        return lastResponseSw;
    }

    private static String expectationText(ReceiptExpectation expectation) {
        return String.format("counter=%d operation=%s visitId=%s",
                expectation.counterAfter(), Receipt.operationText(expectation.operation()),
                expectation.visitId() == null ? "<khong dung>" : hex(expectation.visitId()));
    }

    private static String receiptStatusText(int sw) {
        if (sw == Sw.SECURITY_NOT_SATISFIED) {
            return "thiếu student authorization — VERIFY_STUDENT_PIN trước khi đọc receipt";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa personalize hoặc chưa có receipt nào được lưu";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Le thiếu so với độ dài response thật";
        }
        return String.format("SW=%04X không đọc được receipt", sw);
    }

    Result disconnect() throws CardException {
        ready = false;
        status = null;
        lastReceipt = null;
        lastProfile = null;
        lastProfileMeta = null;
        lastPhoto = null;
        lastPinSw = -1;
        channel = null;
        terminal = null;
        factory = null;
        boolean wasConnected = card != null;
        try {
            if (card != null) {
                card.disconnect(true);
            }
        } finally {
            card = null;
            connectedFlag = false;
        }
        return wasConnected ? Result.ok("Đã đóng kết nối") : Result.ok("Vốn đã ngắt kết nối");
    }

    boolean isConnected() {
        return connectedFlag;
    }

    boolean isReady() {
        return ready;
    }

    Status status() {
        return status;
    }

    private void requireConnected() {
        if (channel == null) {
            throw new IllegalStateException("Chưa kết nối");
        }
    }

    static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "<rong>";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
