package vn.labaccess.desktop.virtualreader;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

import vn.labaccess.desktop.ui.foundation.Icons;
import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

public final class VirtualReaderView {
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final Label message = new Label();
    private final Label count = new Label();
    private final Button create = action("Tạo thẻ trắng", Feather.PLUS, "la-primary");
    private final Button importCard = action("Thêm EEPROM có sẵn", Feather.FOLDER_PLUS);
    private final SlotPanel staff;
    private final SlotPanel kiosk;
    private final ScrollPane root;
    private final HBox feedback;
    private final BiConsumer<String, Callable<Void>> run;

    public VirtualReaderView(VirtualReaderController staffController, VirtualReaderController kioskController,
            int port, BiConsumer<String, Callable<Void>> run) {
        this.run = run;
        staff = new SlotPanel("Staff Console", "staff", port, Feather.MONITOR, staffController);
        kiosk = new SlotPanel("Kiosk", "kiosk", port + 1, Feather.LAYOUT, kioskController);
        create.setId("reader-create");
        importCard.setId("reader-import");
        create.setOnAction(event -> createCard());
        importCard.setOnAction(event -> importExisting());
        TilePane slots = new TilePane(UiScale.SPACE_4, UiScale.SPACE_4, staff.node, kiosk.node);
        slots.setPrefColumns(2);
        slots.setMinWidth(0);
        slots.setTileAlignment(Pos.TOP_LEFT);
        slots.setId("reader-slots");
        feedback = feedback();
        Label hint = text("Ngắt kết nối Staff/Kiosk trước khi đổi hoặc rút thẻ. "
                + "Thẻ trắng cần cài Applet bằng công cụ riêng.", "la-reader-help");
        HBox help = new HBox(UiScale.SPACE_2, Icons.info(), hint);
        help.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(hint, Priority.ALWAYS);
        VBox content = new VBox(UiScale.SPACE_4, header(), slots, library(), feedback, help);
        content.setPadding(new Insets(UiScale.SPACE_5));
        content.setMinWidth(0);
        content.setMaxWidth(1100);
        content.setMinHeight(Region.USE_PREF_SIZE);
        VBox canvas = new VBox(content);
        canvas.setMinWidth(0);
        canvas.setAlignment(Pos.TOP_CENTER);
        root = UiSection.scroll(canvas);
        root.setId("virtual-reader-scroll");
        root.getStyleClass().add("la-reader");
        slots.prefTileWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            double width = Math.max(0, content.getWidth() - UiScale.SPACE_5 * 2);
            return width >= 720 ? (width - UiScale.SPACE_4) / 2 : width;
        }, content.widthProperty()));
    }

    public Parent node() {
        return root;
    }

    public void message(String value, boolean failed) {
        message.setText(value == null ? "Không thể hoàn tất thao tác. Hãy kiểm tra log." : value);
        feedback.getStyleClass().remove("la-reader-feedback--error");
        if (failed) {
            feedback.getStyleClass().add("la-reader-feedback--error");
        }
    }

    public void reload() throws IOException {
        List<VirtualCardCatalog.Card> all = staff.controller.cards();
        staff.reload(all);
        kiosk.reload(all);
        count.setText(all.size() + " thẻ");
    }

    public void refresh(boolean working) {
        busy.set(working);
        boolean disabled = working || staff.controller.recoveryRequired() || kiosk.controller.recoveryRequired();
        staff.refresh(disabled, kiosk.controller.active());
        kiosk.refresh(disabled, staff.controller.active());
        create.setDisable(disabled);
        importCard.setDisable(disabled);
    }

    private void createCard() {
        TextInputDialog dialog = new TextInputDialog("Thẻ " + (staff.cards.getItems().size() + 1));
        dialog.initOwner(root.getScene().getWindow());
        dialog.getDialogPane().getStylesheets().setAll(root.getScene().getStylesheets());
        dialog.setTitle("Tạo thẻ trắng");
        dialog.setHeaderText("Tạo EEPROM trắng tại đầu đọc Staff, chưa cài Applet.");
        dialog.setContentText("Tên thẻ:");
        dialog.showAndWait().ifPresent(label -> run.accept("Đang tạo EEPROM trắng...", () -> {
            staff.controller.create(label);
            return null;
        }));
    }

    private void importExisting() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn EEPROM hiện có (không sao chép)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("EEPROM", "*.eeprom", "*.bin", "*.eep"));
        java.io.File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) {
            return;
        }
        TextInputDialog name = new TextInputDialog(file.getName());
        name.initOwner(root.getScene().getWindow());
        name.getDialogPane().getStylesheets().setAll(root.getScene().getStylesheets());
        name.setTitle("Tên hiển thị");
        name.setHeaderText("Đăng ký đường dẫn EEPROM gốc, không tạo bản sao.");
        name.showAndWait().ifPresent(label -> run.accept("Đang thêm thẻ...", () -> {
            staff.controller.importCard(label, file.toPath());
            return null;
        }));
    }

    private HBox header() {
        Label mark = new Label("", Icons.identify());
        mark.getStyleClass().add("la-reader-mark");
        Label brand = text("LabAccess", "la-reader-brand");
        Label title = text("Virtual Card Reader", "la-reader-title");
        Label description = text("Quản lý thẻ ảo cho từng đầu đọc Staff và Kiosk.", "la-reader-description");
        VBox identity = new VBox(UiScale.SPACE_1, brand, title, description);
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);
        HBox header = new HBox(UiScale.SPACE_4, mark, identity);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox library() {
        Label title = text("Thư viện thẻ", "la-reader-section-title");
        count.getStyleClass().addAll("la-chip", "la-chip--neutral");
        count.setId("reader-card-count");
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox heading = new HBox(UiScale.SPACE_3, title, space, count);
        heading.setAlignment(Pos.CENTER_LEFT);
        Label description = text("Tạo EEPROM trắng cho Staff hoặc thêm tệp EEPROM hiện có.",
                "la-reader-description");
        FlowPane actions = new FlowPane(UiScale.SPACE_3, UiScale.SPACE_3, create, importCard);
        VBox library = new VBox(UiScale.SPACE_3, heading, description, actions);
        library.getStyleClass().add("la-reader-library");
        return library;
    }

    private HBox feedback() {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMinSize(UiScale.SPACE_4, UiScale.SPACE_4);
        progress.setMaxSize(UiScale.SPACE_4, UiScale.SPACE_4);
        progress.setFocusTraversable(false);
        progress.getStyleClass().add("la-spinner");
        progress.visibleProperty().bind(busy);
        progress.managedProperty().bind(progress.visibleProperty());
        message.setId("reader-message");
        message.setWrapText(true);
        message.setMinWidth(0);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        HBox box = new HBox(UiScale.SPACE_3, progress, message);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("la-reader-feedback");
        box.visibleProperty().bind(message.textProperty().isNotEmpty());
        box.managedProperty().bind(box.visibleProperty());
        return box;
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinWidth(0);
        return label;
    }

    private static FontIcon icon(Feather value) {
        FontIcon icon = new FontIcon(value);
        icon.getStyleClass().add("la-icon");
        return icon;
    }

    private static Button action(String title, Feather graphic, String... styles) {
        Button button = new Button(title, icon(graphic));
        button.getStyleClass().add("la-action");
        button.getStyleClass().addAll(styles);
        return button;
    }

    private final class SlotPanel {
        private final VirtualReaderController controller;
        private final ComboBox<VirtualCardCatalog.Card> cards = new ComboBox<>();
        private final Label badge = new Label();
        private final Label activeName = text("", "la-reader-card-name");
        private final Label state = text("", "la-reader-description");
        private final Label warning = text("", "la-reader-warning");
        private final Button insert = action("Gắn thẻ", Feather.LOG_IN, "la-primary");
        private final Button eject = action("Rút thẻ", Feather.LOG_OUT);
        private final VBox node;

        SlotPanel(String title, String id, int port, Feather graphic, VirtualReaderController controller) {
            this.controller = controller;
            Label mark = new Label("", icon(graphic));
            mark.getStyleClass().add("la-reader-slot-mark");
            Label name = text(title, "la-reader-section-title");
            Label connection = text("Cổng " + port, "la-caption");
            VBox identity = new VBox(UiScale.SPACE_1, name, connection);
            identity.setMinWidth(0);
            HBox.setHgrow(identity, Priority.ALWAYS);
            badge.setId("reader-" + id + "-badge");
            badge.getStyleClass().add("la-chip");
            HBox heading = new HBox(UiScale.SPACE_3, mark, identity, badge);
            heading.setAlignment(Pos.CENTER_LEFT);
            VBox detail = new VBox(UiScale.SPACE_1, activeName, state);
            detail.setMinWidth(0);
            HBox.setHgrow(detail, Priority.ALWAYS);
            HBox current = new HBox(UiScale.SPACE_3, Icons.identify(), detail);
            current.setAlignment(Pos.CENTER_LEFT);
            current.getStyleClass().add("la-reader-current");
            cards.setId("reader-" + id + "-cards");
            cards.setAccessibleText("Chọn thẻ cho " + title);
            cards.setMaxWidth(Double.MAX_VALUE);
            cards.setMinWidth(0);
            cards.setConverter(new StringConverter<>() {
                @Override
                public String toString(VirtualCardCatalog.Card card) {
                    return card == null ? "" : card.name();
                }

                @Override
                public VirtualCardCatalog.Card fromString(String value) {
                    return null;
                }
            });
            cards.valueProperty().addListener((observable, oldValue, newValue) ->
                    VirtualReaderView.this.refresh(busy.get()));
            insert.setId("reader-" + id + "-insert");
            eject.setId("reader-" + id + "-eject");
            insert.setOnAction(event -> {
                VirtualCardCatalog.Card selected = cards.getValue();
                if (selected != null) {
                    run.accept("Đang chọn " + selected.name() + " cho " + title + "...", () -> {
                        controller.insert(selected);
                        return null;
                    });
                }
            });
            eject.setOnAction(event -> run.accept("Đang rút thẻ khỏi " + title + "...", () -> {
                controller.eject();
                return null;
            }));
            insert.setMaxWidth(Double.MAX_VALUE);
            eject.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(insert, Priority.ALWAYS);
            HBox.setHgrow(eject, Priority.ALWAYS);
            HBox actions = new HBox(UiScale.SPACE_3, insert, eject);
            warning.setId("reader-" + id + "-warning");
            warning.visibleProperty().bind(warning.textProperty().isNotEmpty());
            warning.managedProperty().bind(warning.visibleProperty());
            node = new VBox(UiScale.SPACE_4, heading, current, cards, warning, actions);
            node.setId("reader-" + id);
            node.setMinWidth(0);
            node.setMaxWidth(Double.MAX_VALUE);
            node.getStyleClass().add("la-reader-slot");
        }

        void reload(List<VirtualCardCatalog.Card> all) {
            VirtualCardCatalog.Card selected = cards.getValue();
            cards.getItems().setAll(all);
            cards.setValue(controller.active() != null ? controller.active() : selected);
            cards.setPromptText(all.isEmpty() ? "Chưa có thẻ trong thư viện" : "Chọn thẻ trong thư viện");
        }

        void refresh(boolean disabled, VirtualCardCatalog.Card other) {
            VirtualCardCatalog.Card selected = cards.getValue();
            VirtualCardCatalog.Card active = controller.active();
            boolean occupied = selected != null && selected.equals(other);
            boolean recovery = controller.recoveryRequired();
            badge.getStyleClass().removeAll("la-chip--neutral", "la-chip--ok", "la-chip--pending", "la-chip--error");
            badge.getStyleClass().add(recovery ? "la-chip--error" : busy.get() ? "la-chip--pending"
                    : active == null ? "la-chip--neutral" : "la-chip--ok");
            badge.setText(recovery ? "Cần kiểm tra" : busy.get() ? "Đang xử lý"
                    : active == null ? "Trống" : "Đã gắn thẻ");
            activeName.setText(active == null ? "Chưa gắn thẻ" : active.name());
            state.setText(active == null ? "Chọn thẻ bên dưới để bắt đầu." : "Thẻ hiện tại của đầu đọc này.");
            warning.setText(recovery ? "Cần dừng hệ thống và kiểm tra log trước khi tiếp tục."
                    : occupied ? "Thẻ đang ở đầu đọc còn lại. Hãy rút thẻ ở đó trước." : "");
            cards.setDisable(disabled);
            insert.setDisable(disabled || selected == null || selected.equals(active) || occupied);
            eject.setDisable(disabled || active == null);
        }
    }
}
