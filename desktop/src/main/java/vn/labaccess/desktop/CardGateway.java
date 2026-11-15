package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.HexFormat;

import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

final class CardGateway {

    private static final byte[] INSTANCE_AID = HexFormat.of().parseHex("F04C4142010101");

    private static final byte INS_GET_STATUS = (byte) 0x10;
    private static final byte INS_PERSONALIZE = (byte) 0x30;
    private static final byte INS_GRANT_QUOTA = (byte) 0x50;
    private static final byte INS_START_VISIT = (byte) 0x51;
    private static final byte INS_END_VISIT = (byte) 0x52;
    private static final byte INS_GET_LAST_RECEIPT = (byte) 0x60;
    private static final byte INS_GET_CARD_PUBLIC_KEY = (byte) 0x61;
    private static final byte INS_SIGN_REGISTRATION_CHALLENGE = (byte) 0x62;
    private static final int PUBLIC_KEY_LE = 256;
    private static final int SIGNATURE_LE = 256;
    private static final int NONCE_LENGTH = 32;
    private static final int PUBLIC_POINT_LENGTH = 65;
    private static final int DER_SIGNATURE_MIN = 8;
    private static final int DER_SIGNATURE_MAX = 72;
    private static final int RECEIPT_LE = 256;
    private static final int STATE_RESPONSE_LENGTH = 6;
    private static final int GRANT_DATA_LENGTH = 10;
    private static final int VISIT_ID_LENGTH = 16;
    private static final int PROTOCOL_VERSION = 0x01;

    private final CardTransport transport;

    private volatile boolean ready;
    private volatile CardStatus status;

    private final Reconciliation reconciliation = new Reconciliation();
    private EnrollmentCheck cardEnrollment;
    private String enrollmentCardId;
    private String enrollmentDetail = "chưa kiểm tra đăng ký thẻ trên Backend";
    private Receipt lastReceipt;
    private boolean lastReceiptSignatureOk;
    private boolean signatureChecked;
    private java.security.PublicKey cardPublicKey;
    private byte[] cardPublicKeyBytes;
    private byte[] enrollmentSignature;
    private int lastResponseSw = -1;
    private final CardPinCommands pinCommands;
    private final CardPhotoCommands photoCommands;
    private final CardProfileCommands profileCommands;

    CardGateway(String host, int port) {
        this.transport = new CardTransport(host, port);
        this.pinCommands = new CardPinCommands(transport, () -> ready ? status : null,
                sw -> lastResponseSw = sw);
        this.photoCommands = new CardPhotoCommands(transport, this::verifyStudentPin,
                sw -> lastResponseSw = sw);
        this.profileCommands = new CardProfileCommands(transport, pinCommands,
                sw -> lastResponseSw = sw);
    }

    CardGateway(CardChannel channel) {
        this.transport = new CardTransport(channel);
        this.ready = channel != null;
        this.pinCommands = new CardPinCommands(transport, () -> ready ? status : null,
                sw -> lastResponseSw = sw);
        this.photoCommands = new CardPhotoCommands(transport, this::verifyStudentPin,
                sw -> lastResponseSw = sw);
        this.profileCommands = new CardProfileCommands(transport, pinCommands,
                sw -> lastResponseSw = sw);
    }

    CardOperationResult connect() throws CardException, java.security.NoSuchAlgorithmException,
            java.security.NoSuchProviderException {

        if (transport.isOpen()) {
            return CardOperationResult.fail("Đã kết nối rồi, phải Disconnect trước");
        }

        CardTransport.Opened opened = transport.connect();
        resetSession("vừa kết nối, chưa kiểm tra đăng ký thẻ");
        return CardOperationResult.ok("terminal=" + opened.terminalName() + " | ATR="
                + (opened.atr() == null ? "<không có ATR>" : hex(opened.atr())));
    }

    private void resetSession(String enrollmentDetail) {
        ready = false;
        status = null;
        lastReceipt = null;
        cardPublicKey = null;
        profileCommands.reset();
        photoCommands.reset();
        pinCommands.reset();
        clearCardEnrollment(enrollmentDetail);
    }


    CardOperationResult identify() throws CardException {
        return identifyWith(INSTANCE_AID);
    }

