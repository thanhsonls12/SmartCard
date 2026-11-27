package vn.labaccess.desktop.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;

import vn.labaccess.desktop.backend.BackendClient;

public final class EnrollmentFlow {

    private static final int PUBLIC_POINT_LENGTH = 65;

    public enum Step {
        CHECK_CARD("Kiểm tra thẻ và trạng thái ban đầu"),
        PUBLIC_KEY("Kiểm tra khóa của thẻ"),
        ADMIN_SESSION("Kết nối hệ thống quản lý"),
        CHALLENGE("Tạo yêu cầu đăng ký"),
        SIGN("Xác nhận bằng chữ ký trên thẻ"),
        COMPLETE("Gửi chữ ký để hoàn tất");

        public final String title;

        Step(String title) {
            this.title = title;
        }
    }

    public interface Progress {

        void started(Step step);

        void finished(Step step);
    }

    public static final class StepFailure extends Exception {

        public final Step step;
        private final boolean requestSent;
        private final int statusCode;

        StepFailure(Step step, String detail, boolean requestSent) {
            this(step, detail, requestSent, 0);
        }

        StepFailure(Step step, String detail, boolean requestSent, int statusCode) {
            super(detail);
            this.step = step;
            this.requestSent = requestSent;
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }

        public boolean sentWithoutReply() {
            return requestSent && statusCode == 0;
        }
    }

    public record Outcome(BackendClient.Enrollment enrollment, String challengeId) {
    }

    private final CardGateway gateway;
    private final BackendClient backend;
    private final Consumer<String> log;

    public EnrollmentFlow(CardGateway gateway, BackendClient backend, Consumer<String> log) {
        this.gateway = gateway;
        this.backend = backend;
        this.log = log;
    }

