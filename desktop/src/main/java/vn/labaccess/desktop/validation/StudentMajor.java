package vn.labaccess.desktop.validation;

import java.util.Locale;

public final class StudentMajor {

    private StudentMajor() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }

    public static String fromStudentId(String studentId) {
        if (studentId == null || studentId.length() < 2) {
            return "";
        }
        return switch (studentId.substring(0, 2).toUpperCase(Locale.ROOT)) {
            case "CT" -> "Công nghệ thông tin";
            case "AT" -> "An toàn thông tin";
            case "DT" -> "Điện tử viễn thông";
            default -> "";
        };
    }
}
