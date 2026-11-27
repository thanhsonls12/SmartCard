package vn.labaccess.desktop.ui.profile;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import javax.smartcardio.CardException;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardProfileUpdateCommands;
import vn.labaccess.desktop.card.PhotoImage;
import vn.labaccess.desktop.card.Profile;
import vn.labaccess.desktop.card.ProfileCodec;
import vn.labaccess.desktop.card.ProfileCryptoMeta;
import vn.labaccess.desktop.card.ProfileEditRequest;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.validation.StudentMajor;

public final class ProfileUpdateFlow {

    public enum State {
        DONE, REJECTED, STALE, CLASS_REQUIRED, UNVERIFIED
    }

    public record Result(State state, String message, String detail) {

        static Result done(String message, String detail) {
            return new Result(State.DONE, message, detail);
        }

        static Result rejected(String message, String detail) {
            return new Result(State.REJECTED, message, detail);
        }

        public static Result stale(String message, String detail) {
            return new Result(State.STALE, message, detail);
        }

        public static Result unverified(String detail) {
            return new Result(State.UNVERIFIED, UiLabels.PROFILE_VERIFY_PENDING, detail);
        }
    }

    private record Pending(String cardId, String operationId, ProfileEditRequest request, String expectedVersionHex) {
    }

    private record CardSnapshot(Profile profile, ProfileCryptoMeta meta) {
    }

    private record Step<T>(T value, Result failure) {

        static <T> Step<T> ok(T value) {
            return new Step<>(value, null);
        }

        static <T> Step<T> failed(Result failure) {
            return new Step<>(null, failure);
        }
    }

    private final CardGateway gateway;
    private final BackendClient backend;

    private volatile Pending pending;
    private volatile Profile lastReadProfile;
    private volatile ProfileEditRequest lastCompletedRequest;

    public ProfileUpdateFlow(CardGateway gateway, BackendClient backend) {
        this.gateway = gateway;
        this.backend = backend;
    }

    public Profile lastReadProfile() {
        return lastReadProfile;
    }

    public ProfileEditRequest lastCompletedRequest() {
        return lastCompletedRequest;
    }

    public boolean hasPending() {
        return pending != null;
    }

    public String pendingCardId() {
        Pending current = pending;
        return current == null ? "" : current.cardId();
    }

    public ProfileEditRequest pendingRequest() {
        Pending current = pending;
        return current == null ? null : current.request();
    }

    void clearPending() {
        pending = null;
    }

    void clearReadProfile() {
        lastReadProfile = null;
        lastCompletedRequest = null;
    }

    Result save(String cardId, ProfileEditRequest request, char[] adminPin, char[] studentPin)
            throws CardException {
        Step<CardSnapshot> snapshot = readCard(studentPin);
        if (snapshot.failure() != null) {
            return snapshot.failure();
        }
        Step<String> session = openSession(cardId, snapshot.value(), adminPin);
        if (session.failure() != null) {
            return session.failure();
        }
        String expectedVersion = snapshot.value().meta().nonceHex();

        BackendClient.ProfileUpdate operation;
        try {
            operation = backend.createProfileUpdate(session.value(), cardId, request, expectedVersion);
        } catch (BackendClient.Failure failure) {
            return createFailed(cardId, request, failure);
        }
        pending = new Pending(cardId, operation.operationId(), request, expectedVersion);

        Profile onCard = snapshot.value().profile();
        boolean identityChanged = !matches(onCard, request);
        return writeAndComplete(session.value(), cardId, request, expectedVersion, operation.nonceHex(),
                adminPin, identityChanged);
    }

