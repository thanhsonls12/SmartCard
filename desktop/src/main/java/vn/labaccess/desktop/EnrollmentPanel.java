package vn.labaccess.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Luồng "Đăng ký thẻ": một nút, sáu bước, bốn trạng thái.
 *
 * <p>Panel sở hữu state enrollment, Mã sinh viên dùng cho challenge, PIN học viên dùng để kiểm tra thẻ chưa có giao dịch, nhãn tiến độ và kết quả. Thứ tự APDU/HTTP do luồng điều phối trong panel giữ; {@code App} chỉ cấp worker và client Backend.
 */
final class EnrollmentPanel {

    private static final int PIN_LENGTH = 6;
    private static final int PUBLIC_POINT_LENGTH = 65;

    private enum Step {
        CHECK_CARD("Kiểm tra thẻ và trạng thái ban đầu"),
        PUBLIC_KEY("Kiểm tra khóa của thẻ"),
        ADMIN_SESSION("Kết nối hệ thống quản lý"),
        CHALLENGE("Tạo yêu cầu đăng ký"),
        SIGN("Xác nhận bằng chữ ký trên thẻ"),
        COMPLETE("Gửi chữ ký để hoàn tất");

        private final String title;

        Step(String title) {
            this.title = title;
        }
    }

    private enum Mark {
        PENDING("•"), RUNNING("…"), DONE("✓"), FAILED("✕");

        private final String symbol;

        Mark(String symbol) {
            this.symbol = symbol;
        }
    }

    private enum State {
        NOT_READY, RUNNING, ENROLLED, ERROR, UNKNOWN
    }

    /** Lỗi mang theo bước, mã HTTP và việc request đã rời máy hay chưa. */
    private static final class StepFailure extends Exception {

        private final Step step;
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

        int statusCode() {
            return statusCode;
        }

        /** Request đã rời máy mà chưa có mã HTTP: chỉ khi đó mới không loại trừ được việc Backend đã xử lý. */
        boolean sentWithoutReply() {
            return requestSent && statusCode == 0;
        }
    }

    private record Outcome(BackendClient.Enrollment enrollment, String challengeId) {
    }

    /** Kết luận của một lượt đăng ký, để App cập nhật thanh trạng thái mà không phải đọc log. */
    enum OutcomeKind {
        SUCCESS, ERROR, UNKNOWN
    }

    /** Panel tự chuyển kết quả nghiệp vụ thành câu hiển thị; App chỉ đặt câu đó lên thanh trạng thái. */
    interface Reporter {
        void report(OutcomeKind kind, String message);
    }

    private final CardGateway gateway;
    private final EnrollmentRunner runner;
    private final BackendClient backend;
    private final Consumer<String> log;
    private final Reporter reporter;

    private final TextField fieldStudentId = new TextField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Button btnEnroll = new Button("Đăng ký thẻ");

    private final Label valueCardId = UiLabels.value();
    private final Label valueStudentId = UiLabels.value();
    private final Label valueEnrolledAt = UiLabels.value();
    private final Label stateChip = UiLabels.chip();
    private final Label message = new Label();
    private final Label[] stepMarks = new Label[Step.values().length];
    private final VBox node;
    private final FlowPane steps;

    private boolean enabled;
    private String enabledReason;
    private State state = State.NOT_READY;
    private boolean enrolled;
    /** Lỗi tạm thời: sửa input thì xoá. */
    private boolean failed;
    /** Mã thẻ của phiên, do App truyền sang; panel không đọc state của component khác. */
    private String currentCardId;

