package vn.labaccess.desktop.card;

import java.util.Arrays;
import java.util.HexFormat;

import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.virtualreader.protocol.VirtualReaderSession;

import vn.labaccess.desktop.transport.CardTransport;

public final class CardGateway {

    private static final byte[] INSTANCE_AID = HexFormat.of().parseHex("F04C4142010101");

    private static final byte INS_GET_STATUS = (byte) 0x10;
    private static final byte INS_GRANT_QUOTA = (byte) 0x50;
    private static final byte INS_START_VISIT = (byte) 0x51;
    private static final byte INS_END_VISIT = (byte) 0x52;
    static final int STATE_RESPONSE_LENGTH = 6;
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
    private CardAdminCommands.ReceiptRead adminReceiptRead;
    private boolean lastReceiptSignatureOk;
    private boolean signatureChecked;
    private int lastResponseSw = -1;
    private final CardPinCommands pinCommands;
    private final CardEnrollmentCommands enrollmentCommands;
    private final CardPersonalizationCommands personalizationCommands;
    private final CardPhotoCommands photoCommands;
    private final CardProfileCommands profileCommands;
    private final CardAdminCommands adminCommands;
    private final CardProfileUpdateCommands profileUpdateCommands;
    private final CardAccess cardAccess;
    private final StateTransactionFlow stateFlow;
    private final ReconciliationFlow reconciliationFlow;

    public CardGateway(String host, int port) {
        this(new CardTransport(host, port), false);
    }

    public CardGateway(CardChannel channel) {
        this(new CardTransport(channel), channel != null);
    }

    private CardGateway(CardTransport transport, boolean initiallyReady) {
        this.transport = transport;
        this.ready = initiallyReady;
        this.pinCommands = new CardPinCommands(transport, () -> isReady() ? status : null,
                sw -> lastResponseSw = sw);
        this.photoCommands = new CardPhotoCommands(transport, this::verifyStudentPin,
                sw -> lastResponseSw = sw);
        this.profileCommands = new CardProfileCommands(transport, pinCommands,
                sw -> lastResponseSw = sw);
        this.adminCommands = new CardAdminCommands(transport, sw -> lastResponseSw = sw);
        this.enrollmentCommands = new CardEnrollmentCommands(transport, () -> status, sw -> lastResponseSw = sw);
        this.personalizationCommands = new CardPersonalizationCommands(
                transport, sw -> lastResponseSw = sw, this::identify, () -> status);
        this.profileUpdateCommands = new CardProfileUpdateCommands(transport, sw -> lastResponseSw = sw);
        this.cardAccess = new CardAccess();
        this.stateFlow = newStateFlow();
        this.reconciliationFlow = newReconciliationFlow();
    }

    private StateTransactionFlow newStateFlow() {
        return new StateTransactionFlow(cardAccess, reconciliation, () -> receiptSink,
                sw -> lastResponseSw = sw);
    }

    private ReconciliationFlow newReconciliationFlow() {
        return new ReconciliationFlow(cardAccess, reconciliation, () -> receiptSink);
    }

    private final class CardAccess implements StateTransactionFlow.CardAccess, ReconciliationFlow.CardAccess {

        @Override
        public void requireConnected() throws CardException {
            transport.requireConnected();
        }

        @Override
        public boolean selected() {
            return ready;
        }

        @Override
        public CardStatus status() {
            return CardGateway.this.status;
        }

        @Override
        public String cardId() {
            return gatewayCardId();
        }

        @Override
        public String enrollmentBlockReason() {
            return CardGateway.this.enrollmentBlockReason();
        }

        @Override
        public Receipt lastReceipt() {
            return CardGateway.this.lastReceipt;
        }

        @Override
        public void storeReceipt(Receipt receipt) {
            CardGateway.this.lastReceipt = receipt;
        }

        @Override
        public void recordSignature(boolean ok, boolean checked) {
            lastReceiptSignatureOk = ok;
            signatureChecked = checked;
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            return transport.transmit(command);
        }

        @Override
        public void recordSw(int sw) {
            lastResponseSw = sw;
        }

        @Override
        public int lastResponseSw() {
            return lastResponseSw;
        }

        @Override
        public java.security.PublicKey cardPublicKey() {
            return CardGateway.this.cardPublicKey();
        }

        @Override
        public CardOperationResult ensureCardPublicKeyLoaded() throws CardException {
            return CardGateway.this.ensureCardPublicKeyLoaded();
        }

        @Override
        public CardOperationResult readCardPublicKey() throws CardException {
            return CardGateway.this.readCardPublicKey();
        }

