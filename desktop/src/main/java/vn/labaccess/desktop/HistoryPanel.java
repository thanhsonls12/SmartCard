package vn.labaccess.desktop;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

final class HistoryPanel {

    @FunctionalInterface
    interface Runner {
        <T> void submit(String label, Callable<T> work, Consumer<T> onSuccess, Consumer<Exception> onFailure);
    }

    private final BackendClient backend;
    private final Runner runner;
    private final Supplier<String> cardId;
    private final Consumer<String> log;
    private final TableView<BackendClient.HistoryEntry> table = new TableView<>();
    private final Button reload = new Button("Tải lịch sử");
    private final Label verdict = new Label("Chưa tải lịch sử");
    private final VBox node;

    HistoryPanel(BackendClient backend, Runner runner, Supplier<String> cardId, Consumer<String> log) {
        this.backend = backend;
        this.runner = runner;
        this.cardId = cardId;
        this.log = log;
        table.setId("history-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Chưa có giao dịch trong sổ"));
        table.getColumns().add(idColumn());
        table.getColumns().add(column("Thao tác", entry -> operationLabel(entry.operation())));
        table.getColumns().add(column("Thay đổi", entry -> deltaLabel(entry.delta())));
        table.getColumns().add(column("Số dư sau", entry -> String.valueOf(entry.balanceAfter())));
        table.getColumns().add(column("Xác minh", entry -> verifyLabel(entry.verifyStatus())));
        table.setMinHeight(UiScale.SPACE_5 * 8);
        table.setPrefHeight(UiScale.SPACE_5 * 8);
        reload.setId("history-reload");
        reload.setOnAction(event -> request());
        verdict.setId("history-verdict");
        verdict.getStyleClass().add("la-note");
        node = UiSection.of("Lịch sử giao dịch", reload, verdict, table);
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled) {
        reload.setDisable(!enabled);
    }

    int rowCount() {
        return table.getItems().size();
    }

    boolean pressReload() {
        if (reload.isDisabled()) {
            return false;
        }
        reload.fire();
        return true;
    }

    void clear() {
        table.getItems().clear();
        verdict.setText("Chưa tải lịch sử");
        UiLabels.applyNoteTone(verdict, UiLabels.Tone.NEUTRAL);
    }

    void request() {
        String id = cardId.get();
        if (id == null || id.isBlank() || id.equals("-")) {
            clear();
            verdict.setText("Chưa có mã thẻ để đọc lịch sử");
            UiLabels.applyNoteTone(verdict, UiLabels.Tone.NEUTRAL);
            return;
        }
        runner.submit("LICH_SU", () -> backend.historyWithLedger(id), this::show, this::showFailure);
    }

    void show(BackendClient.HistoryLoad load) {
        table.getItems().setAll(load.history().entries());
        String problem = mismatch(load);
        for (BackendClient.HistoryEntry entry : load.history().entries()) {
            log.accept(String.format(
                    "LICH SU counter=%d id=%s op=%s delta=%+d balanceAfter=%d verify=%s",
                    entry.counterAfter(), entry.id(), entry.operation(), entry.delta(),
                    entry.balanceAfter(), entry.verifyStatus()));
        }
        log.accept(String.format(
                "LEDGER cardId=%s lastCounter=%d lastBalance=%d",
                load.ledger().cardId(), load.ledger().lastCounter(), load.ledger().lastBalance()));
        if (problem == null) {
            verdict.setText(matchedText(load));
            UiLabels.applyNoteTone(verdict, UiLabels.Tone.OK);
            log.accept("[OK]   LICH_SU: " + verdict.getText());
            return;
        }
        verdict.setText(problem);
        UiLabels.applyNoteTone(verdict, UiLabels.Tone.ERROR);
        log.accept("[FAIL] LICH_SU: " + problem);
    }

    void showFailure(Exception failure) {
        String message = failure.getMessage() == null ? "không đọc được lịch sử" : failure.getMessage();
        int kept = table.getItems().size();
        verdict.setText(kept == 0
                ? message
                : message + " — bảng vẫn là lần tải trước (" + kept + " dòng)");
        UiLabels.applyNoteTone(verdict, UiLabels.Tone.ERROR);
        log.accept("[FAIL] LICH_SU: " + message
                + (kept == 0 ? " | bảng trống" : " | giữ " + kept + " dòng của lần tải trước"));
    }

    private static String matchedText(BackendClient.HistoryLoad load) {
        int count = load.history().entries().size();
        if (count == 0) {
            return "Sổ chưa có giao dịch. Ledger counter 0, số dư 0.";
        }
        BackendClient.HistoryEntry last = load.history().entries().get(count - 1);
        return String.format(
                "Khớp sổ: %d giao dịch, counter %d, số dư %d",
                count, last.counterAfter(), last.balanceAfter());
    }

    private static String mismatch(BackendClient.HistoryLoad load) {
        var entries = load.history().entries();
        BackendClient.LedgerSnapshot ledger = load.ledger();
        if (!ledger.cardId().equalsIgnoreCase(load.history().cardId())) {
            return "cardId lịch sử khác cardId ledger";
        }
        if (entries.isEmpty()) {
            if (ledger.lastCounter() != 0 || ledger.lastBalance() != 0) {
                return String.format(
                        "Sổ không có dòng nhưng ledger là counter %d số dư %d",
                        ledger.lastCounter(), ledger.lastBalance());
            }
            return null;
        }
        BackendClient.HistoryEntry last = entries.get(entries.size() - 1);
        if (entries.size() != ledger.lastCounter() || last.counterAfter() != ledger.lastCounter()) {
            return String.format(
                    "Số dòng %d, counter cuối %d, ledger counter %d",
                    entries.size(), last.counterAfter(), ledger.lastCounter());
        }
        if (last.balanceAfter() != ledger.lastBalance()) {
            return String.format(
                    "Số dư cuối %d khác ledger %d", last.balanceAfter(), ledger.lastBalance());
        }
        return null;
    }

    private static String operationLabel(String operation) {
        return switch (operation) {
            case "GRANT" -> "Cấp quota";
            case "START" -> "Mở ca";
            case "END" -> "Đóng ca";
            default -> operation;
        };
    }

    private static String verifyLabel(String verifyStatus) {
        return "verified".equals(verifyStatus) ? "Đã xác minh" : verifyStatus;
    }

    private static String deltaLabel(int delta) {
        return delta > 0 ? String.format("%+d", delta) : String.valueOf(delta);
    }

    private static TableColumn<BackendClient.HistoryEntry, String> idColumn() {
        TableColumn<BackendClient.HistoryEntry, String> column =
                column("Mã giao dịch", entry -> entry.id());
        column.setPrefWidth(252);
        column.setMinWidth(210);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
        return column;
    }

    private static TableColumn<BackendClient.HistoryEntry, String> column(
            String title, java.util.function.Function<BackendClient.HistoryEntry, String> value) {
        TableColumn<BackendClient.HistoryEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }
}