    EnrollmentPanel(CardGateway gateway, EnrollmentRunner runner, BackendClient backend,
            Consumer<String> log, Reporter reporter) {
        this.gateway = gateway;
        this.runner = runner;
        this.backend = backend;
        this.log = log;
        this.reporter = reporter;

        fieldStudentId.setPrefColumnCount(14);
        fieldStudentId.setPromptText("8–10 chữ/số");
        fieldStudentId.textProperty().addListener((observable, old, now) -> userEdited());
        fieldStudentPin.setPrefColumnCount(7);
        fieldStudentPin.textProperty().addListener((observable, old, now) -> userEdited());

        btnEnroll.setOnAction(e -> enroll());
        btnEnroll.getStyleClass().add("la-action");
        UiLabels.apduHint(btnEnroll, "SIGN_REGISTRATION_CHALLENGE (INS=62)"
                + " — một nút chạy sáu bước, không tách sáu nút");

        message.setWrapText(true);
        message.getStyleClass().add("la-note");
        UiLabels.applyNoteTone(message, UiLabels.Tone.NEUTRAL);
        stateChip.setTooltip(new Tooltip("Backend: " + backend.baseUrl()));

        FlowPane inputs = UiRows.wrapping(
                UiRows.field("Mã sinh viên", fieldStudentId),
                UiRows.field("PIN học viên", fieldStudentPin),
                btnEnroll);

        // Sáu bước xếp hai cột: đủ sáu bước trong tầm mắt, không phải cuộn giữa lúc đang chạy.
        steps = new FlowPane(UiScale.SPACE_3, UiScale.SPACE_1);
        steps.setPrefWrapLength(560);
        for (Step step : Step.values()) {
            Label mark = new Label(Mark.PENDING.symbol);
            Label title = new Label(step.title);
            title.setWrapText(true);
            title.getStyleClass().add("la-step-title");
            stepMarks[step.ordinal()] = mark;
            setMark(mark, Mark.PENDING);
            HBox row = UiRows.pair(mark, title);
            row.getStyleClass().add("la-step-row");
            row.setPrefWidth(270);
            steps.getChildren().add(row);
        }

        FlowPane result = UiRows.wrapping(
                UiRows.field("Mã thẻ", valueCardId),
                UiRows.field("Mã sinh viên", valueStudentId),
                UiRows.field("Thời điểm đăng ký", valueEnrolledAt));

        UiLabels.show(steps, false);
        node = UiSection.of("Đăng ký thẻ", inputs, stateChip, message, result, steps);

        resetView();
    }

    VBox node() {
        return node;
    }

    /**
     * App khoá/mở panel và nói rõ vì sao khoá; panel không tự đoán từ state phiên.
     *
     * @param whyDisabled lý do hiện cho người vận hành khi {@code enabled} là false
     */
    void showTechnical(boolean visible) {
        UiLabels.show(steps, visible);
    }

    void setEnabled(boolean enabled, String whyDisabled) {
        this.enabled = enabled;
        this.enabledReason = whyDisabled;
        boolean locked = !enabled || "da-dang-ky".equals(gateway.enrollmentText());
        fieldStudentId.setDisable(locked);
        fieldStudentPin.setDisable(locked);
        refresh();
    }

