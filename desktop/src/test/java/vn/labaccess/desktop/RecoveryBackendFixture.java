package vn.labaccess.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class RecoveryBackendFixture implements AutoCloseable {
    record Reply(int status, String body) {
    }

    @FunctionalInterface
    interface Handler {
        Reply reply(String request, String body) throws IOException;
    }

    private final ServerSocket server;
    private final CompletableFuture<Void> serving;
    volatile int status = 200;
    volatile int counter = 1;
    volatile int balance = 5;

    RecoveryBackendFixture() throws Exception {
        this(null);
    }

    RecoveryBackendFixture(Handler handler) throws Exception {
        server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        serving = CompletableFuture.runAsync(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                            StandardCharsets.US_ASCII));
                    String request = input.readLine();
                    int length = 0;
                    for (String line = input.readLine(); line != null && !line.isEmpty(); line = input.readLine()) {
                        if (line.length() > 8192) throw new IOException("header too large");
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
                        }
                    }
                    StringBuilder incoming = new StringBuilder(length);
                    for (int index = 0; index < length; index++) {
                        int character = input.read();
                        if (character < 0) throw new IOException("missing request body");
                        incoming.append((char) character);
                    }
                    Reply reply = handler == null ? null : handler.reply(request, incoming.toString());
                    if (reply == null) {
                        reply = new Reply(status, body(request));
                    }
                    if (reply.status() == 0) continue;
                    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 " + reply.status() + " Fixture\r\nConnection: close\r\nContent-Type: application/json"
                            + "\r\nContent-Length: " + body.length + "\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                } catch (IOException failure) {
                    if (!server.isClosed()) throw new IllegalStateException(failure);
                }
            }
        });
    }

    private String body(String request) {
        return request.contains("/health/") ? "{\"status\":\"ok\",\"provider\":\"postgresql\"}"
                : request.contains("/receipts/ledger/") ? "{\"cardId\":\"" + RecoveryCardFixture.CARD_ID
                        + "\",\"lastCounter\":" + counter + ",\"lastBalance\":" + balance + ",\"activeVisitId\":null}"
                : "{\"cardId\":\"" + RecoveryCardFixture.CARD_ID + "\",\"transactions\":["
                        + "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"counterAfter\":1,"
                        + "\"operation\":\"GRANT\",\"delta\":5,\"balanceAfter\":5,"
                        + "\"verifyStatus\":\"verified\",\"recordedAt\":\"2026-11-16T08:00:00Z\"}]}";
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Override
    public void close() throws Exception {
        server.close();
        serving.get(10, TimeUnit.SECONDS);
    }
}
