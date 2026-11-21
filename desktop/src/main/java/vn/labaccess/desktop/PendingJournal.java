package vn.labaccess.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

record PendingJournal(Path file) {

    record Entry(
            String cardId,
            long expectedCounter,
            int operation,
            int delta,
            int expectedBalance,
            byte[] visitId,
            long createdAtEpochMillis) {

        String visitIdHex() {
            return visitId == null ? "" : HexFormat.of().formatHex(visitId);
        }
    }

    enum Status {
        PENDING, RESOLVED
    }

    record Loaded(boolean present, boolean valid, Entry entry, Status status, String problem) {
        String recoverySummary() {
            return valid
                    ? "Nhật ký đã đánh dấu hoàn tất nhưng chưa được dọn khỏi máy."
                    : "Không đọc được nhật ký giao dịch: " + problem + ".";
        }

        static Loaded absent() {
            return new Loaded(false, true, null, null, null);
        }
    }

    private static final String FIELD_SEPARATOR = "|";

    boolean writePending(Entry entry) {
        if (load().present()) {
            return false;
        }
        Loaded planned = parse(formatLine(entry, Status.PENDING));
        if (!planned.valid()) {
            return false;
        }
        return writeLine(formatLine(entry, Status.PENDING));
    }

    boolean writeResolved(Entry entry) {
        return writeLine(formatLine(entry, Status.RESOLVED));
    }

    Loaded load() {
        if (!Files.isRegularFile(file)) {
            return Loaded.absent();
        }
        String line;
        try {
            line = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException | java.io.UncheckedIOException ex) {
            return new Loaded(true, false, null, null, "đọc lỗi: " + ex.getClass().getSimpleName());
        }
        if (line.isEmpty()) {
            return new Loaded(true, false, null, null, "file rỗng");
        }
        return parse(line);
    }

    void remove() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    static Loaded parse(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 9) {
            return new Loaded(true, false, null, null, "số trường không hợp lệ");
        }
        try {
            if (!"v1".equals(fields[0])) {
                return new Loaded(true, false, null, null, "schema không hỗ trợ");
            }
            String statusText = fields[1];
            if (!statusText.equals(Status.PENDING.name()) && !statusText.equals(Status.RESOLVED.name())) {
                return new Loaded(true, false, null, null, "trạng thái không hợp lệ");
            }
            Status status = Status.valueOf(statusText);
            String cardId = fields[2];
            if (!cardId.matches("[0-9A-Fa-f]{16}")) {
                return new Loaded(true, false, null, null, "cardId không hợp lệ");
            }
            long counter = Long.parseLong(fields[3]);
            if (counter < 0 || counter > Receipt.COUNTER_MASK) {
                return new Loaded(true, false, null, null, "counter không hợp lệ");
            }
            int operation = Integer.parseInt(fields[4]);
            if (operation != Receipt.OPERATION_GRANT && operation != Receipt.OPERATION_START
                    && operation != Receipt.OPERATION_END) {
                return new Loaded(true, false, null, null, "operation không hợp lệ");
            }
            int delta = Short.parseShort(fields[5]);
            if ((operation == Receipt.OPERATION_GRANT && (delta <= 0 || delta > Receipt.MAX_BALANCE))
                    || (operation == Receipt.OPERATION_START && delta != -1)
                    || (operation == Receipt.OPERATION_END && delta != 0)) {
                return new Loaded(true, false, null, null, "delta không khớp operation");
            }
            int balance = Integer.parseInt(fields[6]);
            if (balance < 0 || balance > Receipt.MAX_BALANCE) {
                return new Loaded(true, false, null, null, "balance không hợp lệ");
            }
            String visitIdHex = fields[7];
            byte[] visitId;
            if (visitIdHex.isEmpty()) {
                visitId = null;
            } else {
                if (!visitIdHex.matches("[0-9A-Fa-f]{32}")) {
                    return new Loaded(true, false, null, null, "visitId không hợp lệ");
                }
                visitId = HexFormat.of().parseHex(visitIdHex);
            }
            if (operation == Receipt.OPERATION_GRANT && !visitIdHex.isEmpty()) {
                return new Loaded(true, false, null, null, "GRANT có visitId");
            }
            if (operation != Receipt.OPERATION_GRANT
                    && (visitIdHex.isEmpty() || isAllZero(visitId))) {
                return new Loaded(true, false, null, null, "START/END thiếu visitId hợp lệ");
            }
            long createdAt = Long.parseLong(fields[8]);
            if (createdAt < 0) {
                return new Loaded(true, false, null, null, "creation time không hợp lệ");
            }
            Entry entry = new Entry(cardId.toUpperCase(), counter, operation, delta, balance,
                    visitId, createdAt);
            return new Loaded(true, true, entry, status, null);
        } catch (NumberFormatException ex) {
            return new Loaded(true, false, null, null, "số sai định dạng");
        }
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static String formatLine(Entry entry, Status status) {
        return String.join(FIELD_SEPARATOR,
                "v1",
                status.name(),
                entry.cardId(),
                Long.toString(entry.expectedCounter()),
                Integer.toString(entry.operation()),
                Integer.toString(entry.delta()),
                Integer.toString(entry.expectedBalance()),
                entry.visitIdHex(),
                Long.toString(entry.createdAtEpochMillis()));
    }

    private boolean writeLine(String line) {
        Path parent = file.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (AtomicMoveNotSupportedException ex) {
                return false;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            return false;
        }
    }
}
