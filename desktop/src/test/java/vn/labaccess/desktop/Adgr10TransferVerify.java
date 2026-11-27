package vn.labaccess.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.labaccess.desktop.transport.CardTransport;

import vn.labaccess.desktop.card.CardGateway;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;
import vn.labaccess.desktop.card.PendingJournal;
import vn.labaccess.desktop.card.Receipt;
import vn.labaccess.desktop.card.ReceiptCodec;
import vn.labaccess.desktop.card.ReceiptExpectation;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.AdminGrantRecovery;
import vn.labaccess.desktop.session.AdminGrantService;
import vn.labaccess.desktop.session.SupersededRecovery;

public final class Adgr10TransferVerify {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int STATE_GRANT = 0x50;
    private static final int STATE_START = 0x51;
    private static final int STATE_END = 0x52;

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("labaccess.host", "127.0.0.1");
        int port = Integer.getInteger("labaccess.port");
        Path evidence = Path.of(System.getProperty("labaccess.evidence"));
        Files.createDirectories(evidence);
        char[] admin = readPin(8);
        char[] student = readPin(6);
        String cardId = cardId();
        String studentId = "AD10" + cardId.substring(0, 4);
        LedgerDouble ledger = new LedgerDouble();
        ledger.start();
        BackendClient backend = new BackendClient(ledger.baseUrl());
        Path staffFile = evidence.resolve("staff-journal.txt");
        Path kioskFile = evidence.resolve("kiosk-journal.txt");
        PendingJournal staffJournal = new PendingJournal(staffFile);
        PendingJournal kioskJournal = new PendingJournal(kioskFile);
        List<Integer> apdus = new ArrayList<>();
        System.out.println("ADGR-10 cardId=" + cardId + " studentId=" + studentId);
        System.out.println("CAP=" + System.getProperty("labaccess.cap", "khong-truyen"));
        System.out.println("CAP-SHA256=" + System.getProperty("labaccess.capHash", "khong-truyen"));
        System.out.println("ledger=" + ledger.baseUrl());
        CardGateway staff = null;
        CardGateway kiosk = null;
        try {
            staff = open("staff", host, port, apdus);
            CardOperationResult identified = staff.identify();
            require(identified.ok(), "nhan dien truoc phat hanh: " + identified.text());
            require(staff.personalize(admin.clone(), cardId, studentId, student.clone(), "Tran Thi B").ok(),
                    "personalize");
            require(staff.identify().ok(), "nhan dien sau phat hanh");
            enroll(staff, backend, cardId, studentId);
            bind(staff, backend, ledger);
            System.out.println("SETUP ledger=" + ledger.text(cardId) + " staffJournal=" + journalText(staffJournal)
                    + " kioskJournal=" + journalText(kioskJournal));

            caseGrantMovesToKiosk(staff, backend, ledger, staffJournal, kioskJournal, host, port, apdus,
                    admin, student, cardId);
            staff = open("staff", host, port, apdus);
            bind(staff, backend, ledger);
            require(staff.identify().ok(), "staff noi lai de ghi so GRANT");
            mark(staff, cardId);
            ledger.rejectPosts = false;
            CardOperationResult posted = new AdminGrantRecovery(staff, staffJournal, backend)
                    .reconcile(() -> admin.clone());
            require(posted.ok() && !staffJournal.load().present(), "staff doi soat GRANT: " + posted.text());
            System.out.println("SETUP sau doi soat GRANT ledger=" + ledger.text(cardId)
                    + " journal=" + journalText(staffJournal));
            staff.disconnect();

            caseVisitMovesToStaff(backend, ledger, staffJournal, kioskJournal, host, port, apdus,
                    admin, student, cardId);
            kiosk = open("kiosk", host, port, apdus);
            bind(kiosk, backend, ledger);
            require(kiosk.identify().ok(), "kiosk noi lai de ghi so START");
            mark(kiosk, cardId);
            ledger.rejectPosts = false;
            CardOperationResult startPosted = kiosk.reconcilePending(kioskJournal, student.clone(), null);
            require(startPosted.ok() && !kioskJournal.load().present(), "kiosk doi soat START: " + startPosted.text());
            System.out.println("SETUP sau doi soat START ledger=" + ledger.text(cardId));
            kiosk.disconnect();

            caseCounterTaken(backend, ledger, staffJournal, kioskJournal, host, port, apdus, admin, student, cardId);
            caseReopen(backend, ledger, kioskJournal, host, port, apdus, student, cardId);
            caseReopenEnd(backend, ledger, kioskJournal, host, port, apdus, student, cardId);
            System.out.println("ADGR-10 PASS");
        } finally {
            Arrays.fill(admin, '\0');
            Arrays.fill(student, '\0');
            if (staff != null && staff.isConnected()) {
                staff.disconnect();
            }
            if (kiosk != null && kiosk.isConnected()) {
                kiosk.disconnect();
            }
            ledger.stop();
        }
    }

    private static void caseGrantMovesToKiosk(CardGateway staff, BackendClient backend, LedgerDouble ledger,
            PendingJournal staffJournal, PendingJournal kioskJournal, String host, int port, List<Integer> apdus,
            char[] admin, char[] student, String cardId) throws Exception {
        System.out.println("--- CA1 Staff GRANT da commit, chua ghi so, chuyen sang Kiosk ---");
        String beforeLedger = ledger.text(cardId);
        String beforeJournal = journalText(staffJournal);
        ledger.rejectPosts = true;
        apdus.clear();
        ledger.posts.set(0);
        CardOperationResult grant = new AdminGrantService(staff, staffJournal, backend).grant(5, () -> admin.clone());
        require(!grant.ok() && staffJournal.load().present(), "GRANT giu journal: " + grant.text());
        staff.disconnect();
        CardGateway kiosk = open("kiosk", host, port, apdus);
        try {
            bind(kiosk, backend, ledger);
            require(kiosk.identify().ok(), "kiosk nhan the");
            mark(kiosk, cardId);
            apdus.clear();
            ledger.posts.set(0);
            ledger.verifyCalls.set(0);
            byte[] visit = visit();
            CardStatus state = kiosk.status();
            CardOperationResult blocked = kiosk.runStateTransaction(kioskJournal,
                    plan(cardId, state.counter() + 1, Receipt.OPERATION_START, visit, -1, state.balance() - 1),
                    () -> kiosk.visitCommand((byte) 0x51, visit).transmit(), student.clone());
            require(!blocked.ok() && blocked.text().contains("Quay lại máy đang giữ nhật ký"),
                    "kiosk phai bao quay lai may giu journal: " + blocked.text());
            require(stateCount(apdus) == 0 && ledger.posts.get() == 0 && ledger.verifyCalls.get() == 0
                    && !kioskJournal.load().present(),
                    "kiosk khong duoc ghi journal hoac gui lenh doi state");
            System.out.println("CA1 truoc ledger=" + beforeLedger + " journalStaff=" + beforeJournal);
            System.out.println("CA1 sau ledger=" + ledger.text(cardId) + " journalStaff=" + journalText(staffJournal)
                    + " journalKiosk=" + journalText(kioskJournal));
            System.out.println("CA1 may giu journal=Staff file=" + staffJournal.file());
            System.out.println("CA1 kiosk=" + blocked.text());
            System.out.println("CA1 apdu=" + apdus + " post=" + ledger.posts.get());
        } finally {
            kiosk.disconnect();
        }
    }

    private static void caseVisitMovesToStaff(BackendClient backend, LedgerDouble ledger,
            PendingJournal staffJournal, PendingJournal kioskJournal, String host, int port, List<Integer> apdus,
            char[] admin, char[] student, String cardId) throws Exception {
        System.out.println("--- CA1b Kiosk START da commit, chua ghi so, chuyen sang Staff ---");
        CardGateway kiosk = open("kiosk", host, port, apdus);
        bind(kiosk, backend, ledger);
        require(kiosk.identify().ok() && kiosk.readAuthenticatedStatus(student.clone()).ok(), "doc state kiosk");
        mark(kiosk, cardId);
        CardStatus state = kiosk.status();
        byte[] visit = visit();
        String beforeLedger = ledger.text(cardId);
        ledger.rejectPosts = true;
        apdus.clear();
        CardOperationResult started = kiosk.runStateTransaction(kioskJournal,
                plan(cardId, state.counter() + 1, Receipt.OPERATION_START, visit, -1, state.balance() - 1),
                () -> kiosk.visitCommand((byte) 0x51, visit).transmit(), student.clone());
        require(!started.ok() && kioskJournal.load().present(), "START giu journal: " + started.text());
        kiosk.disconnect();
        CardGateway staff = open("staff", host, port, apdus);
        try {
            bind(staff, backend, ledger);
            require(staff.identify().ok(), "staff nhan the");
            mark(staff, cardId);
            apdus.clear();
            ledger.posts.set(0);
            ledger.verifyCalls.set(0);
            CardOperationResult blocked = new AdminGrantService(staff, staffJournal, backend)
                    .grant(5, () -> admin.clone());
            require(!blocked.ok() && blocked.text().contains("chưa khớp"),
                    "staff phai chan vi lech ledger: " + blocked.text());
            require(stateCount(apdus) == 0 && ledger.posts.get() == 0 && ledger.verifyCalls.get() == 0
                    && !staffJournal.load().present(),
                    "staff khong duoc ghi journal hoac gui GRANT");
            System.out.println("CA1b truoc ledger=" + beforeLedger);
            System.out.println("CA1b sau ledger=" + ledger.text(cardId) + " journalKiosk=" + journalText(kioskJournal)
                    + " journalStaff=" + journalText(staffJournal));
            System.out.println("CA1b may giu journal=Kiosk file=" + kioskJournal.file());
            System.out.println("CA1b staff=" + blocked.text());
            System.out.println("CA1b apdu=" + apdus + " post=" + ledger.posts.get());
        } finally {
            staff.disconnect();
        }
    }

    private static void caseCounterTaken(BackendClient backend, LedgerDouble ledger, PendingJournal staffJournal,
            PendingJournal kioskJournal, String host, int port, List<Integer> apdus, char[] admin, char[] student,
            String cardId) throws Exception {
        System.out.println("--- CA2 START PENDING chua commit, Staff GRANT chiem counter ---");
        CardGateway kiosk = open("kiosk", host, port, apdus);
        CardGateway holder = kiosk;
        bind(holder, backend, ledger);
        require(holder.identify().ok() && holder.readAuthenticatedStatus(student.clone()).ok(), "dong ca truoc START treo");
        mark(holder, cardId);
        byte[] openVisit = hex(holder.status().activeVisitId());
        CardOperationResult ended = holder.runStateTransaction(kioskJournal,
                plan(cardId, holder.status().counter() + 1, Receipt.OPERATION_END, openVisit, 0,
                        holder.status().balance()),
                () -> holder.visitCommand((byte) 0x52, openVisit).transmit(), student.clone());
        require(ended.ok() && !kioskJournal.load().present(), "END de mo START treo: " + ended.text());
        require(holder.readAuthenticatedStatus(student.clone()).ok(), "state sau END");
        CardStatus before = holder.status();
        byte[] visit = visit();
        ledger.rejectPosts = false;
        CardOperationResult rejected = holder.runStateTransaction(kioskJournal,
                plan(cardId, before.counter() + 1, Receipt.OPERATION_START, visit, -1, before.balance() - 1),
                () -> transmit(holder, new CommandAPDU(0x80, 0x51, 0x00, 0x00, visit, 1)), student.clone());
        require(!rejected.ok() && kioskJournal.load().present(), "START treo: " + rejected.text());
        require(kiosk.readAuthenticatedStatus(student.clone()).ok() && kiosk.status().counter() == before.counter()
                && kiosk.status().balance() == before.balance(), "START khong duoc commit");
        long waited = kioskJournal.load().entry().expectedCounter();
        kiosk.disconnect();
        CardGateway staff = open("staff", host, port, apdus);
        bind(staff, backend, ledger);
        require(staff.identify().ok(), "staff nhan the de GRANT");
        mark(staff, cardId);
        CardOperationResult grant = new AdminGrantService(staff, staffJournal, backend).grant(5, () -> admin.clone());
        require(grant.ok() && !staffJournal.load().present(), "GRANT chiem counter: " + grant.text());
        require(ledger.snapshot(cardId).lastCounter() == waited, "ledger phai dung counter START dang cho");
        staff.disconnect();
        kiosk = open("kiosk", host, port, apdus);
        try {
            bind(kiosk, backend, ledger);
            require(kiosk.identify().ok(), "kiosk nhan lai the");
            mark(kiosk, cardId);
            apdus.clear();
            ledger.posts.set(0);
            ledger.verifyCalls.set(0);
            SupersededRecovery recovery = new SupersededRecovery(kiosk, kioskJournal, backend);
            CardOperationResult evidence = kiosk.reconcilePending(kioskJournal, student.clone(),
                    recovery::studentEvidence);
            require(evidence.superseded() != null && kioskJournal.load().present(),
                    "can xac nhan nhan vien: " + evidence.text());
            require(stateCount(apdus) == 0 && ledger.posts.get() == 0 && ledger.verifyCalls.get() == 0,
                    "doc bang chung khong doi state va khong POST");
            System.out.println("CA2 sau GRANT ledger=" + ledger.text(cardId) + " journalKiosk="
                    + journalText(kioskJournal));
            System.out.println("CA2 doc bang chung apdu=" + apdus + " post=" + ledger.posts.get());
            apdus.clear();
            ledger.posts.set(0);
            ledger.verifyCalls.set(0);
            SupersededRecovery.Confirmation closed = recovery.confirm(evidence.superseded(), admin.clone());
            require(closed.result().ok() && !kioskJournal.load().present(), "dong SUPERSEDED: " + closed.result().text());
            require(stateCount(apdus) == 0 && ledger.posts.get() == 0 && ledger.verifyCalls.get() == 0,
                    "xac nhan khong doi state va khong POST");
            System.out.println("CA2 xac nhan=" + closed.result().text());
            System.out.println("CA2 xac nhan apdu=" + apdus + " post=" + ledger.posts.get()
                    + " ledger=" + ledger.text(cardId) + " journal=" + journalText(kioskJournal));
        } finally {
            kiosk.disconnect();
        }
    }

    private static void caseReopen(BackendClient backend, LedgerDouble ledger, PendingJournal kioskJournal,
            String host, int port, List<Integer> apdus, char[] student, String cardId) throws Exception {
        System.out.println("--- CA3 mo lai Gateway khi START con PENDING ---");
        CardGateway kiosk = open("kiosk", host, port, apdus);
        bind(kiosk, backend, ledger);
        require(kiosk.identify().ok() && kiosk.readAuthenticatedStatus(student.clone()).ok(), "state truoc START mo lai");
        mark(kiosk, cardId);
        require(!kiosk.status().hasActiveVisit(), "ca da dong truoc START mo lai");
        CardStatus ready = kiosk.status();
        byte[] visit = visit();
        String beforeLedger = ledger.text(cardId);
        ledger.rejectPosts = true;
        CardOperationResult started = kiosk.runStateTransaction(kioskJournal,
                plan(cardId, ready.counter() + 1, Receipt.OPERATION_START, visit, -1, ready.balance() - 1),
                () -> kiosk.visitCommand((byte) 0x51, visit).transmit(), student.clone());
        require(!started.ok() && kioskJournal.load().present()
                && kioskJournal.load().entry().operation() == Receipt.OPERATION_START, "START cho mo lai");
        kiosk.disconnect();
        CardGateway reopened = open("kiosk-reopen", host, port, apdus);
        try {
            bind(reopened, backend, ledger);
            ledger.rejectPosts = false;
            require(reopened.identify().ok(), "gateway moi nhan dien dung the");
            mark(reopened, cardId);
            apdus.clear();
            ledger.posts.set(0);
            CardOperationResult closed = reopened.reconcilePending(kioskJournal, student.clone(), null);
            require(closed.ok() && !kioskJournal.load().present(), "mo lai dong journal: " + closed.text());
            require(stateCount(apdus) == 0, "mo lai khong phat lai START/END: " + apdus);
            require(ledger.posts.get() == 1 && ledger.snapshot(cardId).lastCounter() == ready.counter() + 1,
                    "mo lai ghi dung mot receipt");
            System.out.println("CA3 truoc ledger=" + beforeLedger + " journal=" + "PENDING START");
            System.out.println("CA3 sau ledger=" + ledger.text(cardId) + " journal=" + journalText(kioskJournal));
            System.out.println("CA3 apdu=" + apdus + " post=" + ledger.posts.get());
            System.out.println("CA3 " + closed.text());
        } finally {
            reopened.disconnect();
        }
    }

    private static void caseReopenEnd(BackendClient backend, LedgerDouble ledger, PendingJournal kioskJournal,
            String host, int port, List<Integer> apdus, char[] student, String cardId) throws Exception {
        System.out.println("--- CA3b mo lai Gateway khi END con PENDING ---");
        CardGateway kiosk = open("kiosk", host, port, apdus);
        bind(kiosk, backend, ledger);
        require(kiosk.identify().ok() && kiosk.readAuthenticatedStatus(student.clone()).ok(), "state truoc END mo lai");
        mark(kiosk, cardId);
        require(kiosk.status().hasActiveVisit(), "can mot ca dang mo de END");
        CardStatus ready = kiosk.status();
        byte[] openVisit = hex(kiosk.status().activeVisitId());
        String beforeLedger = ledger.text(cardId);
        ledger.rejectPosts = true;
        CardOperationResult ended = kiosk.runStateTransaction(kioskJournal,
                plan(cardId, ready.counter() + 1, Receipt.OPERATION_END, openVisit, 0, ready.balance()),
                () -> kiosk.visitCommand((byte) 0x52, openVisit).transmit(), student.clone());
        require(!ended.ok() && kioskJournal.load().present()
                && kioskJournal.load().entry().operation() == Receipt.OPERATION_END, "END cho mo lai");
        kiosk.disconnect();
        CardGateway reopened = open("kiosk-reopen-end", host, port, apdus);
        try {
            bind(reopened, backend, ledger);
            ledger.rejectPosts = false;
            require(reopened.identify().ok(), "gateway moi nhan dien dung the");
            mark(reopened, cardId);
            apdus.clear();
            ledger.posts.set(0);
            ledger.verifyCalls.set(0);
            CardOperationResult closed = reopened.reconcilePending(kioskJournal, student.clone(), null);
            BackendClient.LedgerSnapshot after = ledger.snapshot(cardId);
            require(closed.ok() && !kioskJournal.load().present(), "mo lai END dong journal: " + closed.text());
            require(stateCount(apdus) == 0, "mo lai END khong phat lai START/END: " + apdus);
            require(ledger.posts.get() == 1 && ledger.verifyCalls.get() == 1, "mo lai END ghi dung mot receipt");
            require(after.lastCounter() == ready.counter() + 1 && after.lastBalance() == ready.balance()
                    && after.activeVisitId() == null, "END tang counter, giu so luot, dong ca");
            System.out.println("CA3b truoc ledger=" + beforeLedger + " journal=PENDING END counter="
                    + (ready.counter() + 1) + " balance=" + ready.balance());
            System.out.println("CA3b sau ledger=" + ledger.text(cardId) + " journal=" + journalText(kioskJournal));
            System.out.println("CA3b apdu=" + apdus + " post=" + ledger.posts.get());
            System.out.println("CA3b " + closed.text());
        } finally {
            reopened.disconnect();
        }
    }

    private static CardGateway open(String role, String host, int port, List<Integer> apdus) throws Exception {
        CardGateway gateway = new CardGateway(host, port);
        require(gateway.connect().ok(), role + " ket noi");
        gateway.traceCommands(apdus::add);
        gateway.setReceiptSink(null);
        return gateway;
    }

    private static void bind(CardGateway gateway, BackendClient backend, LedgerDouble ledger) {
        gateway.setReceiptSink(new CardGateway.ReceiptSink() {
            @Override
            public CardGateway.ReceiptSave save(Receipt receipt) {
                ledger.posts.incrementAndGet();
                if (ledger.rejectPosts) {
                    return new CardGateway.ReceiptSave(false, "tu choi ghi so");
                }
                try {
                    BackendClient.SavedReceipt saved = backend.verifyReceipt(receipt);
                    return new CardGateway.ReceiptSave(true, saved.status());
                } catch (BackendClient.Failure failure) {
                    return new CardGateway.ReceiptSave(false, failure.getMessage());
                }
            }

            @Override
            public CardGateway.LedgerCheck ledger(String cardIdHex) {
                try {
                    BackendClient.LedgerSnapshot snapshot = backend.ledgerOf(cardIdHex);
                    return new CardGateway.LedgerCheck(true, "ledger", snapshot.cardId(), snapshot.lastCounter(),
                            snapshot.lastBalance(), snapshot.activeVisitId());
                } catch (BackendClient.Failure failure) {
                    return new CardGateway.LedgerCheck(false, failure.getMessage(), cardIdHex, -1, -1, null);
                }
            }
        });
    }

    private static void mark(CardGateway gateway, String cardId) throws Exception {
        gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "cardId=" + cardId));
    }

    private static void enroll(CardGateway gateway, BackendClient backend, String cardId, String studentId)
            throws Exception {
        require(gateway.readCardPublicKey().ok(), "doc khoa cong khai");
        String publicKey = hex(gateway.lastCardPublicKeyBytes());
        BackendClient.AdminSession session = backend.issueAdminSession();
        BackendClient.Challenge challenge = backend.createChallenge(session.sessionId(), cardId, studentId, publicKey);
        require(gateway.signRegistrationChallenge(hex(challenge.nonceHex())).ok(), "ky challenge");
        backend.completeEnrollment(session.sessionId(), challenge.challengeId(),
                hex(gateway.lastEnrollmentSignature()));
        mark(gateway, cardId);
    }

    private static javax.smartcardio.ResponseAPDU transmit(CardGateway gateway, CommandAPDU command)
            throws CardException {
        try {
            java.lang.reflect.Field field = CardGateway.class.getDeclaredField("transport");
            field.setAccessible(true);
            return ((CardTransport) field.get(gateway)).transmit(command);
        } catch (ReflectiveOperationException failure) {
            throw new CardException("khong gui duoc APDU ngan", failure);
        }
    }

    private static CardGateway.TransactionPlan plan(String cardId, long counter, int operation, byte[] visit,
            int delta, int balance) {
        return new CardGateway.TransactionPlan(new ReceiptExpectation(hex(cardId), counter, operation, visit),
                delta, balance);
    }

    private static int stateCount(List<Integer> apdus) {
        int count = 0;
        for (int ins : apdus) {
            if (ins == STATE_GRANT || ins == STATE_START || ins == STATE_END) {
                count++;
            }
        }
        return count;
    }

    private static String journalText(PendingJournal journal) {
        PendingJournal.Loaded loaded = journal.load();
        if (!loaded.present()) {
            return "khong-co";
        }
        if (!loaded.valid()) {
            return "KHONG-HOP-LE";
        }
        return loaded.status() + " op=" + loaded.entry().operation() + " counter=" + loaded.entry().expectedCounter();
    }

    private static byte[] visit() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        value[0] = 1;
        return value;
    }

    private static String cardId() {
        byte[] value = new byte[8];
        RANDOM.nextBytes(value);
        value[0] = 1;
        return hex(value);
    }

    private static java.io.BufferedReader pinReader;

    private static char[] readPin(int length) throws IOException {
        if (pinReader == null) {
            pinReader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                    java.nio.charset.StandardCharsets.US_ASCII));
        }
        String line = pinReader.readLine();
        if (line == null || line.strip().length() != length) {
            throw new IllegalStateException("PIN stdin sai do dai");
        }
        return line.strip().toCharArray();
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value).toUpperCase();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class LedgerDouble {
        private final ServerSocket server;
        private final Map<String, CardLedger> cards = new LinkedHashMap<>();
        private final Map<String, Challenge> challenges = new LinkedHashMap<>();
        final AtomicInteger posts = new AtomicInteger();
        final AtomicInteger verifyCalls = new AtomicInteger();
        volatile boolean rejectPosts;
        private volatile boolean open = true;

        LedgerDouble() throws IOException {
            server = new ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"));
        }

        void start() {
            Thread thread = new Thread(this::serve, "adgr10-ledger");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws IOException {
            open = false;
            server.close();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/";
        }

        private void serve() {
            while (open) {
                try (Socket socket = server.accept()) {
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    String request = readHeaders(input);
                    int length = contentLength(request);
                    byte[] body = input.readNBytes(length);
                    String line = request.substring(0, request.indexOf("\r\n"));
                    String method = line.substring(0, line.indexOf(' '));
                    String path = line.substring(line.indexOf(' ') + 1, line.lastIndexOf(' '));
                    Reply reply = route(method, path, body);
                    BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                    output.write(("HTTP/1.1 " + reply.status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                            + reply.body.length + "\r\nConnection: close\r\n\r\n").getBytes(
                                    java.nio.charset.StandardCharsets.US_ASCII));
                    output.write(reply.body);
                    output.flush();
                } catch (Exception failure) {
                    if (open) {
                        System.out.println("ledger socket: " + failure.getClass().getSimpleName());
                    }
                }
            }
        }

        private static String readHeaders(BufferedInputStream input) throws IOException {
            StringBuilder text = new StringBuilder();
            int previous = -1;
            int current;
            while ((current = input.read()) >= 0) {
                text.append((char) current);
                if (previous == '\r' && current == '\n' && text.toString().endsWith("\r\n\r\n")) {
                    return text.toString();
                }
                previous = current;
            }
            return text.toString();
        }

        private static int contentLength(String headers) {
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            return 0;
        }

        BackendClient.LedgerSnapshot snapshot(String cardId) {
            CardLedger card = cards.get(cardId.toUpperCase());
            return new BackendClient.LedgerSnapshot(card.cardId, card.counter, card.balance, card.visit);
        }

        String text(String cardId) {
            BackendClient.LedgerSnapshot snapshot = snapshot(cardId);
            return "counter=" + snapshot.lastCounter() + " balance=" + snapshot.lastBalance()
                    + " ca=" + (snapshot.activeVisitId() == null ? "khong" : snapshot.activeVisitId());
        }

        private Reply route(String method, String path, byte[] raw) throws IOException {
            JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
            if ("POST".equals(method) && "/admin/sessions".equals(path)) {
                byte[] sessionNonce = new byte[32];
                RANDOM.nextBytes(sessionNonce);
                return json(201, "{\"sessionId\":\"" + java.util.UUID.randomUUID()
                        + "\",\"nonce\":\"" + hex(sessionNonce) + "\",\"expiresAt\":null}");
            }
            if ("POST".equals(method) && "/enrollments/challenges".equals(path)) {
                String id = java.util.UUID.randomUUID().toString();
                byte[] nonce = new byte[32];
                RANDOM.nextBytes(nonce);
                challenges.put(id, new Challenge(body.get("cardId").asText().toUpperCase(),
                        body.get("studentId").asText(), body.get("publicKey").asText(), hex(nonce)));
                return json(201, "{\"challengeId\":\"" + id + "\",\"nonce\":\"" + hex(nonce) + "\",\"expiresAt\":null}");
            }
            if ("POST".equals(method) && "/enrollments/complete".equals(path)) {
                Challenge challenge = challenges.remove(body.get("challengeId").asText());
                CardLedger card = new CardLedger(challenge.cardId, challenge.publicKey);
                cards.put(card.cardId, card);
                return json(201, "{\"cardId\":\"" + card.cardId + "\",\"studentId\":\"" + challenge.studentId
                        + "\",\"enrolledAt\":\"2026-11-19T00:00:00Z\"}");
            }
            if ("GET".equals(method) && path.startsWith("/enrollments/cards/")) {
                CardLedger card = cards.get(path.substring("/enrollments/cards/".length()).toUpperCase());
                if (card == null) {
                    return json(404, "{\"message\":\"chua enroll\"}");
                }
                return json(200, "{\"cardId\":\"" + card.cardId + "\",\"publicKey\":\"" + card.publicKey + "\"}");
            }
            if ("GET".equals(method) && path.startsWith("/receipts/ledger/")) {
                CardLedger card = cards.get(path.substring("/receipts/ledger/".length()).toUpperCase());
                if (card == null) {
                    return json(404, "{\"message\":\"chua co so\"}");
                }
                return json(200, "{\"cardId\":\"" + card.cardId + "\",\"lastCounter\":" + card.counter
                        + ",\"lastBalance\":" + card.balance + ",\"activeVisitId\":"
                        + (card.visit == null ? "null" : "\"" + card.visit + "\"") + "}");
            }
            if ("POST".equals(method) && "/receipts/verify".equals(path)) {
                return verify(body);
            }
            if ("GET".equals(method) && path.startsWith("/cards/")) {
                String[] parts = path.split("/");
                CardLedger card = cards.get(parts[2].toUpperCase());
                long counter = Long.parseLong(parts[4]);
                String payload = card == null ? null : card.payloads.get(counter);
                if (payload == null) {
                    return json(404, "{\"message\":\"khong co dong\"}");
                }
                return json(200, "{\"cardId\":\"" + card.cardId + "\",\"counterAfter\":" + counter
                        + ",\"payload\":\"" + payload + "\"}");
            }
            return json(404, "{\"message\":\"khong co\"}");
        }

        private Reply verify(JsonNode body) {
            verifyCalls.incrementAndGet();
            if (rejectPosts) {
                return json(500, "{\"message\":\"tu choi ghi so\"}");
            }
            byte[] payload = hex(body.get("payload").asText());
            Receipt receipt = ReceiptCodec.parsePayload(payload, hex(body.get("signature").asText())).receipt();
            CardLedger card = cards.get(receipt.cardIdHex().toUpperCase());
            String stored = card.payloads.get(receipt.counterAfter());
            String status = stored == null ? "recorded" : "duplicate";
            if (stored == null) {
                card.payloads.put(receipt.counterAfter(), receipt.payloadHex());
                card.counter = receipt.counterAfter();
                card.balance = receipt.balanceAfter();
                if (receipt.operation() == Receipt.OPERATION_START) {
                    card.visit = receipt.visitIdHex();
                } else if (receipt.operation() == Receipt.OPERATION_END) {
                    card.visit = null;
                }
            }
            String visit = receipt.operation() == Receipt.OPERATION_GRANT ? "null" : "\"" + receipt.visitIdHex() + "\"";
            return json(201, "{\"status\":\"" + status + "\",\"cardId\":\"" + receipt.cardIdHex()
                    + "\",\"operation\":\"" + operationName(receipt.operation()) + "\",\"counterAfter\":"
                    + receipt.counterAfter() + ",\"delta\":" + receipt.delta() + ",\"balanceAfter\":"
                    + receipt.balanceAfter() + ",\"visitId\":" + visit + "}");
        }

        private static String operationName(int operation) {
            return switch (operation) {
                case Receipt.OPERATION_GRANT -> "GRANT";
                case Receipt.OPERATION_START -> "START";
                case Receipt.OPERATION_END -> "END";
                default -> "UNKNOWN";
            };
        }

        private static Reply json(int status, String body) {
            return new Reply(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private record Reply(int status, byte[] body) {
        }

        private record Challenge(String cardId, String studentId, String publicKey, String nonce) {
        }

        private static final class CardLedger {
            final String cardId;
            final String publicKey;
            final Map<Long, String> payloads = new LinkedHashMap<>();
            long counter;
            int balance;
            String visit;

            CardLedger(String cardId, String publicKey) {
                this.cardId = cardId;
                this.publicKey = publicKey;
            }
        }
    }
}
