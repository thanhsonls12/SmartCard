package vn.labaccess.desktop.virtualreader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import vn.labaccess.desktop.virtualreader.protocol.VirtualReaderProtocol;

import vn.labaccess.desktop.session.CardSession;

public final class VirtualReaderController {
    private final Path root;
    private final Path directory;
    private final VirtualReaderProtocol protocol;
    private final VirtualCardCatalog catalog;
    private final int port;
    private final CardSession.Role role;
    private final VirtualReaderProtocol otherProtocol;
    private volatile VirtualCardCatalog.Card active;
    private volatile boolean recoveryRequired;

    public VirtualReaderController(Path root, Path directory, int port, CardSession.Role role) throws IOException {
        this.root = root;
        this.directory = directory;
        this.port = port;
        this.role = role;
        protocol = new VirtualReaderProtocol(directory.resolve(slotName(role)));
        CardSession.Role other = role == CardSession.Role.STAFF ? CardSession.Role.KIOSK : CardSession.Role.STAFF;
        otherProtocol = new VirtualReaderProtocol(directory.resolve(slotName(other)));
        catalog = new VirtualCardCatalog(directory);
    }

    private static String slotName(CardSession.Role role) {
        return role.name().toLowerCase(java.util.Locale.ROOT);
    }

    boolean recoveryRequired() {
        return recoveryRequired;
    }

    public List<VirtualCardCatalog.Card> cards() throws IOException {
        return catalog.load();
    }

    public VirtualCardCatalog.Card active() {
        return active;
    }

    void importCard(String name, Path image) throws IOException {
        catalog.save(catalog.existingCard(name, image));
    }

    public void insert(VirtualCardCatalog.Card card) throws Exception {
        if (!Files.isRegularFile(card.image()) || Files.size(card.image()) == 0) {
            throw new IOException("Không tìm thấy EEPROM hợp lệ của thẻ.");
        }
        switchCard(card, false);
    }

    public void create(String name) throws Exception {
        switchCard(catalog.newCard(name), true);
    }

    public void eject() throws Exception {
        switchCard(null, false);
    }

    private void switchCard(VirtualCardCatalog.Card next, boolean createBlank) throws Exception {
        VirtualReaderProtocol.Selection other = otherProtocol.read();
        if (next != null && other != null && next.id().equals(other.cardId())) {
            throw new IOException("Thẻ đang được chọn ở đầu đọc còn lại. Hãy rút thẻ ở đó trước.");
        }
        VirtualCardCatalog.Card previous = active;
        VirtualReaderProtocol.Lease acquired = protocol.tryAcquire();
        if (acquired == null) {
            throw new IOException("Hãy bấm Ngắt kết nối trên " + role + " trước khi đổi thẻ.");
        }
        boolean simulatorChangeStarted = false;
        try {
            if (previous != null && pendingJournal()) {
                throw new IOException("Còn journal chưa đối soát. Giữ thẻ hiện tại và xử lý giao dịch trước.");
            }
            protocol.publish(null);
            simulatorChangeStarted = true;
            if (previous != null) {
                runLauncher("reader-eject", previous.image());
                if (!Files.isRegularFile(previous.image()) || Files.size(previous.image()) == 0) {
                    throw new IOException("EEPROM cũ không còn hợp lệ; dừng chuyển thẻ.");
                }
            }
            active = null;
            if (next == null) {
                return;
            }
            runLauncher("reader-insert", next.image());
            if (createBlank) {
                runLauncher("reader-eject", next.image());
                if (!Files.isRegularFile(next.image()) || Files.size(next.image()) == 0) {
                    throw new IOException("Không lưu được EEPROM mới.");
                }
                catalog.save(next);
                runLauncher("reader-insert", next.image());
            }
            active = next;
            protocol.publish(new VirtualReaderProtocol.Selection(next.id()));
        } catch (Exception failure) {
            if (simulatorChangeStarted && !recoveryRequired) {
                try {
                    runLauncher("reader-eject", null);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    recoveryRequired = true;
                }
            }
            if (simulatorChangeStarted) {
                active = null;
            }
            throw failure;
        } finally {
            acquired.close();
        }
    }

    private boolean pendingJournal() {
        Path journals = Path.of(System.getProperty("user.home"), ".labaccess");
        return List.of(slotName(role) + "-journal.txt", "pending-journal.txt").stream()
                .map(journals::resolve).anyMatch(path -> !Files.notExists(path));
    }

    private void runLauncher(String action, Path image) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-File", root.resolve("scripts/labaccess.ps1").toString(),
                "-Action", action, "-SimPort", Integer.toString(port),
                "-ReaderDirectory", directory.toString(), "-ReaderSlot", slotName(role)));
        if (image != null) {
            command.add("-Eeprom");
            command.add(image.toString());
        }
        Path log = Files.createTempFile(directory, "reader-", ".log");
        Process child = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        child.getOutputStream().close();
        if (!child.waitFor(120, TimeUnit.SECONDS)) {
            recoveryRequired = true;
            throw new IOException("Launcher chưa hoàn tất. Kiểm tra " + log.getFileName());
        }
        if (child.exitValue() != 0) {
            throw new IOException("Thao tác Simulator thất bại. Xem " + log.getFileName());
        }
    }
}
