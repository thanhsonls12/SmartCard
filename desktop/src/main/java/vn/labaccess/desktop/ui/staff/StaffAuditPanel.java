package vn.labaccess.desktop.ui.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.backend.BackendClient;
import vn.labaccess.desktop.backend.StaffAuditEntry;

import vn.labaccess.desktop.ui.shared.EnrollmentRunner;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class StaffAuditPanel {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE);
    private final BackendClient backend;
    private final EnrollmentRunner runner;
    private final TextField staff = new TextField();
    private final TextField card = new TextField();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final Button reload = new Button("Tra cứu");
    private final Button clear = new Button("Xóa bộ lọc");
    private final Label status = UiLabels.caption("Chọn bộ lọc hoặc tra cứu toàn bộ.");
    private final Label detail = UiLabels.caption("Chọn một dòng để xem chi tiết.");
    private final TableView<StaffAuditEntry> table = new TableView<>();
    private final VBox node;
    private boolean active = true;
    private boolean loaded;
    private boolean busy;

    public StaffAuditPanel(BackendClient backend, EnrollmentRunner runner) {
        this.backend = backend;
        this.runner = runner;
        staff.setId("audit-staff-code");
        staff.setPromptText("VD: STF001");
        card.setId("audit-card-id");
        card.setPromptText("16 ký tự hex");
        from.setId("audit-from");
        to.setId("audit-to");
        from.setEditable(false);
        to.setEditable(false);
        reload.setId("audit-reload");
        clear.setId("audit-clear");
        status.setId("audit-status");
        detail.setId("audit-detail");
        table.setId("audit-table");
        staff.setPrefColumnCount(12);
        card.setPrefColumnCount(16);
        from.setPromptText("Không giới hạn");
        to.setPromptText("Không giới hạn");
        reload.getStyleClass().addAll("la-action", "la-primary");
        clear.getStyleClass().add("la-action");
        reload.setOnAction(event -> request());
        clear.setOnAction(event -> reset());
        status.setWrapText(true);
        detail.setWrapText(true);
        table.setPlaceholder(new Label("Không có dữ liệu hiển thị"));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(column("Thời gian", 145, row -> TIME.format(row.createdAt())));
        table.getColumns().add(column("Nhân viên", 180, row -> row.staff().display()));
        table.getColumns().add(column("Thao tác", 135, row -> action(row.action())));
        table.getColumns().add(column("Mã thẻ", 145, row -> value(row.cardId())));
        table.getColumns().add(column("Kết quả", 185, row -> outcome(row.outcome())));
        table.setMinHeight(200);
        table.setPrefHeight(320);
        table.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> show(row));
        node = UiSection.of("Nhật ký hoạt động nhân viên",
                UiLabels.caption("Tra cứu theo dữ liệu Backend, không cần kết nối thẻ. Tối đa 100 yêu cầu mới nhất."),
                UiRows.wrapping(UiRows.field("Mã nhân viên", staff), UiRows.field("Mã thẻ", card)),
                UiRows.wrapping(UiRows.field("Từ ngày", from), UiRows.field("Đến ngày", to), reload, clear),
                UiLabels.caption("Ngày theo múi giờ " + ZONE + " · Bao gồm toàn bộ ngày kết thúc."),
                status, table, UiSection.of("Chi tiết và bằng chứng", detail));
    }

    public VBox node() {
        return node;
    }

    public void activate() {
        if (active && !loaded && !busy) {
            request();
        }
    }

    public void setBusy(boolean value) {
        busy = value;
        reload.setDisable(value || !active);
        clear.setDisable(value || !active);
        staff.setDisable(value || !active);
        card.setDisable(value || !active);
        from.setDisable(value || !active);
        to.setDisable(value || !active);
    }

    public void close() {
        active = false;
        table.getItems().clear();
        detail.setText("");
        status.setText("");
        setBusy(true);
    }

    private void reset() {
        staff.clear();
        card.clear();
        from.setValue(null);
        to.setValue(null);
        request();
    }

    private void request() {
        if (!active || busy) {
            return;
        }
        String staffCode = staff.getText().strip();
        String cardId = card.getText().strip();
        LocalDate first = from.getValue();
        LocalDate last = to.getValue();
        table.getItems().clear();
        if (!staffCode.isEmpty() && !staffCode.matches("[A-Za-z0-9_-]{1,20}")) {
            status.setText("Mã nhân viên không hợp lệ.");
            return;
        }
        if (!cardId.isEmpty() && !cardId.matches("[0-9A-Fa-f]{16}")) {
            status.setText("Mã thẻ phải gồm đúng 16 ký tự hex.");
            return;
        }
        if (first != null && last != null && first.isAfter(last)) {
            status.setText("Từ ngày không được sau đến ngày.");
            return;
        }
        Instant start = first == null ? null : first.atStartOfDay(ZONE).toInstant();
        Instant end = last == null ? null : last.plusDays(1).atStartOfDay(ZONE).toInstant();
        status.setText("Đang tải nhật ký...");
        runner.run("STAFF_AUDIT_LIST", () -> backend.staffAudit(staffCode, cardId, start, end),
                this::display, failure -> {
                    if (active) {
                        status.setText("Không tải được nhật ký. Kiểm tra phiên hoặc kết nối Backend.");
                    }
                });
    }

    private void display(List<StaffAuditEntry> entries) {
        if (!active) {
            return;
        }
        loaded = true;
        table.getItems().setAll(entries);
        status.setText(entries.isEmpty() ? "Không tìm thấy hoạt động phù hợp."
                : entries.size() + " yêu cầu" + (entries.size() == 100 ? " · Đạt giới hạn, hãy thu hẹp bộ lọc." : ""));
    }

    private void show(StaffAuditEntry row) {
        if (row == null) {
            detail.setText("Chọn một dòng để xem chi tiết.");
            return;
        }
        StringBuilder text = new StringBuilder("Mã thao tác: ").append(row.operationId())
                .append("\nNhân viên thực hiện: ").append(row.staff().display())
                .append("\nYêu cầu lúc: ").append(TIME.format(row.createdAt()))
                .append("\nThẻ: ").append(value(row.cardId()));
        if (row.originId() != null) {
            text.append("\nThao tác gốc: ").append(row.originId())
                    .append("\nNgười khởi tạo: ").append(row.originStaff().display())
                    .append("\nNgười đối soát: ").append(row.staff().display());
        } else if ("RECONCILE".equals(row.action())) {
            text.append("\nNgười khởi tạo: Chưa xác định (không có liên kết gốc)");
        }
        text.append("\nKết quả: ").append(outcome(row.outcome()));
        for (StaffAuditEntry.Event event : row.events()) {
            text.append("\n• ").append(TIME.format(event.createdAt())).append(" · ").append(outcome(event.outcome()));
            if (event.receipt() != null) {
                text.append(" · Receipt ").append(event.receipt().operation())
                        .append(" / counter ").append(event.receipt().counter())
                        .append(" / ").append(event.receipt().cardId());
            }
        }
        detail.setText(text.toString());
    }

    private static TableColumn<StaffAuditEntry, String> column(String title, int width,
            Function<StaffAuditEntry, String> value) {
        TableColumn<StaffAuditEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private static String value(String text) {
        return text == null ? "Chưa xác định" : text;
    }

    private static String action(String code) {
        return switch (code) {
            case "PERSONALIZE" -> "Phát hành thẻ";
            case "ENROLLMENT" -> "Đăng ký thẻ";
            case "GRANT" -> "Cấp lượt";
            case "PHOTO_UPLOAD" -> "Nạp ảnh";
            case "PHOTO_REPLACE" -> "Thay ảnh";
            case "UNBLOCK" -> "Mở khóa PIN";
            case "PROFILE_SAVE" -> "Sửa hồ sơ";
            case "PROFILE_REOPEN" -> "Mở lại hồ sơ";
            case "PROFILE_REPLACE" -> "Thay yêu cầu hồ sơ";
            case "RECONCILE" -> "Đối soát";
            default -> code;
        };
    }

    private static String outcome(String code) {
        return switch (code) {
            case "RECEIPT_VERIFIED" -> "Receipt đã xác minh";
            case "CLIENT_CONFIRMED" -> "Desktop xác nhận";
            case "CLIENT_NOT_EXECUTED" -> "Desktop: chưa thực hiện";
            case "CLIENT_SUPERSEDED" -> "Desktop: intent bị thay thế";
            case "UNKNOWN" -> "Chưa rõ kết quả";
            default -> code;
        };
    }
}