    Result replacePhoto(byte[] image, char[] adminPin, char[] studentPin) throws CardException {
        clearReadProfile();
        CardOperationResult written = gateway.replacePhoto(image, adminPin);
        if (!written.ok()) {
            return Result.rejected("Chưa thay được ảnh trên thẻ.", written.text());
        }
        CardOperationResult read = gateway.readPhoto(studentPin);
        byte[] readBack = gateway.lastPhoto();
        if (!read.ok() || readBack == null) {
            return Result.unverified("Thẻ đã nhận ảnh mới nhưng chưa đọc lại được để đối chiếu: " + read.text());
        }
        String mismatch = PhotoImage.firstMismatch(image, readBack);
        if (mismatch != null) {
            return Result.unverified("Ảnh đọc lại không khớp ảnh vừa gửi: " + mismatch);
        }
        return Result.done("Đã thay ảnh trên thẻ.", written.text() + " | đọc lại " + readBack.length
                + " byte khớp từng byte | sha256=" + PhotoImage.fingerprint(readBack));
    }

    public Result reopen(String cardId, char[] adminPin, char[] studentPin) throws CardException {
        Step<CardSnapshot> snapshot = readCard(studentPin);
        if (snapshot.failure() != null) {
            return snapshot.failure();
        }
        Step<String> session = openSession(cardId, snapshot.value(), adminPin);
        if (session.failure() != null) {
            return session.failure();
        }

        Pending held = pending;
        BackendClient.FoundProfileUpdate found;
        try {
            found = held != null && held.operationId() != null
                    ? backend.findProfileUpdateById(session.value(), held.operationId())
                    : backend.findProfileUpdateByCard(session.value(), cardId);
        } catch (BackendClient.Failure failure) {
            return Result.rejected("Chưa đọc được yêu cầu sửa hồ sơ trên Backend nên chưa gửi lệnh thẻ.",
                    failure.getMessage());
        }
        if (!found.cardId().equalsIgnoreCase(cardId)) {
            return Result.rejected("Yêu cầu trên Backend thuộc thẻ khác thẻ đang chọn nên không tự gửi lại.",
                    "Backend=" + found.cardId() + " thẻ=" + cardId);
        }
        if (found instanceof BackendClient.FoundCompleted completed) {
            pending = null;
            return Result.done("Yêu cầu sửa hồ sơ đã hoàn tất trước đó.", "Backend xác nhận yêu cầu "
                    + completed.operationId() + " hoàn tất lúc " + completed.completedAt()
                    + " | mã " + completed.studentId() + " | lớp " + completed.classCode()
                    + " | ngành " + completed.major());
        }

        BackendClient.FoundPending open = (BackendClient.FoundPending) found;
        if (held != null && held.operationId() != null
                && !held.operationId().equalsIgnoreCase(open.operationId())) {
            return Result.rejected("Yêu cầu chờ trên Backend khác yêu cầu đang giữ trong phiên này"
                    + " nên không tự gửi lại.", "Backend=" + open.operationId()
                            + " phiên=" + held.operationId());
        }
        if (open.classCode() == null) {
            pending = new Pending(cardId, open.operationId(),
                    new ProfileEditRequest(open.studentId(), open.displayName(), null), open.expectedVersionHex());
            return new Result(State.CLASS_REQUIRED,
                    "Yêu cầu cũ chưa có lớp. Nhập lớp hợp lệ rồi thay yêu cầu cũ để tiếp tục.",
                    "Yêu cầu " + open.operationId() + " vẫn đang chờ; chưa gửi lệnh ghi trong lần mở lại này.");
        }
        if (held != null && !sameContent(held.request(), open)) {
            return Result.rejected("Nội dung yêu cầu trên Backend khác nội dung đang giữ nên không tự gửi lại.",
                    "Backend mã=" + open.studentId() + " tên=" + open.displayName()
                            + " lớp=" + open.classCode() + " | máy mã=" + held.request().studentId()
                            + " tên=" + held.request().displayName()
                            + " lớp=" + held.request().classCode());
        }

        ProfileEditRequest request = held != null ? held.request()
                : new ProfileEditRequest(open.studentId(), open.displayName(), open.classCode());
        pending = new Pending(cardId, open.operationId(), request, open.expectedVersionHex());

        Profile onCard = snapshot.value().profile();
        boolean alreadyWritten = matches(onCard, request);
        if (alreadyWritten) {
            CardOperationResult signed = gateway.signProfileChallenge(adminPin, open.nonceHex());
            if (!signed.ok()) {
                return Result.unverified("Hồ sơ trên thẻ đã đúng nhưng chưa ký lại được challenge: "
                        + signed.text());
            }
            return complete(session.value(), cardId, request, gateway.lastProfileUpdateSignatureHex());
        }

        String onCardVersion = snapshot.value().meta().nonceHex();
        if (!onCardVersion.equalsIgnoreCase(open.expectedVersionHex())) {
            return Result.stale("Hồ sơ trên thẻ đã thay đổi so với lúc tạo yêu cầu nên chưa ghi gì;"
                    + " bấm Thay yêu cầu cũ để thay yêu cầu bằng hồ sơ hiện tại trên thẻ.",
                    "thẻ=" + onCardVersion + " yêu cầu=" + open.expectedVersionHex());
        }
        return writeAndComplete(session.value(), cardId, request, open.expectedVersionHex(),
                open.nonceHex(), adminPin, true);
    }