    CardOperationResult identifyWith(byte[] instanceAid) throws CardException {
        transport.requireConnected();
        ready = false;
        status = null;
        cardPublicKey = null;
        profileCommands.reset();
        photoCommands.reset();
        pinCommands.reset();
        clearCardEnrollment("vừa nhận diện lại thẻ, chưa kiểm tra đăng ký thẻ");

        ResponseAPDU selectResponse = transport.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, instanceAid, 256));
        if (selectResponse.getSW() != Sw.OK) {
            return CardOperationResult.fail(String.format(
                    "SELECT AID=%s SW=%04X | kết luận: CHƯA SẴN SÀNG",
                    hex(instanceAid), selectResponse.getSW()));
        }
        String selectPrefix = String.format("SELECT AID=%s SW=9000", hex(instanceAid));

        ResponseAPDU response = transport.transmit(
                new CommandAPDU(CardProtocol.CLA_APPLICATION, INS_GET_STATUS, 0x00, 0x00, 256));
        CardOperationResult checked = checkStatus(response, instanceAid);
        if (!checked.ok()) {
            return CardOperationResult.fail(selectPrefix + " | " + checked.text());
        }
        ready = true;
        return CardOperationResult.ok(String.format(
                "%s | GET_STATUS SW=9000 | %s",
                selectPrefix, checked.text()));
    }

    CardOperationResult checkStatus(ResponseAPDU response, byte[] requestedAid) {
        CardStatusCodec.Parsed parsed = CardStatusCodec.parse(response, requestedAid);
        if (!parsed.ok()) {
            return CardOperationResult.fail(parsed.problem());
        }
        status = parsed.status();
        CardStatus current = status;
        return CardOperationResult.ok(String.format(
                "AID=%s protocol=%02X lifecycle=%s cardId=%s%s | kết luận: SẴN SÀNG",
                current.instanceAid(), current.protocolVersion(), CardLifecycle.text(current.lifecycle()),
                current.cardId(), current.studentStateAvailable()
                        ? String.format(" balance=%d counter=%d activeVisit=%s",
                                current.balance(), current.counter(), current.hasActiveVisit())
                        : ""));
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    CardOperationResult readLastReceipt(ReceiptExpectation expectation) throws CardException {
        return readLastReceipt(expectation, RECEIPT_LE);
    }

    CardOperationResult readCardPublicKey() throws CardException {
        transport.requireConnected();
        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_GET_CARD_PUBLIC_KEY, 0x00, 0x00, PUBLIC_KEY_LE));
        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        if (sw != Sw.OK) {
            cardPublicKey = null;
            cardPublicKeyBytes = null;
            return CardOperationResult.fail(String.format(
                    "GET_CARD_PUBLIC_KEY INS=61 P1P2=0000 Le=%d SW=%04X | %s | kết luận: CHƯA SẴN SÀNG",
                    PUBLIC_KEY_LE, sw, publicKeyStatusText(sw)));
        }
        ReceiptVerifier.ParsedKey parsed = ReceiptVerifier.parsePublicKey(data);
        if (!parsed.ok()) {
            cardPublicKey = null;
            cardPublicKeyBytes = null;
            return CardOperationResult.fail(String.format(
                    "GET_CARD_PUBLIC_KEY INS=61 SW=9000 dataLen=%d | %s | kết luận: CHƯA SẴN SÀNG",
                    data.length, parsed.problem()));
        }
        cardPublicKey = parsed.key();
        // Giữ nguyên 65 byte thẻ trả về: enrollment gửi Backend đúng chuỗi này, không dựng lại từ PublicKey.
        cardPublicKeyBytes = data.clone();
        String cardId = status == null ? "chưa nhận diện" : status.cardId();
        return CardOperationResult.ok(String.format(
                "GET_CARD_PUBLIC_KEY INS=61 SW=9000 dataLen=%d | cardId=%s | P-256 point=%s",
                data.length, cardId, hex(data)));
    }

    byte[] lastCardPublicKeyBytes() {
        byte[] value = cardPublicKeyBytes;
        return value == null ? null : value.clone();
    }

    byte[] lastEnrollmentSignature() {
        byte[] value = enrollmentSignature;
        return value == null ? null : value.clone();
    }

    /**
     * SIGN_REGISTRATION_CHALLENGE: Case 4S, Lc=32, data là nonce, Le=00.
     *
     * <p>Client chỉ gửi nonce; thẻ tự lấy cardId và public key của chính nó theo contract.
     */
    CardOperationResult signRegistrationChallenge(byte[] nonce) throws CardException {
        transport.requireConnected();
        enrollmentSignature = null;
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            return CardOperationResult.fail("SIGN_REGISTRATION_CHALLENGE: nonce phải đúng 32 byte | không gửi APDU");
        }
        CardStatus current = status;
        if (current == null || current.lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail("SIGN_REGISTRATION_CHALLENGE: thẻ chưa ISSUED | không gửi APDU");
        }

        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_SIGN_REGISTRATION_CHALLENGE, 0x00, 0x00, nonce, SIGNATURE_LE));
        int sw = response.getSW();
        byte[] signature = response.getData();
        lastResponseSw = sw;
        String apdu = String.format(
                "SIGN_REGISTRATION_CHALLENGE INS=62 P1P2=0000 case=4S Le=00 Lc=%d SW=%04X",
                nonce.length, sw);
        if (sw != Sw.OK) {
            return CardOperationResult.fail(apdu + " | " + signChallengeStatusText(sw)
                    + " | kết luận: CHƯA SẴN SÀNG");
        }
        if (signature.length < DER_SIGNATURE_MIN || signature.length > DER_SIGNATURE_MAX
                || signature[0] != 0x30) {
            return CardOperationResult.fail(String.format(
                    "%s sigLen=%d | không phải DER SEQUENCE hợp lệ | kết luận: CHƯA SẴN SÀNG",
                    apdu, signature.length));
        }
        enrollmentSignature = signature;
        return CardOperationResult.ok(String.format(
                "%s sigLen=%d | DER SEQUENCE | nonce 32 byte đã ký (không ghi nội dung)",
                apdu, signature.length));
    }

    private static String signChallengeStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa ISSUED — chỉ ký challenge sau PERSONALIZE";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc khác 32 hoặc Le khác 00 (Le tường minh 1..71 bị từ chối)";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_DATA) {
            return "nonce bị từ chối";
        }
        if (sw == Sw.INTERNAL_ERROR) {
            return "lỗi nội bộ khi ký";
        }
        return "không xác định được kết quả ký";
    }

    private static String publicKeyStatusText(int sw) {        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
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

    CardOperationResult readProfile() throws CardException {
        return profileCommands.readProfile();
    }

    CardOperationResult readProfile(int p1, int p2) throws CardException {
        return profileCommands.readProfile(p1, p2);
    }

    CardOperationResult readProfileCryptoMeta() throws CardException {
        return profileCommands.readProfileCryptoMeta();
    }

    CardOperationResult readProfileCryptoMeta(int p1, int p2) throws CardException {
        return profileCommands.readProfileCryptoMeta(p1, p2);
    }

    CardOperationResult readProfileWithPin(char[] pin) throws CardException {
        return profileCommands.readProfileWithPin(pin);
    }

    CardOperationResult readProfileCryptoMetaWithPin(char[] pin) throws CardException {
        return profileCommands.readProfileCryptoMetaWithPin(pin);
    }

    ResponseAPDU transmitPhotoBegin(byte[] data) throws CardException {
        return photoCommands.transmitPhotoBegin(data);
    }

    ResponseAPDU transmitPhotoWriteChunk(int offset, byte[] chunk) throws CardException {
        return photoCommands.transmitPhotoWriteChunk(offset, chunk);
    }

    ResponseAPDU transmitPhotoFinish() throws CardException {
        return photoCommands.transmitPhotoFinish();
    }

    CardOperationResult uploadPhoto(byte[] image, char[] adminPin) throws CardException {
        return photoCommands.uploadPhoto(image, adminPin);
    }

    ResponseAPDU transmitReadChunk(int offset, int le) throws CardException {
        return photoCommands.transmitReadChunk(offset, le);
    }

    int selectInstance(byte[] instanceAid) throws CardException {
        transport.requireConnected();
        ResponseAPDU response = transport.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, instanceAid, 256));
        lastResponseSw = response.getSW();
        return response.getSW();
    }

    ResponseAPDU transmitStatus() throws CardException {
        transport.requireConnected();
        ResponseAPDU response = transport.transmit(
                new CommandAPDU(CardProtocol.CLA_APPLICATION, INS_GET_STATUS, 0x00, 0x00, 256));
        lastResponseSw = response.getSW();
        return response;
    }

    void clearAuthenticatedStatus() {
        CardStatus current = status;
        if (current != null) {
            status = current.publicView();
        }
    }

    CardOperationResult readAuthenticatedStatus(char[] studentPin) throws CardException {
        transport.requireConnected();
        CardStatus current = status;
        if (current == null || !ready) {
            return CardOperationResult.fail("GET_STATUS state cần SELECT applet trước");
        }
        status = current.publicView();
        CardOperationResult verified = verifyStudentPin(studentPin);
        if (!verified.ok()) {
            return CardOperationResult.fail("GET_STATUS state không chạy — VERIFY: " + verified.text());
        }
        ResponseAPDU response = transmitStatus();
        CardOperationResult parsed = checkStatus(response, HexFormat.of().parseHex(current.instanceAid()));
        if (!parsed.ok()) {
            return parsed;
        }
        CardStatus refreshed = status;
        if (refreshed == null || !refreshed.studentStateAvailable()) {
            return CardOperationResult.fail("GET_STATUS không trả state sau VERIFY");
        }
        return CardOperationResult.ok(String.format(
                "VERIFY SW=9000 | GET_STATUS state SW=9000 balance=%d counter=%d activeVisit=%s",
                refreshed.balance(), refreshed.counter(), refreshed.hasActiveVisit()));
    }

    CardOperationResult readPhoto(char[] pin) throws CardException {
        return photoCommands.readPhoto(pin);
    }

    byte[] lastPhoto() {
        return photoCommands.lastPhoto();
    }

    Profile lastProfile() {
        return profileCommands.lastProfile();
    }

    ProfileCryptoMeta lastProfileMeta() {
        return profileCommands.lastProfileMeta();
    }

    int lastPinSw() {
        return pinCommands.lastPinSw();
    }

    java.security.PublicKey cardPublicKey() {
        return cardPublicKey;
    }

    CardOperationResult personalize(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) throws CardException {
        transport.requireConnected();

        String problem = PersonalizeRequest.problem(
                adminPin, cardIdHex, studentId, studentPin, displayName);
        if (problem != null) {
            return CardOperationResult.fail("PERSONALIZE: " + problem + " | không gửi APDU");
        }

        String expectedCardId = cardIdHex.strip().toUpperCase();
        byte[] data = PersonalizeRequest.payload(
                adminPin, cardIdHex, studentId, studentPin, displayName);
        int lc = data.length;
        ResponseAPDU response;
        try {
            response = transport.transmit(new CommandAPDU(
                    CardProtocol.CLA_APPLICATION, INS_PERSONALIZE, 0x00, 0x00, data, 256));
        } finally {
            Arrays.fill(data, (byte) 0);
        }

        int sw = response.getSW();
        lastResponseSw = sw;
        String apdu = String.format(
                "PERSONALIZE INS=30 P1P2=0000 case=4S Le=00 Lc=%d SW=%04X", lc, sw);
        if (sw != Sw.OK) {
            return CardOperationResult.fail(apdu + " | " + personalizeStatusText(sw)
                    + " | kết luận: CHƯA SẴN SÀNG");
        }

        CardOperationResult reidentified = identify();
        if (!reidentified.ok()) {
            return CardOperationResult.fail(apdu + " | " + reidentified.text());
        }
        CardStatus current = status;
        if (current == null || current.lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail(apdu + " | sau PERSONALIZE lifecycle không phải ISSUED"
                    + " | kết luận: CHƯA SẴN SÀNG");
        }
        if (!current.cardId().equalsIgnoreCase(expectedCardId)) {
            return CardOperationResult.fail(String.format("%s | cardId trên thẻ=%s khác yêu cầu=%s"
                    + " | kết luận: CHƯA SẴN SÀNG", apdu, current.cardId(), expectedCardId));
        }
        return CardOperationResult.ok(apdu + " | " + reidentified.text());
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

    CardOperationResult readLastReceipt(ReceiptExpectation expectation, int le) throws CardException {
        transport.requireConnected();

        ResponseAPDU response = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_GET_LAST_RECEIPT, 0x00, 0x00, le));
        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        String apdu = String.format("GET_LAST_RECEIPT INS=60 P1P2=0000 Le=%d SW=%04X dataLen=%d",
                le, sw, data.length);

        if (sw != Sw.OK) {
            lastReceipt = null;
            return CardOperationResult.fail(apdu + " | " + receiptStatusText(sw));
        }

        int signatureLength = data.length > ReceiptCodec.PAYLOAD_LENGTH
                ? (data[ReceiptCodec.PAYLOAD_LENGTH] & 0xFF)
                : -1;
        ReceiptCodec.Parse parse = ReceiptCodec.parse(data);
        if (!parse.ok()) {
            lastReceipt = null;
            reconciliation.markUnknown("receipt không đọc được: " + parse.problem());
            return CardOperationResult.fail(String.format("%s sigLen=%d parser=FAIL (%s) | đối soát=%s",
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
            return CardOperationResult.fail(String.format("%s | kết luận=MISMATCH (%s) state=%s",
                    detail, cardProblem, reconciliation.state()));
        }

        CardOperationResult signatureCheck = verifyReceiptSignature(receipt);
        if (!signatureCheck.ok()) {
            lastReceipt = null;
            reconciliation.markUnknown("chữ ký receipt không hợp lệ");
            return CardOperationResult.fail(String.format("%s | ECDSA=FAIL (%s) | kết luận=MISMATCH state=%s",
                    detail, signatureCheck.text(), reconciliation.state()));
        }
        detail = detail + " | ECDSA=PASS";

        if (expectation == null) {
            return CardOperationResult.ok(detail + " | chưa đối soát — thiếu kỳ vọng của giao dịch");
        }

        Reconciliation.Verdict verdict = reconciliation.resolve(receipt, expectation);
        if (verdict == Reconciliation.Verdict.MISMATCH) {
            reconciliation.markUnknown(reconciliation.detail());
        }
        String text = String.format("%s | expected %s | kết luận=%s state=%s",
                detail, expectationText(expectation), verdict, reconciliation.state());
        return verdict == Reconciliation.Verdict.MISMATCH
                ? CardOperationResult.fail(text)
                : CardOperationResult.ok(text);
    }

    private String cardIdProblem(Receipt receipt) {
        CardStatus current = status();
        if (current == null) {
            return "chưa nhận diện thẻ để đối chiếu cardId";
        }
        if (receipt.cardIdHex().equalsIgnoreCase(current.cardId())) {
            return null;
        }
        return String.format("cardId receipt=%s khác thẻ đang nhận diện=%s",
                receipt.cardIdHex(), current.cardId());
    }

    CardOperationResult prepareReceiptVerification() throws CardException {
        return ensureCardPublicKeyLoaded();
    }

    private CardOperationResult verifyReceiptSignature(Receipt receipt) throws CardException {
        CardOperationResult loaded = ensureCardPublicKeyLoaded();
        if (!loaded.ok()) {
            lastReceiptSignatureOk = false;
            signatureChecked = false;
            return CardOperationResult.fail(loaded.text());
        }
        ReceiptVerifier.Result verified = ReceiptVerifier.verifyReceipt(cardPublicKey, receipt);
        lastReceiptSignatureOk = verified.ok();
        signatureChecked = true;
        if (verified.ok()) {
            return CardOperationResult.ok("ECDSA P-256/SHA-256 hợp lệ");
        }
        return CardOperationResult.fail(verified.problem());
    }

    void markTransactionUnknown(String cause) {
        reconciliation.markUnknown(cause);
    }

    record EnrollmentCheck(boolean ok, boolean enrolled, String detail) {
    }

    void applyCardEnrollment(EnrollmentCheck check) {
        CardStatus current = status;
        enrollmentCardId = current == null ? null : current.cardId();
        cardEnrollment = check;
        enrollmentDetail = check == null ? "chưa kiểm tra" : check.detail();
    }

    void clearCardEnrollment(String detail) {
        enrollmentCardId = null;
        cardEnrollment = null;
        enrollmentDetail = detail;
    }

    String enrollmentBlockReason() {
        CardStatus current = status;
        if (!ready || current == null) {
            return "chưa nhận diện thẻ";
        }
        if (cardEnrollment == null || enrollmentCardId == null
                || !current.cardId().equalsIgnoreCase(enrollmentCardId)) {
            return "chưa kiểm tra đăng ký thẻ cho cardId=" + current.cardId()
                    + " (" + enrollmentDetail + ")";
        }
        if (!cardEnrollment.ok()) {
            return "chưa xác nhận được đăng ký thẻ trên Backend: " + cardEnrollment.detail();
        }
        if (!cardEnrollment.enrolled()) {
            return "thẻ chưa đăng ký trên Backend (cardId=" + current.cardId() + ")"
                    + " — đăng ký thẻ trước khi cấp lượt/mở ca/đóng ca";
        }
        return null;
    }

    String enrollmentText() {
        CardStatus current = status;
        if (current == null || cardEnrollment == null || enrollmentCardId == null
                || !current.cardId().equalsIgnoreCase(enrollmentCardId)) {
            return "chua-kiem-tra";
        }
        if (!cardEnrollment.ok()) {
            return "khong-xac-nhan-duoc";
        }
        return cardEnrollment.enrolled() ? "da-dang-ky" : "chua-dang-ky";
    }

    CardOperationResult verifyStudentPin(char[] pin) throws CardException {
        return pinCommands.verifyStudentPin(pin);
    }

    CardOperationResult changeStudentPin(char[] oldPin, char[] newPin) throws CardException {
        return pinCommands.changeStudentPin(oldPin, newPin);
    }

    CardOperationResult unblockStudentPin(char[] adminPin, char[] newPin) throws CardException {
        return pinCommands.unblockStudentPin(adminPin, newPin);
    }

    record TransactionPlan(ReceiptExpectation expectation, int delta, int expectedBalance) {
    }

    CardOperationResult runStateTransaction(PendingJournal journal, TransactionPlan plan,
            CommandSupplier command, char[] studentPin) throws CardException {
        transport.requireConnected();
        if (!ready || status() == null || status().lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail("giao dịch bị từ chối: phải SELECT + GET_STATUS thẻ ISSUED trước");
        }
        if (!reconciliation.transactionAllowed()) {
            return CardOperationResult.fail("giao dịch bị khóa: đối soát=" + reconciliation.state()
                    + " (" + reconciliation.detail() + ")");
        }
        if (journal.load().present()) {
            return CardOperationResult.fail("đã có pending journal — phải đối soát trước giao dịch mới");
        }
        if (receiptSink == null) {
            return CardOperationResult.fail(
                    "chưa cấu hình ledger Backend — không gửi APDU đổi state, không ghi journal");
        }
        String enrollmentBlock = enrollmentBlockReason();
        if (enrollmentBlock != null) {
            return CardOperationResult.fail("giao dịch bị chặn vì đăng ký thẻ: " + enrollmentBlock
                    + " | không ghi journal, không gửi APDU đổi state");
        }
        PendingJournal.Entry entry = new PendingJournal.Entry(
                gatewayCardId(), plan.expectation().counterAfter(),
                plan.expectation().operation(), plan.delta(), plan.expectedBalance(),
                plan.expectation().visitId(), System.currentTimeMillis());
        if (!journal.writePending(entry)) {
            return CardOperationResult.fail("ghi pending journal thất bại — không gửi APDU");
        }

        ResponseAPDU response;
        try {
            response = command.transmit();
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất kết nối khi chờ response: " + ex.getMessage());
            return CardOperationResult.fail("APDU không có response — journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }

        int sw = response.getSW();
        byte[] data = response.getData();
        lastResponseSw = sw;
        String apdu = String.format("APDU đổi state SW=%04X dataLen=%d", sw, data.length);

        if (sw == Sw.INTERNAL_ERROR) {
            reconciliation.markUnknown("Applet trả 6F00 — có thể đã commit, có thể chưa");
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (sw != Sw.OK) {
            reconciliation.markUnknown("APDU đổi state bị từ chối; cần xác nhận state trước khi xóa journal");
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (data.length != STATE_RESPONSE_LENGTH) {
            reconciliation.markUnknown("response " + data.length + " byte khác 6 — không đọc được state");
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }

        CardOperationResult receiptRead;
        try {
            receiptRead = verifyAndReadReceipt(plan.expectation(), studentPin);
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất response khi đọc receipt: " + ex.getMessage());
            return CardOperationResult.fail(apdu + " | journal vẫn PENDING — đối soát="
                    + reconciliation.state());
        }
        if (!receiptRead.ok()) {
            reconciliation.markUnknown("đọc receipt sau 9000 thất bại");
            return CardOperationResult.fail(apdu + " | " + receiptRead.text() + " | journal vẫn PENDING"
                    + " | đối soát=" + reconciliation.state());
        }
        if (lastReceipt == null
                || lastReceipt.counterAfter() != plan.expectation().counterAfter()) {
            reconciliation.markUnknown("APDU trả 9000 nhưng receipt không chứng minh counter giao dịch vừa gửi");
            return CardOperationResult.fail(apdu + " | receipt không khớp counter sau 9000"
                    + " | journal vẫn PENDING | đối soát=" + reconciliation.state());
        }
        String valueProblem = transactionValueProblem(lastReceipt, plan);
        if (valueProblem != null) {
            reconciliation.markUnknown(valueProblem);
            return CardOperationResult.fail(apdu + " | receipt không khớp expected delta/balance: "
                    + valueProblem + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        // UI-07: chưa lưu được vào ledger Backend thì journal ở lại PENDING, và journal PENDING
        // chính là thứ chặn giao dịch kế tiếp ở đầu runStateTransaction.
        ReceiptSave saved = saveReceiptToLedger(lastReceipt);
        if (!saved.ok()) {
            reconciliation.markUnknown("receipt đã xác minh trên thẻ nhưng chưa lưu được vào ledger");
            return CardOperationResult.fail(apdu + " | " + receiptRead.text() + " | lưu ledger=FAIL ("
                    + saved.detail() + ") | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        CardOperationResult journalResult = finishJournal(journal, entry);
        if (!journalResult.ok()) {
            return CardOperationResult.fail(apdu + " | " + receiptRead.text() + " | " + saved.detail()
                    + " | " + journalResult.text());
        }
        reconciliation.markKnown("receipt khớp, ledger đã lưu, journal đã đóng");
        return CardOperationResult.ok(apdu + " | " + receiptRead.text() + " | " + saved.detail()
                + " | " + journalResult.text());
    }

    private ReceiptSave saveReceiptToLedger(Receipt receipt) {
        if (receiptSink == null) {
            return new ReceiptSave(false, "chưa cấu hình ledger Backend");
        }
        return receiptSink.save(receipt);
    }

    /** Kết quả ghi ledger: thành công kèm mô tả, thất bại kèm lý do để journal ở lại PENDING. */
    record ReceiptSave(boolean ok, String detail) {
    }

    /** Cửa duy nhất để gateway ghi receipt sang ledger Backend; App cài đặt bằng BackendClient. */
    interface ReceiptSink {
        ReceiptSave save(Receipt receipt);

        LedgerCheck ledger(String cardIdHex);
    }

    /** Kết quả đọc mốc ledger Backend dùng cho đối soát nhánh chưa thực thi. */
    record LedgerCheck(boolean ok, String detail, long lastCounter, int lastBalance, String activeVisitId) {
    }

    private ReceiptSink receiptSink;

    void setReceiptSink(ReceiptSink sink) {
        this.receiptSink = sink;
    }

    CardOperationResult reconcilePending(PendingJournal journal, char[] studentPin) throws CardException {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            return CardOperationResult.fail("không có pending journal");
        }
        if (!loaded.valid() || loaded.status() != PendingJournal.Status.PENDING) {
            reconciliation.markUnknown("pending journal không hợp lệ hoặc đã RESOLVED");
            return CardOperationResult.fail("pending journal cần xử lý thủ công: " + loaded.problem());
        }
        PendingJournal.Entry entry = loaded.entry();
        if (!entry.cardId().equalsIgnoreCase(gatewayCardId())) {
            reconciliation.markUnknown("pending journal thuộc cardId khác thẻ đang nhận diện");
            return CardOperationResult.fail("cardId của journal khác cardId hiện tại — vẫn giữ journal");
        }

        CardOperationResult keyLoaded = ensureCardPublicKeyLoaded();
        if (!keyLoaded.ok()) {
            reconciliation.markUnknown("không nạp được public key để xác minh receipt");
            return CardOperationResult.fail(keyLoaded.text() + " | journal vẫn PENDING");
        }
        CardOperationResult verify = verifyStudentPin(studentPin);
        if (!verify.ok()) {
            return CardOperationResult.fail("VERIFY để đối soát thất bại: " + verify.text());
        }
        CardOperationResult receiptRead;
        try {
            receiptRead = readLastReceipt(null);
        } catch (CardException | RuntimeException ex) {
            reconciliation.markUnknown("mất response khi đối soát receipt: " + ex.getMessage());
            return CardOperationResult.fail("không đọc được receipt — journal vẫn PENDING");
        }
        if (!receiptRead.ok()) {
            if (lastResponseSw == Sw.CONDITIONS_NOT_SATISFIED && entry.expectedCounter() == 1) {
                CardOperationResult stateRead = confirmStateUnchanged(entry, studentPin);
                if (!stateRead.ok()) {
                    reconciliation.markUnknown("chưa có receipt nhưng không xác nhận được state chưa đổi");
                    return CardOperationResult.fail(stateRead.text() + " | journal vẫn PENDING | đối soát="
                            + reconciliation.state());
                }
                CardOperationResult closed = finishJournal(journal, entry,
                        Reconciliation.Verdict.NOT_EXECUTED, stateRead.text());
                if (closed.ok()) {
                    reconciliation.markKnown(
                            "chưa có receipt; state xác thực xác nhận chưa thực thi (ledger chưa đối chiếu)");
                }
                return closed;
            }
            reconciliation.markUnknown("không chứng minh được giao dịch đã hoặc chưa thực thi");
            return CardOperationResult.fail(receiptRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }

        ReceiptExpectation expectation = new ReceiptExpectation(
                HexFormat.of().parseHex(entry.cardId()), entry.expectedCounter(),
                entry.operation(), entry.visitId());
        Reconciliation.Verdict verdict = reconciliation.resolve(lastReceipt, expectation);
        if (verdict == Reconciliation.Verdict.MISMATCH) {
            reconciliation.markUnknown("receipt không khớp pending journal");
            return CardOperationResult.fail(receiptRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        if (verdict == Reconciliation.Verdict.EXECUTED) {
            String valueProblem = transactionValueProblem(lastReceipt, entry);
            if (valueProblem != null) {
                reconciliation.markUnknown(valueProblem);
                return CardOperationResult.fail("receipt cùng counter nhưng sai delta/balance: " + valueProblem
                        + " | journal vẫn PENDING");
            }
            // SIGN-08: ledger phải có receipt trước khi journal được đóng, nếu không thì đối soát
            // xong sẽ mở giao dịch mới trong khi Backend vẫn thiếu giao dịch này.
            ReceiptSave saved = saveReceiptToLedger(lastReceipt);
            if (!saved.ok()) {
                reconciliation.markUnknown("giao dịch đã thực thi trên thẻ nhưng chưa lưu được vào ledger");
                return CardOperationResult.fail(receiptRead.text() + " | lưu ledger=FAIL (" + saved.detail()
                        + ") | journal vẫn PENDING | đối soát=" + reconciliation.state());
            }
            String ledgerProblem = ledgerMatchesReceipt(entry.cardId(), lastReceipt);
            if (ledgerProblem != null) {
                reconciliation.markUnknown(ledgerProblem);
                return CardOperationResult.fail(receiptRead.text() + " | " + saved.detail() + " | " + ledgerProblem
                        + " | journal vẫn PENDING | đối soát=" + reconciliation.state());
            }
            CardOperationResult closed = finishJournal(journal, entry, verdict,
                    receiptRead.text() + " | " + saved.detail());
            if (closed.ok()) {
                reconciliation.markKnown("đã lưu ledger cho giao dịch đã thực thi");
            }
            return closed;
        }

        if (lastReceipt.balanceAfter() != entry.expectedBalance() - entry.delta()) {
            reconciliation.markUnknown("balance receipt trước giao dịch không khớp expected state cũ");
            return CardOperationResult.fail("receipt cũ không chứng minh state chưa đổi — journal vẫn PENDING");
        }
        CardOperationResult stateRead = confirmStateUnchanged(entry, studentPin);
        if (!stateRead.ok()) {
            reconciliation.markUnknown("receipt cho thấy chưa thực thi nhưng state chưa được xác nhận");
            return CardOperationResult.fail(stateRead.text() + " | journal vẫn PENDING | đối soát="
                    + reconciliation.state());
        }
        CardOperationResult closed = finishJournal(journal, entry, verdict,
                receiptRead.text() + " | " + stateRead.text());
        if (closed.ok()) {
            reconciliation.markKnown(
                    "state xác thực xác nhận giao dịch chưa thực thi (ledger chưa đối chiếu)");
        }
        return closed;
    }

    /**
     * Xác nhận bằng state xác thực của thẻ (VERIFY + GET_STATUS) rằng giao dịch trong journal chưa
     * đổi counter/balance. Chỉ khi đó mới được xóa pending, thay vì suy từ việc thiếu receipt.
     */
    private CardOperationResult confirmStateUnchanged(PendingJournal.Entry entry, char[] studentPin)
            throws CardException {
        CardOperationResult stateRead = readAuthenticatedStatus(studentPin);
        if (!stateRead.ok()) {
            return CardOperationResult.fail("không đọc được state xác thực: " + stateRead.text());
        }
        CardStatus current = status;
        if (current == null || !current.studentStateAvailable()) {
            return CardOperationResult.fail("state xác thực không khả dụng sau GET_STATUS");
        }
        long wantCounter = entry.expectedCounter() - 1;
        int wantBalance = entry.expectedBalance() - entry.delta();
        if (current.counter() != wantCounter || current.balance() != wantBalance) {
            return CardOperationResult.fail(String.format(
                    "state thẻ counter=%d balance=%d khác mốc trước giao dịch %d/%d",
                    current.counter(), current.balance(), wantCounter, wantBalance));
        }

        // Chưa đối chiếu được ledger thì không được đóng pending: thiếu nguồn, lỗi HTTP, sai schema
        // hoặc lệch mốc đều giữ journal lại.
        if (receiptSink == null) {
            return CardOperationResult.fail("chưa cấu hình nguồn ledger Backend để đối chiếu");
        }
        LedgerCheck ledger = receiptSink.ledger(entry.cardId());
        if (!ledger.ok()) {
            return CardOperationResult.fail("không đọc được ledger Backend: " + ledger.detail());
        }
        if (ledger.lastCounter() != current.counter() || ledger.lastBalance() != current.balance()) {
            return CardOperationResult.fail(String.format(
                    "ledger Backend counter=%d balance=%d khác state thẻ counter=%d balance=%d",
                    ledger.lastCounter(), ledger.lastBalance(), current.counter(), current.balance()));
        }
        String cardVisit = current.hasActiveVisit() ? current.activeVisitId() : null;
        String ledgerVisit = ledger.activeVisitId();
        boolean bothClosed = cardVisit == null && ledgerVisit == null;
        boolean sameOpenVisit = cardVisit != null && ledgerVisit != null
                && cardVisit.equalsIgnoreCase(ledgerVisit);
        if (!bothClosed && !sameOpenVisit) {
            return CardOperationResult.fail(String.format("ledger Backend ca mở=%s khác state thẻ ca mở=%s",
                    ledgerVisit == null ? "không" : ledgerVisit,
                    cardVisit == null ? "không" : cardVisit));
        }
        return CardOperationResult.ok(String.format("%s | ledger counter=%d balance=%d ca mở=%s"
                + " | state thẻ và ledger cùng mốc trước giao dịch",
                stateRead.text(), ledger.lastCounter(), ledger.lastBalance(),
                ledgerVisit == null ? "không" : ledgerVisit));
    }

    private String ledgerMatchesReceipt(String cardIdHex, Receipt receipt) {
        if (receiptSink == null) {
            return "chưa cấu hình nguồn ledger Backend để đối chiếu";
        }
        LedgerCheck ledger = receiptSink.ledger(cardIdHex);
        if (!ledger.ok()) {
            return "không đọc được ledger Backend sau khi lưu: " + ledger.detail();
        }
        if (ledger.lastCounter() != receipt.counterAfter()
                || ledger.lastBalance() != receipt.balanceAfter()) {
            return String.format(
                    "ledger Backend counter=%d balance=%d khác receipt counter=%d balance=%d",
                    ledger.lastCounter(), ledger.lastBalance(),
                    receipt.counterAfter(), receipt.balanceAfter());
        }
        return null;
    }

    private CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry) {
        return finishJournal(journal, entry, null, null);
    }

    private CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry,
            Reconciliation.Verdict verdict, String evidence) {
        if (!journal.writeResolved(entry)) {
            reconciliation.markUnknown("ghi trạng thái RESOLVED thất bại; giữ journal");
            return CardOperationResult.fail("ghi RESOLVED thất bại — journal vẫn được giữ lại");
        }
        try {
            journal.remove();
        } catch (RuntimeException ex) {
            reconciliation.markUnknown("xóa journal RESOLVED thất bại; giữ file để xử lý thủ công");
            return CardOperationResult.fail("journal đã RESOLVED nhưng chưa xóa được — cần xử lý thủ công");
        }
        if (verdict == null) {
            return CardOperationResult.ok("journal đã RESOLVED và bị xóa");
        }
        // Không in trạng thái đối soát ở đây: bên gọi chỉ chuyển KNOWN sau khi hàm này trả về, nên
        // in tại chỗ sẽ luôn là giá trị cũ và làm bằng chứng log đọc sai.
        return CardOperationResult.ok(String.format(
                "%s | kết luận=%s | journal đã RESOLVED và bị xóa",
                evidence, verdict));
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

    CardOperationResult verifyAndReadReceipt(ReceiptExpectation expectation, char[] studentPin)
            throws CardException {
        CardOperationResult keyLoaded = ensureCardPublicKeyLoaded();
        if (!keyLoaded.ok()) {
            return CardOperationResult.fail("không nạp được public key của thẻ: " + keyLoaded.text());
        }
        if (studentPin != null) {
            CardOperationResult verify = verifyStudentPin(studentPin);
            if (!verify.ok()) {
                return CardOperationResult.fail("VERIFY lần hai sau giao dịch thất bại: " + verify.text());
            }
        }
        return readLastReceipt(expectation);
    }

    private CardOperationResult ensureCardPublicKeyLoaded() throws CardException {
        if (cardPublicKey != null) {
            return CardOperationResult.ok("public key đã nạp");
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
                return transport.transmit(new CommandAPDU(
                        CardProtocol.CLA_APPLICATION, INS_GRANT_QUOTA, 0x00, 0x00, data, STATE_RESPONSE_LENGTH));
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        };
    }

    CommandSupplier visitCommand(byte ins, byte[] visitId) {
        return () -> transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, ins, 0x00, 0x00, visitId, STATE_RESPONSE_LENGTH));
    }

    private String gatewayCardId() {
        CardStatus status = status();
        return status == null ? "0000000000000000" : status.cardId();
    }

    Reconciliation reconciliation() {
        return reconciliation;
    }

    Receipt lastReceipt() {
        return lastReceipt;
    }

    /** Kết quả xác minh chữ ký của biên nhận đọc gần nhất; chỉ có nghĩa khi đã kiểm tra. */
    boolean lastReceiptSignatureOk() {
        return lastReceiptSignatureOk;
    }

    boolean lastReceiptSignatureChecked() {
        return signatureChecked;
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

    CardOperationResult disconnect() throws CardException {
        resetSession("đã ngắt kết nối");
        boolean wasConnected = transport.disconnect();
        return wasConnected ? CardOperationResult.ok("Đã đóng kết nối") : CardOperationResult.ok("Vốn đã ngắt kết nối");
    }

    void setDiagnostics(java.util.function.Consumer<String> sink) {
        transport.setDiagnostics(sink);
    }

    boolean isConnected() {
        return transport.isConnected();
    }

    boolean isReady() {
        return ready;
    }

    CardStatus status() {
        return status;
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
