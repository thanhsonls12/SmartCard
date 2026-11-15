package vn.labaccess.desktop;

record PinState(String title, String detail) {

    enum Operation {
        VERIFY, CHANGE, UNBLOCK
    }

    static PinState unknown() {
        return new PinState("Chưa biết trạng thái PIN", "nhập PIN và bấm Xác thực PIN để kiểm tra");
    }

    static PinState notSent() {
        return new PinState("Chưa gửi lệnh PIN", "chưa có kết quả từ thẻ");
    }

    static PinState of(Operation operation, boolean ok, int sw) {
        if (sw < 0) {
            return notSent();
        }
        if (sw == Sw.OK) {
            return ok ? success(operation)
                    : new PinState("Chưa xác nhận kết quả PIN", "xem Nhật ký trước khi thao tác lại");
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
                    "thẻ chưa phát hành hoặc PIN chưa được gán");
        }
        if (sw == Sw.WRONG_DATA) {
            return new PinState("Dữ liệu PIN sai định dạng", "PIN phải là chữ số 0-9");
        }
        if (sw == Sw.WRONG_LENGTH) {
            return new PinState("Không thực hiện được thao tác PIN",
                    "ứng dụng gửi dữ liệu không hợp lệ; xem Nhật ký hoặc liên hệ người quản trị");
        }
        if (sw == Sw.WRONG_P1P2) {
            return new PinState("Không thực hiện được thao tác PIN",
                    "yêu cầu gửi tới thẻ không hợp lệ; xem Nhật ký hoặc liên hệ người quản trị");
        }
        return new PinState("Chưa xác định được trạng thái PIN",
                "xem Nhật ký để kiểm tra phản hồi của thẻ trước khi thử lại");
    }

    private static PinState success(Operation operation) {
        if (operation == Operation.VERIFY) {
            return new PinState("PIN đúng ở lần kiểm tra vừa rồi",
                    "các thao tác khác vẫn cần nhập PIN");
        }
        if (operation == Operation.CHANGE) {
            return new PinState("PIN đã đổi", "còn 3 lượt thử, PIN cũ không dùng được");
        }
        return new PinState("PIN đã mở khóa", "còn 3 lượt thử, dùng PIN mới");
    }

    private static PinState blocked(Operation operation) {
        if (operation == Operation.UNBLOCK) {
            return new PinState("PIN quản trị đang khóa",
                    "thẻ không hỗ trợ mở khóa PIN quản trị");
        }
        return new PinState("PIN đang khóa", "dùng PIN quản trị đúng để mở khóa");
    }

    private static String subject(Operation operation) {
        if (operation == Operation.CHANGE) {
            return "PIN cũ";
        }
        if (operation == Operation.UNBLOCK) {
            return "PIN quản trị";
        }
        return "PIN";
    }

    String text() {
        return title + " — " + detail;
    }

    static UiLabels.Tone tone(boolean ok, int sw) {
        if (sw < 0) {
            return UiLabels.Tone.NEUTRAL;
        }
        if (sw == Sw.OK) {
            return ok ? UiLabels.Tone.OK : UiLabels.Tone.PENDING;
        }
        return UiLabels.Tone.ERROR;
    }
}