    public Result replaceLegacyPending(String cardId, String classCode, char[] adminPin, char[] studentPin)
            throws CardException {
        if (classCode == null || !classCode.matches("[A-Z0-9]{5,7}")) {
            return Result.rejected("Lớp phải gồm 5–7 ký tự chữ hoặc số viết hoa.", "");
        }
        Step<CardSnapshot> snapshot = readCard(studentPin);
        if (snapshot.failure() != null) {
            return snapshot.failure();
        }
        Pending held = pending;
        if (held == null || held.operationId() == null || held.request().classCode() != null) {
            return Result.rejected("Không còn yêu cầu hồ sơ cũ cần bổ sung lớp; hãy mở lại yêu cầu.", "");
        }
        if (!held.cardId().equalsIgnoreCase(cardId)) {
            return Result.rejected("Yêu cầu đang chờ thuộc thẻ khác; chưa thay đổi gì.",
                    "yêu cầu=" + held.cardId() + " thẻ=" + cardId);
        }
        Profile onCard = snapshot.value().profile();
        boolean unchangedVersion = snapshot.value().meta().nonceHex().equalsIgnoreCase(held.expectedVersionHex());
        boolean legacyTarget = onCard.studentId().equalsIgnoreCase(held.request().studentId())
                && onCard.displayName().equals(held.request().displayName());
        if (!unchangedVersion && !legacyTarget) {
            return new Result(State.CLASS_REQUIRED, "Hồ sơ trên thẻ đã thay đổi; chưa thay yêu cầu cũ.",
                    "Yêu cầu mã=" + held.request().studentId() + " tên=" + held.request().displayName()
                            + " | thẻ mã=" + onCard.studentId() + " tên=" + onCard.displayName()
                            + "; mở lại đúng thẻ để bổ sung lớp cho yêu cầu cũ.");
        }
        Step<String> session = openSession(cardId, snapshot.value(), adminPin);
        if (session.failure() != null) {
            return session.failure();
        }
        ProfileEditRequest replacementRequest = new ProfileEditRequest(held.request().studentId(),
                held.request().displayName(), classCode);
        BackendClient.ProfileUpdate replacement;
        try {
            replacement = backend.replaceProfileUpdate(session.value(), held.operationId(), replacementRequest,
                    snapshot.value().meta().nonceHex());
        } catch (BackendClient.Failure failure) {
            if (failure.statusCode() == 409) {
                return Result.rejected("Yêu cầu cũ không còn chờ thay thế; mở lại yêu cầu để kiểm tra trạng thái.",
                        failure.getMessage());
            }
            return replaceFailed(cardId, replacementRequest, failure);
        }
        pending = new Pending(cardId, replacement.operationId(), replacementRequest, replacement.expectedVersionHex());
        boolean identityChanged = !matches(onCard, replacementRequest);
        return writeAndComplete(session.value(), cardId, replacementRequest,
                snapshot.value().meta().nonceHex(), replacement.nonceHex(), adminPin, identityChanged);
    }

