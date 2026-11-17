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
import javafx.scene.layout.VBox;

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
                UiTheme.apply(new javafx.scene.Scene(new VBox()));
                historyStates();
                operatorIdentityAndEffects();
                groupedReasons();
                failureMessages();
                receiptPresentation();
                formErrorLayout();
                photoDraftFeedback();
                pinConfirmation();
                recoveryGuidance();
                nextStepAfterInputError();
                copyControls();
                inputFeedbackLifecycle();
                sessionInputs();
                uiReviewFixes();
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
        Label loadStatus = (Label) panel.node().lookup("#history-load-status");
        check("unloaded history is not reported empty", placeholder.getText().equals("Chưa tải lịch sử"));
        panel.setEnabled(true, null);
        check("reload invokes existing runner", panel.pressReload() && requests[0] == 1);
        check("loading placeholder", placeholder.getText().equals("Đang tải lịch sử…"));
        panel.showFailure(new IllegalStateException("Backend chưa phản hồi"));
        check("failure placeholder", placeholder.getText().startsWith("Không tải được lịch sử"));
        check("failure retains reason", loadStatus.getText().contains("Backend chưa phản hồi"));

        panel.show(history(List.of(), 0, 0));
        check("successful empty result", placeholder.getText().equals("Sổ chưa có giao dịch"));
        panel.show(history(List.of(), 2, 5));
        check("inconsistent empty result is not claimed valid",
                placeholder.getText().contains("dữ liệu không khớp"));

        BackendClient.HistoryEntry row = new BackendClient.HistoryEntry(
                "giao-dich-thu-nghiem", 1, "GRANT", 5, 5, "verified",
                java.time.Instant.parse("2026-11-12T08:00:00Z"));
        panel.show(history(List.of(row), 1, 5));
        check("loaded history", panel.rowCount() == 1);
        panel.pressReload();
        check("reload preserves existing rows", panel.rowCount() == 1
                && loadStatus.getText().contains("lần tải trước"));
        panel.showFailure(new IllegalStateException("Mất kết nối Backend"));
        check("failed reload preserves existing rows", panel.rowCount() == 1
                && loadStatus.getText().contains("lần tải trước (1 dòng)"));
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
        panel.onCard(CARD_ID);
        BackendClient.HistoryEntry start = new BackendClient.HistoryEntry(
                "start-id", 2, "START", -1, 4, "verified", java.time.Instant.parse("2026-11-13T08:00:00Z"));
        BackendClient.HistoryEntry end = new BackendClient.HistoryEntry(
                "end-id", 3, "END", 0, 4, "verified", java.time.Instant.parse("2026-11-13T09:00:00Z"));
        panel.show(history(List.of(row, start, end), 3, 4));
        check("history displays newest counter first", table.getItems().equals(List.of(end, start, row)));
        javafx.scene.control.ComboBox<?> filter = (javafx.scene.control.ComboBox<?>) panel.node().lookup("#history-operation");
        javafx.scene.control.DatePicker day = (javafx.scene.control.DatePicker) panel.node().lookup("#history-day");
        Button clear = (Button) panel.node().lookup("#history-clear-filters");
        check("unfiltered history has no reset action", clear.isDisabled());
        filter.getSelectionModel().select(2);
        check("operation filter isolates START", table.getItems().equals(List.of(start)));
        table.getSelectionModel().select(0);
        Label detail = (Label) ((javafx.scene.control.TitledPane) panel.node().lookup("TitledPane")).getContent();
        check("selected transaction includes complete identity and time", detail.getText().contains("start-id")
                && detail.getText().contains("13/11/2026") && detail.getText().contains("Số thứ tự trên thẻ: 2"));
        day.setValue(java.time.LocalDate.of(2026, 11, 12));
        check("date and operation filters combine", panel.rowCount() == 0
                && placeholder.getText().contains("Xóa lọc") && !detail.getText().contains("start-id"));
        clear.fire();
        check("clearing filters restores all rows", panel.rowCount() == 3 && clear.isDisabled());
        day.setValue(java.time.LocalDate.of(2026, 11, 13));
        check("recording date filter", table.getItems().equals(List.of(end, start)));
        panel.show(history(List.of(row, start, end), 3, 9));
        String mismatch = verdict.getText();
        filter.getSelectionModel().select(1);
        check("filters never hide full-ledger mismatch", panel.rowCount() == 0
                && mismatch.contains("khác sổ") && verdict.getText().equals(mismatch));
        panel.request();
        check("loading never hides mismatch", verdict.getText().equals(mismatch)
                && loadStatus.isVisible() && loadStatus.getText().contains("Đang cập nhật"));
        panel.showFailure(new IllegalStateException("Mất kết nối"));
        check("failed reload keeps both warnings visible", verdict.isVisible()
                && verdict.getText().equals(mismatch) && loadStatus.isVisible());
        clear.fire();
        check("filter changes retain stale-data warning", panel.rowCount() == 3
                && loadStatus.getText().contains("lần tải trước (3 dòng)")
                && verdict.getText().equals(mismatch));
        panel.onCard("0011223344556677");
        check("new card clears filters and selection", day.getValue() == null
                && filter.getSelectionModel().getSelectedIndex() == 0 && panel.rowCount() == 0
                && !detail.getText().contains("start-id"));
    }

    private static void operatorIdentityAndEffects() {
        CardIdentityPanel identity = new CardIdentityPanel();
        CardStatus status = new CardStatus(1, CardLifecycle.ISSUED, "F04C4142010101", CARD_ID,
                true, 5, 1, false, "");
        Profile profile = new Profile(1, "SV2024001", "Nguyễn Thị Phương Thảo", 30);
        byte[] photo = new byte[4096];
        identity.show(status, profile, photo, false);
        check("operator sees student identity", hasText(identity.node(), "Nguyễn Thị Phương Thảo")
                && hasText(identity.node(), "Mã sinh viên: SV2024001"));
        javafx.scene.image.ImageView portrait = (javafx.scene.image.ImageView) identity.node().lookup("ImageView");
        check("operator sees card photo", portrait.getImage() != null);
        identity.show(status, null, null, false);
        check("new unread session clears name and photo", portrait.getImage() == null
                && !hasText(identity.node(), "Nguyễn Thị Phương Thảo")
                && hasText(identity.node(), "Nhập PIN và bấm Đọc thẻ để xem hồ sơ, ảnh và số lượt."));
        identity.show(status, profile, null, true);
        check("missing photo is explicit", hasText(identity.node(), "Thẻ chưa có ảnh"));
        identity.show(status, profile, null, false);
        check("invalidated photo is not claimed absent", hasText(identity.node(), "Chưa đọc ảnh")
                && !hasText(identity.node(), "Thẻ chưa có ảnh"));
        identity.show(null, null, null, false);
        check("disconnected identity gives connection guidance", hasText(identity.node(), "Kết nối thẻ để bắt đầu."));

        CardGateway gateway = new CardGateway("127.0.0.1", 1);
        TransactionPanel panel = new TransactionPanel(gateway, (label, work, callback) -> {
            throw new AssertionError("Preview must not send card commands");
        }, ignored -> { }, new TransactionPanel.Session(() -> true, () -> true,
                (plan, supplier, pin) -> { throw new AssertionError("Preview must not transact"); },
                () -> { }, () -> { }, ignored -> { }, () -> { }, () -> { }));
        panel.showState(status, true);
        check("START and GRANT show consequences before acting", hasText(panel.node(), "Sau khi mở ca: còn 4 lượt.")
                && hasText(panel.node(), "Cấp thêm 5 → còn 10 lượt."));
        panel.showState(new CardStatus(1, 1, status.instanceAid(), CARD_ID, true, 0, 2, false, ""), true);
        check("empty balance explains prerequisite", hasText(panel.node(), "Thẻ hết lượt. Cấp thêm lượt trước khi mở ca."));
        panel.showState(new CardStatus(1, 1, status.instanceAid(), CARD_ID, true, 4, 2, true, "11".repeat(16)), true);
        check("END explains no additional charge", hasText(panel.node(), "Đóng ca không trừ thêm lượt."));
        panel.showState(status, false);
        check("unknown state hides projections", !hasText(panel.node(), "Cấp thêm 5 → còn 10 lượt.")
                && hasText(panel.node(), "Đọc thẻ để xem tác động của ca sử dụng."));
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
        check("connection error has recovery guidance", connect.contains("đầu đọc thẻ")
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
                expected.setBoundsType(((javafx.scene.text.Text) label.lookup(".text")).getBoundsType());
                expected.setLineSpacing(label.getLineSpacing());
                expected.setWrappingWidth(label.getWidth() - label.getInsets().getLeft()
                        - label.getInsets().getRight());
                double requiredHeight = expected.getLayoutBounds().getHeight()
                        + label.getInsets().getTop() + label.getInsets().getBottom();
                check("field error fits at width " + width + ": " + label.getText()
                                + " [width=" + label.getWidth() + ", height=" + label.getHeight()
                                + ", required=" + requiredHeight + "]",
                        label.getHeight() + 1 >= requiredHeight);
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
                hasText(panel.node(), "PIN chưa khớp."));
        inputs.get(3).setText(different);
        check("editing confirmation clears mismatch before submission",
                !hasText(panel.node(), "PIN nhập lại chưa khớp. Nhập lại PIN mới.")
                        && !hasText(panel.node(), "PIN chưa khớp."));
        inputs.get(3).setText(pin);
        panel.pressChange();
        inputs.get(2).setText(pin);
        check("editing new PIN clears mismatch before submission",
                !hasText(panel.node(), "PIN nhập lại chưa khớp. Nhập lại PIN mới.")
                        && !hasText(panel.node(), "PIN chưa khớp."));
        panel.pressChange();
        check("matching confirmation submits once and clears change inputs", submissions[0] == 1
                && inputs.subList(1, 4).stream().allMatch(field -> field.getText().isEmpty()));
        inputs.get(4).setText("3".repeat(8));
        inputs.get(5).setText(pin);
        inputs.get(6).setText(different);
        panel.pressUnblock();
        check("mismatched confirmation never submits unblock", submissions[0] == 1);
        inputs.get(6).setText(pin);
        check("editing unblock confirmation clears mismatch",
                !hasText(panel.node(), "PIN nhập lại chưa khớp. Nhập lại PIN mới.")
                        && !hasText(panel.node(), "PIN chưa khớp."));
        inputs.get(6).setText(different);
        panel.pressUnblock();
        inputs.get(5).setText(different);
        check("editing unblock PIN clears mismatch",
                !hasText(panel.node(), "PIN nhập lại chưa khớp. Nhập lại PIN mới.")
                        && !hasText(panel.node(), "PIN chưa khớp."));
        panel.pressUnblock();
        check("matching confirmation clears unblock inputs", submissions[0] == 2
                && inputs.subList(4, 7).stream().allMatch(field -> field.getText().isEmpty()));
        for (int width : new int[] {420, 820}) {
            verifyFieldErrors(panel.node(), width, panel::pressChange);
        }
    }

    private static void photoDraftFeedback() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("labaccess-photo-");
        java.nio.file.Path valid = directory.resolve("valid.png");
        java.nio.file.Path invalid = directory.resolve("invalid.png");
        try {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                    java.awt.image.BufferedImage.TYPE_INT_RGB), "png", valid.toFile());
            java.nio.file.Files.writeString(invalid, "not an image");
            PhotoPanel panel = new PhotoPanel(new CardGateway("127.0.0.1", 1),
                    (operation, work, callback) -> {
                        throw new AssertionError("Selecting a photo must not start card I/O");
                    }, ignored -> { });
            panel.setEnabled(true, null);
            check("invalid initial photo shows nearby error", !panel.loadDraft(invalid)
                    && !panel.hasDraft()
                    && hasText(panel.node(), "Không đọc được ảnh. Chọn tệp ảnh khác."));
            check("valid photo clears selection error", panel.loadDraft(valid)
                    && !hasText(panel.node(), "Không đọc được ảnh. Chọn tệp ảnh khác."));
            byte[] previous = panel.draftBytes();
            String status = panel.statusText();
            check("invalid replacement preserves draft and its description", !panel.loadDraft(invalid)
                    && java.util.Arrays.equals(previous, panel.draftBytes())
                    && status.equals(panel.statusText())
                    && hasText(panel.node(), "Không đọc được ảnh mới. Đang giữ ảnh nháp trước đó."));
            for (int width : new int[] {420, 820}) {
                verifyFieldErrors(panel.node(), width, () -> panel.loadDraft(invalid));
            }
            check("valid replacement clears its error", panel.loadDraft(valid)
                    && !hasText(panel.node(), "Không đọc được ảnh mới. Đang giữ ảnh nháp trước đó."));
            panel.loadDraft(invalid);
            panel.resetView();
            check("reset clears draft selection errors", !panel.hasDraft()
                    && panel.node().lookupAll(".la-field-error").stream()
                            .noneMatch(javafx.scene.Node::isVisible));
        } finally {
            java.nio.file.Files.deleteIfExists(valid);
            java.nio.file.Files.deleteIfExists(invalid);
            java.nio.file.Files.delete(directory);
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
        Label value = new Label(UiLabels.EMPTY_VALUE);
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

    private static void uiReviewFixes() {
        VBox field = UiRows.field("Nhãn", new javafx.scene.control.TextField());
        Label empty = (Label) field.getChildren().getLast();
        check("empty field error reserves no space", !empty.isManaged() && !empty.isVisible());

        Label longError = UiLabels.fieldError();
        UiLabels.setFieldError(longError, "Mã sinh viên phải 8–10 ký tự chữ A–Z hoặc số 0–9.");
        check("field error wraps inside the field instead of widening its column",
                longError.isWrapText() && longError.getMinWidth() == 0
                        && longError.getPrefWidth() == 0);
        check("long field error moves to the panel note", UiLabels
                .longFieldError("Mã sinh viên phải 8–10 ký tự chữ A–Z hoặc số 0–9.") != null
                && UiLabels.longFieldError("mã thẻ không được để trống") == null);

        Label value = UiLabels.value();
        value.setText(CARD_ID);
        Button copy = UiLabels.copyButton(value, "mã thẻ");
        check("copy action is a compact icon", (copy.getText() == null || copy.getText().isEmpty())
                && copy.getGraphic() != null);
        check("copy icon keeps an accessible name",
                "Sao chép mã thẻ".equals(copy.getAccessibleText()));

        StatusBar bar = new StatusBar(index -> { });
        javafx.scene.Node link = bar.node().lookup("#next-step");
        check("next step is not stretched",
                javafx.scene.layout.HBox.getHgrow(link) != javafx.scene.layout.Priority.ALWAYS);
        check("status bar has no spacer", bar.node().getChildren().size() == 2);

        check("session-level reason is not repeated on every panel",
                SessionGates.panelNote("Chưa kết nối thẻ.") == null
                        && SessionGates.panelNote("Chưa nhận diện thẻ.") == null
                        && SessionGates.panelNote(App.BUSY_NOTE) == null);
        check("panel-specific reason stays on the panel",
                "Thẻ chưa phát hành — phát hành thẻ trước khi đăng ký."
                        .equals(SessionGates.panelNote(
                                "Thẻ chưa phát hành — phát hành thẻ trước khi đăng ký.")));

        EnrollmentPanel enrollment = new EnrollmentPanel(new CardGateway("127.0.0.1", 1),
                new EnrollmentRunner() {
                    @Override
                    public <T> void run(String label, Callable<T> operation,
                            Consumer<T> onSuccess, Consumer<Exception> onFailure) {
                        throw new AssertionError("Locked form must not start enrollment");
                    }
                }, new BackendClient("http://127.0.0.1:1"), ignored -> { }, (kind, message) -> { });
        enrollment.setEnabled(false, null);
        javafx.scene.control.TextField studentId = (javafx.scene.control.TextField)
                enrollment.node().lookupAll(".text-field").iterator().next();
        check("locked student id drops the example placeholder", studentId.getPromptText() == null);
        Label details = (Label) enrollment.node().lookupAll(".la-caption").stream()
                .filter(node -> "Thời điểm đăng ký".equals(((Label) node).getText()))
                .findFirst().orElseThrow();
        boolean hidden = false;
        for (javafx.scene.Node node = details; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                hidden = true;
                break;
            }
        }
        check("unknown registration details stay hidden", hidden);
    }

    private static void check(String name, boolean passed) {
        if (!passed) {
            throw new AssertionError(name);
        }
        checks++;
        System.out.println("PASS " + name);
    }
}
