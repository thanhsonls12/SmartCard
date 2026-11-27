package vn.labaccess.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;

import vn.labaccess.desktop.backend.StaffAuditEntry;

public final class StaffAuthUiVerify {
    private static Stage window;
    private static int checks;

    private StaffAuthUiVerify() { }

    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String userA = input.readLine();
        char[] passwordA = input.readLine().toCharArray();
        String userB = input.readLine();
        char[] passwordB = input.readLine().toCharArray();
        String cardId = input.readLine();
        String codeA = input.readLine();
        String codeB = input.readLine();
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            fx(() -> {
                window = new Stage();
                new StaffApp().start(window);
                require(window.isShowing(), "real Staff window starts");
                require(window.getScene().lookup("#staff-login-submit") != null, "login displayed first");
                require(window.getScene().lookup("#staff-session-logout") == null, "workspace inaccessible without login");
                return null;
            });
            char[] wrong = Arrays.copyOf(passwordA, passwordA.length + 1);
            wrong[wrong.length - 1] = 'x';
            try {
                enter(userA, wrong);
            } finally {
                Arrays.fill(wrong, '\0');
            }
            await(() -> {
                Label label = (Label) window.getScene().lookup("#staff-login-message");
                return label != null && label.getText().contains("không đúng");
            });
            require(true, "incorrect login stays on login view");
            enter(userA, passwordA);
            await(() -> window.getScene().lookup("#staff-session-identity") != null);
            require(fx(() -> identity().contains("Staff Test A")), "Staff A identity shown");
            verifyAudit(cardId, codeA, codeB);
            logout();
            enter(userB, passwordB);
            await(() -> window.getScene().lookup("#staff-session-identity") != null);
            require(fx(() -> identity().contains("Staff Test B")), "Staff B identity shown after handover");
            require(fx(() -> {
                TabPane tabs = (TabPane) window.getScene().lookup(".la-tabs");
                var tab = tabs.getTabs().stream().filter(item -> "Nhật ký nhân viên".equals(item.getText()))
                        .findFirst().orElseThrow();
                return ((TableView<?>) ((ScrollPane) tab.getContent()).getContent()
                        .lookup("#audit-table")).getItems().isEmpty();
            }), "new Staff workspace has no cached audit data");
            logout();
            System.out.println("STAFF AUTH UI PASS: " + checks + " checks");
        } finally {
            Arrays.fill(passwordA, '\0');
            Arrays.fill(passwordB, '\0');
            Platform.exit();
        }
    }

    private static void verifyAudit(String cardId, String codeA, String codeB) throws Exception {
        fx(() -> {
            TabPane tabs = (TabPane) window.getScene().lookup(".la-tabs");
            tabs.getSelectionModel().select(tabs.getTabs().stream()
                    .filter(tab -> "Nhật ký nhân viên".equals(tab.getText())).findFirst().orElseThrow());
            return null;
        });
        await(() -> window.getScene().lookup("#audit-reload") != null && !button("audit-reload").isDisabled());
        filter(cardId, "", null, null);
        await(() -> auditTable().getItems().size() == 3 && !button("audit-reload").isDisabled());
        require(true, "audit reads three operations without connected card");
        require(fx(() -> auditTable().getItems().stream().map(StaffAuditEntry.class::cast)
                .anyMatch(row -> "UNKNOWN".equals(row.outcome()))), "unknown remains visible");
        filter(cardId, codeB, LocalDate.now(), LocalDate.now());
        await(() -> auditTable().getItems().size() == 1 && !button("audit-reload").isDisabled());
        fx(() -> {
            auditTable().getSelectionModel().select(0);
            String detail = label("audit-detail").getText();
            require(detail.contains("Người khởi tạo: Staff Test A") && detail.contains(codeA)
                    && detail.contains("Người đối soát: Staff Test B") && detail.contains(codeB),
                    "filtered reconciliation detail retains both actors");
            require(detail.contains("Receipt GRANT / counter 1"), "verified receipt linked in detail");
            return null;
        });
        filter(cardId, codeA, null, null);
        await(() -> auditTable().getItems().size() == 2 && !button("audit-reload").isDisabled());
        require(true, "filter by initiating staff");
        filter(cardId, "", LocalDate.now().minusDays(2), LocalDate.now().minusDays(1));
        await(() -> label("audit-status").getText().contains("Không tìm thấy") && !button("audit-reload").isDisabled());
        require(fx(() -> auditTable().getItems().isEmpty()), "date range excludes today's fixture");
        filter("bad", "", null, null);
        require(fx(() -> label("audit-status").getText().contains("16 ký tự")), "invalid card filter rejected");
        filter(cardId, "", LocalDate.now().plusDays(1), LocalDate.now());
        require(fx(() -> label("audit-status").getText().contains("Từ ngày")), "inverted date range rejected");
        fx(() -> {
            button("audit-clear").fire();
            return null;
        });
        await(() -> !button("audit-reload").isDisabled());
        require(fx(() -> ((TextField) window.getScene().lookup("#audit-card-id")).getText().isEmpty()
                && ((DatePicker) window.getScene().lookup("#audit-from")).getValue() == null),
                "clear filter resets and reloads");
    }

    private static void filter(String card, String staff, LocalDate from, LocalDate to) throws Exception {
        fx(() -> {
            ((TextField) window.getScene().lookup("#audit-card-id")).setText(card);
            ((TextField) window.getScene().lookup("#audit-staff-code")).setText(staff);
            ((DatePicker) window.getScene().lookup("#audit-from")).setValue(from);
            ((DatePicker) window.getScene().lookup("#audit-to")).setValue(to);
            button("audit-reload").fire();
            return null;
        });
    }

    private static TableView<?> auditTable() {
        return (TableView<?>) window.getScene().lookup("#audit-table");
    }

    private static Button button(String id) {
        return (Button) window.getScene().lookup("#" + id);
    }

    private static Label label(String id) {
        return (Label) window.getScene().lookup("#" + id);
    }

    private static void enter(String username, char[] password) throws Exception {
        fx(() -> {
            ((TextField) window.getScene().lookup("#staff-login-username")).setText(username);
            ((PasswordField) window.getScene().lookup("#staff-login-password")).setText(new String(password));
            Button button = (Button) window.getScene().lookup("#staff-login-submit");
            require(!button.isDisabled(), "login button enabled");
            button.fire();
            return null;
        });
    }

    private static String identity() {
        return ((Label) window.getScene().lookup("#staff-session-identity")).getText();
    }

    private static void logout() throws Exception {
        fx(() -> {
            ((Button) window.getScene().lookup("#staff-session-logout")).fire();
            return null;
        });
        await(() -> window.getScene().lookup("#staff-login-submit") != null);
        require(fx(() -> window.getScene().lookup("#staff-session-logout") == null), "logout removes staff workspace");
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (fx(condition)) { return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for Staff UI state");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> future = new FutureTask<>(action);
        Platform.runLater(future);
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean value, String name) {
        if (!value) { throw new AssertionError(name); }
        checks++;
        System.out.println("PASS " + name);
    }
}
