package vn.labaccess.desktop.ui.shared;

import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import vn.labaccess.desktop.card.PhotoImage;

public final class PhotoPreview {

    public static final double HEIGHT = 260;
    public static final double MAX_WIDTH = 280;

    private static final double CARD_ASPECT = (double) PhotoImage.WIDTH / PhotoImage.HEIGHT;

    private PhotoPreview() {
    }

    public static StackPane frame(ImageView view, Label placeholder) {
        return frame(view, placeholder, MAX_WIDTH, HEIGHT);
    }

    static StackPane frame(ImageView view, Label placeholder, double maxWidth, double maxHeight) {
        view.setPreserveRatio(true);
        view.setSmooth(true);
        placeholder.setWrapText(true);
        placeholder.visibleProperty().bind(view.imageProperty().isNull());
        placeholder.managedProperty().bind(placeholder.visibleProperty());
        StackPane frame = new StackPane(placeholder, view);
        frame.getStyleClass().add("la-preview");
        frame.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        frame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        view.imageProperty().addListener(
                (observable, was, now) -> fit(view, placeholder, frame, maxWidth, maxHeight));
        fit(view, placeholder, frame, maxWidth, maxHeight);
        return frame;
    }

    private static void fit(ImageView view, Label placeholder, StackPane frame,
            double maxWidth, double maxHeight) {
        Image image = view.getImage();
        boolean measured = image != null && image.getWidth() > 0 && image.getHeight() > 0;
        double aspect = measured ? image.getWidth() / image.getHeight() : CARD_ASPECT;
        double width = Math.min(maxWidth, maxHeight * aspect);
        double height = width / aspect;
        view.setFitWidth(width);
        view.setFitHeight(height);
        placeholder.setMinWidth(width);
        placeholder.setMaxWidth(width);
        frame.requestLayout();
    }
}
