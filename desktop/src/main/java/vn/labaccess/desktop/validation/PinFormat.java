package vn.labaccess.desktop.validation;

public final class PinFormat {
    private PinFormat() {
    }

    public static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    public static boolean isDigits(char[] value, int length) {
        if (value == null || value.length != length) {
            return false;
        }
        for (char digit : value) {
            if (!isAsciiDigit(digit)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isDigits(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!isAsciiDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
