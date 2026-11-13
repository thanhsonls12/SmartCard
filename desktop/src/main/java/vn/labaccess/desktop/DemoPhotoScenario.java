package vn.labaccess.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

final class DemoPhotoScenario {

    private final App app;
    private final PhotoPanel photoPanel;
    private final Consumer<String> log;

    private java.nio.file.Path sampleFile;

    DemoPhotoScenario(App app, PhotoPanel photoPanel, Consumer<String> log) {
        this.app = app;
        this.photoPanel = photoPanel;
        this.log = log;
    }

    List<Runnable> steps(char[] adminPin, char[] studentPin, char[] wrongStudentPin) {
        java.nio.file.Path sample = sample();
        List<Runnable> steps = new ArrayList<>();
        if (sample == null) {
            return steps;
        }
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
            state("MARK A", "sau read PIN sai định dạng");
        });
        steps.add(() -> {
            photoPanel.fillStudentPin(studentPin);
            log.accept("DEMO: đọc ảnh từ thẻ — kỳ vọng VERIFY + 32 chunk 9000");
            fire(photoPanel.pressRead(), "PHOTO read");
        });
        steps.add(this::verify);
        steps.add(() -> {
            photoPanel.fillStudentPin(wrongStudentPin);
            Arrays.fill(wrongStudentPin, '\0');
            log.accept("DEMO: đọc với student PIN sai — kỳ vọng 63Cx, preview thẻ phải bị xoá,"
                    + " nhãn trạng thái không được giữ fingerprint cũ");
            fire(photoPanel.pressRead(), "PHOTO read (PIN sai)");
        });
        steps.add(() -> {
            state("MARK B", "sau read PIN sai");
            photoPanel.fillStudentPin(studentPin);
            log.accept("DEMO: đọc lại bằng PIN đúng — preview thẻ phải hiện lại");
            fire(photoPanel.pressRead(), "PHOTO read");
        });
        steps.add(() -> {
            log.accept("DEMO: SELECT lại khi panel đang hiện ảnh thẻ — preview thẻ phải bị xoá");
            fire(app.pressIdentify(), "SELECT + GET_STATUS");
        });
        steps.add(() -> state("MARK C", "sau SELECT lại"));
        return steps;
    }

    void state(String mark, String moment) {
        log.accept("DEMO " + mark + ": trạng thái ảnh " + moment + " — preview thẻ="
                + (photoPanel.hasCardImage() ? "CÓ ẢNH" : "trống")
                + ", ảnh nháp=" + (photoPanel.hasDraft() ? "còn" : "mất")
                + " | nhãn: " + photoPanel.statusText());
    }

    void afterDisconnect() {
        log.accept("DEMO MARK D: sau disconnect — preview thẻ="
                + (photoPanel.hasCardImage() ? "CÓ ẢNH (sai)" : "trống")
                + ", ảnh nháp=" + (photoPanel.hasDraft() ? "còn" : "mất")
                + " | nhãn: " + photoPanel.statusText());
    }

    void verify() {
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

    java.nio.file.Path sample() {
        if (sampleFile != null) {
            return sampleFile;
        }
        String given = System.getProperty("labaccess.demo.photo.file", "");
        if (!given.isEmpty()) {
            sampleFile = java.nio.file.Path.of(given);
            return sampleFile;
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
            sampleFile = java.nio.file.Files.createTempFile("labaccess-demo-photo", ".png");
            javax.imageio.ImageIO.write(sample, "png", sampleFile.toFile());
            log.accept("DEMO: tạo ảnh mẫu 256×128 bốn góc khác nhau tại " + sampleFile);
            return sampleFile;
        } catch (java.io.IOException exception) {
            log.accept("DEMO: không tạo được ảnh mẫu: " + exception);
            return null;
        }
    }

    void disposeSample() {
        if (sampleFile != null && System.getProperty("labaccess.demo.photo.file", "").isEmpty()) {
            try {
                java.nio.file.Files.deleteIfExists(sampleFile);
                log.accept("DEMO: đã xoá ảnh mẫu tạm " + sampleFile.getFileName());
            } catch (java.io.IOException exception) {
                log.accept("DEMO: không xoá được ảnh mẫu tạm: " + exception);
            }
            sampleFile = null;
        }
    }

    private void fire(boolean pressed, String name) {
        if (!pressed) {
            log.accept("DEMO: bỏ qua bước " + name + " (nút đang bị khoá)");
        }
    }
}
