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
import vn.labaccess.desktop.card.ReceiptVerifier;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.AdminGrantService;
import vn.labaccess.desktop.session.StaffManualReconcile;

public final class Adgr13ManualVerify {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("labaccess.host", "127.0.0.1");
        int port = Integer.getInteger("labaccess.port");
        Path evidence = Path.of(System.getProperty("labaccess.evidence"));
        Files.createDirectories(evidence);
        char[] admin = readPin(8);
        char[] student = readPin(6);
        String cardId = cardId();
        String studentId = "AD13" + cardId.substring(0, 4);
        LedgerDouble ledger = new LedgerDouble();
        ledger.start();
        BackendClient backend = new BackendClient(ledger.baseUrl());
        PendingJournal journal = new PendingJournal(evidence.resolve("kiosk-journal.txt"));
        List<Integer> apdus = new ArrayList<>();
        System.out.println("ADGR-13 cardId=" + cardId + " studentId=" + studentId);
        System.out.println("CAP=" + System.getProperty("labaccess.cap", "khong-truyen"));
        System.out.println("CAP-SHA256=" + System.getProperty("labaccess.capHash", "khong-truyen"));
        CardGateway kiosk = open(host, port, apdus);
        try {
            require(kiosk.identify().ok() && kiosk.personalize(admin.clone(), cardId, studentId,
                    student.clone(), "Do Van E").ok(), "phat hanh");
            require(kiosk.identify().ok(), "nhan dien");
            enroll(kiosk, backend, cardId, studentId);
            bind(kiosk, backend, ledger);
            CardOperationResult grant = new AdminGrantService(kiosk, journal, backend).grant(5, () -> admin.clone());
            require(grant.ok() && !journal.load().present(), "GRANT ghi so: " + grant.text());
            System.out.println("SETUP sau GRANT ledger=" + ledger.text(cardId));
            absentStart(kiosk, journal, backend, ledger, host, port, apdus, admin, student, cardId);
            lostAck(journal, backend, ledger, host, port, apdus, admin, student, cardId);
            rejectedCases(backend, ledger, host, port, apdus, admin, student, cardId);
            System.out.println("ADGR-13 PASS");
        } finally {
            Arrays.fill(admin, '\0');
            Arrays.fill(student, '\0');
            if (kiosk.isConnected()) {
                kiosk.disconnect();
            }
            ledger.stop();
        }
    }

    private static void absentStart(CardGateway kiosk, PendingJournal journal, BackendClient backend,
            LedgerDouble ledger, String host, int port, List<Integer> apdus, char[] admin, char[] student,
            String cardId) throws Exception {
        System.out.println("--- START da commit, chua ghi so, journal vang ---");
        require(kiosk.readAuthenticatedStatus(student.clone()).ok(), "state truoc START");
        CardStatus before = kiosk.status();
        byte[] visit = visit();
        ledger.rejectPosts = true;
        CardOperationResult started = kiosk.runStateTransaction(journal, plan(cardId, before.counter() + 1,
                Receipt.OPERATION_START, visit, -1, before.balance() - 1),
                () -> kiosk.visitCommand((byte) 0x51, visit).transmit(), student.clone());
        require(!started.ok() && journal.load().present(), "START giu journal tam: " + started.text());
        journal.remove();
        require(!journal.load().present(), "journal Kiosk da xoa");
        kiosk.disconnect();
        String beforeLedger = ledger.text(cardId);
        CardGateway staff = open(host, port, apdus);
        try {
            bind(staff, backend, ledger);
            require(staff.identify().ok(), "staff nhan the");
            StaffManualReconcile manual = new StaffManualReconcile(staff, backend);
            apdus.clear();
            int posts = ledger.verifyCalls.get();
            StaffManualReconcile.Decision preview = manual.inspect(() -> admin.clone());
            require(preview.kind() == StaffManualReconcile.Kind.NEEDS_CONFIRMATION
                    && preview.receipt().operation() == Receipt.OPERATION_START, preview.detail());
            CardOperationResult confirmed = manual.confirm(preview, () -> admin.clone());
            require(confirmed.ok(), "xac nhan: " + confirmed.text());
            require(ledger.verifyCalls.get() == posts + 1, "dung mot POST");
            require(stateCount(apdus) == 0 && !journal.load().present(), "khong APDU doi state, khong journal");
            System.out.println("START truoc ledger=" + beforeLedger + " journal=vang");
            System.out.println("START xem=" + preview.detail());
            System.out.println("START sau ledger=" + ledger.text(cardId) + " rows=" + ledger.rows(cardId)
                    + " apdu=" + apdus + " post=1");
            System.out.println("START " + confirmed.text());
        } finally {
            staff.disconnect();
        }
    }

    private static void lostAck(PendingJournal journal, BackendClient backend, LedgerDouble ledger, String host,
            int port, List<Integer> apdus, char[] admin, char[] student, String cardId) throws Exception {
        System.out.println("--- END da insert nhung mat ACK, journal vang ---");
        CardGateway kiosk = open(host, port, apdus);
        require(kiosk.identify().ok(), "kiosk nhan dien truoc END");
        bind(kiosk, backend, ledger);
        require(kiosk.readAuthenticatedStatus(student.clone()).ok(), "state truoc END");
        byte[] visit = hex(kiosk.status().activeVisitId());
        ledger.rejectPosts = false;
        ledger.dropNextAck = true;
        CardOperationResult ended = kiosk.runStateTransaction(journal, plan(cardId, kiosk.status().counter() + 1,
                Receipt.OPERATION_END, visit, 0, kiosk.status().balance()),
                () -> kiosk.visitCommand((byte) 0x52, visit).transmit(), student.clone());
        require(!ended.ok() && journal.load().present(), "END mat ACK: " + ended.text());
        journal.remove();
        kiosk.disconnect();
        CardGateway staff = open(host, port, apdus);
        try {
            bind(staff, backend, ledger);
            require(staff.identify().ok(), "staff nhan the da ghi so");
            apdus.clear();
            int posts = ledger.verifyCalls.get();
            StaffManualReconcile.Decision preview = new StaffManualReconcile(staff, backend)
                    .inspect(() -> admin.clone());
            require(preview.kind() == StaffManualReconcile.Kind.ALREADY_RECORDED
                    && ledger.verifyCalls.get() == posts && stateCount(apdus) == 0, preview.detail());
            System.out.println("ACK ledger=" + ledger.text(cardId) + " rows=" + ledger.rows(cardId)
                    + " apdu=" + apdus + " post=0");
            System.out.println("ACK " + preview.detail());
        } finally {
            staff.disconnect();
        }
    }

    private static void rejectedCases(BackendClient backend, LedgerDouble ledger, String host, int port,
            List<Integer> apdus, char[] admin, char[] student, String cardId) throws Exception {
        CardGateway staff = open(host, port, apdus);
        require(staff.identify().ok(), "staff cho ca tu choi");
        bind(staff, backend, ledger);
        StaffManualReconcile manual = new StaffManualReconcile(staff, backend);
        int balance = ledger.snapshot(cardId).lastBalance();
        ledger.setBalance(cardId, balance + 9);
        apdus.clear();
        int posts = ledger.verifyCalls.get();
        StaffManualReconcile.Decision conflict = manual.inspect(() -> admin.clone());
        ledger.setBalance(cardId, balance);
        require(conflict.kind() == StaffManualReconcile.Kind.REJECTED && ledger.verifyCalls.get() == posts
                && stateCount(apdus) == 0, "mau thuan: " + conflict.detail());
        System.out.println("REJECT mau thuan post=0 apdu=" + apdus + " | " + conflict.detail());
        String savedKey = ledger.publicKey(cardId);
        ledger.setPublicKey(cardId, otherKey());
        apdus.clear();
        posts = ledger.verifyCalls.get();
        StaffManualReconcile.Decision badKey = manual.inspect(() -> admin.clone());
        ledger.setPublicKey(cardId, savedKey);
        require(badKey.kind() == StaffManualReconcile.Kind.REJECTED && ledger.verifyCalls.get() == posts
                && stateCount(apdus) == 0, "chu ky sai: " + badKey.detail());
        System.out.println("REJECT chu ky post=0 apdu=" + apdus + " | " + badKey.detail());
        staff.disconnect();

        CardGateway kiosk = open(host, port, apdus);
        require(kiosk.identify().ok(), "kiosk nhan dien truoc gap");
        bind(kiosk, backend, ledger);
        require(kiosk.readAuthenticatedStatus(student.clone()).ok(), "state truoc gap");
        PendingJournal journal = new PendingJournal(Path.of(System.getProperty("labaccess.evidence"), "gap.txt"));
        ledger.rejectPosts = true;
        byte[] visit = visit();
        CardStatus open = kiosk.status();
        require(!kiosk.runStateTransaction(journal, plan(cardId, open.counter() + 1, Receipt.OPERATION_START, visit,
                -1, open.balance() - 1), () -> kiosk.visitCommand((byte) 0x51, visit).transmit(),
                student.clone()).ok(), "START chua ghi de tao gap");
        journal.remove();
        require(kiosk.readAuthenticatedStatus(student.clone()).ok(), "state sau START gap");
        byte[] endVisit = hex(kiosk.status().activeVisitId());
        require(transmit(kiosk, new javax.smartcardio.CommandAPDU(0x80, 0x20, 0x00, 0x00, digits(student)))
                .getSW() == 0x9000, "VERIFY truoc END gap");
        javax.smartcardio.ResponseAPDU ended = transmit(kiosk,
                new javax.smartcardio.CommandAPDU(0x80, 0x52, 0x00, 0x00, endVisit, 6));
        require(ended.getSW() == 0x9000 && ended.getData().length == 6, "END commit khong qua so");
        kiosk.disconnect();
        staff = open(host, port, apdus);
        try {
            require(staff.identify().ok(), "staff thay counter nhay coc");
            bind(staff, backend, ledger);
            apdus.clear();
            posts = ledger.verifyCalls.get();
            StaffManualReconcile.Decision gap = new StaffManualReconcile(staff, backend).inspect(() -> admin.clone());
            require(gap.kind() == StaffManualReconcile.Kind.REJECTED && gap.detail().contains("nhảy cóc")
                    && ledger.verifyCalls.get() == posts && stateCount(apdus) == 0, gap.detail());
            System.out.println("REJECT gap post=0 apdu=" + apdus + " | " + gap.detail());
        } finally {
            staff.disconnect();
        }
    }

    private static CardGateway open(String host, int port, List<Integer> apdus) throws Exception {
        CardGateway gateway = new CardGateway(host, port);
        require(gateway.connect().ok(), "ket noi");
        gateway.traceCommands(apdus::add);
        return gateway;
    }

    private static void bind(CardGateway gateway, BackendClient backend, LedgerDouble ledger) {
        gateway.setReceiptSink(new CardGateway.ReceiptSink() {
            @Override
            public CardGateway.ReceiptSave save(Receipt receipt) {
                if (ledger.rejectPosts) {
                    return new CardGateway.ReceiptSave(false, "tu choi ghi so");
                }
                try {
                    return new CardGateway.ReceiptSave(true, backend.verifyReceipt(receipt).status());
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
        gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "da dang ky"));
    }

    private static void enroll(CardGateway gateway, BackendClient backend, String cardId, String studentId)
            throws Exception {
        require(gateway.readCardPublicKey().ok(), "khoa cong khai");
        BackendClient.AdminSession session = backend.issueAdminSession();
        BackendClient.Challenge challenge = backend.createChallenge(session.sessionId(), cardId, studentId,
                hex(gateway.lastCardPublicKeyBytes()));
        require(gateway.signRegistrationChallenge(hex(challenge.nonceHex())).ok(), "ky challenge");
        backend.completeEnrollment(session.sessionId(), challenge.challengeId(),
                hex(gateway.lastEnrollmentSignature()));
    }

    private static javax.smartcardio.ResponseAPDU transmit(CardGateway gateway,
            javax.smartcardio.CommandAPDU command) throws CardException {
        try {
            java.lang.reflect.Field field = CardGateway.class.getDeclaredField("transport");
            field.setAccessible(true);
            return ((CardTransport) field.get(gateway)).transmit(command);
        } catch (ReflectiveOperationException failure) {
            throw new CardException("khong gui duoc APDU", failure);
        }
    }

    private static byte[] digits(char[] pin) {
        byte[] data = new byte[pin.length];
        for (int index = 0; index < pin.length; index++) {
            data[index] = (byte) pin[index];
        }
        return data;
    }

    private static CardGateway.TransactionPlan plan(String cardId, long counter, int operation, byte[] visit,
            int delta, int balance) {
        return new CardGateway.TransactionPlan(new ReceiptExpectation(hex(cardId), counter, operation, visit),
                delta, balance);
    }

    private static int stateCount(List<Integer> apdus) {
        int count = 0;
        for (int ins : apdus) {
            if (ins == 0x50 || ins == 0x51 || ins == 0x52) {
                count++;
            }
        }
        return count;
    }

    private static byte[] visit() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        value[0] = 1;
        return value;
    }

    private static String otherKey() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return hex(ReceiptVerifier.encodePoint(generator.generateKeyPair().getPublic()));
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
        final AtomicInteger verifyCalls = new AtomicInteger();
        volatile boolean rejectPosts;
        volatile boolean dropNextAck;
        private volatile boolean open = true;

        LedgerDouble() throws IOException {
            server = new ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"));
        }

        void start() {
            Thread thread = new Thread(this::serve, "adgr13-ledger");
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

        BackendClient.LedgerSnapshot snapshot(String cardId) {
            CardLedger card = cards.get(cardId.toUpperCase());
            return new BackendClient.LedgerSnapshot(card.cardId, card.counter, card.balance, card.visit);
        }

        int rows(String cardId) {
            return cards.get(cardId.toUpperCase()).payloads.size();
        }

        String text(String cardId) {
            BackendClient.LedgerSnapshot snapshot = snapshot(cardId);
            return "counter=" + snapshot.lastCounter() + " balance=" + snapshot.lastBalance()
                    + " ca=" + (snapshot.activeVisitId() == null ? "khong" : snapshot.activeVisitId());
        }

        void setBalance(String cardId, int balance) {
            cards.get(cardId.toUpperCase()).balance = balance;
        }

        String publicKey(String cardId) {
            return cards.get(cardId.toUpperCase()).publicKey;
        }

        void setPublicKey(String cardId, String publicKey) {
            cards.get(cardId.toUpperCase()).publicKey = publicKey;
        }

        private void serve() {
            while (open) {
                try (Socket socket = server.accept()) {
                    BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                    String request = readHeaders(input);
                    byte[] body = input.readNBytes(contentLength(request));
                    String line = request.substring(0, request.indexOf("\r\n"));
                    String method = line.substring(0, line.indexOf(' '));
                    String path = line.substring(line.indexOf(' ') + 1, line.lastIndexOf(' '));
                    Reply reply = route(method, path, body);
                    if (reply == null) {
                        continue;
                    }
                    BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                    output.write(("HTTP/1.1 " + reply.status
                            + " X\r\nContent-Type: application/json\r\nContent-Length: " + reply.body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
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
                        body.get("publicKey").asText()));
                return json(201, "{\"challengeId\":\"" + id + "\",\"nonce\":\"" + hex(nonce)
                        + "\",\"expiresAt\":null}");
            }
            if ("POST".equals(method) && "/enrollments/complete".equals(path)) {
                Challenge challenge = challenges.remove(body.get("challengeId").asText());
                CardLedger card = new CardLedger(challenge.cardId, challenge.publicKey);
                cards.put(card.cardId, card);
                return json(201, "{\"cardId\":\"" + card.cardId + "\",\"studentId\":\"AD13\","
                        + "\"enrolledAt\":\"2026-11-19T00:00:00Z\"}");
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
            if (dropNextAck) {
                dropNextAck = false;
                return null;
            }
            String visit = receipt.operation() == Receipt.OPERATION_GRANT ? "null"
                    : "\"" + receipt.visitIdHex() + "\"";
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

        private record Challenge(String cardId, String publicKey) {
        }

        private static final class CardLedger {
            final String cardId;
            String publicKey;
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
