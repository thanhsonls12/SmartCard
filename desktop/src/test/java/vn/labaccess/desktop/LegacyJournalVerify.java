package vn.labaccess.desktop;

import static vn.labaccess.desktop.RoleLedgerDisplayVerify.field;
import static vn.labaccess.desktop.RoleLedgerDisplayVerify.fx;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.image.PixelFormat;
import javafx.stage.WindowEvent;
import javax.imageio.ImageIO;
import vn.labaccess.desktop.RoleLedgerDisplayVerify.Fixture;
import vn.labaccess.desktop.RoleLedgerDisplayVerify.Window;

import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;

import vn.labaccess.desktop.session.AdminGrantService;
import vn.labaccess.desktop.session.StaffManualReconcile;

import vn.labaccess.desktop.ui.shared.CardTaskRunner;

import vn.labaccess.desktop.ui.enrollment.PersonalizePanel;

import vn.labaccess.desktop.ui.photo.PhotoUploadPanel;

import vn.labaccess.desktop.ui.pin.StudentPinUnblockPanel;

import vn.labaccess.desktop.ui.receipt.ReceiptPanel;

import vn.labaccess.desktop.ui.kiosk.KioskPinPrompt;

import vn.labaccess.desktop.ui.transaction.GrantPanel;

public final class LegacyJournalVerify {

