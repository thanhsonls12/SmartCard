package vn.labaccess.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import vn.labaccess.desktop.ui.foundation.UiTheme;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.PersonalizeRequest;
import vn.labaccess.desktop.card.Sw;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.ui.shared.UiLabels;

import vn.labaccess.desktop.ui.enrollment.PersonalizePanel;

public final class PersonalizeSelfTest {

    private static final String CARD_ID = "A1B2C3D411223344";
    private static final String STUDENT_ID = "CT2024008";
    private static final String CLASS_CODE = "CNTT1";
    private static final String DISPLAY_NAME = "Nguyễn Văn Á";
    private static final char[] ADMIN_PIN = "12345678".toCharArray();
    private static final char[] STUDENT_PIN = "654321".toCharArray();
    private static final int INS_PERSONALIZE = 0x30;
    private static final int HEX_CARD_ID_LENGTH = PersonalizeRequest.CARD_ID_LENGTH * 2;
    private static final String CARD_ID_HEX_PATTERN = "[0-9A-F]{" + HEX_CARD_ID_LENGTH + "}";
    private static final String UNREACHABLE_BACKEND = "http://127.0.0.1:1";
    private static final String NOT_CHECKED_TEXT = "Chưa kiểm được trùng với Backend nên chưa ghi thẻ";
    private static final int GENERATED_SAMPLE = 200;

    private static int checks;
    private static int failures;
    private static volatile boolean clickerActive = true;

