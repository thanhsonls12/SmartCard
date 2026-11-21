package vn.labaccess.desktop;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.stage.Window;

public final class PhotoUploadComponentVerify {

    private static int checks;

    private PhotoUploadComponentVerify() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("labaccess-photo-upload-");
        Path image = directory.resolve("draft.png");
        Path invalid = directory.resolve("invalid.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Files.writeString(invalid, "not an image");
        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            fx(() -> {
                selectionAndCleanup(image, invalid);
                rejectedAndCancelled(image);
                return null;
            });
            uploadCompletion(image);
            System.out.println("PHOTO UPLOAD COMPONENT PASS: " + checks + " checks");
        } finally {
            Platform.exit();
            Files.deleteIfExists(image);
            Files.deleteIfExists(invalid);
            Files.delete(directory);
        }
    }

    private static void selectionAndCleanup(Path image, Path invalid) {
        Submission submission = new Submission();
        PhotoUploadPanel panel = new PhotoUploadPanel(new CardGateway(new PhotoCard()), submission,
                ignored -> { });
        panel.setEnabled(true, null);
        check("standalone owns only Admin PIN and no Student read controls",
                nodes(panel.node(), PasswordField.class).size() == 1
                        && nodes(panel.node(), Button.class).stream().noneMatch(b -> b.getText().equals("Đọc ảnh"))
                        && !text(panel.node()).contains("PIN học viên"));
        check("no upload before a draft", !panel.pressUpload() && submission.work == null);
        check("invalid initial file explains selection failure", !panel.loadDraft(invalid)
                && !panel.hasDraft() && text(panel.node()).contains("Không đọc được ảnh. Chọn tệp ảnh khác."));
        check("valid draft uses existing conversion", panel.loadDraft(image)
                && Arrays.equals(panel.draftBytes(), converted(image)));
        byte[] previous = panel.draftBytes();
        check("invalid replacement retains previous draft", !panel.loadDraft(invalid)
                && Arrays.equals(previous, panel.draftBytes())
                && text(panel.node()).contains("Đang giữ ảnh nháp trước đó."));
        byte[] returned = panel.draftBytes();
        returned[0] ^= 1;
        check("draft bytes are defensive copies", Arrays.equals(previous, panel.draftBytes()));
        panel.fillAdminPin(new char[] {'x'});
        panel.pressUpload();
        check("invalid Admin PIN does not reach runner", submission.work == null
                && nodes(panel.node(), Label.class).stream().anyMatch(label -> label.isVisible()
                        && label.getText().equals(PersonalizeRequest.adminPinProblem() + ".")));
        panel.clearInputs();
        check("input cleanup clears PIN and validation", nodes(panel.node(), PasswordField.class).stream()
                .allMatch(field -> field.getText().isEmpty())
                && !text(panel.node()).contains(PersonalizeRequest.adminPinProblem()));
        panel.onCard("aabb");
        panel.loadDraft(image);
        panel.onCard(" AABB ");
        check("same normalized card preserves draft", panel.hasDraft());
        panel.onCard("ccdd");
        check("changed card clears draft and errors", !panel.hasDraft()
                && nodes(panel.node(), Label.class).stream().noneMatch(label -> label.isVisible()
                        && label.getStyleClass().contains("la-field-error")));
        panel.setEnabled(false, "chưa kết nối thẻ");
        check("session gate disables choose and upload", !panel.pressChoose() && !panel.pressUpload());

        PhotoPanel legacy = new PhotoPanel(new CardGateway(new PhotoCard()), submission, ignored -> { });
        check("legacy retains both photo responsibilities", nodes(legacy.node(), PasswordField.class).size() == 2
                && text(legacy.node()).contains("Ghi ảnh lên thẻ")
                && text(legacy.node()).contains("Đọc ảnh từ thẻ")
                && legacy.statusText().equals("Chưa chọn ảnh | Chưa đọc ảnh từ thẻ — chưa đọc ảnh từ thẻ"));
    }

    private static void rejectedAndCancelled(Path image) {
        Submission submission = new Submission();
        PhotoUploadPanel panel = new PhotoUploadPanel(new CardGateway(new PhotoCard()), submission,
                ignored -> { });
        panel.setEnabled(true, null);
        panel.loadDraft(image);
        char[] pin = randomPin();
        try {
            panel.fillAdminPin(pin);
            answerConfirmation(false);
            panel.pressUpload();
            check("cancel does not submit or clear editable PIN", submission.work == null
                    && !nodes(panel.node(), PasswordField.class).get(0).getText().isEmpty());
            submission.accepted = false;
            answerConfirmation(true);
            panel.pressUpload();
            check("rejected runner clears input and captured PIN", submission.work != null
                    && allZero(submission.pin)
                    && nodes(panel.node(), PasswordField.class).get(0).getText().isEmpty());
            submission.throwOnSubmit = true;
            panel.fillAdminPin(pin);
            answerConfirmation(true);
            try {
                panel.pressUpload();
                throw new AssertionError("expected runner rejection exception");
            } catch (IllegalStateException expected) {
                check("throwing runner also wipes captured PIN", allZero(submission.pin));
            }
        } finally {
            Arrays.fill(pin, '\0');
            panel.clearInputs();
        }
    }

    private static void uploadCompletion(Path image) throws Exception {
        PhotoCard card = new PhotoCard();
        Submission submission = new Submission();
        PhotoUploadPanel panel = fx(() -> {
            PhotoUploadPanel value = new PhotoUploadPanel(new CardGateway(card), submission, ignored -> { });
            value.onCard("aabb");
            value.setEnabled(true, null);
            value.loadDraft(image);
            submit(value);
            return value;
        });
        CardOperationResult result = submission.work.call();
        check("upload uses existing gateway write protocol without Student VERIFY", result.ok()
                && card.commands.size() == 34 && card.commands.get(0) == 0x32
                && card.commands.get(33) == 0x35
                && card.commands.subList(1, 33).stream().allMatch(ins -> ins == 0x33)
                && Arrays.equals(card.image, converted(image)) && allZero(submission.pin));
        fx(() -> {
            submission.finished.accept(result.ok());
            check("success feedback locks second upload without Student instructions", !panel.pressUpload()
                    && text(panel.node()).contains("Đã ghi ảnh lên thẻ.")
                    && !text(panel.node()).contains("PIN học viên"));
            panel.onCard("ccdd");
            panel.loadDraft(image);
            submit(panel);
            panel.onCard("eeff");
            submission.finished.accept(true);
            check("late completion cannot restore old card draft or success", !panel.hasDraft()
                    && !panel.state().cardHasPhoto() && !text(panel.node()).contains("Đã ghi ảnh lên thẻ."));
            return null;
        });
        submission.work.call();
        check("late worker still wipes captured PIN", allZero(submission.pin));
        fx(() -> {
            panel.loadDraft(image);
            submit(panel);
            return null;
        });
        card.failTransport = true;
        try {
            submission.work.call();
            throw new AssertionError("expected transport failure");
        } catch (CardException expected) {
            check("worker exception wipes captured PIN", allZero(submission.pin));
        }
        fx(() -> {
            panel.setEnabled(false, "chưa kết nối thẻ");
            submission.finished.accept(false);
            check("failure stays gated and does not direct Staff to Student PIN", !panel.pressUpload()
                    && !panel.pressChoose() && text(panel.node()).contains("Không tự động ghi lại.")
                    && !text(panel.node()).contains("PIN học viên"));
            return null;
        });
    }

    private static void submit(PhotoUploadPanel panel) {
        char[] pin = randomPin();
        try {
            panel.fillAdminPin(pin);
            answerConfirmation(true);
            check("enabled upload action fires", panel.pressUpload());
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    private static void answerConfirmation(boolean confirm) {
        Platform.runLater(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane pane) {
                    var type = pane.getButtonTypes().stream().filter(button -> button.getButtonData()
                            == (confirm ? ButtonBar.ButtonData.OK_DONE : ButtonBar.ButtonData.CANCEL_CLOSE))
                            .findFirst().orElseThrow();
                    ((Button) pane.lookupButton(type)).fire();
                    return;
                }
            }
            throw new AssertionError("missing photo confirmation dialog");
        });
    }

    private static byte[] converted(Path image) {
        try {
            return PhotoDraft.fromFile(image);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static char[] randomPin() {
        char[] pin = new char[PersonalizeRequest.ADMIN_PIN_LENGTH];
        SecureRandom random = new SecureRandom();
        for (int index = 0; index < pin.length; index++) {
            pin[index] = (char) ('0' + random.nextInt(10));
        }
        return pin;
    }

    private static boolean allZero(char[] value) {
        if (value == null) {
            return false;
        }
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }

    private static String text(Parent parent) {
        return String.join(" ", nodes(parent, Label.class).stream()
                .filter(Label::isVisible).map(Label::getText).toList());
    }

    private static <T extends Node> List<T> nodes(Parent parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) {
                result.add(type.cast(child));
            }
            if (child instanceof Parent nested) {
                result.addAll(nodes(nested, type));
            }
        }
        return result;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    private static void check(String label, boolean ok) {
        if (!ok) {
            throw new AssertionError(label);
        }
        checks++;
        System.out.println("PASS " + label);
    }

    private static final class Submission implements CardOpRunner {
        private Callable<CardOperationResult> work;
        private Consumer<Boolean> finished;
        private char[] pin;
        private boolean accepted = true;
        private boolean throwOnSubmit;

        @Override
        public boolean run(String label, Callable<CardOperationResult> operation, Consumer<Boolean> callback) {
            work = operation;
            finished = callback;
            try {
                for (Field field : operation.getClass().getDeclaredFields()) {
                    if (field.getType() == char[].class) {
                        field.setAccessible(true);
                        pin = (char[]) field.get(operation);
                    }
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            if (throwOnSubmit) {
                throw new IllegalStateException("fixture rejected submission");
            }
            return accepted;
        }
    }

    private static final class PhotoCard extends CardChannel {
        private final byte[] image = new byte[PhotoImage.LENGTH];
        private final List<Integer> commands = new ArrayList<>();
        private boolean failTransport;

        @Override
        public ResponseAPDU transmit(CommandAPDU command) throws CardException {
            if (failTransport) {
                throw new CardException("fixture transport failure");
            }
            int ins = command.getINS();
            commands.add(ins);
            if (ins == 0x33) {
                byte[] chunk = command.getData();
                System.arraycopy(chunk, 0, image, (command.getP1() << 8) | command.getP2(), chunk.length);
            } else if (ins != 0x32 && ins != 0x35) {
                throw new AssertionError("unexpected instruction " + ins);
            }
            return new ResponseAPDU(new byte[] {(byte) 0x90, 0});
        }

        @Override
        public Card getCard() {
            return null;
        }

        @Override
        public int getChannelNumber() {
            return 0;
        }

        @Override
        public int transmit(ByteBuffer command, ByteBuffer response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
