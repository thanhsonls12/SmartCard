package vn.labaccess.desktop.ui.staff;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import vn.labaccess.desktop.ui.foundation.UiScale;

import vn.labaccess.desktop.backend.StaffIdentity;

public final class StaffSessionBar {
    private final Button logout = new Button("Đăng xuất");
    private final Label identity = new Label();
    private final HBox root;

    public StaffSessionBar(StaffIdentity staff, Runnable action) {
        identity.setText(staff.displayName() + " · " + staff.staffCode());
        identity.setId("staff-session-identity");
        logout.setId("staff-session-logout");
        identity.getStyleClass().add("la-brand");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        logout.setOnAction(event -> action.run());
        root = new HBox(UiScale.SPACE_3, identity, spacer, logout);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(UiScale.SPACE_2, UiScale.SPACE_4, UiScale.SPACE_2, UiScale.SPACE_4));
        root.getStyleClass().add("la-header");
    }

    public HBox node() {
        return root;
    }

    public void busy(boolean value) {
        logout.setDisable(value);
    }

    public void invalid() {
        identity.setText("Phiên không còn hiệu lực — đăng xuất để đăng nhập lại");
    }

    public void logoutFailed() {
        identity.setText("Chưa xác nhận đăng xuất — hãy thử lại");
    }
}