    public Result replaceStale(String cardId, ProfileEditRequest request, char[] adminPin, char[] studentPin)
            throws CardException {
        Step<CardSnapshot> snapshot = readCard(studentPin);
        if (snapshot.failure() != null) {
            return snapshot.failure();
        }
        Pending held = pending;
        if (held == null || held.operationId() == null) {
            return Result.rejected("Chưa giữ được mã yêu cầu cũ nên chưa thay được;"
                    + " bấm Mở lại yêu cầu để đọc lại yêu cầu trên Backend.", "");
        }
        if (held.request().classCode() == null) {
            return new Result(State.CLASS_REQUIRED,
                    "Yêu cầu cũ chưa có lớp. Nhập lớp hợp lệ rồi thay yêu cầu cũ để tiếp tục.", "");
        }
        if (request == null || request.classCode() == null) {
            return Result.rejected("Chưa có lớp hợp lệ để thay yêu cầu; hãy mở lại yêu cầu.", "");
        }
        Step<String> session = openSession(cardId, snapshot.value(), adminPin);
        if (session.failure() != null) {
            return session.failure();
        }

        Profile onCard = snapshot.value().profile();
        ProfileEditRequest target = held.request();
        if (matches(onCard, target)) {
            return reopen(cardId, adminPin, studentPin);
        }

        String expectedVersion = snapshot.value().meta().nonceHex();
        BackendClient.ProfileUpdate replacement;
        try {
            replacement = backend.replaceProfileUpdate(session.value(), held.operationId(), request,
                    expectedVersion);
        } catch (BackendClient.Failure failure) {
            if (failure.statusCode() == 409) {
                return reopen(cardId, adminPin, studentPin);
            }
            return replaceFailed(cardId, request, failure);
        }
        pending = new Pending(cardId, replacement.operationId(), request, replacement.expectedVersionHex());

        boolean identityChanged = !matches(onCard, request);
        return writeAndComplete(session.value(), cardId, request, expectedVersion,
                replacement.nonceHex(), adminPin, identityChanged);
    }

    private Step<CardSnapshot> readCard(char[] studentPin) throws CardException {
        clearReadProfile();
        CardOperationResult profileRead = gateway.readProfileWithPin(studentPin);
        Profile profile = gateway.lastProfile();
        if (!profileRead.ok() || profile == null) {
            return Step.failed(Result.rejected("Chưa đọc được hồ sơ hiện tại trên thẻ nên chưa gửi yêu cầu sửa.",
                    profileRead.text()));
        }
        lastReadProfile = profile;
        CardOperationResult metaRead = gateway.readProfileCryptoMetaWithPin(studentPin);
        ProfileCryptoMeta meta = gateway.lastProfileMeta();
        if (!metaRead.ok() || meta == null) {
            return Step.failed(Result.rejected("Chưa đọc được thông tin mã hóa của hồ sơ nên chưa gửi yêu cầu sửa.",
                    metaRead.text()));
        }
        return Step.ok(new CardSnapshot(profile, meta));
    }