    private static final String VISIT = "01010101010101010101010101010101";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        check("isolated test home", home.equals(output.resolve("home")));
        Path directory = Files.createDirectories(home.resolve(".labaccess"));
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        try {
            if (args.length == 2) {
                closing(args[1].equals("staff"), directory);
            } else {
                migration(directory, output);
            }
            System.out.println("LEGACY JOURNAL PASS: " + checks + " checks");
        } finally {
            Platform.exit();
        }
    }

    private static void migration(Path directory, Path output) throws Exception {
        Path old = directory.resolve("pending-journal.txt");
        Path staffJournal = directory.resolve("staff-journal.txt");
        Path kioskJournal = directory.resolve("kiosk-journal.txt");
        byte[] staffBytes = seed(staffJournal, Receipt.OPERATION_GRANT, "0011223344556677");
        byte[] kioskBytes = seed(kioskJournal, Receipt.OPERATION_START, "0011223344556677");
        for (int operation : List.of(Receipt.OPERATION_GRANT, Receipt.OPERATION_START, Receipt.OPERATION_END)) {
            boolean staff = operation == Receipt.OPERATION_GRANT;
            String name = Receipt.operationText(operation);
            byte[] before = seed(old, operation, RecoveryCardFixture.CARD_ID);
            try (Fixture fixture = committed(operation);
                    RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply)) {
                if (staff) {
                    reject(true, name + " staff default", null, old, before, fixture, backend.url(), "Còn journal cũ");
                    reject(false, name + " kiosk default", null, old, before, fixture, backend.url(), "Còn journal cũ");
                    reject(true, name + " other journal override", staffJournal, old, before, fixture, backend.url(), "Còn journal cũ");
                }
                reject(!staff, name + " wrong role", old, old, before, fixture, backend.url(), "Sai ứng dụng");
                fixture.card.cardId = "8899AABBCCDDEEFF";
                reject(staff, name + " wrong card", old, old, before, fixture, backend.url(), null);
                fixture.card.cardId = RecoveryCardFixture.CARD_ID;
                try (Window app = open(staff, name + " selected old", fixture, backend.url(), old)) {
                    check(name + " correct legacy path", app.session.journalFile().equals(old));
                    check(name + " new transactions locked in recovery mode", blocked(app, false));
                    check(name + " correct role may reconcile", canRecover(app));
                    fixture.card.receiptMode = 1;
                    fixture.ins.clear();
                    requestRecovery(app);
                    app.idle();
                    check(name + " insufficient receipt keeps original bytes", same(old, before));
                    check(name + " insufficient receipt sends no POST or mutation", fixture.posts == 0 && fixture.mutations() == 0);
                    System.out.println("REFUSED " + name + " missing-proof INS=" + fixture.ins + " journal=PENDING unchanged");
                    if (staff) capture(app, output.resolve("staff-pending.png"));
                    fixture.card.receiptMode = 0;
                    fixture.ins.clear();
                    fixture.hold = true;
                    try {
                        requestRecovery(app);
                        check(name + " signed receipt reached ledger POST", fixture.postEntered.await(10, TimeUnit.SECONDS));
                        check(name + " before ACK legacy bytes unchanged", same(old, before));
                        check(name + " before ACK role journals unchanged", same(staffJournal, staffBytes) && same(kioskJournal, kioskBytes));
                    } finally {
                        fixture.release.countDown();
                    }
                    app.idle();
                    check(name + " after ACK legacy journal removed", !Files.exists(old) && fixture.posts == 1);
                    check(name + " ACK state and no mutation replay", fixture.ledgerCounter == 2 && fixture.mutations() == 0);
                    check(name + " authentication routed to correct role", staff
                            ? fixture.ins.containsAll(List.of(0x63, 0x11)) && !fixture.ins.contains(0x20)
                            : fixture.ins.containsAll(List.of(0x20, 0x60)) && !fixture.ins.contains(0x11) && !fixture.ins.contains(0x63));
                    check(name + " role journals preserved", same(staffJournal, staffBytes) && same(kioskJournal, kioskBytes));
                    check(name + " recovery window requires reopening", blocked(app, true)
                            && guidance(app).contains("mở lại với journal mặc định"));
                    System.out.println("CLOSED " + name + " INS=" + fixture.ins + " POST=1 mutation=0 legacy=ABSENT separate=unchanged");
                    if (operation == Receipt.OPERATION_START) capture(app, output.resolve("kiosk-reopen.png"));
                }
            }
        }
        byte[] invalid = new byte[] {0x2d, (byte) 0xff, 0x00};
        Files.write(old, invalid);
        try (Fixture fixture = committed(Receipt.OPERATION_GRANT);
                RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply)) {
            for (boolean staff : List.of(true, false)) {
                reject(staff, "malformed " + staff, old, old, invalid, fixture, backend.url(), "Không đọc được nhật ký");
            }
            Files.delete(old);
            for (boolean staff : List.of(true, false)) {
                try (Window app = open(staff, "reopen " + staff, fixture, backend.url(), null)) {
                    check("reopen selects own path " + staff, app.session.journalFile().equals(staff ? staffJournal : kioskJournal));
                    check("reopen drops legacy restriction " + staff, app.session.newTransactionProblem() == null);
                    check("own pending still blocks new work " + staff, blocked(app, true));
                    check("reopen preserves both role journals " + staff, same(staffJournal, staffBytes) && same(kioskJournal, kioskBytes));
                }
            }
        }
    }

    private static void reject(boolean staff, String name, Path override, Path old, byte[] original,
            Fixture fixture, String url, String reason) throws Exception {
        try (Window app = open(staff, name, fixture, url, override)) {
            int apdus = fixture.ins.size(), posts = fixture.posts, gets = fixture.requests;
            check(name + " mutations and reconciliation blocked", blocked(app, true));
            if (reason != null) check(name + " explains refusal", guidance(app).contains(reason));
            AdminGrantService.PinSource noPin = () -> { throw new AssertionError("unexpected PIN request"); };
            check(name + " GRANT worker rejects before PIN", !app.session.grant(5, noPin).ok());
            check(name + " manual read worker rejects before PIN", app.session.inspectManualReconcile(noPin).kind()
                    == StaffManualReconcile.Kind.REJECTED);
            check(name + " manual post worker rejects before PIN", !app.session.confirmManualReconcile(null, noPin).ok());
            check(name + " Admin recovery worker rejects", !app.session.reconcileAdminGrant(noPin).ok());
            char[] pin = RecoveryCardFixture.pin(6);
            try {
                check(name + " Student recovery worker rejects", !app.session.reconcilePending(pin).ok());
            } finally {
                Arrays.fill(pin, (char) 0);
            }
            check(name + " keeps legacy file byte-for-byte", same(old, original));
            check(name + " refusal produces zero APDU/HTTP", fixture.ins.size() == apdus
                    && fixture.posts == posts && fixture.requests == gets);
            System.out.println("REFUSED " + name + " APDU=0 HTTP=0 legacy=unchanged guidance=" + guidance(app));
        }
    }

    private static Window open(boolean staff, String name, Fixture fixture, String url, Path path) throws Exception {
        String host = staff ? "127.0.0.2" : "127.0.0.3";
        int port = staff ? 9131 : 9132;
        System.setProperty("labaccess.host", host);
        System.setProperty("labaccess.port", Integer.toString(port));
        Window app = new Window(staff, name, fixture, url, path);
        Object transport = field(app.session.gateway(), "transport");
        check(name + " host/port applied to transport", host.equals(field(transport, "host"))
                && Integer.valueOf(port).equals(field(transport, "port")));
        System.out.println("ENTRY " + app.app.getClass().getSimpleName() + " pid=" + ProcessHandle.current().pid()
                + " host=" + host + " port=" + port + " journal=" + app.session.journalFile().getFileName());
        return app;
    }

    private static boolean canRecover(Window app) throws Exception {
        return fx(() -> app.staff ? ((ReceiptPanel) field(app.workspace, "receipts")).reconcileEnabled()
                : !((Button) field(app.workspace, "reconcile")).isDisabled());
    }

    private static boolean blocked(Window app, boolean alsoRecover) throws Exception {
        boolean locked = fx(() -> {
            if (app.staff) {
                return !((GrantPanel) field(app.workspace, "grants")).press()
                        && !((StudentPinUnblockPanel) field(app.workspace, "unblock")).pressUnblock()
                        && !((PhotoUploadPanel) field(app.workspace, "photos")).pressUpload()
                        && !((PersonalizePanel) field(app.workspace, "personalize")).pressPersonalize();
            }
            for (String action : List.of("start", "end", "info", "change")) {
                Button button = (Button) field(app.workspace, action);
                if (!button.isDisabled()) return false;
                button.fire();
            }
            return !((KioskPinPrompt) field(app.workspace, "prompt")).isOpen();
        });
        if (!alsoRecover) return locked;
        boolean recoveryLocked = fx(() -> {
            if (app.staff) return !((ReceiptPanel) field(app.workspace, "receipts")).pressReconcile();
            Button reconcile = (Button) field(app.workspace, "reconcile");
            reconcile.fire();
            return reconcile.isDisabled() && !((KioskPinPrompt) field(app.workspace, "prompt")).isOpen();
        });
        return locked && recoveryLocked;
    }

    private static String guidance(Window app) throws Exception {
        return app.text(app.staff ? "guidance" : "pendingNote");
    }

    private static void requestRecovery(Window app) throws Exception {
        if (app.staff) {
            fx(() -> {
                ((GrantPanel) field(app.workspace, "grants")).fillInputs(app.pin, 5);
                Platform.runLater(() -> {
                    var windows = List.copyOf(javafx.stage.Window.getWindows());
                    for (var window : windows) {
                        if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane pane
                                && "Đọc biên nhận để xử lý nhật ký chờ gửi?".equals(pane.getHeaderText())) {
                            pane.getButtonTypes().stream().filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                                    .findFirst().ifPresent(type -> ((Button) pane.lookupButton(type)).fire());
                        }
                    }
                });
                check("Staff recovery button fired", ((ReceiptPanel) field(app.workspace, "receipts")).pressReconcile());
                return null;
            });
        } else {
            app.act("reconcile");
        }
    }

    private static byte[] seed(Path file, int operation, String cardId) throws Exception {
        byte[] visit = operation == Receipt.OPERATION_GRANT ? null : HexFormat.of().parseHex(VISIT);
        PendingJournal.Entry entry = new PendingJournal.Entry(cardId, 2, operation, delta(operation), balance(operation), visit, 1);
        check("created isolated PENDING " + file.getFileName(), new PendingJournal(file).writePending(entry));
        return Files.readAllBytes(file);
    }

    private static Fixture committed(int operation) throws Exception {
        Fixture fixture = new Fixture(operation == Receipt.OPERATION_END ? "END" : "Staff");
        fixture.card.counter = 2;
        fixture.card.balance = balance(operation);
        fixture.operation = operation;
        fixture.delta = delta(operation);
        fixture.receiptVisit = operation == Receipt.OPERATION_GRANT ? new byte[16] : HexFormat.of().parseHex(VISIT);
        fixture.visit = operation == Receipt.OPERATION_START ? VISIT : null;
        return fixture;
    }

    private static int delta(int operation) { return operation == Receipt.OPERATION_GRANT ? 5 : operation == Receipt.OPERATION_START ? -1 : 0; }
    private static int balance(int operation) { return operation == Receipt.OPERATION_GRANT ? 10 : operation == Receipt.OPERATION_START ? 4 : 0; }
    private static boolean same(Path path, byte[] expected) throws Exception { return Arrays.equals(expected, Files.readAllBytes(path)); }

    private static void capture(Window app, Path file) throws Exception {
        fx(() -> {
            var snapshot = app.stage.getScene().getRoot().snapshot(null, null);
            int width = (int) snapshot.getWidth(), height = (int) snapshot.getHeight();
            int[] pixels = new int[width * height];
            snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            ImageIO.write(image, "png", file.toFile());
            return null;
        });
    }

    private static void closing(boolean staff, Path directory) throws Exception {
        Path old = directory.resolve("pending-journal.txt");
        byte[] before = seed(old, staff ? Receipt.OPERATION_GRANT : Receipt.OPERATION_START, RecoveryCardFixture.CARD_ID);
        try (Fixture fixture = committed(Receipt.OPERATION_GRANT);
                RecoveryBackendFixture backend = new RecoveryBackendFixture(fixture::reply)) {
            Window app = open(staff, "close", fixture, backend.url(), null);
            CardTaskRunner tasks = (CardTaskRunner) field(app.app, "tasks");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            CountDownLatch workerDone = new CountDownLatch(1);
            fx(() -> {
                check("worker scheduled", tasks.submit("fixture worker", () -> {
                    System.out.println("CLOSE worker-enter thread=" + Thread.currentThread().getName());
                    entered.countDown();
                    try {
                        if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                        return null;
                    } finally {
                        System.out.println("CLOSE worker-finished thread=" + Thread.currentThread().getName());
                        workerDone.countDown();
                    }
                }, new CardTaskRunner.Callbacks<>(rejection -> { throw new AssertionError(rejection); },
                        () -> { }, outcome -> { }, outcome -> { throw new AssertionError("late callback applied"); },
                        outcome -> { throw new AssertionError("late callback applied"); })));
                return null;
            });
            try {
                check("worker held before close", entered.await(3, TimeUnit.SECONDS));
                fx(() -> { Event.fireEvent(app.stage, new WindowEvent(app.stage, WindowEvent.WINDOW_CLOSE_REQUEST)); return null; });
                check("close locks controls before worker completes", tasks.isClosing() && !tasks.cleanupDone()
                        && app.session.gateway().isConnected() && blocked(app, true));
            } finally {
                release.countDown();
            }
            check("worker completed", workerDone.await(4, TimeUnit.SECONDS));
            check("cleanup and exit finish", app.executor.awaitTermination(5, TimeUnit.SECONDS));
            check("disconnect cleared fixture transport", tasks.cleanupDone() && !app.session.gateway().isConnected());
            check("closing preserves legacy journal bytes", same(old, before));
            Arrays.fill(app.pin, '\0');
        }
    }

    private static void check(String message, boolean passed) {
        if (!passed) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
}
