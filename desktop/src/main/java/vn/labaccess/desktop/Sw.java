package vn.labaccess.desktop;

/**
 * Status Word dùng ở tầng desktop.
 *
 * Đây là nơi duy nhất được phép so sánh SW; UI và gateway dùng hằng thay vì
 * literal (Q-15). Tập giá trị phải khớp bảng SW trong
 * docs/design/apdu-commands.md — không tự thêm mã ngoài bảng.
 */
final class Sw {

    /** Lệnh thành công. */
    static final int OK = 0x9000;

    /** Chưa đáp ứng điều kiện bảo mật. */
    static final int SECURITY_NOT_SATISFIED = 0x6982;

    /** PIN hoặc phương thức xác thực đã bị khóa. */
    static final int AUTH_BLOCKED = 0x6983;

    /** Trạng thái nghiệp vụ không cho phép. */
    static final int CONDITIONS_NOT_SATISFIED = 0x6985;

    /** Độ dài APDU không hợp lệ. */
    static final int WRONG_LENGTH = 0x6700;

    /** Dữ liệu truyền vào không hợp lệ. */
    static final int WRONG_DATA = 0x6A80;

    /** P1/P2 không hợp lệ. */
    static final int WRONG_P1P2 = 0x6A86;

    /** INS không hỗ trợ. */
    static final int INS_NOT_SUPPORTED = 0x6D00;

    /** CLA không hỗ trợ. */
    static final int CLA_NOT_SUPPORTED = 0x6E00;

    /** Lỗi nội bộ chưa phân loại; không tự gửi lại lệnh thay đổi số dư. */
    static final int INTERNAL_ERROR = 0x6F00;

    private Sw() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }
}
