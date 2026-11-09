package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

final class DemoScenario {

    private final App app;
    private final PinPanel pinPanel;
    private final PersonalizePanel personalizePanel;
    private final Consumer<String> log;

    private Timeline timeline;
    private char[] demoAdminPin;
    private char[] demoStudentPin;
    private char[] demoChangedPin;
    private char[] demoUnblockedPin;
    private char[] demoLockedPin;

    DemoScenario(App app, PinPanel pinPanel, PersonalizePanel personalizePanel,
            Consumer<String> log) {
        this.app = app;
        this.pinPanel = pinPanel;
        this.personalizePanel = personalizePanel;
        this.log = log;
    }

    void start() {
        log.accept("DEMO: tự động bấm nút theo thứ tự (cùng handler với người dùng)");
        if (Boolean.getBoolean("labaccess.demo.personalize")) {
            startPersonalizeDemo();
            return;
        }
        run(new Runnable[] {
                () -> fire(app.pressConnect(), "Connect"),
                () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"),
                () -> personalizeRejected("ZZZZ", "SV2024-001", "Nguyễn Văn Ánh",
                        "cardId không phải hex"),
                () -> personalizeRejected("0000000000000000", "sv2024", "",
                        "cardId toàn 00, studentId chữ thường, tên rỗng"),
                () -> fire(app.pressReadReceipt(), "GET_LAST_RECEIPT"),
                () -> fire(app.pressGrant(), "GRANT_QUOTA (kỳ vọng bị chặn vì thiếu VERIFY)"),
                () -> fire(app.pressDisconnect(), "Disconnect"),
        }, 800, 5000);
    }

