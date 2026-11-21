package vn.labaccess.desktop;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

final class HistoryPanel {

    private enum OperationFilter {
        ALL("Tất cả thao tác", null), GRANT("Cấp lượt", "GRANT"),
        START("Mở ca", "START"), END("Đóng ca", "END");

        private final String label;
        private final String operation;

        OperationFilter(String label, String operation) {
            this.label = label;
            this.operation = operation;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final ZoneId DISPLAY_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss").withZone(DISPLAY_ZONE);

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
    private final Label loadStatus = UiLabels.caption("");
    private final Label placeholder = UiLabels.caption("Chưa tải lịch sử");
    private final Label why = UiLabels.whyNote();
    private final ComboBox<OperationFilter> operationFilter = new ComboBox<>();
    private final DatePicker recordedDay = new DatePicker();
    private final Button clearFilters = new Button("Xóa lọc");
    private final Label filteredCount = UiLabels.caption("");
    private final Label selection = UiLabels.value();
    private List<BackendClient.HistoryEntry> entries = List.of();
    private boolean loaded;
    private String integrityProblem;
    private final VBox node;
    private String currentCardId;

    HistoryPanel(BackendClient backend, Runner runner, Supplier<String> cardId, Consumer<String> log) {
        this.backend = backend;
        this.runner = runner;
        this.cardId = cardId;
        this.log = log;
        currentCardId = normalizeCardId(cardId.get());
        table.setId("history-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.widthProperty().addListener((observable, previous, width) -> table.setColumnResizePolicy(
                width.doubleValue() < 420
                        ? TableView.UNCONSTRAINED_RESIZE_POLICY
                        : TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN));
        table.setPlaceholder(placeholder);
        table.getColumns().add(column("Ghi nhận lúc", 68, entry -> DISPLAY_TIME.format(entry.recordedAt())));
        table.getColumns().add(column("Thao tác", 104, entry -> operationLabel(entry.operation())));
        table.getColumns().add(column("Thay đổi", 104, entry -> deltaLabel(entry.delta())));
        table.getColumns().add(column("Lượt còn lại", 48, entry -> String.valueOf(entry.balanceAfter())));
        table.getColumns().add(column("Xác minh", 57, entry -> verifyLabel(entry.verifyStatus())));
        table.setMinHeight(UiScale.SPACE_5 * 8);
        table.setPrefHeight(UiScale.SPACE_5 * 8);
        reload.setId("history-reload");
        reload.getStyleClass().add("la-action");
        reload.setOnAction(event -> request());
        operationFilter.getItems().setAll(OperationFilter.values());
        operationFilter.setValue(OperationFilter.ALL);
        operationFilter.setId("history-operation");
        operationFilter.valueProperty().addListener((observable, was, now) -> applyFilters());
        recordedDay.setId("history-day");
        recordedDay.setEditable(false);
        recordedDay.setPromptText("Tất cả ngày");
        recordedDay.setTooltip(new Tooltip("Ngày hệ thống ghi nhận, theo múi giờ " + DISPLAY_ZONE));
        recordedDay.valueProperty().addListener((observable, was, now) -> applyFilters());
        clearFilters.getStyleClass().add("la-action");
        clearFilters.setId("history-clear-filters");
        clearFilters.setDisable(true);
        clearFilters.setOnAction(event -> {
            operationFilter.setValue(OperationFilter.ALL);
            recordedDay.setValue(null);
        });
        selection.setWrapText(true);
        selection.setId("history-selection");
        selection.setText("Chọn một giao dịch để xem mã và thông tin ghi nhận.");
        table.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> showSelection(now));
        TitledPane details = new TitledPane("Chi tiết giao dịch đã chọn", selection);
        details.setExpanded(false);
        details.getStyleClass().add("la-receipt-details");
        filteredCount.setId("history-count");
        UiLabels.show(filteredCount, false);
        loadStatus.setId("history-load-status");
        loadStatus.setWrapText(true);
        UiLabels.show(loadStatus, false);
        verdict.setId("history-verdict");
        verdict.getStyleClass().add("la-note");
        verdict.setWrapText(true);
        placeholder.setWrapText(true);
        node = UiSection.of("Lịch sử giao dịch", UiRows.wrapping(reload, loadStatus), verdict,
                UiRows.wrapping(UiRows.field("Thao tác", operationFilter),
                        UiRows.field("Ngày ghi nhận", recordedDay), clearFilters),
                UiLabels.caption("Mới nhất trước · thời điểm hệ thống lưu, không phải thời gian do thẻ ký."),
                why, filteredCount, table, details);
    }

