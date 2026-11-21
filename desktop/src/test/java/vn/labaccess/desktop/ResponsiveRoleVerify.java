package vn.labaccess.desktop;

import static vn.labaccess.desktop.RoleLedgerDisplayVerify.field;
import static vn.labaccess.desktop.RoleLedgerDisplayVerify.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javax.imageio.ImageIO;
import vn.labaccess.desktop.RoleLedgerDisplayVerify.Fixture;
import vn.labaccess.desktop.RoleLedgerDisplayVerify.Window;

public final class ResponsiveRoleVerify {

    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final Map<String, Object> ENVIRONMENT = new LinkedHashMap<>();
    private static int failures;
    private static Robot robot;
    private static Path output;
    private static String context;

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        if (!Path.of(System.getProperty("user.home")).toAbsolutePath().equals(output.resolve("home"))) {
            throw new IllegalArgumentException("isolated test home required");
        }
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        double scale = Double.parseDouble(System.getProperty("glass.win.uiScale"));
        boolean baseline = Boolean.getBoolean("labaccess.responsive.baseline");
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            robot = fx(Robot::new);
            ENVIRONMENT.put("requestedPhysicalResolution", width + "x" + height);
            ENVIRONMENT.put("forcedJavaFxScale", scale);
            var device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            ENVIRONMENT.put("actualWindowsResolution", device.getDisplayMode().getWidth() + "x" + device.getDisplayMode().getHeight());
            ENVIRONMENT.put("actualWindowsScale", device.getDefaultConfiguration().getDefaultTransform().getScaleX());
            ENVIRONMENT.put("physicalMatrixVerified", false);
            ENVIRONMENT.put("javaFxScreen", fx(() -> Screen.getPrimary().getBounds().toString()));
            ENVIRONMENT.put("outputScale", fx(() -> Screen.getPrimary().getOutputScaleX()));
            System.out.println("ENVIRONMENT " + new ObjectMapper().writeValueAsString(ENVIRONMENT));
            for (boolean staff : List.of(true, false)) {
                context = staff ? "Staff" : "Kiosk";
                try (Fixture fixture = new Fixture(staff ? "Staff" : "START");
                        RecoveryBackendFixture backend = new RecoveryBackendFixture((request, body) -> {
                            if (!staff && request.split(" ")[1].endsWith("/transactions")) {
                                return new RecoveryBackendFixture.Reply(200,
                                        "{\"cardId\":\"" + RecoveryCardFixture.CARD_ID
                                                + "\",\"transactions\":[{\"id\":\"11111111-1111-4111-8111-111111111111\","
                                                + "\"counterAfter\":1,\"operation\":\"GRANT\",\"delta\":5,"
                                                + "\"balanceAfter\":5,\"verifyStatus\":\"verified\","
                                                + "\"recordedAt\":\"2026-11-16T08:00:00Z\"}]}");
                            }
                            return fixture.reply(request, body);
                        });
                        Window app = new Window(staff, context, fixture, backend.url(), output.resolve(context + "-journal.txt"))) {
                    resize(app, Math.min(staff ? 1180 : 1060, width / scale - 48), Math.min(800, height / scale - 80));
                    capture(app.stage.getScene(), context + "-matrix.png");
                    check("window fits requested logical work area", fx(() -> app.stage.getWidth() <= width / scale
                            && app.stage.getHeight() <= height / scale && app.stage.getScene().getWidth() <= width / scale
                            && app.stage.getScene().getHeight() <= height / scale));
                    check("requested JavaFX output scale applied", fx(() -> Math.abs(app.stage.getOutputScaleX() - scale) < 0.01));
                    if (baseline) {
                        resize(app, staff ? 840 : 640, 420);
                        check("narrow width accepted", fx(() -> app.stage.getMinWidth() <= (staff ? 840 : 640)
                                && app.stage.getScene().getWidth() <= (staff ? 840 : 640) + 1));
                        check("short height accepted", fx(() -> app.stage.getMinHeight() <= 420
                                && app.stage.getScene().getHeight() <= 421));
                        if (!staff) kioskSizes(app);
                        capture(app.stage.getScene(), context + "-narrow.png");
                        continue;
                    }
                    layout(app, "matrix");
                    if (staff) {
                        staff(app);
                    } else {
                        kiosk(app, fixture);
                    }
                    resize(app, staff ? 640 : 480, 360);
                    layout(app, "minimum");
                    check("minimum width below 900", fx(() -> app.stage.getWidth() < 900));
                    if (!staff) {
                        fx(() -> {
                            HistoryPanel history = (HistoryPanel) field(app.workspace, "history");
                            check("Kiosk history reload control is usable at minimum width", history.pressReload());
                            return null;
                        });
                        app.idle();
                        check("Kiosk history fixture row is displayed at minimum width", fx(() -> {
                            HistoryPanel history = (HistoryPanel) field(app.workspace, "history");
                            return history.rowCount() == 1 && history.node().isVisible();
                        }));
                        check("Kiosk operation and change columns remain readable at minimum width", fx(() -> {
                            HistoryPanel history = (HistoryPanel) field(app.workspace, "history");
                            var table = (javafx.scene.control.TableView<?>) history.node().lookup("#history-table");
                            var columns = table.getColumns();
                            return columns.get(1).getWidth() >= 104 && columns.get(2).getWidth() >= 104;
                        }));
                        check("Kiosk narrow history exposes horizontal scrolling", fx(() -> {
                            HistoryPanel history = (HistoryPanel) field(app.workspace, "history");
                            var table = (javafx.scene.control.TableView<?>) history.node().lookup("#history-table");
                            return table.lookupAll(".scroll-bar").stream().anyMatch(node ->
                                    node instanceof javafx.scene.control.ScrollBar bar
                                            && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                                            && bar.isVisible());
                        }));
                        layout(app, "minimum-history");
                        javafx.scene.layout.BorderPane root = (javafx.scene.layout.BorderPane) field(app.workspace, "root");
                        scrollEnd((ScrollPane) root.getCenter(), "Kiosk-history");
                        ScrollPane content = (ScrollPane) root.getCenter();
                        fx(() -> { content.setVvalue(Math.max(0, content.getVmax() - 0.04)); return null; });
                        settle();
                    }
                    capture(app.stage.getScene(), context + "-minimum.png");
                    confirmation(app, false);
                    confirmation(app, true);
                    superseded(app);
                    check("layout and rejected input cause no mutation", fixture.mutations() == 0 && fixture.posts == 0);
                }
            }
        } finally {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("environment", ENVIRONMENT);
            report.put("baseline", baseline);
            report.put("checks", RESULTS);
            report.put("failures", failures);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);
            Platform.exit();
        }
        if (failures > 0) throw new AssertionError(failures + " responsive checks failed");
        System.out.println("RESPONSIVE PASS " + RESULTS.size() + " checks");
    }

    private static void resize(Window app, double width, double height) throws Exception {
        fx(() -> {
            app.stage.setWidth(width);
            app.stage.setHeight(height);
            app.stage.setX(0);
            app.stage.setY(0);
            app.stage.toFront();
            app.stage.requestFocus();
            return null;
        });
        settle();
        fx(() -> {
            var scene = app.stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            System.out.printf("WINDOW %s requested=%.1fx%.1f actual=%.1fx%.1f scene=%.1fx%.1f scale=%.2f%n",
                    context, width, height, app.stage.getWidth(), app.stage.getHeight(), scene.getWidth(), scene.getHeight(),
                    app.stage.getOutputScaleX());
            return null;
        });
    }

    private static void staff(Window app) throws Exception {
        resize(app, 840, 420);
        click((ToggleButton) field(app.workspace, "technical"));
        click((ToggleButton) field(app.workspace, "logToggle"));
        TabPane tabs = (TabPane) field(app.workspace, "tabs");
        for (int index = 0; index < tabs.getTabs().size(); index++) {
            int selected = index;
            fx(() -> { tabs.getSelectionModel().select(selected); return null; });
            settle();
            layout(app, "narrow-tab-" + index);
            ScrollPane scroll = (ScrollPane) tabs.getTabs().get(index).getContent();
            scrollEnd(scroll, "tab-" + index);
            tabOrder(app, "tab-" + index);
            capture(app.stage.getScene(), "Staff-tab-" + index + ".png");
        }
    }

    private static void kiosk(Window app, Fixture fixture) throws Exception {
        kioskSizes(app);
        check("Kiosk main controls visible on initial view", fx(() -> visible((Button) field(app.workspace, "start"))
                && visible((Button) field(app.workspace, "end")) && visible((Button) field(app.workspace, "info"))));
        resize(app, 640, 420);
        click((Button) field(app.workspace, "start"));
        KioskPinPrompt prompt = (KioskPinPrompt) field(app.workspace, "prompt");
        PasswordField pin = (PasswordField) field(prompt, "pin");
        check("PIN gets focus and is visible after action", fx(() -> pin.isFocused() && visible(pin)));
        int before = fixture.ins.size();
        key(KeyCode.ENTER);
        Label error = (Label) field(prompt, "pinError");
        check("empty PIN error is reachable", fx(() -> !error.getText().isEmpty() && visible(error) && visible(pin)));
        check("empty PIN sends no APDU", fixture.ins.size() == before);
        capture(app.stage.getScene(), "Kiosk-pin-error.png");
        key(KeyCode.ESCAPE);
        check("Escape cancels PIN form", fx(() -> !prompt.isOpen() && pin.getText().isEmpty()));
        Button change = (Button) field(app.workspace, "change");
        fx(() -> {
            for (Node parent = change.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof TitledPane pane) pane.setExpanded(true);
            }
            return null;
        });
        Thread.sleep(350);
        settle();
        click(change);
        check("change PIN form opens", fx(prompt::isOpen));
        PasswordField next = (PasswordField) field(prompt, "newPin");
        PasswordField repeated = (PasswordField) field(prompt, "confirmation");
        for (Node expected : List.of(next, repeated, (Button) field(prompt, "submit"), (Button) field(prompt, "cancel"))) {
            key(KeyCode.TAB);
            check("PIN Tab reaches " + name(expected), fx(() -> expected.isFocused() && visible(expected)));
        }
        key(KeyCode.ENTER);
        check("keyboard cancel clears all PIN fields", fx(() -> !prompt.isOpen()
                && pin.getText().isEmpty() && next.getText().isEmpty() && repeated.getText().isEmpty()));
        click((Button) field(app.workspace, "info"));
        fx(() -> { pin.setText(new String(app.pin)); return null; });
        key(KeyCode.ENTER);
        app.idle();
        settle();
        check("read-only INFO completed through PIN Enter", !app.text("name").isEmpty());
        layout(app, "narrow-info");
        javafx.scene.layout.BorderPane root = (javafx.scene.layout.BorderPane) field(app.workspace, "root");
        scrollEnd((ScrollPane) root.getCenter(), "Kiosk-content");
        tabOrder(app, "authenticated");
        capture(app.stage.getScene(), "Kiosk-info.png");
    }

    private static void kioskSizes(Window app) throws Exception {
        check("Kiosk main buttons at least 56 high and 20pt", fx(() -> {
            for (String id : List.of("start", "end", "info")) {
                Button button = (Button) field(app.workspace, id);
                if (button.getHeight() < 55 || button.getFont().getSize() < 20) return false;
            }
            return true;
        }));
    }

    private static void layout(Window app, String label) throws Exception {
        check(label + " controls fit their content width", fx(() -> {
            List<Node> nodes = new ArrayList<>();
            walk(app.stage.getScene().getRoot(), nodes);
            for (Node node : nodes) {
                if (!(node instanceof ButtonBase || node instanceof TextInputControl) || !shown(node)) continue;
                Node parent = node.getParent();
                while (parent != null && !(parent instanceof ScrollPane)) parent = parent.getParent();
                if (parent instanceof ScrollPane scroll) {
                    Bounds b = scroll.getContent().sceneToLocal(node.localToScene(node.getBoundsInLocal()));
                    if (b.getMinX() < -2 || b.getMaxX() > scroll.getContent().getLayoutBounds().getWidth() + 2) {
                        System.out.println("OVERFLOW " + name(node) + " " + b);
                        return false;
                    }
                }
            }
            return true;
        }));
    }

    private static void scrollEnd(ScrollPane scroll, String label) throws Exception {
        fx(() -> {
            scroll.getScene().getWindow().requestFocus();
            scroll.requestFocus();
            scroll.getScene().getRoot().applyCss();
            scroll.getScene().getRoot().layout();
            return null;
        });
        for (int attempt = 0; attempt < 40 && !fx(() -> scroll.isFocused()
                && scroll.getScene().getWindow().isFocused()); attempt++) Thread.sleep(25);
        settle();
        key(KeyCode.END);
        boolean reached = false;
        for (int attempt = 0; attempt < 40 && !reached; attempt++) {
            reached = fx(() -> scroll.getContent().getLayoutBounds().getHeight()
                    <= scroll.getViewportBounds().getHeight() + 1 || scroll.getVvalue() >= scroll.getVmax() - 0.01);
            if (!reached) Thread.sleep(25);
        }
        System.out.println(fx(() -> "SCROLL " + context + " " + label + " v=" + scroll.getVvalue()
                + " max=" + scroll.getVmax() + " viewport=" + scroll.getViewportBounds().getHeight()
                + " content=" + scroll.getContent().getLayoutBounds().getHeight() + " focus=" + scroll.isFocused()));
        check(label + " End scrolls to bottom", reached);
    }

    private static void tabOrder(Window app, String label) throws Exception {
        Set<Node> expected = Collections.newSetFromMap(new IdentityHashMap<>());
        fx(() -> {
            List<Node> nodes = new ArrayList<>();
            walk(app.stage.getScene().getRoot(), nodes);
            for (Node node : nodes) {
                boolean spinnerEditor = node instanceof TextInputControl
                        && node.getParent() instanceof javafx.scene.control.Spinner<?>;
                if ((node instanceof ButtonBase || node instanceof TextInputControl || node instanceof ComboBoxBase<?>
                        || node instanceof javafx.scene.control.Spinner<?>) && !spinnerEditor
                        && shown(node) && node.isFocusTraversable() && !node.isDisabled()) expected.add(node);
            }
            app.stage.toFront();
            app.stage.requestFocus();
            return null;
        });
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> order = new ArrayList<>();
        boolean allVisible = true;
        for (int count = 0; count < 100 && !seen.containsAll(expected); count++) {
            key(KeyCode.TAB);
            Node focused = fx(() -> app.stage.getScene().getFocusOwner());
            if (focused != null && expected.contains(focused)) {
                seen.add(focused);
                order.add(name(focused));
                allVisible &= fx(() -> visible(focused));
            }
        }
        List<String> missed = expected.stream().filter(node -> !seen.contains(node)).map(ResponsiveRoleVerify::name).toList();
        System.out.println("TAB " + context + " " + label + " order=" + order + " missed=" + missed);
        check(label + " Tab reaches every enabled input/button", missed.isEmpty() && !expected.isEmpty());
        check(label + " focused controls scroll into view", allVisible);
    }

    private static void confirmation(Window app, boolean accepted) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
                dialog.setTitle("Kiểm bố cục xác nhận");
                dialog.setHeaderText("Đối soát giao dịch đang chờ; không thực hiện lại giao dịch");
                dialog.setContentText("Mã thẻ minh họa: " + RecoveryCardFixture.CARD_ID + "\n"
                        + "Chỉ đọc bằng chứng và ghi sổ khi có xác nhận phù hợp. ".repeat(12));
                result.complete(UiLabels.confirm(dialog, app.stage.getScene().getRoot(), "Đối soát giao dịch"));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        DialogPane pane = dialogPane();
        check("dialog is owned and bounded", fx(() -> pane.getScene().getWindow().getWidth() <= app.stage.getWidth() + 1
                && pane.getScene().getWindow().getHeight() <= Screen.getPrimary().getVisualBounds().getHeight()
                && ((javafx.stage.Stage) pane.getScene().getWindow()).getOwner() == app.stage));
        capture(pane.getScene(), context + (accepted ? "-dialog-confirm.png" : "-dialog-cancel.png"));
        ScrollPane content = fx(() -> (ScrollPane) pane.getContent());
        scrollEnd(content, "dialog-content");
        check("dialog buttons remain reachable after scroll", fx(() -> pane.getButtonTypes().stream()
                .allMatch(type -> visible(pane.lookupButton(type)))));
        key(accepted ? KeyCode.ENTER : KeyCode.ESCAPE);
        check(accepted ? "Enter confirms dialog" : "Escape cancels dialog", result.get(5, TimeUnit.SECONDS) == accepted);
    }

    private static void superseded(Window app) throws Exception {
        CompletableFuture<char[]> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                CardStatus state = new CardStatus(1, 1, "", RecoveryCardFixture.CARD_ID,
                        CardStatus.StateSource.STUDENT, 5, 2, false, "");
                result.complete(SupersededDialog.confirm(app.stage.getScene().getRoot(),
                        new CardOperationResult.SupersededProof("layout-only", state, 2, "00", "00"), 4));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        DialogPane pane = dialogPane();
        PasswordField pin = fx(() -> (PasswordField) pane.lookup(".password-field"));
        check("SUPERSEDED PIN dialog fits narrow owner", fx(() -> pane.getScene().getWindow().getWidth() <= app.stage.getWidth() + 1
                && pane.getScene().getWindow().getHeight() <= Screen.getPrimary().getVisualBounds().getHeight()));
        fx(() -> { pin.requestFocus(); return null; });
        settle();
        check("SUPERSEDED PIN can receive focus", fx(() -> pin.isFocused() && visible(pin)));
        key(KeyCode.ESCAPE);
        check("SUPERSEDED keyboard cancel returns no PIN", result.get(5, TimeUnit.SECONDS) == null);
    }

    private static DialogPane dialogPane() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            DialogPane pane = fx(() -> javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing)
                    .map(javafx.stage.Window::getScene).filter(scene -> scene != null && scene.getRoot() instanceof DialogPane)
                    .map(scene -> (DialogPane) scene.getRoot()).findFirst().orElse(null));
            if (pane != null) { settle(); return pane; }
            Thread.sleep(30);
        }
        throw new AssertionError("dialog did not open");
    }

    private static void click(Control control) throws Exception {
        fx(() -> { control.requestFocus(); return null; });
        settle();
        fx(() -> {
            Bounds bounds = control.localToScreen(control.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        settle();
    }

    private static void key(KeyCode code) throws Exception {
        fx(() -> { robot.keyType(code); return null; });
        Thread.sleep(45);
        fx(() -> null);
    }

    private static void settle() throws Exception {
        Thread.sleep(120);
        fx(() -> null);
    }

    private static boolean shown(Node node) {
        for (Node parent = node; parent != null; parent = parent.getParent()) {
            if (!parent.isVisible()) return false;
        }
        return node.getBoundsInLocal().getWidth() > 0 && node.getBoundsInLocal().getHeight() > 0;
    }

    private static boolean visible(Node node) {
        if (!shown(node)) return false;
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        if (bounds.getMinX() < -2 || bounds.getMaxX() > node.getScene().getWidth() + 2
                || bounds.getMinY() < -2 || bounds.getMaxY() > node.getScene().getHeight() + 2) return false;
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll) {
                Node viewport = scroll.lookup(".viewport");
                if (viewport == null) return false;
                Bounds view = viewport.localToScene(viewport.getBoundsInLocal());
                if (bounds.getMinX() < view.getMinX() - 2 || bounds.getMaxX() > view.getMaxX() + 2
                        || bounds.getMinY() < view.getMinY() - 2 || bounds.getMaxY() > view.getMaxY() + 2) return false;
            }
        }
        return true;
    }

    private static void walk(Node node, List<Node> nodes) {
        nodes.add(node);
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) walk(child, nodes);
    }

    private static String name(Node node) {
        if (node instanceof ButtonBase button) return node.getClass().getSimpleName() + ":" + button.getText();
        if (node instanceof TextInputControl text) return node.getClass().getSimpleName() + ":" + text.getPromptText();
        return node.getClass().getSimpleName();
    }

    private static void capture(javafx.scene.Scene scene, String name) throws Exception {
        BufferedImage image = fx(() -> {
            var shot = scene.snapshot(null);
            int width = (int) shot.getWidth(), height = (int) shot.getHeight();
            int[] pixels = new int[width * height];
            shot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            result.setRGB(0, 0, width, height, pixels, 0, width);
            return result;
        });
        ImageIO.write(image, "png", output.resolve(name).toFile());
    }

    private static void check(String name, boolean passed) {
        RESULTS.add(Map.of("role", context, "case", name, "passed", passed));
        if (!passed) failures++;
        System.out.println((passed ? "PASS " : "FAIL ") + context + " " + name);
    }
}
