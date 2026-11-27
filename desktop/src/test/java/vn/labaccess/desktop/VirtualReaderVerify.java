package vn.labaccess.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.smartcardio.CardException;

import vn.labaccess.desktop.virtualreader.protocol.VirtualReaderProtocol;
import vn.labaccess.desktop.virtualreader.protocol.VirtualReaderSession;

import vn.labaccess.desktop.card.CardGateway;

import vn.labaccess.desktop.session.CardSession;

import vn.labaccess.desktop.virtualreader.VirtualCardCatalog;
import vn.labaccess.desktop.virtualreader.VirtualReaderController;

public final class VirtualReaderVerify {
    private static int checks;

    private VirtualReaderVerify() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "virtual-reader-verify-");
        checkCatalog(directory);
        VirtualReaderProtocol protocol = new VirtualReaderProtocol(directory);
        checkProtocol(protocol);
        checkManualSessions(protocol);
        checkSeparateSlots(directory);
        if (args.length == 1) {
            checkSimulator(directory, protocol, Integer.parseInt(args[0]));
        } else if (args.length == 2) {
            checkDualSimulator(directory, Integer.parseInt(args[0]), Integer.parseInt(args[1]));
        }
        System.out.println("VIRTUAL READER PASS: " + checks + " checks; " + directory);
    }

    private static void checkCatalog(Path directory) throws Exception {
        VirtualCardCatalog catalog = new VirtualCardCatalog(directory);
        VirtualCardCatalog.Card a = catalog.newCard("Thẻ A");
        VirtualCardCatalog.Card b = catalog.newCard("Thẻ B");
        require(!a.id().equals(b.id()) && !a.image().equals(b.image()), "unique EEPROM paths");
        Files.write(a.image(), new byte[] {1, 2, 3});
        Files.write(b.image(), new byte[] {4, 5, 6});
        catalog.save(a);
        catalog.save(b);
        VirtualCardCatalog reloaded = new VirtualCardCatalog(directory);
        require(reloaded.load().equals(java.util.List.of(a, b)), "catalog survives reopening");
        expectFailure(() -> reloaded.newCard(" thẻ a "), "duplicate name rejected");
        expectFailure(() -> reloaded.existingCard("Alias", a.image()), "duplicate image rejected");
        Path external = directory.resolve("external.eeprom");
        Files.write(external, new byte[] {7, 8, 9});
        VirtualCardCatalog.Card imported = reloaded.existingCard("Imported", external);
        reloaded.save(imported);
        require(Files.isSameFile(imported.image(), external), "import uses original image");
        require(reloaded.load().size() == 3, "import persists");
        require(Files.readAllBytes(a.image())[0] == 1 && Files.readAllBytes(b.image())[0] == 4,
                "catalog does not overwrite EEPROM");
    }

    private static void checkProtocol(VirtualReaderProtocol protocol) throws Exception {
        protocol.publish(null);
        require(protocol.read() == null, "empty reader");
        VirtualReaderProtocol.Selection selection = selection();
        protocol.publish(selection);
        require(selection.equals(protocol.read()), "selection round trip");
        try (VirtualReaderProtocol.Lease lease = protocol.tryAcquire()) {
            require(lease != null, "first lease acquired");
            require(protocol.tryAcquire() == null, "second lease rejected");
        }
        try (VirtualReaderProtocol.Lease lease = protocol.tryAcquire()) {
            require(lease != null, "lease available after release");
        }
    }

    private static void checkManualSessions(VirtualReaderProtocol protocol) throws Exception {
        protocol.publish(null);
        VirtualReaderSession staff = new VirtualReaderSession(protocol);
        VirtualReaderSession kiosk = new VirtualReaderSession(protocol);
        expectCardFailure(kiosk::acquire, "manual connect without selected card rejected");
        VirtualReaderProtocol.Selection a = selection();
        protocol.publish(a);
        try (VirtualReaderProtocol.Lease lease = protocol.tryAcquire()) {
            require(lease != null, "selection alone does not connect");
        }
        kiosk.acquire();
        require(protocol.tryAcquire() == null, "manual Kiosk connection prevents switch");
        expectCardFailure(staff::acquire, "Staff cannot connect while Kiosk owns session");
        require(a.equals(protocol.read()), "blocked switch leaves selection unchanged");
        kiosk.release();
        try (VirtualReaderProtocol.Lease lease = protocol.tryAcquire()) {
            require(lease != null, "manual disconnect permits switch");
            protocol.publish(null);
            expectCardFailure(staff::acquire, "connect rejected during switching");
            protocol.publish(selection());
        }
        staff.acquire();
        expectCardFailure(kiosk::acquire, "Kiosk cannot connect while Staff owns session");
        staff.release();
        kiosk.acquire();
        kiosk.release();
        require(protocol.read() != null, "disconnect does not remove selected card");
        protocol.publish(null);
        expectCardFailure(staff::acquire, "ejected card cannot connect");
        try (VirtualReaderProtocol.Lease lease = protocol.tryAcquire()) {
            require(lease != null, "failed connect releases lease");
        }
        VirtualReaderSession normal = new VirtualReaderSession(null);
        normal.acquire();
        normal.release();
        require(!normal.isEnabled(), "ordinary mode unaffected");
    }

    private static VirtualReaderProtocol.Selection selection() {
        return new VirtualReaderProtocol.Selection(UUID.randomUUID());
    }

    private static void checkSeparateSlots(Path directory) throws Exception {
        VirtualReaderProtocol staff = new VirtualReaderProtocol(directory.resolve("staff"));
        VirtualReaderProtocol kiosk = new VirtualReaderProtocol(directory.resolve("kiosk"));
        VirtualCardCatalog catalog = new VirtualCardCatalog(directory);
        VirtualCardCatalog.Card a = catalog.load().get(1);
        staff.publish(new VirtualReaderProtocol.Selection(a.id()));
        kiosk.publish(null);
        VirtualReaderController kioskController = new VirtualReaderController(directory, directory, 19025,
                CardSession.Role.KIOSK);
        expectFailure(() -> {
            kioskController.insert(a);
            return null;
        }, "same catalog card cannot be selected in both slots");
        VirtualReaderSession staffSession = new VirtualReaderSession(staff);
        VirtualReaderSession kioskSession = new VirtualReaderSession(kiosk);
        staffSession.acquire();
        try {
            require(staff.tryAcquire() == null, "Staff lease blocks Staff switch");
            try (VirtualReaderProtocol.Lease lease = kiosk.tryAcquire()) {
                require(lease != null, "Staff connection does not block Kiosk switch");
                kiosk.publish(selection());
            }
            kioskSession.acquire();
            require(staff.tryAcquire() == null && kiosk.tryAcquire() == null,
                    "both slots can hold independent leases");
            kioskSession.release();
            require(staff.tryAcquire() == null, "Kiosk release does not release Staff lease");
        } finally {
            kioskSession.release();
            staffSession.release();
        }
    }

    private static void checkDualSimulator(Path directory, int staffPort, int kioskPort) throws Exception {
        Path staffPath = directory.resolve("staff");
        Path kioskPath = directory.resolve("kiosk");
        VirtualReaderProtocol staffProtocol = new VirtualReaderProtocol(staffPath);
        VirtualReaderProtocol kioskProtocol = new VirtualReaderProtocol(kioskPath);
        staffProtocol.publish(selection());
        kioskProtocol.publish(selection());
        System.setProperty("labaccess.virtualReader", staffPath.toAbsolutePath().toString());
        CardGateway staff = new CardGateway("127.0.0.1", staffPort);
        System.setProperty("labaccess.virtualReader", kioskPath.toAbsolutePath().toString());
        CardGateway kiosk = new CardGateway("127.0.0.1", kioskPort);
        try {
            require(!staff.isConnected() && !kiosk.isConnected(), "neither slot auto-connects");
            require(staff.connect().ok() && staff.identify().ok(), "Staff connects on its own port");
            require(kiosk.connect().ok() && kiosk.identify().ok(), "Kiosk connects while Staff remains connected");
            require(staff.isConnected() && kiosk.isConnected(), "two real card sockets open together");
            require(kiosk.disconnect().ok(), "Kiosk disconnect succeeds independently");
            require(staff.isConnected() && staff.identify().ok(), "Staff card unaffected by Kiosk disconnect");
            try (VirtualReaderProtocol.Lease lease = kioskProtocol.tryAcquire()) {
                require(lease != null, "Kiosk can switch while Staff still connected");
                kioskProtocol.publish(null);
            }
            require(!kiosk.connect().ok(), "empty Kiosk slot cannot connect to Staff card");
            require(staff.disconnect().ok(), "Staff disconnect succeeds");
        } finally {
            kiosk.disconnect();
            staff.disconnect();
            System.clearProperty("labaccess.virtualReader");
        }
    }

    private static void checkSimulator(Path directory, VirtualReaderProtocol protocol, int port) throws Exception {
        System.setProperty("labaccess.virtualReader", directory.toAbsolutePath().toString());
        CardGateway kiosk = new CardGateway("127.0.0.1", port);
        CardGateway staff = new CardGateway("127.0.0.1", port);
        try {
            protocol.publish(null);
            require(!kiosk.connect().ok() && !kiosk.isConnected(), "real gateway requires selection");
            VirtualReaderProtocol.Selection selected = selection();
            protocol.publish(selected);
            require(!kiosk.isConnected() && !staff.isConnected(), "selection does not open socket");
            require(kiosk.connect().ok() && kiosk.identify().ok(), "manual Kiosk CONNECT and SELECT succeed");
            require(!staff.connect().ok(), "second real gateway rejected while Kiosk connected");
            require(protocol.tryAcquire() == null, "Reader cannot switch while real socket connected");
            require(kiosk.disconnect().ok() && !kiosk.isConnected(), "manual Kiosk disconnect");
            require(selected.equals(protocol.read()), "selection retained after real disconnect");
            require(staff.connect().ok() && staff.identify().ok(), "Staff manually connects to same selected card");
            require(staff.disconnect().ok(), "manual Staff disconnect");
            protocol.publish(null);
            require(!kiosk.connect().ok(), "ejected selection rejects real gateway");
        } finally {
            kiosk.disconnect();
            staff.disconnect();
            System.clearProperty("labaccess.virtualReader");
        }
    }

    private static void expectCardFailure(CardAction action, String description) throws Exception {
        try {
            action.run();
        } catch (CardException expected) {
            require(true, description);
            return;
        }
        throw new AssertionError(description);
    }

    private interface CardAction {
        void run() throws CardException;
    }

    private static void expectFailure(Callable<?> action, String description) throws Exception {
        try {
            action.call();
        } catch (IOException expected) {
            require(true, description);
            return;
        }
        throw new AssertionError(description);
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
        checks++;
        System.out.println("PASS " + description);
    }
}
