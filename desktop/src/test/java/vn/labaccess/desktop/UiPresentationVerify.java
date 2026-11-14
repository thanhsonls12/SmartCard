package vn.labaccess.desktop;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;

public final class UiPresentationVerify {

    private static final String CARD_ID = "A1B2C3D411223344";
    private static int checks;

    private UiPresentationVerify() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                historyStates();
                groupedReasons();
                failureMessages();
                receiptPresentation();
                formErrorLayout();
                pinConfirmation();
                recoveryGuidance();
                nextStepAfterInputError();
                copyControls();
                inputFeedbackLifecycle();
                sessionInputs();
                System.out.println("UI PRESENTATION PASS: " + checks + " checks");
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        if (failure.get() != null) {
            throw new AssertionError("UI presentation regression", failure.get());
        }
    }

    private static void historyStates() {
        int[] requests = {0};
        HistoryPanel.Runner runner = new HistoryPanel.Runner() {
            @Override
            public <T> void submit(String label, Callable<T> work,
                    Consumer<T> onSuccess, Consumer<Exception> onFailure) {
                requests[0]++;
            }
        };
        HistoryPanel panel = new HistoryPanel(new BackendClient("http://127.0.0.1:1"),
                runner, () -> CARD_ID, ignored -> { });
        TableView<?> table = (TableView<?>) panel.node().lookup("#history-table");
        Label placeholder = (Label) table.getPlaceholder();
        Label verdict = (Label) panel.node().lookup("#history-verdict");
        check("unloaded history is not reported empty", placeholder.getText().equals("Chưa tải lịch sử"));
        panel.setEnabled(true, null);
        check("reload invokes existing runner", panel.pressReload() && requests[0] == 1);
        check("loading placeholder", placeholder.getText().equals("Đang tải lịch sử…"));
        panel.showFailure(new IllegalStateException("Backend chưa phản hồi"));
        check("failure placeholder", placeholder.getText().startsWith("Không tải được lịch sử"));
        check("failure retains reason", verdict.getText().contains("Backend chưa phản hồi"));

        panel.show(history(List.of(), 0, 0));
        check("successful empty result", placeholder.getText().equals("Sổ chưa có giao dịch"));
        panel.show(history(List.of(), 2, 5));
        check("inconsistent empty result is not claimed valid",
                placeholder.getText().contains("dữ liệu không khớp"));

        BackendClient.HistoryEntry row = new BackendClient.HistoryEntry(
                "giao-dich-thu-nghiem", 1, "GRANT", 5, 5, "verified");
        panel.show(history(List.of(row), 1, 5));
        check("loaded history", panel.rowCount() == 1);
        panel.pressReload();
        check("reload preserves existing rows", panel.rowCount() == 1
                && verdict.getText().contains("lần tải trước"));
        panel.showFailure(new IllegalStateException("Mất kết nối Backend"));
        check("failed reload preserves existing rows", panel.rowCount() == 1
                && verdict.getText().contains("lần tải trước (1 dòng)"));
        panel.onCard("0011223344556677");
        check("another card clears loaded rows", panel.rowCount() == 0);
        panel.show(history(List.of(row), 1, 5));
        check("old card result never repopulates the current card", panel.rowCount() == 0);
        panel.onCard(CARD_ID);
        panel.show(history(List.of(row), 1, 5));
        panel.onCard(CARD_ID.toLowerCase(java.util.Locale.ROOT));
        check("same card retains its history", panel.rowCount() == 1);
        panel.onCard(null);
        check("lost identity clears history", panel.rowCount() == 0);
        panel.clear();
        check("clear resets placeholder and data", panel.rowCount() == 0
                && placeholder.getText().equals("Chưa tải lịch sử"));
    }

    private static BackendClient.HistoryLoad history(List<BackendClient.HistoryEntry> rows,
            long counter, int balance) {
        return new BackendClient.HistoryLoad(new BackendClient.TransactionHistory(CARD_ID, rows),
                new BackendClient.LedgerSnapshot(CARD_ID, counter, balance, null));
    }

    private static void groupedReasons() {
        Button read = new Button("Đọc trạng thái thẻ");
        Button start = new Button("Mở ca");
        Button end = new Button("Đóng ca");
        read.setDisable(true);
        start.setDisable(true);
        end.setDisable(true);
        check("shared blockers appear once", UiLabels.whyLine(read, "chưa kết nối thẻ",
                start, "chưa kết nối thẻ", end, "chưa kết nối thẻ")
                .equals("Kết nối thẻ để sử dụng các thao tác này."));
        String separate = UiLabels.whyLine(read, "chưa nhận diện thẻ", start,
                "thẻ đang có ca mở", end, null);
        check("different blockers remain visible", separate.contains("Đọc trạng thái thẻ")
                && separate.contains("Mở ca") && !separate.contains("Đóng ca"));
        read.setDisable(false);
        check("enabled controls have no blocking note", UiLabels.whyLine(read, "chưa nhận diện thẻ") == null);
        SessionGates.State disconnected = new SessionGates.State(false, false, false, false,
                true, false, false, false, null, null);
        SessionGates.Reasons reasons = SessionGates.of(disconnected);
        check("disconnected operations share root cause", reasons.readReceipt().equals(reasons.grant()));
    }

    private static void failureMessages() {
        String connect = UiLabels.failureMessage("CONNECT", "",
                new IllegalStateException(new ConnectException("Connection refused")));
        check("connection error has recovery guidance", connect.contains("đầu đọc/Simulator")
                && connect.contains("Kiểm tra"));
        check("absent card has concrete recovery guidance", UiLabels.failureMessage("CONNECT", "",
                new javax.smartcardio.CardException("Không có thẻ trên terminal test"))
                .contains("Đặt thẻ vào đầu đọc"));
        String timeout = UiLabels.failureMessage("START_VISIT", "", new SocketTimeoutException());
        check("timeout does not suggest blind replay", timeout.contains("không gửi lại"));
        String pin = UiLabels.failureMessage("VERIFY_STUDENT_PIN", "VERIFY SW=63C2", null);
        check("PIN retry count is explained", pin.contains("còn 2 lượt thử"));
        check("blocked PIN explanation", UiLabels.failureMessage("CHANGE_STUDENT_PIN", "SW=6983", null)
                .contains("PIN đang bị khóa"));
        check("unknown errors retain log route", UiLabels.failureMessage("GET_PROFILE", "SW=6F00", null)
                .contains("Mở Nhật ký"));
    }

    private static void receiptPresentation() {
        ReceiptView view = new ReceiptView();
        check("unread receipt is explicit", hasText(view.node(), "Chưa có biên nhận"));
        Receipt receipt = new Receipt(1, new byte[8], 1, Receipt.OPERATION_GRANT, 5, 5,
                new byte[16], new byte[34], new byte[0]);
        view.show(receipt, true, true, true);
        check("receipt balance has unit", hasText(view.node(), "5 lượt"));
        check("signature result remains visible", hasText(view.node(), "Chữ ký hợp lệ"));
        check("pending ledger is not hidden", hasText(view.node(), "Chưa lưu vào sổ — đang chờ đối soát"));
        check("receipt technical fields remain available", hasText(view.detailsNode(), "Mã ca trên biên nhận"));
        view.clear("Chưa đọc biên nhận từ thẻ");
        check("clear does not show stale balance", !hasText(view.node(), "5 lượt"));
    }

    private static void formErrorLayout() {
        CardGateway gateway = new CardGateway("127.0.0.1", 1);
        CardOpRunner noIo = (operation, work, callback) -> {
            throw new AssertionError("Invalid input must not start card I/O");
        };
        PersonalizePanel issuing = new PersonalizePanel(gateway, noIo, ignored -> { }, (ok, card) -> { });
        issuing.updateAvailability(true, false, null);
        PhotoPanel photos = new PhotoPanel(gateway, noIo, ignored -> { });
        photos.setEnabled(true, null);
        PinPanel pins = new PinPanel(gateway, noIo, ignored -> { });
        pins.setEnabled(true, null);
        for (int width : new int[] {420, 820}) {
            verifyFieldErrors(issuing.node(), width, issuing::pressPersonalize);
            verifyFieldErrors(photos.node(), width, photos::pressRead);
            verifyFieldErrors(pins.node(), width, pins::pressVerify);
        }
    }

    private static void verifyFieldErrors(Parent content, int width, Runnable invalidAction) {
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        javafx.scene.Scene scene = new javafx.scene.Scene(scroll, width, 600);
        UiTheme.apply(scene);
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setScene(scene);
        stage.show();
        invalidAction.run();
        scroll.applyCss();
        scroll.layout();
        int visibleErrors = 0;
        for (javafx.scene.Node node : content.lookupAll(".la-field-error")) {
            if (node instanceof Label label && label.isVisible()) {
                visibleErrors++;
                javafx.scene.text.Text expected = new javafx.scene.text.Text(label.getText());
                expected.setFont(label.getFont());
                expected.setWrappingWidth(label.getWidth() - label.getInsets().getLeft()
                        - label.getInsets().getRight());
                check("field error fits at width " + width + ": " + label.getText(),
                        label.getHeight() + 1 >= expected.getLayoutBounds().getHeight()
                                + label.getInsets().getTop() + label.getInsets().getBottom());
            }
        }
        check("invalid form exposes field errors at width " + width, visibleErrors > 0);
        stage.hide();
        scroll.setContent(null);
    }

    private static boolean hasText(Parent parent, String text) {
        return parent.getChildrenUnmodifiable().stream().anyMatch(node -> node.isVisible()
                && (node instanceof Label label && text.equals(label.getText())
                || node instanceof Parent child && hasText(child, text)));
    }

    private static void pinConfirmation() {
        int[] submissions = {0};
        PinPanel panel = new PinPanel(new CardGateway("127.0.0.1", 1),
                (operation, work, callback) -> submissions[0]++, ignored -> { });
        panel.setEnabled(true, null);
        List<javafx.scene.control.PasswordField> inputs = fields(panel.node());
        check("PIN form has separate confirmation fields", inputs.size() == 7);
        String pin = "1".repeat(6);
        String different = "2".repeat(6);
        inputs.get(1).setText(pin);
        inputs.get(2).setText(different);
        panel.pressChange();
        check("missing confirmation never submits PIN change", submissions[0] == 0);
        inputs.get(3).setText(pin);
        panel.pressChange();
        check("mismatched confirmation never submits PIN change", submissions[0] == 0);
        check("PIN mismatch is explained beside the field",
                hasText(panel.node(), "PIN nhập lại chưa khớp. Nhập lại PIN mới."));
        inputs.get(3).setText(different);
        panel.pressChange();
        check("matching confirmation submits once and clears change inputs", submissions[0] == 1
                && inputs.subList(1, 4).stream().allMatch(field -> field.getText().isEmpty()));
        inputs.get(4).setText("3".repeat(8));
        inputs.get(5).setText(pin);
        inputs.get(6).setText(different);
        panel.pressUnblock();
        check("mismatched confirmation never submits unblock", submissions[0] == 1);
        inputs.get(6).setText(pin);
        panel.pressUnblock();
        check("matching confirmation clears unblock inputs", submissions[0] == 2
                && inputs.subList(4, 7).stream().allMatch(field -> field.getText().isEmpty()));
        for (int width : new int[] {420, 820}) {
            verifyFieldErrors(panel.node(), width, panel::pressChange);
        }
    }

    private static List<javafx.scene.control.PasswordField> fields(Parent parent) {
        List<javafx.scene.control.PasswordField> result = new java.util.ArrayList<>();
        for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof javafx.scene.control.PasswordField field) {
                result.add(field);
            } else if (child instanceof Parent nested) {
                result.addAll(fields(nested));
            }
        }
        return result;
    }

    private static void recoveryGuidance() {
        PendingJournal.Loaded damaged = PendingJournal.parse("broken");
        PendingJournal.Loaded resolved = PendingJournal.parse(
                "v1|RESOLVED|A1B2C3D411223344|1|1|5|5||0");
        check("damaged journal has a recovery route", JournalRecoveryView.needed(damaged));
        check("resolved journal is described as completed, not corrupted",
                JournalRecoveryView.summary(resolved).contains("hoàn tất")
                        && !JournalRecoveryView.summary(resolved).contains("null"));
        String instructions = JournalRecoveryView.instructions(resolved,
                java.nio.file.Path.of("fixture", "pending-journal.txt"));
        check("recovery guidance identifies journal and transaction",
                instructions.contains("pending-journal.txt") && instructions.contains(CARD_ID)
                        && instructions.contains("RESOLVED") && instructions.contains("người quản trị"));
        SessionGates.State blocked = new SessionGates.State(false, true, true, false,
                false, true, false, true, null,
                new CardStatus(1, CardLifecycle.ISSUED, "F04C4142010101", CARD_ID,
                        false, -1, -1, false, ""));
        check("recovery state does not claim no pending transaction",
                SessionGates.of(blocked).reconcile().contains("Hướng dẫn khôi phục"));
        check("blocked transactions point to recovery instead of unavailable state read",
                SessionGates.of(blocked).grant().contains("Hướng dẫn khôi phục"));
        check("normal state has no recovery notice", !JournalRecoveryView.needed(PendingJournal.Loaded.absent()));
    }

    private static void nextStepAfterInputError() {
        CardStatus card = new CardStatus(1, CardLifecycle.ISSUED, "F04C4142010101", CARD_ID,
                true, 5, 1, true, "11".repeat(16));
        SessionGates.State ready = new SessionGates.State(false, true, true, true,
                true, false, false, true, null, card);
        SessionGates.NextStep next = SessionGates.nextStep(false, ready, "da-dang-ky", true, App.TAB_CARD);
        check("input error takes priority over closing an active visit",
                next.tab() == App.TAB_CARD && next.text().contains("thông tin vừa nhập"));
        check("normal guidance resumes without input error",
                SessionGates.nextStep(false, ready, "da-dang-ky", false, App.TAB_CARD)
                        .text().equals("đóng ca khi kết thúc"));
    }

    private static void copyControls() {
        Label value = new Label("-");
        Button copy = UiLabels.copyButton(value, "mã thẻ");
        check("unavailable value cannot be copied", copy.isDisabled());
        value.setText(CARD_ID);
        check("copy action is keyboard focusable and named", !copy.isDisabled()
                && copy.isFocusTraversable() && copy.getAccessibleText().equals("Sao chép mã thẻ"));
        value.setText("Chưa đọc khóa công khai");
        check("copy never offers placeholder text", copy.isDisabled());
    }

    private static void inputFeedbackLifecycle() {
        InputFeedback feedback = new InputFeedback(() -> { });
        javafx.scene.control.TextField first = new javafx.scene.control.TextField();
        javafx.scene.control.TextField second = new javafx.scene.control.TextField();
        javafx.scene.layout.VBox form = new javafx.scene.layout.VBox(first, second);
        form.addEventHandler(InputFeedback.Problem.TYPE, feedback::show);
        UiLabels.inputProblem(ignored -> { }, first, "Sửa ô thứ nhất");
        check("input feedback follows the reporting field", "Sửa ô thứ nhất".equals(feedback.message()));
        second.setText("unrelated");
        check("editing another field preserves the error", "Sửa ô thứ nhất".equals(feedback.message()));
        first.setText("corrected");
        check("editing the reported field clears feedback", feedback.message() == null);
        UiLabels.inputProblem(ignored -> { }, first, "Lỗi trước");
        UiLabels.inputProblem(ignored -> { }, second, "Lỗi hiện tại");
        first.clear();
        check("old listener cannot clear a newer error", "Lỗi hiện tại".equals(feedback.message()));
        feedback.clear();
        check("leaving the form dismisses input feedback", feedback.message() == null);
    }

    private static void sessionInputs() {
        CardGateway gateway = new CardGateway("127.0.0.1", 1);
        CardOpRunner noIo = (label, work, callback) -> {
            throw new AssertionError("Reset must not send card commands");
        };
        PinPanel pins = new PinPanel(gateway, noIo, ignored -> { });
        pins.setEnabled(true, null);
        pins.pressVerify();
        fields(pins.node()).forEach(field -> field.setText("1".repeat(8)));
        pins.resetState("new card");
        check("new PIN session clears every PIN and confirmation", fields(pins.node()).stream()
                .allMatch(field -> field.getText().isEmpty()));
        check("new PIN session clears validation", pins.node().lookupAll(".la-field-error").stream()
                .noneMatch(javafx.scene.Node::isVisible));
        PhotoPanel photos = new PhotoPanel(gateway, noIo, ignored -> { });
        fields(photos.node()).forEach(field -> field.setText("1".repeat(8)));
        photos.resetView();
        check("photo reset clears both PINs", fields(photos.node()).stream()
                .allMatch(field -> field.getText().isEmpty()));
        ProfilePanel profile = new ProfilePanel(gateway, noIo, ignored -> { });
        profile.fillPin("1".repeat(6).toCharArray());
        profile.resetView("new card");
        check("profile reset clears PIN", fields(profile.node()).getFirst().getText().isEmpty());
        PersonalizePanel issuing = new PersonalizePanel(gateway, noIo, ignored -> { }, (ok, card) -> { });
        fields(issuing.node()).forEach(field -> field.setText("1".repeat(8)));
        issuing.resetView();
        check("issuing reset clears PINs", fields(issuing.node()).stream()
                .allMatch(field -> field.getText().isEmpty()));
    }

    private static void check(String name, boolean passed) {
        if (!passed) {
            throw new AssertionError(name);
        }
        checks++;
        System.out.println("PASS " + name);
    }
}
