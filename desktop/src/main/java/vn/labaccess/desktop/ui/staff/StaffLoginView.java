package vn.labaccess.desktop.ui.staff;

import java.util.function.BiConsumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import vn.labaccess.desktop.ui.foundation.Icons;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

public final class StaffLoginView {
    private static final double PANEL_WIDTH = 448;

    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Button login = new Button("Đăng nhập");
    private final Label message = new Label();
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final ScrollPane root;

    public StaffLoginView(BiConsumer<String, char[]> submit) {
        username.setPromptText("Nhập tên đăng nhập");
        password.setPromptText("Nhập mật khẩu");
        username.setId("staff-login-username");
        password.setId("staff-login-password");
        login.setId("staff-login-submit");
        message.setId("staff-login-message");
        username.getStyleClass().add("la-login-field");
        password.getStyleClass().add("la-login-field");
        username.disableProperty().bind(busy);
        password.disableProperty().bind(busy);
        login.setDefaultButton(true);
        login.getStyleClass().addAll("la-primary", "la-login-submit");
        FontIcon arrow = new FontIcon(Feather.LOG_IN);
        arrow.getStyleClass().add("la-icon");
        login.setGraphic(arrow);
        login.setMaxWidth(Double.MAX_VALUE);
        login.disableProperty().bind(busy.or(username.textProperty().isEmpty())
                .or(password.textProperty().isEmpty()));
        login.setOnAction(event -> {
            char[] value = password.getText().toCharArray();
            password.clear();
            submit.accept(username.getText().strip(), value);
        });

        Label title = new Label("Đăng nhập nhân viên");
        title.getStyleClass().add("la-login-title");
        title.setWrapText(true);
        Label description = new Label("Sử dụng tài khoản được cấp để truy cập công cụ quản lý.");
        description.getStyleClass().add("la-login-description");
        description.setWrapText(true);
        VBox heading = new VBox(UiScale.SPACE_2, title, description);
        VBox fields = new VBox(UiScale.SPACE_4,
                field("Tên đăng nhập", username), field("Mật khẩu", password));
        VBox action = new VBox(UiScale.SPACE_3, login, feedback());
        VBox panel = new VBox(UiScale.SPACE_5, heading, fields, action);
        panel.getStyleClass().add("la-login-panel");
        panel.setPadding(new Insets(UiScale.SPACE_6));
        panel.setMinHeight(Region.USE_PREF_SIZE);

        Label help = new Label("Liên hệ quản trị viên nếu bạn cần cấp lại mật khẩu.");
        help.getStyleClass().add("la-login-help");
        help.setWrapText(true);
        help.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(UiScale.SPACE_5, brand(), panel, help);
        content.setMaxWidth(PANEL_WIDTH);
        content.setMaxHeight(Region.USE_PREF_SIZE);
        content.setMinHeight(Region.USE_PREF_SIZE);
        StackPane canvas = new StackPane(content);
        canvas.setAlignment(Pos.CENTER);
        canvas.setPadding(new Insets(UiScale.SPACE_5));
        root = UiSection.scroll(canvas);
        root.setId("staff-login-scroll");
        root.getStyleClass().add("la-staff-login");
        canvas.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> root.getViewportBounds().getHeight(), root.viewportBoundsProperty()));
    }

    public Parent node() {
        return root;
    }

    public void busy(boolean value) {
        busy.set(value);
        message.setText(value ? "Đang xác thực..." : "");
    }

    public void message(String value) {
        message.setText(value);
    }

    public void clear() {
        password.clear();
    }

    private VBox field(String text, TextField input) {
        Label label = new Label(text);
        label.getStyleClass().add("la-login-label");
        label.setLabelFor(input);
        return new VBox(UiScale.SPACE_2, label, input);
    }

    private HBox feedback() {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(UiScale.SPACE_4, UiScale.SPACE_4);
        progress.setMinSize(UiScale.SPACE_4, UiScale.SPACE_4);
        progress.setFocusTraversable(false);
        progress.getStyleClass().add("la-spinner");
        progress.visibleProperty().bind(busy);
        progress.managedProperty().bind(progress.visibleProperty());
        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        message.getStyleClass().add("la-login-message");
        HBox.setHgrow(message, Priority.ALWAYS);
        HBox feedback = new HBox(UiScale.SPACE_2, progress, message);
        feedback.setAlignment(Pos.CENTER_LEFT);
        feedback.getStyleClass().add("la-login-feedback");
        feedback.visibleProperty().bind(message.textProperty().isNotEmpty());
        feedback.managedProperty().bind(feedback.visibleProperty());
        return feedback;
    }

    private HBox brand() {
        Label mark = new Label("", Icons.identify());
        mark.getStyleClass().add("la-login-mark");
        Label name = new Label("LabAccess");
        name.getStyleClass().add("la-brand");
        Label subtitle = new Label("Hệ thống quản lý phòng lab");
        subtitle.getStyleClass().add("la-caption");
        VBox identity = new VBox(UiScale.SPACE_1, name, subtitle);
        HBox brand = new HBox(UiScale.SPACE_3, mark, identity);
        brand.setAlignment(Pos.CENTER);
        return brand;
    }
}
