package vn.labaccess.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardLifecycle;

import vn.labaccess.desktop.session.CardSession;

import vn.labaccess.desktop.virtualreader.VirtualCardCatalog;
import vn.labaccess.desktop.virtualreader.VirtualReaderController;

public final class VirtualReaderLifecycleVerify {
    private static int checks;

    private VirtualReaderLifecycleVerify() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path directory = Path.of(args[1]).toAbsolutePath();
        if (!directory.startsWith(root.resolve("logs")) || !directory.getFileName().toString().startsWith("reader-separation-")) {
            throw new IllegalArgumentException("Use an isolated reader-separation directory under logs");
        }
        int port = Integer.parseInt(args[2]);
        System.setProperty("user.home", directory.toString());
        VirtualReaderController staff = new VirtualReaderController(root, directory, port, CardSession.Role.STAFF);
        VirtualReaderController kiosk = new VirtualReaderController(root, directory, port, CardSession.Role.KIOSK);
        char[] admin = randomPin(8);
        char[] student = randomPin(6);
        System.setProperty("labaccess.virtualReader", directory.resolve("staff").toString());
        CardGateway staffGateway = new CardGateway("127.0.0.1", port);
        System.setProperty("labaccess.virtualReader", directory.resolve("kiosk").toString());
        CardGateway kioskGateway = new CardGateway("127.0.0.1", port + 1);
        try {
            require(install(root, directory, admin, "empty") != 0, "installer rejects empty reader");
            staff.create("Scratch A");
            VirtualCardCatalog.Card card = staff.active();
            require(card != null && Files.size(card.image()) > 0, "blank EEPROM created and selected");
            require(staff.cards().equals(List.of(card)), "blank card persisted in catalog");
            require(staffGateway.connect().ok(), "blank card socket connects");
            require(!staffGateway.identify().ok(), "blank card does not contain LabAccess applet");
            require(install(root, directory, admin, "busy") != 0, "installer rejects connected reader");
            require(staffGateway.disconnect().ok(), "blank card disconnects");
            require(install(root, directory, admin, "install") == 0, "standalone installer succeeds");
            require(staffGateway.connect().ok() && staffGateway.identify().ok(), "installed applet responds");
            require(staffGateway.status().lifecycle() == CardLifecycle.UNPERSONALIZED, "new applet is unpersonalized");
            byte[] cardId = new byte[8];
            new SecureRandom().nextBytes(cardId);
            String id = HexFormat.of().withUpperCase().formatHex(cardId);
            require(staffGateway.personalize(admin, id, "SVTEST01", student, "Test Student").ok(), "provisioned PIN personalizes card");
            require(staffGateway.disconnect().ok(), "issued card disconnects");
            require(install(root, directory, admin, "existing") != 0, "installer refuses existing issued applet");
            staff.eject();
            require(staff.active() == null, "staff reader ejected");
            kiosk.insert(card);
            require(kioskGateway.connect().ok() && kioskGateway.identify().ok(), "same EEPROM responds at kiosk");
            require(kioskGateway.status().lifecycle() == CardLifecycle.ISSUED
                    && kioskGateway.status().cardId().equalsIgnoreCase(id), "issued state and card ID preserved");
            require(kioskGateway.readAuthenticatedStatus(student).ok(), "student PIN remains valid after switch");
            require(kioskGateway.status().counter() == 0 && kioskGateway.status().balance() == 0,
                    "counter and balance preserved");
            require(kioskGateway.disconnect().ok(), "kiosk disconnects");
            kiosk.eject();
            System.out.println("VIRTUAL READER LIFECYCLE PASS: " + checks + " checks");
        } finally {
            Arrays.fill(admin, '\0');
            Arrays.fill(student, '\0');
            staffGateway.disconnect();
            kioskGateway.disconnect();
            System.clearProperty("labaccess.virtualReader");
        }
    }

    private static char[] randomPin(int length) {
        SecureRandom random = new SecureRandom();
        char[] pin = new char[length];
        for (int index = 0; index < length; index++) {
            pin[index] = (char) ('0' + random.nextInt(10));
        }
        return pin;
    }

    private static int install(Path root, Path directory, char[] pin, String label) throws Exception {
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                root.resolve("scripts/labaccess.ps1").toString(), "-Action", "install", "-PinStdin")
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("installer-" + label + ".log").toFile()).start();
        try (var input = process.getOutputStream()) {
            for (char digit : pin) { input.write(digit); }
            input.write('\n');
        }
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Installer did not finish: " + label);
        }
        return process.exitValue();
    }

    private static void require(boolean condition, String name) {
        if (!condition) { throw new AssertionError(name); }
        checks++;
        System.out.println("PASS " + name);
    }
}
