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

/**
 * ADGR-11: mất phản hồi APDU sau GRANT đã commit, và mất ACK HTTP sau khi sổ đã ghi.
 */
public final class Adgr11FaultVerify {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GRANT = 0x50;

    public static void main(String[] args) throws Exception {
        String fault = System.getProperty("labaccess.fault");
        String host = System.getProperty("labaccess.host", "127.0.0.1");
        int simPort = Integer.getInteger("labaccess.simPort");
        int relayPort = Integer.getInteger("labaccess.relayPort", 0);
        int controlPort = Integer.getInteger("labaccess.controlPort", 0);
        Path evidence = Path.of(System.getProperty("labaccess.evidence"));
        Files.createDirectories(evidence);
        char[] admin = readPin(8);
        char[] student = readPin(6);
        String cardId = cardId();
        String studentId = "AD11" + cardId.substring(0, 4);
        LedgerDouble ledger = new LedgerDouble();
        ledger.start();
        BackendClient backend = new BackendClient(ledger.baseUrl());
        PendingJournal journal = new PendingJournal(evidence.resolve("journal.txt"));
        List<Integer> apdus = new ArrayList<>();
        System.out.println("ADGR-11 fault=" + fault + " cardId=" + cardId + " studentId=" + studentId);
        System.out.println("CAP=" + System.getProperty("labaccess.cap", "khong-truyen"));
        System.out.println("CAP-SHA256=" + System.getProperty("labaccess.capHash", "khong-truyen"));
        int setupPort = relayPort > 0 ? relayPort : simPort;
        CardGateway gateway = open("setup", host, setupPort, apdus);
        try {
            CardOperationResult identified = gateway.identify();
            require(identified.ok(), "nhan dien: " + identified.text());
            require(gateway.personalize(admin.clone(), cardId, studentId, student.clone(), "Le Van C").ok(),
                    "personalize");
            require(gateway.identify().ok(), "nhan dien sau phat hanh");
            enroll(gateway, backend, cardId, studentId);
            System.out.println("SETUP ledger=" + ledger.text(cardId) + " journal=" + journalText(journal));
            if ("apdu".equals(fault)) {
                lostApdu(gateway, backend, ledger, journal, host, simPort, controlPort, apdus, admin, cardId);
            } else if ("http".equals(fault)) {
                lostHttp(gateway, backend, ledger, journal, host, simPort, apdus, admin, cardId);
            } else {
                throw new IllegalStateException("fault khong ho tro: " + fault);
            }
            System.out.println("ADGR-11 " + fault + " PASS");
        } finally {
            Arrays.fill(admin, '\0');
            Arrays.fill(student, '\0');
            if (gateway.isConnected()) {
                gateway.disconnect();
            }
            ledger.stop();
        }
    }