    /** App truyền mã thẻ của phiên; đổi sang thẻ khác thì kết quả của thẻ trước không còn hiệu lực. */
    void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase();
        boolean changed = !next.equals(currentCardId);
        currentCardId = next;
        if (changed && state != State.RUNNING) {
            resetResult();
        }
        refresh();
    }

    /** Prefill sau khi phát hành thẻ: nhận giá trị do panel khác truyền sang, không đọc control của panel đó. */
    void prefill(String cardId, String studentId) {
        if (cardId != null && !cardId.isBlank() && !enrolled) {
            valueCardId.setText(cardId.strip().toUpperCase());
        }
        if (studentId != null && !studentId.isBlank() && fieldStudentId.getText().isBlank()) {
            fieldStudentId.setText(studentId.strip());
        }
        refresh();
    }

    void clearInputs() {
        fieldStudentPin.clear();
    }

    void resetView() {
        resetResult();
        refresh();
    }

    /** Xoá kết quả và dữ liệu của lượt trước: đổi thẻ hoặc reset phiên thì không giữ lại gì. */
    private void resetResult() {
        enrolled = false;
        failed = false;
        state = State.NOT_READY;
        valueCardId.setText("—");
        valueStudentId.setText("—");
        valueEnrolledAt.setText("—");
        fieldStudentId.clear();
        fieldStudentPin.clear();
        for (Label mark : stepMarks) {
            setMark(mark, Mark.PENDING);
        }
    }

    boolean pressEnroll() {
        if (btnEnroll.isDisabled()) {
            return false;
        }
        btnEnroll.fire();
        return true;
    }

    boolean enrolled() {
        return enrolled;
    }

    String statusText() {
        return stateChip.getText() + " | " + message.getText();
    }

    void fillInputs(String studentId, char[] pin) {
        fieldStudentId.setText(studentId);
        fieldStudentPin.setText(new String(pin));
    }

    private void refresh() {
        if (state == State.RUNNING) {
            btnEnroll.setDisable(true);
            return;
        }
        // Đã đăng ký xong thì giữ kết quả, không tính lại điều kiện.
        if (state == State.ENROLLED) {
            btnEnroll.setDisable(true);
            UiLabels.markPrimary(btnEnroll, false);
            return;
        }
        if (enabled && gateway.isReady() && "da-dang-ky".equals(gateway.enrollmentText())) {
            btnEnroll.setDisable(true);
            UiLabels.markPrimary(btnEnroll, false);
            valueCardId.setText(gateway.status().cardId());
            applyState("Đã đăng ký", UiLabels.Tone.OK,
                    "Thẻ đã đăng ký trên Backend. Chuyển sang tab Vận hành để sử dụng.");
            return;
        }
        if (state == State.UNKNOWN) {
            btnEnroll.setDisable(true);
            UiLabels.markPrimary(btnEnroll, false);
            return;
        }
        String reason = blockingReason();
        boolean ready = reason == null;
        btnEnroll.setDisable(!ready);
        // Accent chỉ khi bấm được: nút khoá phải trông như đang khoá.
        UiLabels.markPrimary(btnEnroll, ready);
        if (state == State.ERROR || failed) {
            // Đang hiện lỗi: giữ nguyên thông báo và hành động tiếp theo; sửa input mới xoá.
            return;
        }
        if (ready) {
            applyState("Sẵn sàng", UiLabels.Tone.NEUTRAL,
                    "Bấm Đăng ký thẻ để liên kết thẻ với học viên.");
        } else {
            applyState("Chưa sẵn sàng", UiLabels.Tone.PENDING, reason);
        }
    }

    /** Sửa input thì bỏ lỗi tạm thời; kết quả chưa xác nhận thì vẫn giữ. */
    private void userEdited() {
        if (state == State.RUNNING || state == State.ENROLLED) {
            return;
        }
        if (failed) {
            failed = false;
            state = State.NOT_READY;
            for (Label mark : stepMarks) {
                setMark(mark, Mark.PENDING);
            }
        }
        refresh();
    }

    /** Điều kiện đọc được từ control và state công khai; điều kiện 0/0/không ca do bước 1 xác nhận sau VERIFY. */
    private String blockingReason() {
        if (!enabled) {
            return enabledReason == null
                    ? "Chưa sẵn sàng: chưa kết nối thẻ hoặc đang bận thao tác khác."
                    : enabledReason;
        }
        CardStatus current = gateway.status();
        if (current == null) {
            return "Chưa nhận diện thẻ.";
        }
        if (!gateway.isReady()) {
            return "Chưa đọc được applet trên thẻ — nhận diện lại thẻ.";
        }
        if (current.lifecycle() != CardLifecycle.ISSUED) {
            return "Thẻ chưa phát hành — phát hành thẻ trước khi đăng ký.";
        }
        if (current.cardId() == null || current.cardId().isBlank()
                || current.cardId().matches("0+")) {
            return "Chưa nhận diện được mã thẻ.";
        }
        if ("da-dang-ky".equals(gateway.enrollmentText())) {
            return "Thẻ đã đăng ký — chuyển sang tab Vận hành để sử dụng.";
        }
        String studentIdProblem = PersonalizeRequest.studentIdProblem(fieldStudentId.getText());
        if (studentIdProblem != null) {
            return studentIdProblem;
        }
        if (!fieldStudentPin.getText().matches("[0-9]{" + PIN_LENGTH + "}")) {
            return "PIN học viên phải đúng 6 chữ số.";
        }
        if (current.studentStateAvailable()) {
            return stateProblem(current);
        }
        return null;
    }

    private static String stateProblem(CardStatus current) {
        List<String> problems = new ArrayList<>();
        if (current.balance() != 0) {
            problems.add("số dư khác 0");
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

    private void enroll() {
        if (btnEnroll.isDisabled()) {
            return;
        }
        String enteredStudentId = fieldStudentId.getText();
        char[] pin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        if (PersonalizeRequest.studentIdProblem(enteredStudentId) != null || pin.length != PIN_LENGTH) {
            Arrays.fill(pin, '\0');
            logLine("[FAIL] đăng ký thẻ: Mã sinh viên hoặc PIN học viên không hợp lệ");
            return;
        }
        String studentId = PersonalizeRequest.normalizeStudentId(enteredStudentId);
        fieldStudentId.setText(studentId);

        state = State.RUNNING;
        for (Label mark : stepMarks) {
            setMark(mark, Mark.PENDING);
        }
        applyState("Đang đăng ký", UiLabels.Tone.PENDING,
                "Đang chạy sáu bước — không thao tác thẻ trong lúc này.");
        btnEnroll.setDisable(true);
        UiLabels.markPrimary(btnEnroll, false);

        runner.run("ENROLLMENT", () -> flow(studentId, pin), this::showEnrolled, this::showFailure);
    }

    private Outcome flow(String studentId, char[] pin) throws Exception {
        try {
            mark(Step.CHECK_CARD, Mark.RUNNING);
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
            mark(Step.CHECK_CARD, Mark.DONE);
            logLine("ENROLL b1: SELECT + GET_STATUS + VERIFY -> ISSUED SW=9000 | cardId=" + cardId
                    + " | counter=0 balance=0 không có ca");

            mark(Step.PUBLIC_KEY, Mark.RUNNING);
            CardOperationResult keyRead = gateway.readCardPublicKey();
            if (!keyRead.ok()) {
                throw new StepFailure(Step.PUBLIC_KEY, keyRead.text(), false);
            }
            byte[] publicKey = gateway.lastCardPublicKeyBytes();
            if (publicKey == null || publicKey.length != PUBLIC_POINT_LENGTH) {
                throw new StepFailure(Step.PUBLIC_KEY, "public key không phải 65 byte", false);
            }
            mark(Step.PUBLIC_KEY, Mark.DONE);
            logLine("ENROLL b2: GET_CARD_PUBLIC_KEY SW=9000 | publicKey 65 byte P-256 uncompressed");

            mark(Step.ADMIN_SESSION, Mark.RUNNING);
            BackendClient.AdminSession session;
            try {
                session = backend.issueAdminSession();
            } catch (BackendClient.Failure failure) {
                throw new StepFailure(Step.ADMIN_SESSION, failure.getMessage(), false);
            }
            mark(Step.ADMIN_SESSION, Mark.DONE);
            logLine("ENROLL b3: POST /admin/sessions -> HTTP " + session.httpStatus()
                    + " | phiên quản trị TTL 30 phút (không ghi sessionId)");

            mark(Step.CHALLENGE, Mark.RUNNING);
            BackendClient.Challenge challenge;
            try {
                challenge = backend.createChallenge(
                        session.sessionId(), cardId, studentId, hex(publicKey));
            } catch (BackendClient.Failure failure) {
                // Challenge hỏng không tạo enrollment: báo đúng bước 4, không phải "chưa xác định".
                throw new StepFailure(Step.CHALLENGE, describeChallengeFailure(failure), false,
                        failure.statusCode());
            }
            byte[] nonce = HexFormat.of().parseHex(challenge.nonceHex());
            mark(Step.CHALLENGE, Mark.DONE);
            logLine("ENROLL b4: POST /enrollments/challenges -> HTTP " + challenge.httpStatus()
                    + " | challengeId=" + challenge.challengeId()
                    + " | nonce 32 byte (không ghi nội dung) | hết hạn " + challenge.expiresAt());

            mark(Step.SIGN, Mark.RUNNING);
            CardOperationResult signed = gateway.signRegistrationChallenge(nonce);
            if (!signed.ok()) {
                throw new StepFailure(Step.SIGN, signed.text(), false);
            }
            byte[] signature = gateway.lastEnrollmentSignature();
            if (signature == null) {
                throw new StepFailure(Step.SIGN, "thẻ không trả chữ ký", false);
            }
            mark(Step.SIGN, Mark.DONE);
            logLine("ENROLL b5: SIGN_REGISTRATION_CHALLENGE INS=62 SW=9000 | sigLen="
                    + signature.length + " byte (không ghi nội dung)");

            mark(Step.COMPLETE, Mark.RUNNING);
            BackendClient.Enrollment enrollment;
            try {
                enrollment = backend.completeEnrollment(
                        session.sessionId(), challenge.challengeId(), hex(signature));
            } catch (BackendClient.Failure failure) {
                throw new StepFailure(Step.COMPLETE, describeCompleteFailure(failure),
                        failure.requestMayHaveReachedServer(), failure.statusCode());
            }
            // Backend phải xác nhận đúng thẻ và đúng sinh viên của lượt này; sai thì không thể coi là xong.
            if (!enrollment.cardId().equalsIgnoreCase(cardId)
                    || !enrollment.studentId().equals(studentId)) {
                throw new StepFailure(Step.COMPLETE,
                        "Backend trả định danh không khớp lượt đang chạy: cardId="
                                + enrollment.cardId() + " studentId=" + enrollment.studentId(),
                        true, enrollment.httpStatus());
            }
            mark(Step.COMPLETE, Mark.DONE);
            logLine("ENROLL b6: POST /enrollments/complete -> HTTP " + enrollment.httpStatus()
                    + " | cardId=" + enrollment.cardId() + " | studentId=" + enrollment.studentId()
                    + " | enrolledAt=" + enrollment.enrolledAt());
            return new Outcome(enrollment, challenge.challengeId());
        } finally {
            Arrays.fill(pin, '\0');
        }
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

    private void showEnrolled(Outcome outcome) {
        enrolled = true;
        failed = false;
        state = State.ENROLLED;
        String cardId = outcome.enrollment().cardId().toUpperCase();
        String studentId = outcome.enrollment().studentId();
        valueCardId.setText(cardId);
        valueStudentId.setText(studentId);
        valueEnrolledAt.setText(outcome.enrollment().enrolledAt());
        applyState("Đã đăng ký", UiLabels.Tone.OK,
                "Đăng ký thẻ xong — thẻ sẵn sàng cho giao dịch.");
        stateChip.setTooltip(new Tooltip(String.format(
                "challengeId=%s | HTTP %d | Backend: %s",
                outcome.challengeId(), outcome.enrollment().httpStatus(), backend.baseUrl())));
        logLine("[OK] đăng ký thẻ xong | cardId=" + cardId + " | studentId=" + studentId);
        btnEnroll.setDisable(true);
        UiLabels.markPrimary(btnEnroll, false);
        reporter.report(OutcomeKind.SUCCESS,
                "Đăng ký thẻ xong — thẻ sẵn sàng cho giao dịch.");
    }

    private void showFailure(Exception failure) {
        Step step = Step.COMPLETE;
        String detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        int statusCode = 0;
        boolean sentWithoutReply = false;
        if (failure instanceof StepFailure stepFailure) {
            step = stepFailure.step;
            statusCode = stepFailure.statusCode();
            sentWithoutReply = stepFailure.sentWithoutReply();
        }
        applyMark(step, Mark.FAILED);
        // Chỉ complete mới có thể đã ghi vào Backend mà client không xác nhận được kết quả.
        boolean outcomeUnclear = step == Step.COMPLETE
                && (statusCode == 409 || sentWithoutReply || statusCode >= 500
                        || (statusCode >= 200 && statusCode < 300));
        state = outcomeUnclear ? State.UNKNOWN : State.ERROR;
        failed = !outcomeUnclear;
        String chip;
        UiLabels.Tone tone;
        String next;
        if (outcomeUnclear) {
            chip = "Cần kiểm tra";
            tone = UiLabels.Tone.PENDING;
            next = statusCode == 409
                    ? "Kiểm tra trạng thái đăng ký trên Backend trước khi thực hiện lại; không gửi lại chữ ký."
                    : "Không gửi lại chữ ký. Kiểm tra Backend trước khi làm lại từ đầu.";
        } else {
            chip = "Lỗi";
            tone = UiLabels.Tone.ERROR;
            next = switch (step) {
                case CHECK_CARD -> "Chưa tạo yêu cầu đăng ký nào — sửa điều kiện thẻ rồi chạy lại.";
                case PUBLIC_KEY -> "Thẻ phải đã phát hành và còn kết nối; chạy lại.";
                case ADMIN_SESSION -> "Kiểm tra Backend đang chạy rồi chạy lại.";
                case CHALLENGE -> statusCode == 409
                        ? "Thẻ này đã được đăng ký trước đó — dùng thẻ khác."
                        : "Đọc thông báo lỗi Backend rồi chạy lại.";
                case SIGN -> "Kiểm tra thẻ còn kết nối; không dùng lại nonce cũ.";
                case COMPLETE -> "Đối chiếu Backend trước khi chạy lại.";
            };
        }
        applyState(chip, tone,
                "Bước " + (step.ordinal() + 1) + " (" + step.title + "): " + detail + " — " + next);
        logLine("[FAIL] đăng ký thẻ tại bước " + (step.ordinal() + 1) + " (" + step.title + "): "
                + detail);
        btnEnroll.setDisable(true);
        UiLabels.markPrimary(btnEnroll, false);
        reporter.report(outcomeUnclear ? OutcomeKind.UNKNOWN : OutcomeKind.ERROR,
                outcomeUnclear
                        ? "Chưa rõ kết quả đăng ký — kiểm tra Backend trước khi làm lại."
                        : "Đăng ký thẻ lỗi ở bước " + (step.ordinal() + 1) + " (" + step.title
                                + ") — xem chi tiết trong tab Đăng ký thẻ.");
    }

    private void applyState(String chip, UiLabels.Tone tone, String detail) {
        stateChip.setText(chip);
        UiLabels.applyChip(stateChip, tone);
        message.setText(detail);
        UiLabels.applyNoteTone(message, tone);
    }

    private void mark(Step step, Mark value) {
        Platform.runLater(() -> applyMark(step, value));
    }

    private void applyMark(Step step, Mark value) {
        setMark(stepMarks[step.ordinal()], value);
        if (state == State.RUNNING && value == Mark.RUNNING) {
            message.setText(String.format("Đang đăng ký: bước %d/%d — %s",
                    step.ordinal() + 1, Step.values().length, step.title));
        }
    }

    private static void setMark(Label label, Mark value) {
        label.setText(value.symbol);
        label.getStyleClass().removeAll("la-step-mark--running", "la-step-mark--done",
                "la-step-mark--failed");
        switch (value) {
            case RUNNING -> label.getStyleClass().add("la-step-mark--running");
            case DONE -> label.getStyleClass().add("la-step-mark--done");
            case FAILED -> label.getStyleClass().add("la-step-mark--failed");
            case PENDING -> {
            }
            default -> throw new IllegalStateException("Mark khong xac dinh: " + value);
        }
    }

    private void logLine(String line) {
        Platform.runLater(() -> log.accept(line));
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value).toUpperCase();
    }
}
