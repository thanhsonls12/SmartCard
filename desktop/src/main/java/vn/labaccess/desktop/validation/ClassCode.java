package vn.labaccess.desktop.validation;

import java.util.Locale;

public final class ClassCode {

    static final int MIN_LENGTH = 5;
    static final int MAX_LENGTH = 7;
    public static final String PROMPT = "5–7 chữ/số, ví dụ AT19A";

    private static final String PATTERN = "[A-Z0-9]{" + MIN_LENGTH + "," + MAX_LENGTH + "}";

    private ClassCode() {
        throw new AssertionError("Không khởi tạo lớp hằng");
    }

    public static String normalize(String entered) {
        return entered == null ? "" : entered.strip().toUpperCase(Locale.ROOT);
    }

    public static String problem(String entered) {
        if (!normalize(entered).matches(PATTERN)) {
            return "Lớp phải " + MIN_LENGTH + "–" + MAX_LENGTH
                    + " ký tự chữ hoặc số, ví dụ AT19A.";
        }
        return null;
    }
}