    private static void lostApdu(CardGateway gateway, BackendClient backend, LedgerDouble ledger,
            PendingJournal journal, String host, int simPort, int controlPort, List<Integer> apdus,
            char[] admin, String cardId) throws Exception {
        System.out.println("--- FAULT mat response APDU sau GRANT da commit ---");
        arm(controlPort, "drop-grant");
        apdus.clear();
        ledger.verifyCalls.set(0);
        String beforeLedger = ledger.text(cardId);
        CardOperationResult grant = new AdminGrantService(gateway, journal, backend).grant(5, () -> admin.clone());
        require(!grant.ok(), "GRANT phai loi vi mat response: " + grant.text());
        require(journal.load().present() && journal.load().status() == PendingJournal.Status.PENDING,
                "journal phai PENDING");
        require(gateway.reconciliation().state() == Reconciliation.State.UNKNOWN, "doi soat phai UNKNOWN");
        require(count(apdus, GRANT) == 1 && ledger.verifyCalls.get() == 0, "dung mot GRANT da gui, so chua ghi");
        System.out.println("FAULT truoc ledger=" + beforeLedger);
        System.out.println("FAULT luc loi grant=" + grant.text());
        System.out.println("FAULT luc loi journal=" + journalText(journal) + " reconciliation="
                + gateway.reconciliation().state() + " apdu=" + apdus + " post=" + ledger.verifyCalls.get());
        safeDisconnect(gateway);
        Thread.sleep(1500);
        CardGateway reopened = open("reopen", host, simPort, apdus);
        try {
            require(reopened.identify().ok(), "mo lai nhan dien");
            require(reopened.readAdminStatus(admin.clone()).ok(), "doc state sau mat response");
            CardStatus card = reopened.status();
            require(card.counter() == 1 && card.balance() == 5, "the chi tang dung mot lan: " + card);
            require(ledger.rows(cardId) == 0 && ledger.snapshot(cardId).lastCounter() == 0, "so van trong");
            System.out.println("FAULT the=counter=" + card.counter() + " balance=" + card.balance()
                    + " ledger=" + ledger.text(cardId) + " rows=" + ledger.rows(cardId));
            apdus.clear();
            ledger.verifyCalls.set(0);
            CardOperationResult closed = new AdminGrantRecovery(reopened, journal, backend)
                    .reconcile(() -> admin.clone());
            require(closed.ok() && !journal.load().present(), "doi soat: " + closed.text());
            require(count(apdus, GRANT) == 0, "khong phat lai GRANT: " + apdus);
            require(ledger.verifyCalls.get() == 1 && ledger.rows(cardId) == 1, "dung mot ban ghi moi");
            require(reopened.readAdminStatus(admin.clone()).ok(), "doc lai the");
            require(reopened.status().counter() == 1 && reopened.status().balance() == 5
                    && ledger.snapshot(cardId).lastCounter() == 1 && ledger.snapshot(cardId).lastBalance() == 5,
                    "counter va so luot khong tang them");
            System.out.println("RECOVER apdu=" + apdus + " post=" + ledger.verifyCalls.get()
                    + " journal=" + journalText(journal));
            System.out.println("RECOVER the=counter=" + reopened.status().counter() + " balance="
                    + reopened.status().balance() + " ledger=" + ledger.text(cardId) + " rows=" + ledger.rows(cardId));
            System.out.println("RECOVER " + closed.text());
        } finally {
            safeDisconnect(reopened);
        }
    }

    private static void lostHttp(CardGateway gateway, BackendClient backend, LedgerDouble ledger,
            PendingJournal journal, String host, int simPort, List<Integer> apdus, char[] admin, String cardId)
            throws Exception {
        System.out.println("--- FAULT mat ACK HTTP sau khi so da ghi ---");
        ledger.dropNextAck = true;
        apdus.clear();
        ledger.verifyCalls.set(0);
        String beforeLedger = ledger.text(cardId);
        CardOperationResult grant = new AdminGrantService(gateway, journal, backend).grant(5, () -> admin.clone());
        require(!grant.ok(), "GRANT phai loi vi mat ACK: " + grant.text());
        require(journal.load().present() && journal.load().status() == PendingJournal.Status.PENDING, "journal PENDING");
        require(gateway.reconciliation().state() == Reconciliation.State.UNKNOWN, "doi soat UNKNOWN");
        require(ledger.rows(cardId) == 1 && ledger.snapshot(cardId).lastCounter() == 1
                && ledger.snapshot(cardId).lastBalance() == 5, "so da co dung mot receipt");
        require(gateway.readAdminStatus(admin.clone()).ok(), "doc the luc loi");
        require(gateway.status().counter() == 1 && gateway.status().balance() == 5, "the da commit dung mot lan");
        System.out.println("FAULT truoc ledger=" + beforeLedger);
        System.out.println("FAULT luc loi grant=" + grant.text());
        System.out.println("FAULT luc loi the=counter=" + gateway.status().counter() + " balance="
                + gateway.status().balance() + " ledger=" + ledger.text(cardId) + " rows=" + ledger.rows(cardId)
                + " journal=" + journalText(journal) + " reconciliation=" + gateway.reconciliation().state()
                + " apdu=" + apdus + " postAttempts=" + ledger.verifyCalls.get());
        safeDisconnect(gateway);
        CardGateway reopened = open("reopen", host, simPort, apdus);
        try {
            require(reopened.identify().ok(), "mo lai nhan dien");
            int balance = ledger.snapshot(cardId).lastBalance();
            apdus.clear();
            ledger.verifyCalls.set(0);
            CardOperationResult closed = new AdminGrantRecovery(reopened, journal, backend)
                    .reconcile(() -> admin.clone());
            require(closed.ok() && closed.text().contains("duplicate") && closed.text().contains("HTTP=201"),
                    "doi soat phai nhan duplicate: " + closed.text());
            require(!journal.load().present(), "journal phai dong");
            require(count(apdus, GRANT) == 0, "khong phat lai GRANT: " + apdus);
            require(ledger.rows(cardId) == 1 && ledger.snapshot(cardId).lastBalance() == balance
                    && ledger.snapshot(cardId).lastCounter() == 1, "khong them dong va khong tang so luot");
            System.out.println("RECOVER apdu=" + apdus + " post=" + ledger.verifyCalls.get()
                    + " journal=" + journalText(journal) + " ledger=" + ledger.text(cardId)
                    + " rows=" + ledger.rows(cardId));
            System.out.println("RECOVER " + closed.text());
        } finally {
            safeDisconnect(reopened);
        }
    }