    VBox node() {
        return node;
    }

    void setEnabled(boolean enabled, String whyDisabled) {
        reload.setDisable(!enabled);
        UiLabels.setWhy(why, whyDisabled);
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

    void onCard(String cardId) {
        String next = normalizeCardId(cardId);
        if (!next.equals(currentCardId)) {
            currentCardId = next;
            clear();
        }
    }

    private static String normalizeCardId(String cardId) {
        return cardId == null || cardId.isBlank() || "-".equals(cardId)
                ? "" : cardId.strip().toUpperCase(java.util.Locale.ROOT);
    }

    void clear() {
        loaded = false;
        UiLabels.show(loadStatus, false);
        loadStatus.setText("");
        entries = List.of();
        integrityProblem = null;
        table.getItems().clear();
        table.getSelectionModel().clearSelection();
        operationFilter.setValue(OperationFilter.ALL);
        recordedDay.setValue(null);
        UiLabels.show(filteredCount, false);
        showSelection(null);
        verdict.setText("Chưa tải lịch sử");
        placeholder.setText("Chưa tải lịch sử");
        UiLabels.applyNoteTone(verdict, UiLabels.Tone.NEUTRAL);
    }

    void request() {
        onCard(cardId.get());
        String id = currentCardId;
        if (id.isEmpty()) {
            clear();
            verdict.setText("Chưa có mã thẻ để đọc lịch sử");
            UiLabels.applyNoteTone(verdict, UiLabels.Tone.NEUTRAL);
            return;
        }
        loadStatus.setText(loaded ? "Đang cập nhật; bảng vẫn là lần tải trước" : "Đang tải lịch sử…");
        UiLabels.show(loadStatus, true);
        UiLabels.applyNoteTone(loadStatus, UiLabels.Tone.NEUTRAL);
        if (!loaded) {
            placeholder.setText("Đang tải lịch sử…");
        }
        runner.submit("LICH_SU", () -> backend.historyWithLedger(id), load -> {
            if (id.equals(currentCardId)) {
                show(load);
            }
        }, failure -> {
            if (id.equals(currentCardId)) {
                showFailure(failure);
            }
        });
    }

    void show(BackendClient.HistoryLoad load) {
        if (!currentCardId.equals(normalizeCardId(load.history().cardId()))) {
            return;
        }
        UiLabels.show(loadStatus, false);
        loadStatus.setText("");
        String problem = mismatch(load);
        entries = List.copyOf(load.history().entries());
        loaded = true;
        integrityProblem = problem;
        applyFilters();
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
        String message = failure instanceof BackendClient.Failure backendFailure
                ? UiLabels.systemFailure(backendFailure)
                : failure.getMessage() == null ? "không đọc được lịch sử" : failure.getMessage();
        int kept = entries.size();
        placeholder.setText("Không tải được lịch sử. Bấm Tải lịch sử để thử lại.");
        loadStatus.setText(!loaded
                ? "Không tải được lịch sử: " + message
                : message + " — bảng vẫn là lần tải trước (" + kept + " dòng)");
        UiLabels.show(loadStatus, true);
        UiLabels.applyNoteTone(loadStatus, UiLabels.Tone.ERROR);
        if (!loaded) {
            verdict.setText("Chưa đối chiếu được lịch sử với sổ giao dịch");
            UiLabels.applyNoteTone(verdict, UiLabels.Tone.NEUTRAL);
        }
        log.accept("[FAIL] LICH_SU: " + message
                + (kept == 0 ? " | bảng trống" : " | giữ " + kept + " dòng của lần tải trước"));
    }

    private static String matchedText(BackendClient.HistoryLoad load) {
        int count = load.history().entries().size();
        if (count == 0) {
            return "Sổ chưa có giao dịch. Sổ Backend ghi 0 lượt còn lại.";
        }
        BackendClient.HistoryEntry last = load.history().entries().get(count - 1);
        return String.format(
                "Khớp sổ: %d giao dịch, còn %d lượt",
                count, last.balanceAfter());
    }

    private static String mismatch(BackendClient.HistoryLoad load) {
        var entries = load.history().entries();
        BackendClient.LedgerSnapshot ledger = load.ledger();
        if (!ledger.cardId().equalsIgnoreCase(load.history().cardId())) {
            return "Mã thẻ của lịch sử khác mã thẻ trong sổ Backend";
        }
        if (entries.isEmpty()) {
            if (ledger.lastCounter() != 0 || ledger.lastBalance() != 0) {
                return String.format(
                        "Sổ không có dòng nhưng sổ Backend ghi %d giao dịch, còn %d lượt",
                        ledger.lastCounter(), ledger.lastBalance());
            }
            return null;
        }
        BackendClient.HistoryEntry last = entries.get(entries.size() - 1);
        if (entries.size() != ledger.lastCounter() || last.counterAfter() != ledger.lastCounter()) {
            return String.format(
                    "Số dòng %d, giao dịch cuối %d, sổ Backend %d giao dịch",
                    entries.size(), last.counterAfter(), ledger.lastCounter());
        }
        if (last.balanceAfter() != ledger.lastBalance()) {
            return String.format(
                    "Số lượt còn lại %d khác sổ Backend %d", last.balanceAfter(), ledger.lastBalance());
        }
        return null;
    }

    private static String operationLabel(String operation) {
        return switch (operation) {
            case "GRANT" -> "Cấp lượt";
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

    private void applyFilters() {
        clearFilters.setDisable(recordedDay.getValue() == null && operationFilter.getValue() == OperationFilter.ALL);
        if (!loaded) {
            return;
        }
        OperationFilter filter = operationFilter.getValue();
        var day = recordedDay.getValue();
        table.getSelectionModel().clearSelection();
        table.getItems().setAll(entries.stream()
                .filter(entry -> filter == null || filter.operation == null
                        || filter.operation.equals(entry.operation()))
                .filter(entry -> day == null || day.equals(entry.recordedAt().atZone(DISPLAY_ZONE).toLocalDate()))
                .sorted(Comparator.comparingLong(BackendClient.HistoryEntry::counterAfter).reversed())
                .toList());
        filteredCount.setText("Hiển thị " + table.getItems().size() + " / " + entries.size() + " giao dịch");
        UiLabels.show(filteredCount, true);
        placeholder.setText(integrityProblem != null ? "Chưa thể xác nhận lịch sử — dữ liệu không khớp sổ"
                : entries.isEmpty() ? "Sổ chưa có giao dịch"
                : "Không có giao dịch phù hợp. Bấm Xóa lọc để xem tất cả.");
        showSelection(table.getSelectionModel().getSelectedItem());
    }

    private void showSelection(BackendClient.HistoryEntry entry) {
        selection.setText(entry == null ? "Chọn một giao dịch để xem mã và thông tin ghi nhận."
                : "Mã giao dịch: " + entry.id()
                        + "\nSố thứ tự trên thẻ: " + entry.counterAfter()
                        + "\nGhi nhận lúc: " + DISPLAY_TIME.format(entry.recordedAt())
                        + " (" + DISPLAY_ZONE + ")"
                        + "\n" + operationLabel(entry.operation()) + " · thay đổi " + deltaLabel(entry.delta())
                        + " lượt · còn " + entry.balanceAfter() + " lượt"
                        + "\n" + verifyLabel(entry.verifyStatus()));
    }

    private static TableColumn<BackendClient.HistoryEntry, String> column(
            String title, double minWidth, java.util.function.Function<BackendClient.HistoryEntry, String> value) {
        TableColumn<BackendClient.HistoryEntry, String> column = new TableColumn<>(title);
        column.setMinWidth(minWidth);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setSortable(false);
        return column;
    }
}