        @Override
        public CardOperationResult verifyStudentPin(char[] pin) throws CardException {
            return CardGateway.this.verifyStudentPin(pin);
        }

        @Override
        public CardOperationResult readAuthenticatedStatus(char[] pin) throws CardException {
            return CardGateway.this.readAuthenticatedStatus(pin);
        }

        @Override
        public CardOperationResult readLastReceipt(ReceiptExpectation expectation) throws CardException {
            return CardGateway.this.readLastReceipt(expectation);
        }

        @Override
        public void receiptVerificationFailed(ReceiptExpectation expectation, String problem) {
            CardGateway.this.receiptVerificationFailed(expectation, problem);
        }

        @Override
        public CardGateway.ReceiptSave saveReceiptToLedger(Receipt receipt) {
            return CardGateway.this.saveReceiptToLedger(receipt);
        }

        @Override
        public CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry,
                Reconciliation.Verdict verdict, String evidence) {
            return CardGateway.this.finishJournal(journal, entry, verdict, evidence);
        }

        @Override
        public CardOperationResult beforeJournal(TransactionPlan plan, char[] studentPin)
                throws CardException {
            return StudentLedgerGate.beforeJournal(CardGateway.this, receiptSink, plan, studentPin);
        }
    }

    public CardOperationResult connect() throws CardException, java.security.NoSuchAlgorithmException,
            java.security.NoSuchProviderException {

        if (transport.isOpen()) {
            return CardOperationResult.fail("Đã kết nối rồi, phải Disconnect trước");
        }

        CardTransport.Opened opened;
        try {
            opened = transport.connect();
        } catch (VirtualReaderSession.Unavailable unavailable) {
            return CardOperationResult.fail(unavailable.getMessage());
        }
        resetSession("vừa kết nối, chưa kiểm tra đăng ký thẻ");
        return CardOperationResult.ok("terminal=" + opened.terminalName() + " | ATR="
                + (opened.atr() == null ? "<không có ATR>" : hex(opened.atr())));
    }

    private void resetSession(String enrollmentDetail) {
        adminCommands.clearVerifiedReceipt();
        ready = false;
        status = null;
        lastReceipt = null;
        adminReceiptRead = null;
        lastReceiptSignatureOk = false;
        signatureChecked = false;
        enrollmentCommands.reset();
        clearCardIdentity();
        pinCommands.reset();
        clearCardEnrollment(enrollmentDetail);
    }

    public CardOperationResult identify() throws CardException {
        return identifyWith(INSTANCE_AID);
    }

    public CardOperationResult identifyWith(byte[] instanceAid) throws CardException {
        transport.requireConnected();
        adminCommands.clearVerifiedReceipt();
        adminReceiptRead = null;
        ready = false;
        status = null;
        enrollmentCommands.clearVerificationKey();
        clearCardIdentity();
        pinCommands.reset();
        clearCardEnrollment("vừa nhận diện lại thẻ, chưa kiểm tra đăng ký thẻ");

        int selectSw = sendSelect(instanceAid);
        if (selectSw != Sw.OK) {
            return CardOperationResult.fail(String.format(
                    "SELECT AID=%s SW=%04X | kết luận: CHƯA SẴN SÀNG",
                    hex(instanceAid), selectSw));
        }
        String selectPrefix = String.format("SELECT AID=%s SW=9000", hex(instanceAid));

        ResponseAPDU response = sendStatus();
        CardOperationResult checked = checkStatus(response, instanceAid, CardStatus.StateSource.PUBLIC);
        if (!checked.ok()) {
            return CardOperationResult.fail(selectPrefix + " | " + checked.text());
        }
        ready = true;
        return CardOperationResult.ok(String.format(
                "%s | GET_STATUS SW=9000 | %s",
                selectPrefix, checked.text()));
    }

    public CardOperationResult checkStatus(ResponseAPDU response, byte[] requestedAid,
            CardStatus.StateSource readSource) {
        clearAuthenticatedStatus();
        CardStatusCodec.Parsed parsed = CardStatusCodec.parse(response, requestedAid, readSource);
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

    public CardOperationResult readLastReceipt(ReceiptExpectation expectation) throws CardException {
        return reconciliationFlow.readLastReceipt(expectation);
    }

    public CardOperationResult readCardPublicKey() throws CardException {
        return enrollmentCommands.readCardPublicKey();
    }

    public byte[] lastCardPublicKeyBytes() {
        return enrollmentCommands.lastCardPublicKeyBytes();
    }

    public byte[] lastEnrollmentSignature() {
        return enrollmentCommands.lastEnrollmentSignature();
    }

    public CardOperationResult signRegistrationChallenge(byte[] nonce) throws CardException {
        return enrollmentCommands.signRegistrationChallenge(nonce);
    }

    CardOperationResult readProfile() throws CardException {
        return profileCommands.readProfile();
    }

    public CardOperationResult readProfile(int p1, int p2) throws CardException {
        return profileCommands.readProfile(p1, p2);
    }

    CardOperationResult readProfileCryptoMeta() throws CardException {
        return profileCommands.readProfileCryptoMeta();
    }

    public CardOperationResult readProfileCryptoMeta(int p1, int p2) throws CardException {
        return profileCommands.readProfileCryptoMeta(p1, p2);
    }

    public CardOperationResult readProfileWithPin(char[] pin) throws CardException {
        return profileCommands.readProfileWithPin(pin);
    }

    public CardOperationResult readProfileCryptoMetaWithPin(char[] pin) throws CardException {
        return profileCommands.readProfileCryptoMetaWithPin(pin);
    }

    public ResponseAPDU transmitPhotoBegin(byte[] data) throws CardException {
        return photoCommands.transmitPhotoBegin(data);
    }

    public ResponseAPDU transmitPhotoWriteChunk(int offset, byte[] chunk) throws CardException {
        return photoCommands.transmitPhotoWriteChunk(offset, chunk);
    }

    public ResponseAPDU transmitPhotoFinish() throws CardException {
        return photoCommands.transmitPhotoFinish();
    }

    public CardOperationResult uploadPhoto(byte[] image, char[] adminPin) throws CardException {
        return photoCommands.uploadPhoto(image, adminPin);
    }

    public CardOperationResult replacePhoto(byte[] image, char[] adminPin) throws CardException {
        return photoCommands.replacePhoto(image, adminPin);
    }

    public CardProfileUpdateCommands.IdentityWrite updateIdentity(ProfileEditRequest request, char[] adminPin,
            byte[] expectedVersion) throws CardException {
        return profileUpdateCommands.updateIdentity(request, adminPin, expectedVersion);
    }

    public CardOperationResult signProfileChallenge(char[] adminPin, String nonceHex) throws CardException {
        return profileUpdateCommands.signProfileChallenge(adminPin, nonceHex);
    }

    public String lastProfileUpdateSignatureHex() {
        return profileUpdateCommands.lastSignatureHex();
    }

    public ResponseAPDU transmitReadChunk(int offset, int le) throws CardException {
        return photoCommands.transmitReadChunk(offset, le);
    }

    public int selectInstance(byte[] instanceAid) throws CardException {
        transport.requireConnected();
        int sw = sendSelect(instanceAid);
        lastResponseSw = sw;
        return sw;
    }

    public ResponseAPDU transmitStatus() throws CardException {
        transport.requireConnected();
        ResponseAPDU response = sendStatus();
        lastResponseSw = response.getSW();
        return response;
    }

    private int sendSelect(byte[] instanceAid) throws CardException {
        return transport.transmit(new CommandAPDU(0x00, 0xA4, 0x04, 0x00, instanceAid, 256)).getSW();
    }

    private ResponseAPDU sendStatus() throws CardException {
        return transport.transmit(
                new CommandAPDU(CardProtocol.CLA_APPLICATION, INS_GET_STATUS, 0x00, 0x00, 256));
    }

    public CardOperationResult readAdminStatus(char[] adminPin) throws CardException {
        CardStatus selected = isReady() ? status : null;
        clearAuthenticatedStatus();
        clearCardIdentity();
        adminReceiptRead = null;
        CardAdminCommands.StatusRead read = adminCommands.readStatus(adminPin, selected);
        if (read.result().ok()) {
            status = read.status();
        } else if (lastResponseSw == Sw.OK) {
            resetSession("response Admin không khớp nhận diện; cần SELECT + GET_STATUS lại");
        }
        return read.result();
    }

    public CardOperationResult readAdminLastReceipt(char[] adminPin, String enrolledCardId,
            byte[] enrolledPublicKey) throws CardException {
        CardStatus selected = isReady() ? status : null;
        if (selected != null && selected.studentStateAvailable()) {
            clearAuthenticatedStatus();
        }
        clearCardIdentity();
        lastReceipt = null;
        lastReceiptSignatureOk = false;
        signatureChecked = false;
        adminReceiptRead = null;
        boolean verified = false;
        try {
            CardAdminCommands.ReceiptRead read = adminCommands.readLastReceipt(
                    adminPin, selected, enrolledCardId, enrolledPublicKey);
            adminReceiptRead = read;
            verified = read.result().ok() && read.signatureChecked();
            if (!read.result().ok() && lastResponseSw == Sw.OK) {
                receiptVerificationFailed(null, read.result().text());
            }
            return read.result();
        } finally {
            if (!verified) {
                clearAuthenticatedStatus();
            }
        }
    }

    public Receipt verifiedAdminReceipt() {
        return adminCommands.verifiedReceipt(isReady() ? status : null);
    }

    public void clearAuthenticatedStatus() {
        CardStatus current = status;
        if (current != null) {
            status = current.publicView();
        }
    }

    public CardOperationResult readAuthenticatedStatus(char[] studentPin) throws CardException {
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
        CardOperationResult parsed = checkStatus(response, HexFormat.of().parseHex(current.instanceAid()),
                CardStatus.StateSource.STUDENT);
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

    public CardOperationResult readPhoto(char[] pin) throws CardException {
        return photoCommands.readPhoto(pin);
    }

    public boolean photoAbsent() {
        return photoCommands.photoAbsent();
    }

    public void clearCardIdentity() {
        profileCommands.reset();
        photoCommands.reset();
        profileUpdateCommands.reset();
    }

    CardOperationResult confirmReceiptRead() {
        if (reconciliation.state() == Reconciliation.State.UNKNOWN) {
            return CardOperationResult.fail(
                    "đang có giao dịch chưa rõ kết quả — đối soát pending trước, không tự mở khóa");
        }
        if (!reconciliation.receiptUnverified()) {
            return CardOperationResult.ok("Không có lỗi xác minh biên nhận cần phục hồi.");
        }
        String problem = ReceiptReadRecovery.problem(status, lastReceipt,
                lastReceiptSignatureOk, receiptSink);
        if (problem != null) {
            return CardOperationResult.fail("Chưa xác minh lại được thẻ: " + problem);
        }
        reconciliation.markKnown("biên nhận, state xác thực và ledger đã khớp");
        return CardOperationResult.ok("Đã xác minh lại biên nhận và trạng thái thẻ với sổ giao dịch.");
    }

    public CardOperationResult readOperatorCard(char[] pin) throws CardException {
        return new CardOverviewReader(this).read(pin);
    }

    public byte[] lastPhoto() {
        return photoCommands.lastPhoto();
    }

    public Profile lastProfile() {
        return profileCommands.lastProfile();
    }

    public ProfileCryptoMeta lastProfileMeta() {
        return profileCommands.lastProfileMeta();
    }

    public int lastPinSw() {
        return pinCommands.lastPinSw();
    }
    public void resetPinResult() { pinCommands.reset(); }

    public java.security.PublicKey cardPublicKey() {
        return enrollmentCommands.cardPublicKey();
    }

    public CardOperationResult personalize(char[] adminPin, String cardIdHex, String studentId,
            char[] studentPin, String displayName) throws CardException {
        return personalizationCommands.personalize(adminPin, cardIdHex, studentId, studentPin, displayName);
    }

    public CardOperationResult readLastReceipt(ReceiptExpectation expectation, int le) throws CardException {
        return reconciliationFlow.readLastReceipt(expectation, le);
    }

    private void receiptVerificationFailed(ReceiptExpectation expectation, String problem) {
        stateFlow.receiptVerificationFailed(expectation, problem);
    }

    public CardOperationResult prepareReceiptVerification() throws CardException {
        return ensureCardPublicKeyLoaded();
    }

    public void markTransactionUnknown(String cause) {
        stateFlow.markTransactionUnknown(cause);
    }

    public record EnrollmentCheck(boolean ok, boolean enrolled, String detail) {
    }

    public void applyCardEnrollment(EnrollmentCheck check) {
        CardStatus current = status;
        enrollmentCardId = current == null ? null : current.cardId();
        cardEnrollment = check;
        enrollmentDetail = check == null ? "chưa kiểm tra" : check.detail();
    }

    public void clearCardEnrollment(String detail) {
        enrollmentCardId = null;
        cardEnrollment = null;
        enrollmentDetail = detail;
    }

    public String enrollmentBlockReason() {
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

    public String enrollmentText() {
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

    public CardOperationResult verifyStudentPin(char[] pin) throws CardException {
        return pinCommands.verifyStudentPin(pin);
    }

    public CardOperationResult changeStudentPin(char[] oldPin, char[] newPin) throws CardException {
        return pinCommands.changeStudentPin(oldPin, newPin);
    }

    public CardOperationResult unblockStudentPin(char[] adminPin, char[] newPin) throws CardException {
        return pinCommands.unblockStudentPin(adminPin, newPin);
    }

    public record TransactionPlan(ReceiptExpectation expectation, int delta, int expectedBalance) {
    }

    public CardOperationResult runStateTransaction(PendingJournal journal, TransactionPlan plan,
            CommandSupplier command, char[] studentPin) throws CardException {
        return stateFlow.runStateTransaction(journal, plan, command, studentPin);
    }

    private ReceiptSave saveReceiptToLedger(Receipt receipt) {
        return stateFlow.saveReceiptToLedger(receipt);
    }

    public record ReceiptSave(boolean ok, String detail) {
    }

    public interface ReceiptSink {
        ReceiptSave save(Receipt receipt);

        LedgerCheck ledger(String cardIdHex);
    }

    public record LedgerCheck(boolean ok, String detail, String cardId, long lastCounter, int lastBalance,
            String activeVisitId) {
    }

    private ReceiptSink receiptSink;

    public void setReceiptSink(ReceiptSink sink) {
        this.receiptSink = sink;
    }

    public CardOperationResult reconcilePending(PendingJournal journal, char[] studentPin) throws CardException {
        return reconciliationFlow.reconcilePending(journal, studentPin);
    }

    public CardOperationResult reconcilePending(PendingJournal journal, char[] studentPin,
            Reconciliation.SupersededEvidence superseded) throws CardException {
        return reconciliationFlow.reconcilePending(journal, studentPin, superseded);
    }

    private CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry) {
        return stateFlow.finishJournal(journal, entry);
    }

    public CardOperationResult finishJournal(PendingJournal journal, PendingJournal.Entry entry,
            Reconciliation.Verdict verdict, String evidence) {
        return stateFlow.finishJournal(journal, entry, verdict, evidence);
    }

    public CardOperationResult verifyAndReadReceipt(ReceiptExpectation expectation, char[] studentPin)
            throws CardException {
        return stateFlow.verifyAndReadReceipt(expectation, studentPin);
    }

    private CardOperationResult ensureCardPublicKeyLoaded() throws CardException {
        return stateFlow.ensureCardPublicKeyLoaded();
    }

    public interface CommandSupplier {
        ResponseAPDU transmit() throws CardException;
    }

    public CommandSupplier grantCommand(byte[] adminPin, int delta) {
        return () -> {
            byte[] data = new byte[GRANT_DATA_LENGTH];
            try {
                if (adminPin == null || adminPin.length != 8) {
                    throw new IllegalArgumentException("admin PIN phải đúng 8 byte");
                }
                System.arraycopy(adminPin, 0, data, 0, 8);
                data[8] = (byte) (delta >> 8);
                data[9] = (byte) delta;
                ResponseAPDU response = transport.transmit(new CommandAPDU(
                        CardProtocol.CLA_APPLICATION, INS_GRANT_QUOTA, 0x00, 0x00, data, STATE_RESPONSE_LENGTH));
                lastResponseSw = response.getSW();
                return response;
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        };
    }

    public CommandSupplier visitCommand(byte ins, byte[] visitId) {
        return () -> transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, ins, 0x00, 0x00, visitId, STATE_RESPONSE_LENGTH));
    }

    private String gatewayCardId() {
        CardStatus status = status();
        return status == null ? "0000000000000000" : status.cardId();
    }

    public Reconciliation reconciliation() {
        return reconciliation;
    }

    public Receipt lastReceipt() {
        return lastReceipt;
    }

    public CardAdminCommands.ReceiptRead lastAdminReceiptRead() {
        return adminReceiptRead;
    }

    public boolean lastReceiptSignatureOk() {
        return lastReceiptSignatureOk;
    }

    public boolean lastReceiptSignatureChecked() {
        return signatureChecked;
    }

    public int lastResponseSw() {
        return lastResponseSw;
    }

    public CardOperationResult disconnect() throws CardException {
        resetSession("đã ngắt kết nối");
        boolean wasConnected = transport.disconnect();
        return wasConnected ? CardOperationResult.ok("Đã đóng kết nối") : CardOperationResult.ok("Vốn đã ngắt kết nối");
    }

    public void setDiagnostics(java.util.function.Consumer<String> sink) {
        transport.setDiagnostics(sink);
    }

    public void traceCommands(java.util.function.IntConsumer sink) {
        transport.traceCommands(sink);
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    public boolean isReady() {
        return ready && transport.isConnected();
    }

    public boolean needsReconnect() {
        return transport.needsReconnect();
    }

    public CardStatus status() {
        return status;
    }

    public static String hex(byte[] bytes) {
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
