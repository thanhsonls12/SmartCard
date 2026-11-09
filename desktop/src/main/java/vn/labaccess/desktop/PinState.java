package vn.labaccess.desktop;

record PinState(String title, String detail) {

    enum Operation {
        VERIFY, CHANGE, UNBLOCK
    }

    static PinState unknown() {
        return new PinState("Chưa biết trạng thái PIN", "bấm VERIFY để đọc lại từ thẻ");
    }

    static PinState notSent() {
        return new PinState("Chưa gửi lệnh PIN", "chưa có kết quả từ thẻ");
    }

    static PinState of(Operation operation, boolean ok, int sw) {
        if (sw < 0) {
            return notSent();
        }
        if (sw == Sw.OK) {
            return success(operation);
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return blocked(operation);
        }
        if ((sw & 0xFF00) == 0x6300) {
            return new PinState("Sai " + subject(operation),
                    "còn " + (sw & 0x0F) + " lượt thử");
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return new PinState("PIN chưa dùng được",
                    "thẻ chưa ISSUED hoặc PIN chưa được gán");
        }
        if (sw == Sw.WRONG_DATA) {
            return new PinState("Dữ liệu PIN sai định dạng", "PIN phải là chữ số 0-9");
        }
        if (sw == Sw.WRONG_LENGTH) {
            return new PinState("Độ dài APDU sai", "Case 3S không gửi Le, Lc phải khớp");
        }
        if (sw == Sw.WRONG_P1P2) {
            return new PinState("P1/P2 không hợp lệ", "phải là 0000");
        }
        return new PinState(String.format("SW=%04X chưa phân loại", sw),
                "không suy ra được trạng thái PIN");
    }

    private static PinState success(Operation operation) {
        if (operation == Operation.VERIFY) {
            return new PinState("Đã xác thực",
                    "quyền chỉ dành cho đúng lệnh nhạy cảm kế tiếp");
        }
        if (operation == Operation.CHANGE) {
            return new PinState("PIN đã đổi", "retry reset về 3, PIN cũ không dùng được");
        }
        return new PinState("PIN đã mở khóa", "retry reset về 3, dùng PIN mới");
    }

    private static PinState blocked(Operation operation) {
        if (operation == Operation.UNBLOCK) {
            return new PinState("admin PIN đang khóa",
                    "không có đường mở khóa admin trong applet");
        }
        return new PinState("PIN đang khóa", "cần admin PIN đúng để UNBLOCK");
    }

    private static String subject(Operation operation) {
        if (operation == Operation.CHANGE) {
            return "PIN cũ";
        }
        if (operation == Operation.UNBLOCK) {
            return "admin PIN";
        }
        return "PIN";
    }

    String text() {
        return title + " — " + detail;
    }
}
