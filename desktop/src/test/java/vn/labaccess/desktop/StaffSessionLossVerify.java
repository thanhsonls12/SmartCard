package vn.labaccess.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

public final class StaffSessionLossVerify {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path evidence = Path.of(args[0]);
        Files.createDirectories(evidence);
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            verify(evidence, false);
            verify(evidence, true);
            System.out.println("STAFF SESSION LOSS " + (failures == 0 ? "PASS" : "FAIL")
                    + ": " + checks + " checks, " + failures + " failures");
            if (failures != 0) throw new AssertionError("session loss assertions failed: " + failures);
        } finally {
            Platform.exit();
        }
    }

    private static void verify(Path evidence, boolean failRefresh) throws Exception {
        String mode = failRefresh ? "ack-refresh-failure" : "unblock-transport-failure";
        Path temporary = Files.createTempDirectory("staff-session-loss-");
        Path journal = temporary.resolve("staff-journal.txt");
        String oldJournal = System.getProperty("labaccess.journal");
        String oldBackend = System.getProperty("labaccess.backend");
        char[] admin = RecoveryCardFixture.pin(8);
        char[] student = RecoveryCardFixture.pin(6);
        StaffApp[] app = {null};
        Stage[] stage = {null};
        ExecutorService[] executor = {null};
        try (Fixture fixture = new Fixture(failRefresh);
                RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply)) {
            System.setProperty("labaccess.journal", journal.toString());
            System.setProperty("labaccess.backend", backend.url());
            StaffWorkspace workspace = fx(() -> {
                app[0] = new StaffApp();
                stage[0] = new Stage();
                app[0].start(stage[0]);
                CardSession session = (CardSession) field(app[0], "session");
                Object transport = field(session.gateway(), "transport");
                set(transport, "channel", fixture);
                set(transport, "connectedFlag", true);
                StaffWorkspace result = (StaffWorkspace) field(app[0], "workspace");
                executor[0] = (ExecutorService) field(field(app[0], "tasks"), "executor");
                result.refresh();
                ((Button) field(result, "identify")).fire();
                return result;
            });
            idle(executor[0]);
            CardSession session = (CardSession) field(app[0], "session");
            GrantPanel grants = (GrantPanel) field(workspace, "grants");
            ReceiptPanel receipts = (ReceiptPanel) field(workspace, "receipts");
            fx(() -> {
                grants.fillInputs(admin, 5);
                check(mode + " GRANT button accepts Admin PIN", grants.press());
                return null;
            });
            idle(executor[0]);
            if (!failRefresh) {
                fx(() -> {
                    check(mode + " GRANT is ACKed and receipt visible", fixture.posts == 1
                            && !Files.exists(journal) && labels(receipts.node()).contains("Chữ ký hợp lệ"));
                    grants.fillInputs(admin, 5);
                    ((PhotoUploadPanel) field(workspace, "photos")).fillAdminPin(admin);
                    ((EnrollmentPanel) field(workspace, "enrollment")).fillInputs("LOSS0001", student);
                    StudentPinUnblockPanel unblock = (StudentPinUnblockPanel) field(workspace, "unblock");
                    unblock.fillAdminPin(admin);
                    unblock.fillNewPin(student);
                    unblock.fillConfirmation(student);
                    check(mode + " PIN remains in the separate Grant form", !fields((Parent) grants.actionsNode()).getFirst().getText().isEmpty());
                    fixture.failUnblock = true;
                    check(mode + " Unblock button starts the failing APDU", unblock.pressUnblock());
                    return null;
                });
                idle(executor[0]);
            }
            fx(() -> {
                boolean receiptCleared = labels(receipts.node()).contains("Chưa đọc biên nhận từ thẻ")
                        && !labels(receipts.node()).contains("Chữ ký hợp lệ");
                boolean pinsCleared = fields(workspace.node()).stream().allMatch(input -> input.getText().isEmpty());
                check(mode + " reports reconnect before any Disconnect click", session.gateway().needsReconnect()
                        && !session.gateway().isReady() && ((Label) field(workspace, "connection")).getText().equals("Cần kết nối lại"));
                check(mode + " old receipt is hidden", receiptCleared);
                check(mode + " every PIN input is empty", pinsCleared);
                check(mode + " mutations are locked", !grants.press()
                        && !((StudentPinUnblockPanel) field(workspace, "unblock")).pressUnblock()
                        && !((PersonalizePanel) field(workspace, "personalize")).pressPersonalize()
                        && !((PhotoUploadPanel) field(workspace, "photos")).pressUpload());
                check(mode + " Disconnect has not reset the connection", !((Button) field(workspace, "disconnect")).isDisabled()
                        && fixture.ins.stream().filter(ins -> ins == 0x50).count() == 1);
                check(mode + " ACKed journal remains absent", !Files.exists(journal) && fixture.posts == 1);
                if (failRefresh) {
                    check(mode + " retains ACKed GRANT reread warning", ((Label) field(workspace, "quota")).getText().equals("Đã cấp — cần đọc lại")
                            && ((Label) field(workspace, "feedback")).getText().contains("Không thực hiện lại GRANT"));
                } else {
                    check(mode + " only Unblock triggers the transport failure", fixture.ins.getLast() == 0x23);
                }
                var snapshot = JSON.createObjectNode().put("case", mode).put("beforeDisconnect", true)
                        .put("connection", ((Label) field(workspace, "connection")).getText())
                        .put("receiptCleared", receiptCleared).put("pinInputsCleared", pinsCleared)
                        .put("journalPresent", Files.exists(journal)).put("receiptPosts", fixture.posts)
                        .put("quota", ((Label) field(workspace, "quota")).getText())
                        .put("feedback", ((Label) field(workspace, "feedback")).getText());
                snapshot.set("ins", JSON.valueToTree(fixture.ins));
                JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(mode + ".json").toFile(), snapshot);
                capture(stage[0].getScene().getRoot(), evidence.resolve(mode + ".png"));
                PendingJournal pending = new PendingJournal(journal);
                check(mode + " pending fixture created", pending.writePending(new PendingJournal.Entry(RecoveryCardFixture.CARD_ID,
                        2, Receipt.OPERATION_GRANT, 5, 10, null, 1)));
                byte[] contents = Files.readAllBytes(journal);
                set(workspace, "shownCard", RecoveryCardFixture.CARD_ID);
                workspace.synchronizeConnection();
                workspace.refresh();
                check(mode + " session cleanup preserves pending bytes", Arrays.equals(contents, Files.readAllBytes(journal)));
                return null;
            });
        } finally {
            if (executor[0] != null) {
                executor[0].submit(() -> {
                    try {
                        ((CardSession) field(app[0], "session")).disconnect();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }).get(10, TimeUnit.SECONDS);
                executor[0].shutdownNow();
            }
            if (stage[0] != null) fx(() -> { stage[0].hide(); return null; });
            Arrays.fill(admin, '\0');
            Arrays.fill(student, '\0');
            restore("labaccess.journal", oldJournal);
            restore("labaccess.backend", oldBackend);
            Files.deleteIfExists(journal);
            Files.delete(temporary);
        }
    }

    private static void capture(Parent root, Path file) throws Exception {
        var image = root.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var buffered = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        javax.imageio.ImageIO.write(buffered, "png", file.toFile());
    }

    private static List<String> labels(Parent parent) {
        List<String> result = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Label label) result.add(label.getText());
            else if (node instanceof Parent nested) result.addAll(labels(nested));
        }
        return result;
    }

    private static List<PasswordField> fields(Parent parent) {
        List<PasswordField> result = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof PasswordField input) result.add(input);
            else if (node instanceof Parent nested) result.addAll(fields(nested));
        }
        return result;
    }

    private static void idle(ExecutorService executor) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        executor.submit(() -> Platform.runLater(done::countDown));
        if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("worker/FX completion timeout");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static void check(String label, boolean passed) {
        checks++;
        if (!passed) failures++;
        System.out.println((passed ? "PASS " : "FAIL ") + label);
    }

    private static final class Fixture extends CardChannel implements AutoCloseable {
        private final RecoveryCardFixture card = new RecoveryCardFixture();
        private final boolean failRefresh;
        private boolean receiptRead;
        private boolean failUnblock;
        private int posts;
        private final List<Integer> ins = new ArrayList<>();

        Fixture(boolean failRefresh) throws Exception {
            this.failRefresh = failRefresh;
            card.counter = 0;
            card.balance = 0;
        }

        @Override
        public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            int instruction = command.getINS();
            ins.add(instruction);
            if (instruction == 0x23 && failUnblock || instruction == 0x11 && receiptRead && failRefresh) {
                throw new CardException("fixture APDU transport failure");
            }
            if (instruction == 0x11) {
                byte[] status = ByteBuffer.allocate(50).put("LABACCESS".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                        .put((byte) 1).put((byte) 7).put(RecoveryCardFixture.AID).put((byte) CardLifecycle.ISSUED)
                        .put(HexFormat.of().parseHex(card.cardId)).putShort((short) card.balance).putInt(card.counter)
                        .put((byte) 0).put(new byte[16]).array();
                return RecoveryCardFixture.response(status, Sw.OK);
            }
            if (instruction == 0x63) {
                receiptRead = true;
                return RecoveryCardFixture.response(card.receiptBytes(), Sw.OK);
            }
            return card.transmit(command);
        }

        RecoveryBackendFixture.Reply reply(String request, String body) throws java.io.IOException {
            String route = request.split(" ")[1];
            var result = JSON.createObjectNode().put("cardId", card.cardId);
            if (route.startsWith("/enrollments/cards/")) {
                result.put("publicKey", HexFormat.of().formatHex(ReceiptVerifier.encodePoint(card.keys.getPublic())));
            } else if (route.equals("/receipts/verify")) {
                posts++;
                result.put("status", "recorded").put("counterAfter", card.counter).put("operation", "GRANT")
                        .put("delta", 5).put("balanceAfter", card.balance).putNull("visitId");
            } else if (route.startsWith("/receipts/ledger/")) {
                result.put("lastCounter", posts == 0 ? 0 : card.counter).put("lastBalance", posts == 0 ? 0 : card.balance)
                        .putNull("activeVisitId");
            } else if (route.endsWith("/transactions")) {
                result.putArray("transactions").addObject().put("id", "11111111-1111-4111-8111-111111111111")
                        .put("counterAfter", card.counter).put("operation", "GRANT").put("delta", 5)
                        .put("balanceAfter", card.balance).put("verifyStatus", "verified").put("recordedAt", "2026-11-20T08:00:00Z");
            } else {
                return new RecoveryBackendFixture.Reply(404, "{}");
            }
            return new RecoveryBackendFixture.Reply(route.equals("/receipts/verify") ? 201 : 200, result.toString());
        }

        @Override public Card getCard() { return null; }
        @Override public int getChannelNumber() { return 0; }
        @Override public int transmit(ByteBuffer command, ByteBuffer response) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}