    private static void arm(int controlPort, String action) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", controlPort)) {
            socket.getOutputStream().write(("{\"action\":\"" + action + "\"}\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            String reply = new String(socket.getInputStream().readNBytes(200),
                    java.nio.charset.StandardCharsets.US_ASCII);
            require(reply.contains("\"ok\":true"), "relay khong nhan lenh: " + reply);
        }
    }

    private static CardGateway open(String role, String host, int port, List<Integer> apdus) throws Exception {
        CardException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            CardGateway gateway = new CardGateway(host, port);
            try {
                CardOperationResult connected = gateway.connect();
                if (connected.ok()) {
                    gateway.traceCommands(apdus::add);
                    return gateway;
                }
                last = new CardException(connected.text());
            } catch (CardException failure) {
                last = failure;
            }
            Thread.sleep(400);
        }
        throw new IllegalStateException(role + " khong ket noi duoc: " + last);
    }

    private static void safeDisconnect(CardGateway gateway) {
        try {
            if (gateway.isConnected() || gateway.needsReconnect()) {
                gateway.disconnect();
            }
        } catch (RuntimeException | CardException ignored) {
            // phiên đã đứt vì mất phản hồi
        }
    }

    private static void enroll(CardGateway gateway, BackendClient backend, String cardId, String studentId)
            throws Exception {
        require(gateway.readCardPublicKey().ok(), "doc khoa cong khai");
        BackendClient.AdminSession session = backend.issueAdminSession();
        BackendClient.Challenge challenge = backend.createChallenge(session.sessionId(), cardId, studentId,
                hex(gateway.lastCardPublicKeyBytes()));
        require(gateway.signRegistrationChallenge(hex(challenge.nonceHex())).ok(), "ky challenge");
        backend.completeEnrollment(session.sessionId(), challenge.challengeId(),
                hex(gateway.lastEnrollmentSignature()));
        gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "cardId=" + cardId));
    }

    private static int count(List<Integer> apdus, int ins) {
        int count = 0;
        for (int value : apdus) {
            if (value == ins) {
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
        return loaded.status() + " op=" + (loaded.valid() ? loaded.entry().operation() : "?")
                + " counter=" + (loaded.valid() ? loaded.entry().expectedCounter() : "?");
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
        volatile boolean dropNextAck;
        private volatile boolean open = true;

        LedgerDouble() throws IOException {
            server = new ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"));
        }

        void start() {
            Thread thread = new Thread(this::serve, "adgr11-ledger");
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

        private Reply route(String method, String path, byte[] raw) throws IOException {
            JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
            if ("POST".equals(method) && "/admin/sessions".equals(path)) {
                return json(201, "{\"sessionId\":\"" + java.util.UUID.randomUUID() + "\",\"expiresAt\":null}");
            }
            if ("POST".equals(method) && "/enrollments/challenges".equals(path)) {
                String id = java.util.UUID.randomUUID().toString();
                byte[] nonce = new byte[32];
                RANDOM.nextBytes(nonce);
                challenges.put(id, new Challenge(body.get("cardId").asText().toUpperCase(),
                        body.get("studentId").asText(), body.get("publicKey").asText()));
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
                        + ",\"lastBalance\":" + card.balance + ",\"activeVisitId\":null}");
            }
            if ("POST".equals(method) && "/receipts/verify".equals(path)) {
                return verify(body);
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
            }
            if (dropNextAck) {
                dropNextAck = false;
                return null;
            }
            return json(201, "{\"status\":\"" + status + "\",\"cardId\":\"" + receipt.cardIdHex()
                    + "\",\"operation\":\"GRANT\",\"counterAfter\":" + receipt.counterAfter()
                    + ",\"delta\":" + receipt.delta() + ",\"balanceAfter\":" + receipt.balanceAfter()
                    + ",\"visitId\":null}");
        }

        private static Reply json(int status, String body) {
            return new Reply(status, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private record Reply(int status, byte[] body) {
        }

        private record Challenge(String cardId, String studentId, String publicKey) {
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