    private Step<String> openSession(String cardId, CardSnapshot snapshot, char[] adminPin)
            throws CardException {
        BackendClient.AdminSession issued;
        try {
            issued = backend.issueAdminSession();
        } catch (BackendClient.Failure failure) {
            return Step.failed(Result.rejected("Chưa mở được phiên quản trị trên Backend nên chưa gửi yêu cầu sửa.",
                    failure.getMessage()));
        }

        CardOperationResult signed = gateway.signProfileChallenge(adminPin, issued.nonceHex());
        if (!signed.ok()) {
            return Step.failed(Result.rejected("Admin PIN chưa ký được thử thách của phiên quản trị"
                    + " nên chưa gửi yêu cầu sửa.", signed.text()));
        }

        try {
            BackendClient.CardSession activated = backend.activateAdminSession(issued.sessionId(), cardId,
                    ProfileCodec.hashHex(snapshot.profile()),
                    gateway.lastProfileUpdateSignatureHex());
            if (!activated.cardId().equalsIgnoreCase(cardId)) {
                return Step.failed(Result.rejected("Backend gắn phiên quản trị vào thẻ khác thẻ đang chọn.",
                        "Backend=" + activated.cardId() + " thẻ=" + cardId));
            }
            return Step.ok(activated.sessionId());
        } catch (BackendClient.Failure failure) {
            return Step.failed(Result.rejected("Chưa xác thực được phiên quản trị bằng thẻ"
                    + " nên chưa gửi yêu cầu sửa.", failure.getMessage()));
        }
    }

    private Result createFailed(String cardId, ProfileEditRequest request, BackendClient.Failure failure) {
        if (failure.statusCode() == 409) {
            pending = new Pending(cardId, null, request, null);
            return Result.unverified("Backend đang giữ một yêu cầu sửa hồ sơ chờ cho thẻ này."
                    + " Bấm Mở lại yêu cầu để đối chiếu với thẻ.");
        }
        if (failure.kind() == BackendClient.Kind.TRANSPORT && !failure.requestMayHaveReachedServer()) {
            return Result.rejected("Chưa gửi được yêu cầu sửa hồ sơ tới Backend nên thẻ chưa bị ghi.",
                    failure.getMessage());
        }
        if (failure.kind() == BackendClient.Kind.HTTP_REJECTED && failure.statusCode() < 500) {
            return Result.rejected("Backend từ chối yêu cầu sửa hồ sơ nên thẻ chưa bị ghi.",
                    failure.getMessage());
        }
        pending = new Pending(cardId, null, request, null);
        return Result.unverified("Chưa xác định được Backend đã nhận yêu cầu sửa hồ sơ hay chưa: "
                + failure.getMessage());
    }

    private Result replaceFailed(String cardId, ProfileEditRequest request, BackendClient.Failure failure) {
        if (failure.kind() == BackendClient.Kind.TRANSPORT && !failure.requestMayHaveReachedServer()) {
            return Result.rejected("Backend chưa nhận yêu cầu thay thế nên thẻ chưa bị ghi.",
                    failure.getMessage());
        }
        if (failure.kind() == BackendClient.Kind.HTTP_REJECTED && failure.statusCode() < 500) {
            return Result.rejected("Backend từ chối thay yêu cầu cũ nên thẻ chưa bị ghi.",
                    failure.getMessage());
        }
        pending = new Pending(cardId, null, request, null);
        return Result.unverified("Chưa xác định được Backend đã nhận yêu cầu thay thế hay chưa: "
                + failure.getMessage());
    }

    private Result writeAndComplete(String sessionId, String cardId, ProfileEditRequest request,
            String expectedVersion, String nonceHex, char[] adminPin, boolean writeIdentity)
            throws CardException {
        if (writeIdentity) {
            Result refused = writeIdentity(request, expectedVersion, adminPin);
            if (refused != null) {
                return refused;
            }
        }
        CardOperationResult signed = gateway.signProfileChallenge(adminPin, nonceHex);
        if (!signed.ok()) {
            String prefix = writeIdentity
                    ? "Thẻ đã ghi hồ sơ mới nhưng chưa ký được challenge: "
                    : "Thẻ chưa ký được challenge để Backend xác nhận hồ sơ: ";
            return Result.unverified(prefix + signed.text());
        }
        return complete(sessionId, cardId, request, gateway.lastProfileUpdateSignatureHex());
    }

