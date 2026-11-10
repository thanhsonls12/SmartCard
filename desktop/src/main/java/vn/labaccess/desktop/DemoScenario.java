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
    private final ProfilePanel profilePanel;
    private final PhotoPanel photoPanel;
    private final Consumer<String> log;

    private Timeline timeline;
    private java.nio.file.Path photoSampleFile;
    private char[] demoAdminPin;
    private char[] demoStudentPin;
    private char[] demoChangedPin;
    private char[] demoUnblockedPin;
    private char[] demoLockedPin;

    DemoScenario(App app, PinPanel pinPanel, PersonalizePanel personalizePanel,
            ProfilePanel profilePanel, PhotoPanel photoPanel, Consumer<String> log) {
        this.app = app;
        this.pinPanel = pinPanel;
        this.personalizePanel = personalizePanel;
        this.profilePanel = profilePanel;
        this.photoPanel = photoPanel;
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
                () -> fire(profilePanel.pressProfile(),
                        "GET_PROFILE (thẻ chưa ISSUED — kỳ vọng nút bị khoá)"),
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
        if (photoSampleFile != null && System.getProperty("labaccess.demo.photo.file", "").isEmpty()) {
            try {
                java.nio.file.Files.deleteIfExists(photoSampleFile);
                log.accept("DEMO: đã xoá ảnh mẫu tạm " + photoSampleFile.getFileName());
            } catch (java.io.IOException exception) {
                log.accept("DEMO: không xoá được ảnh mẫu tạm: " + exception);
            }
            photoSampleFile = null;
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

        java.util.List<Runnable> steps = new java.util.ArrayList<>(java.util.Arrays.asList(
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
                () -> readMeta(studentPin,
                        "GET_PROFILE_CRYPTO_META — VERIFY ngay trước, kỳ vọng 9000 + 65 byte"),
                () -> readProfile(studentPin,
                        "GET_PROFILE — VERIFY ngay trước, kỳ vọng 9000 + UTF-8"),
                () -> readProfile(wrongStudentPin,
                        "GET_PROFILE với PIN sai — kỳ vọng 63C2, xoá kết quả cũ trên UI"),
                () -> readMeta(studentPin,
                        "GET_PROFILE_CRYPTO_META lại để panel có metadata trước ca identify lỗi"),
                () -> readProfile(studentPin,
                        "GET_PROFILE lại để panel có hồ sơ trước ca identify lỗi"),
                () -> identify(
                        "SELECT lại sau khi panel đang hiện hồ sơ — panel phải xoá bất kể kết quả"),
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
                () -> pinOp(unblockedPin, "VERIFY PIN sau UNBLOCK — kỳ vọng 9000")));
        if (Boolean.getBoolean("labaccess.demo.photo")) {
            steps.addAll(photoSteps(adminPin, unblockedPin));
        }
        steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
        if (Boolean.getBoolean("labaccess.demo.photo")) {
            steps.add(this::photoAfterDisconnect);
        }
        run(steps.toArray(new Runnable[0]), 1200, 4500);
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

    private void readProfile(char[] pin, String expectation) {
        profilePanel.fillPin(pin);
        log.accept("DEMO: " + expectation);
        fire(profilePanel.pressProfile(), "GET_PROFILE");
    }

    private void identify(String expectation) {
        log.accept("DEMO: " + expectation);
        fire(app.pressIdentify(), "SELECT + GET_STATUS");
    }

    private void readMeta(char[] pin, String expectation) {
        profilePanel.fillPin(pin);
        log.accept("DEMO: " + expectation);
        fire(profilePanel.pressMeta(), "GET_PROFILE_CRYPTO_META");
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

    private java.util.List<Runnable> photoSteps(char[] adminPin, char[] studentPin) {
        java.nio.file.Path sample = photoSample();
        java.util.List<Runnable> steps = new java.util.ArrayList<>();
        if (sample == null) {
            return steps;
        }
        char[] wrongStudentPin = shiftDigits(studentPin, 5);
        steps.add(() -> {
            log.accept("DEMO: chọn ảnh mẫu bất đối xứng " + sample.getFileName()
                    + " — kỳ vọng preview ảnh nháp hiện 64×64");
            if (!photoPanel.loadDraft(sample)) {
                log.accept("DEMO: không nạp được ảnh mẫu");
            }
        });
        steps.add(() -> {
            photoPanel.fillAdminPin(adminPin);
            log.accept("DEMO: upload ảnh nháp — kỳ vọng PHOTO_BEGIN + 32 chunk + FINISH 9000");
            fire(photoPanel.pressUpload(), "PHOTO upload");
        });
        steps.add(() -> {
            photoPanel.fillStudentPin("123".toCharArray());
            log.accept("DEMO: đọc với student PIN sai định dạng — kỳ vọng KHÔNG gửi APDU,"
                    + " preview thẻ và nhãn trạng thái phải về (trống)");
            fire(photoPanel.pressRead(), "PHOTO read (PIN sai định dạng)");
            photoState("MARK A", "sau read PIN sai định dạng");
        });
        steps.add(() -> {
            photoPanel.fillStudentPin(studentPin);
            log.accept("DEMO: đọc ảnh từ thẻ — kỳ vọng VERIFY + 32 chunk 9000");
            fire(photoPanel.pressRead(), "PHOTO read");
        });
        steps.add(this::photoVerify);
        steps.add(() -> {
            photoPanel.fillStudentPin(wrongStudentPin);
            java.util.Arrays.fill(wrongStudentPin, '\0');
            log.accept("DEMO: đọc với student PIN sai — kỳ vọng 63Cx, preview thẻ phải bị xoá,"
                    + " nhãn trạng thái không được giữ fingerprint cũ");
            fire(photoPanel.pressRead(), "PHOTO read (PIN sai)");
        });
        steps.add(() -> {
            photoState("MARK B", "sau read PIN sai");
            photoPanel.fillStudentPin(studentPin);
            log.accept("DEMO: đọc lại bằng PIN đúng — preview thẻ phải hiện lại");
            fire(photoPanel.pressRead(), "PHOTO read");
        });
        steps.add(() -> {
            log.accept("DEMO: SELECT lại khi panel đang hiện ảnh thẻ — preview thẻ phải bị xoá");
            fire(app.pressIdentify(), "SELECT + GET_STATUS");
        });
        steps.add(() -> photoState("MARK C", "sau SELECT lại"));
        return steps;
    }

    private void photoState(String mark, String moment) {
        log.accept("DEMO " + mark + ": trạng thái ảnh " + moment + " — preview thẻ="
                + (photoPanel.hasCardImage() ? "CÓ ẢNH" : "trống")
                + ", ảnh nháp=" + (photoPanel.hasDraft() ? "còn" : "mất")
                + " | nhãn: " + photoPanel.statusText());
    }

    private void photoAfterDisconnect() {
        log.accept("DEMO MARK D: sau disconnect — preview thẻ="
                + (photoPanel.hasCardImage() ? "CÓ ẢNH (sai)" : "trống")
                + ", ảnh nháp=" + (photoPanel.hasDraft() ? "còn" : "mất")
                + " | nhãn: " + photoPanel.statusText());
    }

    private void photoVerify() {
        byte[] draft = photoPanel.draftBytes();
        byte[] card = photoPanel.cardBytes();
        if (draft == null || card == null) {
            log.accept("DEMO: thiếu ảnh để so — draft=" + (draft != null) + " card=" + (card != null));
            return;
        }
        String mismatch = PhotoImage.firstMismatch(draft, card);
        log.accept("DEMO: so ảnh nháp với ảnh đọc từ thẻ — "
                + (mismatch == null ? "khớp từng byte (4096 byte)" : mismatch));
        log.accept("DEMO: hướng hàng/cột — góc trên-trái=" + grey(draft, 8, 8)
                + " trên-phải=" + grey(draft, 55, 8)
                + " dưới-trái=" + grey(draft, 8, 55)
                + " dưới-phải=" + grey(draft, 55, 55));
        log.accept("DEMO: kỳ vọng trái tối hơn phải ở hàng trên: "
                + (grey(draft, 8, 8) < grey(draft, 55, 8))
                + " | dưới sáng hơn trên ở cột phải: "
                + (grey(draft, 55, 55) > grey(draft, 55, 8)));
    }

    private static int grey(byte[] photo, int column, int row) {
        return photo[row * PhotoImage.WIDTH + column] & 0xFF;
    }

    private java.nio.file.Path photoSample() {
        if (photoSampleFile != null) {
            return photoSampleFile;
        }
        String given = System.getProperty("labaccess.demo.photo.file", "");
        if (!given.isEmpty()) {
            photoSampleFile = java.nio.file.Path.of(given);
            return photoSampleFile;
        }
        java.awt.image.BufferedImage sample = new java.awt.image.BufferedImage(
                256, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 256; x++) {
                int value = x < 128 ? (y < 64 ? 0 : 200) : (y < 64 ? 128 : 255);
                sample.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        try {
            photoSampleFile = java.nio.file.Files.createTempFile("labaccess-demo-photo", ".png");
            javax.imageio.ImageIO.write(sample, "png", photoSampleFile.toFile());
            log.accept("DEMO: tạo ảnh mẫu 256×128 bốn góc khác nhau tại " + photoSampleFile);
            return photoSampleFile;
        } catch (java.io.IOException exception) {
            log.accept("DEMO: không tạo được ảnh mẫu: " + exception);
            return null;
        }
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
