package vn.labaccess.desktop.virtualreader.protocol;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class VirtualReaderProtocol {
    public record Selection(UUID cardId) { }

    private final Path directory;

    public VirtualReaderProtocol(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static VirtualReaderProtocol configured() {
        String path = System.getProperty("labaccess.virtualReader");
        return path == null || path.isBlank() ? null : new VirtualReaderProtocol(Path.of(path));
    }

    public Selection read() throws IOException {
        Path file = directory.resolve("selection.txt");
        if (!Files.exists(file)) {
            return null;
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).strip();
        if ("NONE".equals(value)) {
            return null;
        }
        try {
            return new Selection(UUID.fromString(value));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid virtual reader selection", failure);
        }
    }

    public void publish(Selection selection) throws IOException {
        Files.createDirectories(directory);
        Path temporary = directory.resolve("selection.tmp");
        String value = selection == null ? "NONE" : selection.cardId().toString();
        Files.writeString(temporary, value, StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve("selection.txt"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public Lease tryAcquire() throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return new Lease(channel, lock);
            }
        } catch (OverlappingFileLockException occupied) {
            channel.close();
            return null;
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
        channel.close();
        return null;
    }

    public record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
