package vn.labaccess.desktop.ui.enrollment;

import vn.labaccess.desktop.validation.ClassCode;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardLifecycle;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PersonalizeRequest;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.EnrollmentFlow;

import vn.labaccess.desktop.ui.shared.EnrollmentRunner;
import vn.labaccess.desktop.ui.shared.PinOperation;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class EnrollmentPanel {

    private static final int PIN_LENGTH = 6;

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

    public enum OutcomeKind {
        SUCCESS, ERROR, UNKNOWN
    }

    public interface Reporter {
        void report(OutcomeKind kind, String message);
    }

    private final CardGateway gateway;
    private final EnrollmentRunner runner;
    private final BackendClient backend;
    private final Consumer<String> log;
    private final Reporter reporter;
    private final EnrollmentFlow flow;

    private final TextField fieldStudentId = new TextField();
    private final TextField fieldClassCode = new TextField();
    private final PasswordField fieldStudentPin = new PasswordField();
    private final Button btnEnroll = new Button("Đăng ký thẻ");
    private final Button btnRecheck = new Button("Kiểm tra lại đăng ký");

    private final Label valueCardId = UiLabels.value();
    private final Label valueStudentId = UiLabels.value();
    private final Label valueEnrolledAt = UiLabels.value();
    private final Label stateChip = UiLabels.chip();
    private final Label message = new Label();
    private final Label detailNote = new Label();
    private final Label[] stepMarks = new Label[EnrollmentFlow.Step.values().length];
    private final VBox node;
    private final FlowPane steps;
    private final VBox studentRow;
    private final VBox enrolledAtRow;
    private final FlowPane resultGroup;
    private final EnrollmentFlow.Progress progress = new EnrollmentFlow.Progress() {

        @Override
        public void started(EnrollmentFlow.Step step) {
            mark(step, Mark.RUNNING);
        }

        @Override
        public void finished(EnrollmentFlow.Step step) {
            mark(step, Mark.DONE);
        }
    };

    private boolean enabled;
    private String enabledReason;
    private State state = State.NOT_READY;
    private boolean enrolled;
    private boolean failed;
    private String currentCardId;

    public EnrollmentPanel(CardGateway gateway, EnrollmentRunner runner, BackendClient backend,
            Consumer<String> log, Reporter reporter) {
        this.gateway = gateway;
        this.runner = runner;
        this.backend = backend;
        this.log = log;
        this.reporter = reporter;
        this.flow = new EnrollmentFlow(gateway, backend, this::logLine);

        fieldStudentId.setPrefColumnCount(14);
        fieldStudentId.setPromptText("8–10 chữ/số");
        fieldStudentId.textProperty().addListener((observable, old, now) -> userEdited());
        fieldClassCode.setPrefColumnCount(18);
        fieldClassCode.setPromptText(ClassCode.PROMPT);
        fieldClassCode.textProperty().addListener((observable, old, now) -> userEdited());
        fieldStudentPin.setPrefColumnCount(7);
        fieldStudentPin.textProperty().addListener((observable, old, now) -> userEdited());

        btnEnroll.setOnAction(e -> enroll());
        btnEnroll.getStyleClass().add("la-action");
        UiLabels.apduHint(btnEnroll, "SIGN_REGISTRATION_CHALLENGE (INS=62)"
                + " — một nút chạy sáu bước, không tách sáu nút");
        btnRecheck.setOnAction(e -> recheck());
        btnRecheck.getStyleClass().add("la-action");
        btnRecheck.setTooltip(new Tooltip("Hỏi Backend trạng thái đăng ký của đúng thẻ này;"
                + " không gửi lại chữ ký cũ"));
        UiLabels.show(btnRecheck, false);

        message.setWrapText(true);
        message.getStyleClass().add("la-note");
        UiLabels.applyNoteTone(message, UiLabels.Tone.NEUTRAL);
        stateChip.setTooltip(new Tooltip("Backend: " + backend.baseUrl()));

        FlowPane inputs = UiRows.wrapping(
                UiRows.field("Mã sinh viên", fieldStudentId),
                UiRows.field("Lớp", fieldClassCode),
                UiRows.field("PIN sinh viên", fieldStudentPin),
                btnEnroll, btnRecheck);

        steps = new FlowPane(UiScale.SPACE_3, UiScale.SPACE_1);
        steps.setPrefWrapLength(560);
        for (EnrollmentFlow.Step step : EnrollmentFlow.Step.values()) {
            Label mark = new Label(Mark.PENDING.symbol);
            mark.getStyleClass().add("la-step-mark");
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

        studentRow = UiRows.field("Mã sinh viên", valueStudentId);
        enrolledAtRow = UiRows.field("Thời điểm đăng ký", valueEnrolledAt);
        detailNote.setText("Backend chỉ xác nhận thẻ đã có trong sổ;"
                + " không trả mã sinh viên và thời điểm đăng ký.");
        detailNote.getStyleClass().add("la-caption");
        detailNote.setWrapText(true);
        resultGroup = UiRows.wrapping(UiRows.field("Mã thẻ", valueCardId),
                studentRow, enrolledAtRow, detailNote);
        UiLabels.show(detailNote, false);
        UiLabels.show(resultGroup, false);

        UiLabels.show(steps, false);
        node = UiSection.of("Đăng ký thẻ", inputs, stateChip, message, resultGroup, steps);

        resetView();
    }

    public VBox node() {
        return node;
    }

    public void showTechnical(boolean visible) {
        UiLabels.show(steps, visible);
    }

    public void setEnabled(boolean enabled, String whyDisabled) {
        this.enabled = enabled;
        this.enabledReason = whyDisabled;
        boolean locked = !enabled || "da-dang-ky".equals(gateway.enrollmentText());
        fieldStudentId.setDisable(locked);
        fieldStudentId.setPromptText(locked ? null : "8–10 chữ/số");
        fieldClassCode.setDisable(locked);
        fieldClassCode.setPromptText(locked ? null : ClassCode.PROMPT);
        fieldStudentPin.setDisable(locked);
        refresh();
    }

    public void onCard(String cardId) {
        String next = cardId == null ? "" : cardId.strip().toUpperCase();
        boolean changed = !next.equals(currentCardId);
        currentCardId = next;
        if (changed && state != State.RUNNING) {
            resetResult();
        }
        refresh();
    }

    public void prefill(String cardId, String studentId) {
        prefill(cardId, studentId, null);
    }

    public void prefill(String cardId, String studentId, String classCode) {
        if (cardId != null && !cardId.isBlank() && !enrolled) {
            valueCardId.setText(cardId.strip().toUpperCase());
        }
        if (studentId != null && !studentId.isBlank() && fieldStudentId.getText().isBlank()) {
            fieldStudentId.setText(studentId.strip());
        }
        if (classCode != null && !classCode.isBlank() && fieldClassCode.getText().isBlank()) {
            fieldClassCode.setText(classCode);
        }
        showDetails(!valueStudentId.getText().isBlank()
                && !UiLabels.EMPTY_VALUE.equals(valueStudentId.getText()), false, false);
        refresh();
    }

    private void showDetails(boolean studentIdKnown, boolean enrolledAtKnown, boolean note) {
        UiLabels.show(resultGroup, true);
        UiLabels.show(studentRow, studentIdKnown);
        UiLabels.show(enrolledAtRow, enrolledAtKnown);
        UiLabels.show(detailNote, note);
    }

    public void clearInputs() {
        fieldStudentPin.clear();
    }

    public void resetView() {
        resetResult();
        refresh();
    }

    private void resetResult() {
        enrolled = false;
        failed = false;
        state = State.NOT_READY;
        valueCardId.setText(UiLabels.EMPTY_VALUE);
        valueStudentId.setText(UiLabels.EMPTY_VALUE);
        valueEnrolledAt.setText(UiLabels.EMPTY_VALUE);
        UiLabels.show(resultGroup, false);
        fieldStudentId.clear();
        fieldClassCode.clear();
        fieldStudentPin.clear();
        for (Label mark : stepMarks) {
            setMark(mark, Mark.PENDING);
        }
    }

    public boolean pressEnroll() {
        if (btnEnroll.isDisabled()) {
            return false;
        }
        btnEnroll.fire();
        return true;
    }

    public boolean enrolled() {
        return enrolled;
    }

    public String statusText() {
        return stateChip.getText() + " | " + message.getText();
    }

    public void fillInputs(String studentId, String classCode, char[] pin) {
        fieldStudentId.setText(studentId);
        fieldClassCode.setText(classCode);
        fieldStudentPin.setText(new String(pin));
    }

    private void refresh() {
        boolean unknown = state == State.UNKNOWN;
        UiLabels.show(btnRecheck, unknown);
        btnRecheck.setDisable(!enabled || !unknown);
        if (state == State.RUNNING) {
            btnEnroll.setDisable(true);
            return;
        }
        if (state == State.ENROLLED) {
            btnEnroll.setDisable(true);
            UiLabels.markPrimary(btnEnroll, false);
            return;
        }
        if (enabled && gateway.isReady() && "da-dang-ky".equals(gateway.enrollmentText())) {
            btnEnroll.setDisable(true);
            UiLabels.markPrimary(btnEnroll, false);
            valueCardId.setText(gateway.status().cardId());
            showDetails(false, false, true);
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
        UiLabels.markPrimary(btnEnroll, ready);
        if (state == State.ERROR || failed) {
            return;
        }
        if (ready) {
            applyState("Sẵn sàng", UiLabels.Tone.NEUTRAL,
                    "Bấm Đăng ký thẻ để liên kết thẻ với sinh viên.");
        } else {
            applyState("Chưa sẵn sàng", UiLabels.Tone.PENDING, reason);
        }
    }

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
        String classProblem = ClassCode.problem(fieldClassCode.getText());
        if (classProblem != null) {
            return classProblem;
        }
        if (!fieldStudentPin.getText().matches("[0-9]{" + PIN_LENGTH + "}")) {
            return "PIN sinh viên phải đúng 6 chữ số.";
        }
        if (current.studentStateAvailable()) {
            return EnrollmentFlow.stateProblem(current);
        }
        return null;
    }

    private void enroll() {
        if (btnEnroll.isDisabled()) {
            return;
        }
        String enteredStudentId = fieldStudentId.getText();
        String enteredClassCode = fieldClassCode.getText();
        char[] pin = fieldStudentPin.getText().toCharArray();
        fieldStudentPin.clear();
        if (PersonalizeRequest.studentIdProblem(enteredStudentId) != null || pin.length != PIN_LENGTH
                || ClassCode.problem(enteredClassCode) != null) {
            Arrays.fill(pin, '\0');
            logLine("[FAIL] đăng ký thẻ: Mã sinh viên, Lớp hoặc PIN sinh viên không hợp lệ");
            return;
        }
        String studentId = PersonalizeRequest.normalizeStudentId(enteredStudentId);
        fieldStudentId.setText(studentId);
        String classCode = ClassCode.normalize(enteredClassCode);
        fieldClassCode.setText(classCode);

        state = State.RUNNING;
        for (Label mark : stepMarks) {
            setMark(mark, Mark.PENDING);
        }
        applyState("Đang đăng ký", UiLabels.Tone.PENDING,
                "Đang chạy sáu bước — không thao tác thẻ trong lúc này.");
        btnEnroll.setDisable(true);
        UiLabels.markPrimary(btnEnroll, false);

        runner.run("ENROLLMENT", PinOperation.of(() -> flow.run(studentId, classCode, pin, progress), pin),
                this::showEnrolled, this::showFailure);
    }

    private void showEnrolled(EnrollmentFlow.Outcome outcome) {
        enrolled = true;
        failed = false;
        state = State.ENROLLED;
        String cardId = outcome.enrollment().cardId().toUpperCase();
        String studentId = outcome.enrollment().studentId();
        valueCardId.setText(cardId);
        valueStudentId.setText(studentId);
        valueEnrolledAt.setText(outcome.enrollment().enrolledAt());
        showDetails(true, true, false);
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
        EnrollmentFlow.Step step = EnrollmentFlow.Step.COMPLETE;
        String detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        int statusCode = 0;
        boolean sentWithoutReply = false;
        if (failure instanceof EnrollmentFlow.StepFailure stepFailure) {
            step = stepFailure.step;
            statusCode = stepFailure.statusCode();
            sentWithoutReply = stepFailure.sentWithoutReply();
        }
        applyMark(step, Mark.FAILED);
        boolean outcomeUnclear = step == EnrollmentFlow.Step.COMPLETE
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

    private void recheck() {
        CardStatus current = gateway.status();
        String cardId = currentCardId.isEmpty() && current != null ? current.cardId() : currentCardId;
        if (cardId == null || cardId.isBlank()) {
            applyState("Chưa kiểm tra được", UiLabels.Tone.PENDING,
                    "Chưa có mã thẻ để kiểm tra đăng ký — nhận diện lại thẻ.");
            return;
        }
        String cardIdHex = cardId.strip().toUpperCase();
        applyState("Đang kiểm tra", UiLabels.Tone.NEUTRAL,
                "Đang hỏi Backend trạng thái đăng ký của thẻ " + cardIdHex + " — chưa gửi lệnh thẻ.");
        UiLabels.show(btnRecheck, false);
        runner.run("ENROLLMENT_RECHECK", () -> backend.cardEnrolled(cardIdHex),
                enrolled -> showRecheck(cardIdHex, enrolled), this::showRecheckFailure);
    }

    private void showRecheck(String cardIdHex, boolean backendEnrolled) {
        if (backendEnrolled) {
            enrolled = true;
            failed = false;
            state = State.ENROLLED;
            valueCardId.setText(cardIdHex);
            showDetails(false, false, true);
            applyState("Đã đăng ký", UiLabels.Tone.OK,
                    "Backend xác nhận thẻ đã đăng ký — chuyển sang tab Vận hành để sử dụng.");
            logLine("[OK] kiểm tra lại đăng ký | cardId=" + cardIdHex
                    + " | Backend: da-dang-ky, không gửi chữ ký");
            btnEnroll.setDisable(true);
            UiLabels.markPrimary(btnEnroll, false);
            refresh();
            reporter.report(OutcomeKind.SUCCESS,
                    "Đăng ký thẻ xong — thẻ sẵn sàng cho giao dịch.");
            return;
        }
        if (!confirmRestart(cardIdHex)) {
            applyState("Cần kiểm tra", UiLabels.Tone.PENDING,
                    "Backend xác nhận thẻ chưa đăng ký — giữ nguyên để kiểm tra lại khi cần;"
                            + " chưa gửi chữ ký.");
            logLine("[INFO] kiểm tra lại đăng ký: operator chưa xác nhận chạy lại");
            refresh();
            return;
        }
        resetResult();
        applyState("Chưa đăng ký", UiLabels.Tone.NEUTRAL,
                "Backend xác nhận thẻ chưa đăng ký — nhập Mã sinh viên và PIN sinh viên rồi"
                        + " bấm Đăng ký thẻ để chạy lượt mới.");
        logLine("[INFO] kiểm tra lại đăng ký: Backend chưa có thẻ, operator xác nhận chạy lại"
                + " từ đầu | cardId=" + cardIdHex + " | không dùng lại chữ ký cũ");
        refresh();
        reporter.report(OutcomeKind.UNKNOWN,
                "Thẻ chưa đăng ký — chạy lại lượt đăng ký mới.");
    }

    private void showRecheckFailure(Exception failure) {
        String detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        applyState("Cần kiểm tra", UiLabels.Tone.PENDING,
                "Chưa kiểm tra được trạng thái đăng ký trên Backend (" + detail
                        + ") — không gửi lại chữ ký; kiểm tra Backend rồi bấm Kiểm tra lại đăng ký.");
        logLine("[FAIL] kiểm tra lại đăng ký: " + detail);
        refresh();
        reporter.report(OutcomeKind.UNKNOWN,
                "Chưa kiểm tra được trạng thái đăng ký trên Backend — thử lại sau khi Backend chạy.");
    }

    private boolean confirmRestart(String cardIdHex) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Xác nhận chạy lại đăng ký");
        confirmation.setHeaderText("Backend xác nhận thẻ " + cardIdHex + " chưa đăng ký.");
        confirmation.setContentText("Chạy lại từ đầu sẽ tạo yêu cầu đăng ký mới và cần nhập lại"
                + " Mã sinh viên, PIN sinh viên. Chữ ký của lượt trước không được dùng lại.");
        return UiLabels.confirm(confirmation, node, "Chạy lại");
    }

    private void applyState(String chip, UiLabels.Tone tone, String detail) {
        stateChip.setText(chip);
        UiLabels.applyChip(stateChip, tone);
        message.setText(detail);
        UiLabels.applyNoteTone(message, tone);
    }

    private void mark(EnrollmentFlow.Step step, Mark value) {
        Platform.runLater(() -> applyMark(step, value));
    }

    private void applyMark(EnrollmentFlow.Step step, Mark value) {
        setMark(stepMarks[step.ordinal()], value);
        if (state == State.RUNNING && value == Mark.RUNNING) {
            message.setText(String.format("Đang đăng ký: bước %d/%d — %s",
                    step.ordinal() + 1, EnrollmentFlow.Step.values().length, step.title));
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
}
