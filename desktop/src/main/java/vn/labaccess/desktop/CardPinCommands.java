package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

final class CardPinCommands {

    private static final byte INS_VERIFY_STUDENT_PIN = (byte) 0x20;
    private static final byte INS_CHANGE_STUDENT_PIN = (byte) 0x21;
    private static final byte INS_UNBLOCK_STUDENT_PIN = (byte) 0x23;
    private static final int PIN_LENGTH = 6;
    private static final int ADMIN_PIN_LENGTH = 8;

    private final CardTransport transport;
    private final Supplier<CardStatus> statusSnapshot;
    private final IntConsumer responseSwSink;

    private volatile int lastPinSw = -1;

    CardPinCommands(CardTransport transport, Supplier<CardStatus> statusSnapshot,
            IntConsumer responseSwSink) {
        this.transport = transport;
        this.statusSnapshot = statusSnapshot;
        this.responseSwSink = responseSwSink;
    }

    CardOperationResult verifyStudentPin(char[] pin) throws CardException {
        return pinCommand("VERIFY_STUDENT_PIN", INS_VERIFY_STUDENT_PIN,
                "quyền student đã mở cho APDU kế tiếp",
                new PinField(pin, PIN_LENGTH, "PIN"));
    }

    CardOperationResult changeStudentPin(char[] oldPin, char[] newPin) throws CardException {
        return pinCommand("CHANGE_STUDENT_PIN", INS_CHANGE_STUDENT_PIN,
                "PIN đã đổi, retry reset về 3",
                new PinField(oldPin, PIN_LENGTH, "PIN cũ"),
                new PinField(newPin, PIN_LENGTH, "PIN mới"));
    }

    CardOperationResult unblockStudentPin(char[] adminPin, char[] newPin) throws CardException {
        return pinCommand("UNBLOCK_STUDENT_PIN", INS_UNBLOCK_STUDENT_PIN,
                "PIN đã mở khóa, retry reset về 3",
                new PinField(adminPin, ADMIN_PIN_LENGTH, "admin PIN"),
                new PinField(newPin, PIN_LENGTH, "PIN mới"));
    }

    private CardOperationResult pinCommand(String label, byte ins, String successText,
            PinField... fields) throws CardException {
        transport.requireConnected();
        lastPinSw = -1;
        CardStatus current = statusSnapshot.get();
        if (current == null) {
            return CardOperationResult.fail(
                    label + ": phải SELECT + GET_STATUS thẻ ISSUED trước | không gửi APDU");
        }
        if (current.lifecycle() != CardLifecycle.ISSUED) {
            return CardOperationResult.fail(String.format("%s: lifecycle=%d %s | không gửi APDU",
                    label, current.lifecycle(), CardLifecycle.text(current.lifecycle())));
        }
        int total = 0;
        for (PinField field : fields) {
            total += field.length();
        }
        byte[] data = new byte[total];
        try {
            int offset = 0;
            for (PinField field : fields) {
                String problem = copyDigits(field, data, offset);
                if (problem != null) {
                    return CardOperationResult.fail(label + ": " + problem + " | không gửi APDU");
                }
                offset += field.length();
            }
            ResponseAPDU response = transport.transmit(new CommandAPDU(
                    CardProtocol.CLA_APPLICATION, ins, 0x00, 0x00, data));
            int sw = response.getSW();
            responseSwSink.accept(sw);
            lastPinSw = sw;
            if (sw == Sw.OK) {
                return CardOperationResult.ok(String.format(
                        "%s Case 3S Lc=%d SW=9000 | %s", label, total, successText));
            }
            return CardOperationResult.fail(String.format("%s Case 3S Lc=%d SW=%04X | %s",
                    label, total, sw, pinStatusText(sw)));
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static String copyDigits(PinField field, byte[] target, int offset) {
        if (field.value() == null) {
            return field.name() + " không được để trống";
        }
        if (field.value().length != field.length()) {
            return String.format("%s phải đúng %d chữ số", field.name(), field.length());
        }
        for (int index = 0; index < field.length(); index++) {
            char digit = field.value()[index];
            if (digit < '0' || digit > '9') {
                return field.name() + " chỉ gồm chữ số 0-9";
            }
            target[offset + index] = (byte) digit;
        }
        return null;
    }

    private static String pinStatusText(int sw) {
        if (sw == Sw.AUTH_BLOCKED) {
            return "PIN đã bị khóa, cần UNBLOCK";
        }
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "thẻ chưa personalize hoặc PIN chưa gán";
        }
        if ((sw & 0xFF00) == 0x6300) {
            return String.format("PIN sai, còn %d lần thử", sw & 0x0F);
        }
        if (sw == Sw.WRONG_DATA) {
            return "định dạng PIN sai";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "P1/P2 phải 0000";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc không khớp, Case 3S không Le";
        }
        return String.format("SW=%04X không xác thực được", sw);
    }

    int lastPinSw() {
        return lastPinSw;
    }

    void reset() {
        lastPinSw = -1;
    }

    private record PinField(char[] value, int length, String name) {
    }
}