    public Outcome run(String studentId, String classCode, char[] pin, Progress progress) throws Exception {
        try {
            progress.started(Step.CHECK_CARD);
            CardOperationResult identified = gateway.identify();
            if (!identified.ok()) {
                throw new StepFailure(Step.CHECK_CARD, identified.text(), false);
            }
            CardOperationResult verified = gateway.readAuthenticatedStatus(pin);
            if (!verified.ok()) {
                throw new StepFailure(Step.CHECK_CARD,
                        "VERIFY/GET_STATUS thất bại: " + verified.text(), false);
            }
            CardStatus current = gateway.status();
            if (current == null) {
                throw new StepFailure(Step.CHECK_CARD, "không đọc lại được state thẻ", false);
            }
            String problem = stateProblem(current);
            if (problem != null) {
                throw new StepFailure(Step.CHECK_CARD, problem, false);
            }
            String cardId = current.cardId();
            progress.finished(Step.CHECK_CARD);
            log.accept("ENROLL b1: SELECT + GET_STATUS + VERIFY -> ISSUED SW=9000 | cardId=" + cardId
                    + " | counter=0 balance=0 không có ca");

            progress.started(Step.PUBLIC_KEY);
            CardOperationResult keyRead = gateway.readCardPublicKey();
            if (!keyRead.ok()) {
                throw new StepFailure(Step.PUBLIC_KEY, keyRead.text(), false);
            }
            byte[] publicKey = gateway.lastCardPublicKeyBytes();
            if (publicKey == null || publicKey.length != PUBLIC_POINT_LENGTH) {
                throw new StepFailure(Step.PUBLIC_KEY, "public key không phải 65 byte", false);
            }
            progress.finished(Step.PUBLIC_KEY);
            log.accept("ENROLL b2: GET_CARD_PUBLIC_KEY SW=9000 | publicKey 65 byte P-256 uncompressed");

            progress.started(Step.ADMIN_SESSION);
            BackendClient.AdminSession session;
            try {
                session = backend.issueAdminSession();
            } catch (BackendClient.Failure failure) {
                throw new StepFailure(Step.ADMIN_SESSION, failure.getMessage(), false);
            }
            progress.finished(Step.ADMIN_SESSION);
            log.accept("ENROLL b3: POST /admin/sessions -> HTTP " + session.httpStatus()
                    + " | phiên quản trị TTL 30 phút (không ghi sessionId)");

            progress.started(Step.CHALLENGE);
            BackendClient.Challenge challenge;
            try {
                challenge = backend.createChallenge(
                        session.sessionId(), cardId, studentId, hex(publicKey), classCode);
            } catch (BackendClient.Failure failure) {
                throw new StepFailure(Step.CHALLENGE, describeChallengeFailure(failure), false,
                        failure.statusCode());
            }
            byte[] nonce = HexFormat.of().parseHex(challenge.nonceHex());
            progress.finished(Step.CHALLENGE);
            log.accept("ENROLL b4: POST /enrollments/challenges -> HTTP " + challenge.httpStatus()
                    + " | challengeId=" + challenge.challengeId()
                    + " | lớp=" + classCode
                    + " | nonce 32 byte (không ghi nội dung) | hết hạn " + challenge.expiresAt());

            progress.started(Step.SIGN);
            CardOperationResult signed = gateway.signRegistrationChallenge(nonce);
            if (!signed.ok()) {
                throw new StepFailure(Step.SIGN, signed.text(), false);
            }
            byte[] signature = gateway.lastEnrollmentSignature();
            if (signature == null) {
                throw new StepFailure(Step.SIGN, "thẻ không trả chữ ký", false);
            }
            progress.finished(Step.SIGN);
            log.accept("ENROLL b5: SIGN_REGISTRATION_CHALLENGE INS=62 SW=9000 | sigLen="
                    + signature.length + " byte (không ghi nội dung)");

            progress.started(Step.COMPLETE);
            BackendClient.Enrollment enrollment;
            try {
                enrollment = backend.completeEnrollment(
                        session.sessionId(), challenge.challengeId(), hex(signature));
            } catch (BackendClient.Failure failure) {
                throw new StepFailure(Step.COMPLETE, describeCompleteFailure(failure),
                        failure.requestMayHaveReachedServer(), failure.statusCode());
            }
            if (!enrollment.cardId().equalsIgnoreCase(cardId)
                    || !enrollment.studentId().equals(studentId)) {
                throw new StepFailure(Step.COMPLETE,
                        "Backend trả định danh không khớp lượt đang chạy: cardId="
                                + enrollment.cardId() + " studentId=" + enrollment.studentId(),
                        true, enrollment.httpStatus());
            }
            progress.finished(Step.COMPLETE);
            log.accept("ENROLL b6: POST /enrollments/complete -> HTTP " + enrollment.httpStatus()
                    + " | cardId=" + enrollment.cardId() + " | studentId=" + enrollment.studentId()
                    + " | enrolledAt=" + enrollment.enrolledAt());
            return new Outcome(enrollment, challenge.challengeId());
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    public static String stateProblem(CardStatus current) {
        List<String> problems = new ArrayList<>();
        if (current.balance() != 0) {
            problems.add("số lượt còn lại khác 0");
        }
        if (current.counter() != 0) {
            problems.add("số lượt thay đổi khác 0");
        }
        if (current.hasActiveVisit()) {
            problems.add("đang có ca mở");
        }
        return problems.isEmpty() ? null
                : "Thẻ đã có giao dịch (" + String.join(", ", problems)
                        + ") — chỉ đăng ký thẻ mới.";
    }

    private static String describeChallengeFailure(BackendClient.Failure failure) {
        if (failure.statusCode() == 409) {
            return "Backend từ chối tạo yêu cầu đăng ký (HTTP 409). Thẻ này đã được đăng ký trước đó.";
        }
        return failure.getMessage();
    }

    private static String describeCompleteFailure(BackendClient.Failure failure) {
        if (failure.statusCode() == 409) {
            return "Backend từ chối hoàn tất đăng ký (HTTP 409)."
                    + " Kiểm tra trạng thái đăng ký trên Backend trước khi thực hiện lại.";
        }
        return failure.getMessage();
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value).toUpperCase();
    }
}