    private Result writeIdentity(ProfileEditRequest request, String expectedVersion, char[] adminPin)
            throws CardException {
        lastReadProfile = null;
        byte[] version = HexFormat.of().parseHex(expectedVersion);
        CardProfileUpdateCommands.IdentityWrite write;
        try {
            write = gateway.updateIdentity(request, adminPin, version);
        } catch (CardException lost) {
            return Result.unverified("Chờ xác minh — đọc lại thẻ: đã gửi lệnh ghi hồ sơ nhưng chưa nhận được"
                    + " phản hồi từ thẻ nên chưa rõ thẻ đã ghi hay chưa; yêu cầu vẫn đang chờ trên Backend."
                    + " Kết nối lại đúng thẻ rồi chọn Mở lại yêu cầu: " + lost.getMessage());
        } finally {
            Arrays.fill(version, (byte) 0);
        }
        if (write.result().ok()) {
            return null;
        }
        int sw = write.sw();
        if ((sw & 0xFFF0) == 0x63C0 || sw == Sw.AUTH_BLOCKED) {
            return Result.rejected("Admin PIN bị từ chối nên thẻ chưa đổi hồ sơ;"
                    + " bấm Mở lại yêu cầu sau khi kiểm tra PIN.", write.result().text());
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return Result.rejected("Thẻ chưa ISSUED hoặc hồ sơ trên thẻ đã thay đổi so với lúc đọc"
                    + " (expectedVersion không khớp) nên chưa ghi."
                    + " Bấm Mở lại yêu cầu để đọc lại thẻ.", write.result().text());
        }
        return Result.unverified("UPDATE_IDENTITY trả SW bất thường nên chưa rõ thẻ đã ghi hồ sơ hay chưa: "
                + write.result().text());
    }

    private Result complete(String sessionId, String cardId, ProfileEditRequest request, String signatureHex) {
        Pending current = pending;
        if (current == null || current.operationId() == null) {
            return Result.unverified("Chưa giữ được mã yêu cầu để hoàn tất trên Backend.");
        }
        try {
            BackendClient.ProfileUpdateCompletion done = backend.completeProfileUpdate(sessionId,
                    current.operationId(), signatureHex);
            if (!done.cardId().equalsIgnoreCase(cardId)
                    || !done.studentId().equalsIgnoreCase(request.studentId())
                    || !Objects.equals(done.classCode(), request.classCode())
                    || !Objects.equals(done.major() == null ? "" : done.major(),
                            StudentMajor.fromStudentId(request.studentId()))) {
                return Result.unverified("Backend xác nhận một yêu cầu khác nội dung vừa ghi: cardId="
                        + done.cardId() + " studentId=" + done.studentId()
                        + " classCode=" + done.classCode() + " major=" + done.major());
            }
            lastCompletedRequest = request;
            pending = null;
            return Result.done("Đã lưu hồ sơ.", "Backend hoàn tất yêu cầu " + done.operationId()
                    + " lúc " + done.completedAt() + " | lớp=" + done.classCode()
                    + " ngành=" + done.major() + (done.alreadyCompleted() ? " | đã hoàn tất trước đó" : ""));
        } catch (BackendClient.Failure failure) {
            return Result.unverified("Thẻ đã ghi hồ sơ mới nhưng chưa xác nhận được với Backend: "
                    + failure.getMessage());
        }
    }

    private static boolean matches(Profile profile, ProfileEditRequest request) {
        return profile.studentId().equals(request.studentId())
                && profile.displayName().equals(request.displayName())
                && Objects.equals(profile.classCode(), request.classCode())
                && Objects.equals(profile.major(), StudentMajor.fromStudentId(request.studentId()));
    }

    private static boolean sameContent(ProfileEditRequest request, BackendClient.FoundPending open) {
        return request.studentId().equalsIgnoreCase(open.studentId())
                && request.displayName().equals(open.displayName())
                && Objects.equals(request.classCode(), open.classCode());
    }
}
