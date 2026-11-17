package vn.labaccess.desktop;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class CardIdentityPanel {

    private static final double PORTRAIT_SIZE = 80;

    private final Label name = new Label();
    private final Label studentId = UiLabels.caption("");
    private final Label note = UiLabels.caption("");
    private final Label emptyPhoto = UiLabels.caption("Chưa đọc ảnh");
    private final ImageView portrait = new ImageView();
    private final VBox node;
    private byte[] displayedPhoto;

    CardIdentityPanel() {
        name.setId("operator-student-name");
        name.getStyleClass().add("la-profile-name");
        name.setWrapText(true);
        studentId.setId("operator-student-id");
        note.setWrapText(true);
        emptyPhoto.setWrapText(true);
        emptyPhoto.setMaxWidth(PORTRAIT_SIZE - UiScale.SPACE_3);
        portrait.setFitWidth(PORTRAIT_SIZE);
        portrait.setFitHeight(PORTRAIT_SIZE);
        portrait.setPreserveRatio(true);
        portrait.setSmooth(false);
        emptyPhoto.visibleProperty().bind(portrait.imageProperty().isNull());
        StackPane frame = new StackPane(emptyPhoto, portrait);
        frame.getStyleClass().add("la-preview");
        frame.setMinSize(PORTRAIT_SIZE, PORTRAIT_SIZE);
        frame.setMaxSize(PORTRAIT_SIZE, PORTRAIT_SIZE);
        VBox details = new VBox(UiScale.SPACE_1, name, studentId, note);
        details.setMinWidth(0);
        HBox.setHgrow(details, Priority.ALWAYS);
        HBox identity = new HBox(UiScale.SPACE_3, frame, details);
        identity.setAlignment(Pos.CENTER_LEFT);
        node = UiSection.of("Người sử dụng thẻ", identity);
        show(null, null, null, false);
    }

    VBox node() {
        return node;
    }

    void show(CardStatus card, Profile profile, byte[] photo, boolean photoAbsent) {
        boolean identified = card != null && profile != null;
        name.setText(identified ? profile.displayName() : "Chưa đọc thông tin học viên");
        studentId.setText(identified ? "Mã sinh viên: " + profile.studentId() : "");
        UiLabels.show(studentId, identified);
        note.setText(identified ? photo == null && !photoAbsent
                ? "Ảnh chưa được đọc lại. Nhập PIN và bấm Đọc thẻ."
                : "Đối chiếu người đang dùng thẻ trước khi giao dịch."
                : card == null ? "Kết nối thẻ để bắt đầu."
                : "Nhập PIN và bấm Đọc thẻ để xem hồ sơ, ảnh và số lượt.");
        emptyPhoto.setText(identified && photoAbsent ? "Thẻ chưa có ảnh" : "Chưa đọc ảnh");
        byte[] currentPhoto = identified ? photo : null;
        if (!java.util.Arrays.equals(displayedPhoto, currentPhoto)) {
            displayedPhoto = currentPhoto == null ? null : currentPhoto.clone();
            portrait.setImage(toImage(currentPhoto));
        }
    }

    private static WritableImage toImage(byte[] photo) {
        if (photo == null || photo.length != PhotoImage.LENGTH) {
            return null;
        }
        WritableImage image = new WritableImage(PhotoImage.WIDTH, PhotoImage.HEIGHT);
        for (int row = 0; row < PhotoImage.HEIGHT; row++) {
            for (int column = 0; column < PhotoImage.WIDTH; column++) {
                int gray = photo[row * PhotoImage.WIDTH + column] & 0xFF;
                image.getPixelWriter().setArgb(column, row, 0xFF000000 | gray << 16 | gray << 8 | gray);
            }
        }
        return image;
    }
}