    private PersonalizeSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        generatedCardIds();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                UiTheme.apply(new javafx.scene.Scene(new VBox()));
                startDialogClicker();
                prefillsCardId();
                refusesEnrolledCardId();
                refusesUncheckedBackend();
                issuesWhenCardIdIsFree();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        done.await();
        clickerActive = false;
        Platform.exit();
        if (failure.get() != null) {
            failure.get().printStackTrace();
            checks++;
            failures++;
        }
        System.out.println("PERSONALIZE SELF TEST " + (failures == 0 ? "PASS" : "FAIL") + ": " + checks
                + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void generatedCardIds() {
        String generated = PersonalizeRequest.randomCardId();
        check("PZ01", "mã sinh ra đúng " + HEX_CARD_ID_LENGTH + " ký tự hex in hoa",
                generated.matches(CARD_ID_HEX_PATTERN), "mã=" + generated);
        check("PZ02", "mã sinh ra qua đúng chỗ kiểm hợp lệ của màn phát hành",
                PersonalizeRequest.cardIdProblem(generated) == null, "mã=" + generated);

        Set<String> distinct = new HashSet<>();
        boolean uppercase = true;
        for (int index = 0; index < GENERATED_SAMPLE; index++) {
            String value = PersonalizeRequest.randomCardId();
            uppercase &= value.matches(CARD_ID_HEX_PATTERN);
            distinct.add(value);
        }
        check("PZ03", "sinh " + GENERATED_SAMPLE + " lần không có cặp trùng",
                uppercase && distinct.size() == GENERATED_SAMPLE,
                "số mã khác nhau=" + distinct.size() + "/" + GENERATED_SAMPLE);
    }

    private static void prefillsCardId() throws Exception {
        Scenario scenario = new Scenario(UNREACHABLE_BACKEND);
        check("PZ12", "mở form khi ô mã thẻ còn trống thì tự điền mã hợp lệ",
                PersonalizeRequest.cardIdProblem(scenario.cardIdText()) == null
                        && scenario.cardIdText().matches(CARD_ID_HEX_PATTERN),
                "mã trong ô=" + scenario.cardIdText());
        scenario.setCardIdText("");
        scenario.panel.resetView();
        check("PZ13", "reset khi ô mã thẻ trống sinh lại mã hợp lệ",
                PersonalizeRequest.cardIdProblem(scenario.cardIdText()) == null
                        && scenario.cardIdText().matches(CARD_ID_HEX_PATTERN),
                "mã trong ô=" + scenario.cardIdText());
    }

    private static void refusesEnrolledCardId() throws Exception {
        try (LedgerStub stub = new LedgerStub(200)) {
            Scenario scenario = new Scenario(stub.baseUrl());
            scenario.fill(CARD_ID);
            scenario.panel.pressPersonalize();
            check("PZ04", "mã đã có trên Backend thì không gửi PERSONALIZE",
                    scenario.personalizeCount() == 0,
                    "APDU 0x30 gửi đi=" + scenario.personalizeCount()
                            + " | INS gửi thẻ=" + scenario.channel.insSequence());
            check("PZ05", "ô mã thẻ báo lỗi kèm hướng dẫn bấm Tạo mã mới",
                    scenario.showsText(UiLabels.CARD_ID_ENROLLED)
                            && scenario.log.contains("[INPUT] Phát hành thẻ: " + UiLabels.CARD_ID_ENROLLED),
                    "nhãn nút=" + UiLabels.NEW_CARD_ID + " | log cuối=" + scenario.lastLog());
            check("PZ06", "không báo phát hành thành công khi mã trùng",
                    !scenario.issued, "issued=" + scenario.issued);
        }
    }

    private static void refusesUncheckedBackend() {
        Scenario scenario = new Scenario(UNREACHABLE_BACKEND);
        scenario.fill(CARD_ID);
        scenario.panel.pressPersonalize();
        check("PZ07", "Backend không gọi được thì không gửi PERSONALIZE",
                scenario.personalizeCount() == 0,
                "APDU 0x30 gửi đi=" + scenario.personalizeCount()
                        + " | INS gửi thẻ=" + scenario.channel.insSequence());
        check("PZ08", "ô mã thẻ nói rõ chưa kiểm được trùng nên chưa ghi thẻ",
                scenario.showsText(NOT_CHECKED_TEXT) && !scenario.issued,
                "log cuối=" + scenario.lastLog());
    }

    private static void issuesWhenCardIdIsFree() throws Exception {
        try (LedgerStub stub = new LedgerStub(404)) {
            Scenario scenario = new Scenario(stub.baseUrl());
            scenario.fill(CARD_ID);
            scenario.panel.pressPersonalize();
            check("PZ09", "mã chưa có trên Backend thì PERSONALIZE được gửi đúng một lần",
                    scenario.personalizeCount() == 1,
                    "APDU 0x30 gửi đi=" + scenario.personalizeCount()
                            + " | INS gửi thẻ=" + scenario.channel.insSequence());
            check("PZ10", "ô mã thẻ không báo lỗi khi mã chưa tồn tại",
                    !scenario.showsText(UiLabels.CARD_ID_ENROLLED) && !scenario.showsText(NOT_CHECKED_TEXT),
                    "log cuối=" + scenario.lastLog());
            byte[] sent = scenario.channel.commands.stream()
                    .filter(command -> command.getINS() == INS_PERSONALIZE).findFirst().orElseThrow().getData();
            int extensionOffset = 25 + STUDENT_ID.length()
                    + DISPLAY_NAME.getBytes(StandardCharsets.UTF_8).length;
            byte[] expectedClass = CLASS_CODE.getBytes(StandardCharsets.US_ASCII);
            byte[] expectedMajor = "Công nghệ thông tin".getBytes(StandardCharsets.UTF_8);
            check("PZ14", "PERSONALIZE từ UI gửi extension lớp và ngành schema v2",
                    sent[extensionOffset] == expectedClass.length
                            && java.util.Arrays.equals(expectedClass,
                                    java.util.Arrays.copyOfRange(sent, extensionOffset + 1,
                                            extensionOffset + 1 + expectedClass.length))
                            && sent[extensionOffset + 1 + expectedClass.length] == expectedMajor.length
                            && java.util.Arrays.equals(expectedMajor,
                                    java.util.Arrays.copyOfRange(sent,
                                            extensionOffset + 2 + expectedClass.length, sent.length)),
                    "extension lớp/ngành được giữ nguyên trong APDU");
            String before = scenario.cardIdText();
            scenario.panel.pressNewCardId();
            String after = scenario.cardIdText();
            check("PZ11", "nút Tạo mã mới sinh mã hợp lệ khác mã đang nhập",
                    !before.equals(after) && PersonalizeRequest.cardIdProblem(after) == null
                            && after.matches(CARD_ID_HEX_PATTERN),
                    "trước=" + before + " | sau=" + after);
        }
    }

    private static void startDialogClicker() {
        Thread clicker = new Thread(() -> {
            while (clickerActive) {
                Platform.runLater(() -> {
                    for (Window window : List.copyOf(Window.getWindows())) {
                        if (!window.isShowing() || window.getScene() == null
                                || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                            continue;
                        }
                        pane.getButtonTypes().stream()
                                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                                .findFirst()
                                .ifPresent(type -> ((Button) pane.lookupButton(type)).fire());
                    }
                });
                try {
                    Thread.sleep(40);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "personalize-dialog-clicker");
        clicker.setDaemon(true);
        clicker.start();
    }

    private static void check(String code, String name, boolean passed, String detail) {
        checks++;
        if (!passed) {
            failures++;
        }
        System.out.println((passed ? "PASS " : "FAIL ") + code + " " + name + " | " + detail);
    }

    private static boolean hasText(Parent parent, String text) {
        return parent.getChildrenUnmodifiable().stream().anyMatch(node -> node.isVisible()
                && (node instanceof Label label && label.getText().contains(text)
                || node instanceof Parent child && hasText(child, text)));
    }

    private static TextField cardIdField(PersonalizePanel panel) throws ReflectiveOperationException {
        Field field = PersonalizePanel.class.getDeclaredField("fieldCardId");
        field.setAccessible(true);
        return (TextField) field.get(panel);
    }

    private static final class Scenario {

        private final FakeChannel channel = new FakeChannel();
        private final List<String> log = new ArrayList<>();
        private final PersonalizePanel panel;
        private boolean issued;

        Scenario(String baseUrl) {
            CardGateway gateway = new CardGateway(channel);
            panel = new PersonalizePanel(gateway, (label, work, callback) -> {
                boolean ok;
                try {
                    ok = work.call().ok();
                } catch (Exception failure) {
                    log.add("[ERROR] " + label + ": " + failure);
                    ok = false;
                }
                callback.accept(ok);
                return true;
            }, new BackendClient(baseUrl), log::add, (ok, card) -> issued = ok);
            panel.updateAvailability(true, false, null);
        }

        void fill(String cardId) {
            panel.fillInputs(cardId, STUDENT_ID, DISPLAY_NAME, CLASS_CODE, ADMIN_PIN.clone(), STUDENT_PIN.clone());
        }

        int personalizeCount() {
            return (int) channel.insSequence().stream().filter(ins -> ins == INS_PERSONALIZE).count();
        }

        boolean showsText(String text) {
            return hasText(panel.node(), text);
        }

        String cardIdText() throws ReflectiveOperationException {
            return cardIdField(panel).getText();
        }

        void setCardIdText(String value) throws ReflectiveOperationException {
            cardIdField(panel).setText(value);
        }

        String lastLog() {
            return log.isEmpty() ? "<rỗng>" : log.get(log.size() - 1);
        }
    }

    private static final class FakeChannel extends CardChannel {

        private final List<CommandAPDU> commands = new ArrayList<>();

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
            return new ResponseAPDU(new byte[] {(byte) ((Sw.OK >> 8) & 0xFF), (byte) (Sw.OK & 0xFF)});
        }

        @Override
        public void close() {
        }

        List<Integer> insSequence() {
            return commands.stream().map(command -> command.getINS() & 0xFF).toList();
        }
    }

    private static final class LedgerStub implements AutoCloseable {

        private final ServerSocket server;
        private final int status;

        LedgerStub(int status) throws IOException {
            this.status = status;
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::serve, "personalize-ledger-stub");
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    String cardId = cardIdOf(requestLine(socket.getInputStream()));
                    byte[] body = (status == 200
                            ? "{\"cardId\":\"" + cardId
                                    + "\",\"lastCounter\":0,\"lastBalance\":0,\"activeVisitId\":null}"
                            : "{\"message\":\"cardId has no ledger row\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    OutputStream out = socket.getOutputStream();
                    out.write(head.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                } catch (IOException stopped) {
                    return;
                }
            }
        }

        private static String requestLine(InputStream input) throws IOException {
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                head.append((char) value);
            }
            return head.toString().split("\r\n", 2)[0];
        }

        private static String cardIdOf(String requestLine) {
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "";
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(slash + 1);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