    void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
            log.accept("DEMO: đã dừng Timeline");
        }
        wipe();
    }

    private void startPersonalizeDemo() {
        log.accept("DEMO: PERSONALIZE qua handler GUI — PIN đọc từ stdin, không qua -D và không vào log");
        char[] adminPin = readDemoPin("Admin PIN (8 digits): ", 8);
        char[] studentPin = readDemoPin("Student PIN (6 digits): ", 6);
        if (adminPin == null || studentPin == null) {
            log.accept("DEMO: không đọc được PIN đúng độ dài — dừng demo");
            return;
        }
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        char[] wrongStudentPin = shiftDigits(studentPin, 5);
        char[] wrongAdmin = shiftDigits(adminPin, 1);

        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EE01");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024-002");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyễn Văn Bình");
        char[] changedPin = shiftDigits(studentPin, 1);
        char[] unblockedPin = shiftDigits(studentPin, 2);
        char[] lockedPin = shiftDigits(changedPin, 5);
        demoChangedPin = changedPin;
        demoUnblockedPin = unblockedPin;
        demoLockedPin = lockedPin;

        run(new Runnable[] {
                () -> fire(app.pressConnect(), "Connect"),
                () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"),
                () -> fire(personalizePanel.pressPublicKey(),
                        "GET_CARD_PUBLIC_KEY (trước PERSONALIZE — kỳ vọng 6985)"),
                () -> personalize(cardId, studentId, displayName, wrongAdmin, studentPin,
                        "admin PIN sai — kỳ vọng bị từ chối"),
                () -> personalize(cardId, studentId, displayName, adminPin, studentPin,
                        "dữ liệu hợp lệ — kỳ vọng 9000 + ISSUED"),
                () -> fire(personalizePanel.pressPublicKey(),
                        "GET_CARD_PUBLIC_KEY (sau PERSONALIZE)"),
                () -> fire(personalizePanel.pressPublicKey(),
                        "GET_CARD_PUBLIC_KEY (đọc lại — kỳ vọng cùng key)"),
                () -> pinOp(studentPin, "VERIFY PIN hiện tại — kỳ vọng 9000"),
                () -> pinOp(wrongStudentPin, "VERIFY PIN sai — kỳ vọng 63C2"),
                () -> pinOp(studentPin, "VERIFY PIN đúng lại — kỳ vọng 9000"),
                () -> changePin(studentPin, changedPin),
                () -> pinOp(studentPin, "VERIFY PIN cũ sau CHANGE — kỳ vọng 63Cx"),
                () -> pinOp(changedPin, "VERIFY PIN mới — kỳ vọng 9000"),
                () -> pinOp(lockedPin, "VERIFY sai lần 1/3 — kỳ vọng 63C2"),
                () -> pinOp(lockedPin, "VERIFY sai lần 2/3 — kỳ vọng 63C1"),
                () -> pinOp(lockedPin, "VERIFY sai lần 3/3 — kỳ vọng 6983 khóa"),
                () -> pinOp(changedPin, "VERIFY PIN đúng khi đang khóa — kỳ vọng 6983"),
                () -> unblockPin(wrongAdmin, unblockedPin,
                        "UNBLOCK admin PIN sai — kỳ vọng 63Cx"),
                () -> unblockPin(adminPin, unblockedPin,
                        "UNBLOCK admin PIN đúng — kỳ vọng 9000"),
                () -> pinOp(unblockedPin, "VERIFY PIN sau UNBLOCK — kỳ vọng 9000"),
                () -> fire(app.pressDisconnect(), "Disconnect"),
        }, 1200, 4500);
    }

    private void run(Runnable[] steps, long firstDelay, long interval) {
        timeline = new Timeline();
        for (int index = 0; index < steps.length; index++) {
            final Runnable step = steps[index];
            timeline.getKeyFrames().add(new KeyFrame(
                    Duration.millis(firstDelay + index * interval), event -> step.run()));
        }
        timeline.getKeyFrames().add(new KeyFrame(
                Duration.millis(firstDelay + steps.length * interval),
                event -> {
                    log.accept("DEMO: xong");
                    app.refreshButtons();
                }));
        timeline.play();
    }

    private void fire(boolean pressed, String name) {
        if (!pressed) {
            log.accept("DEMO: bỏ qua bước " + name + " (nút đang bị khoá)");
        }
    }

    private void personalize(String cardId, String studentId, String displayName,
            char[] adminPin, char[] studentPin, String expectation) {
        personalizePanel.fillInputs(cardId, studentId, displayName, adminPin, studentPin);
        log.accept("DEMO: PERSONALIZE — " + expectation);
        fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
    }

    private void personalizeRejected(String cardId, String studentId, String name,
            String expectation) {
        personalizePanel.fillInputs(cardId, studentId, name,
                "1".repeat(8).toCharArray(), "1".repeat(6).toCharArray());
        log.accept("DEMO: PERSONALIZE kỳ vọng bị chặn — " + expectation);
        fire(personalizePanel.pressPersonalize(), "PERSONALIZE (input sai)");
    }

    private void pinOp(char[] pin, String expectation) {
        pinPanel.fillInputs(pin, null, null);
        log.accept("DEMO: " + expectation);
        fire(pinPanel.pressVerify(), "VERIFY_STUDENT_PIN");
    }

    private void changePin(char[] oldPin, char[] newPin) {
        pinPanel.fillInputs(oldPin, newPin, null);
        log.accept("DEMO: CHANGE PIN cũ sang PIN mới — kỳ vọng 9000");
        fire(pinPanel.pressChange(), "CHANGE_STUDENT_PIN");
    }

    private void unblockPin(char[] adminPin, char[] newPin, String expectation) {
        pinPanel.fillInputs(null, newPin, adminPin);
        log.accept("DEMO: " + expectation);
        fire(pinPanel.pressUnblock(), "UNBLOCK_STUDENT_PIN");
    }

    private static char[] shiftDigits(char[] pin, int delta) {
        char[] shifted = new char[pin.length];
        for (int index = 0; index < pin.length; index++) {
            shifted[index] = (char) ('0' + (pin[index] - '0' + delta) % 10);
        }
        return shifted;
    }

    private char[] readDemoPin(String prompt, int expectedLength) {
        System.out.print(prompt);
        System.out.flush();
        try {
            StringBuilder line = new StringBuilder();
            int read;
            while ((read = System.in.read()) != -1 && read != '\n') {
                if (read != '\r') {
                    line.append((char) read);
                }
            }
            String value = line.toString().strip();
            if (value.length() != expectedLength) {
                return null;
            }
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                    return null;
                }
            }
            return value.toCharArray();
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private void wipe() {
        if (demoAdminPin != null) {
            Arrays.fill(demoAdminPin, '\0');
            demoAdminPin = null;
        }
        if (demoStudentPin != null) {
            Arrays.fill(demoStudentPin, '\0');
            demoStudentPin = null;
        }
        if (demoChangedPin != null) {
            Arrays.fill(demoChangedPin, '\0');
            demoChangedPin = null;
        }
        if (demoUnblockedPin != null) {
            Arrays.fill(demoUnblockedPin, '\0');
            demoUnblockedPin = null;
        }
        if (demoLockedPin != null) {
            Arrays.fill(demoLockedPin, '\0');
            demoLockedPin = null;
        }
    }
}
