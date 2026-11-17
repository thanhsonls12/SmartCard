package vn.labaccess.desktop;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

public final class SimulatorConnectionVerify {
    private SimulatorConnectionVerify() {
    }

    public static void main(String[] args) throws Exception {
        for (int sw : new int[] { 0x6C06, 0x6106, 0x9000 }) {
            run("status-" + Integer.toHexString(sw), (input, output) -> {
                require(Arrays.equals(readBlock(input), new byte[] { 0, 0, 5, (byte) 0x80, 0x10, 0, 0, 0,
                        (byte) 0x95 }), "single command bytes");
                frame(output, 0, new byte[] { (byte) (sw >> 8), (byte) sw });
                require(input.read() == -1, "unexpected APDU replay or GET RESPONSE");
            }, connection -> require(connection.transmit(status()).getSW() == sw, "SW changed"));
        }
        run("waiting-time-extension", (input, output) -> {
            readBlock(input);
            output.write(new byte[] { 0, (byte) 0xC3, 1, 1, (byte) 0xC3 });
            require(Arrays.equals(readBlock(input), new byte[] { 0, (byte) 0xE3, 1, 1, (byte) 0xE3 }), "WTX response");
            frame(output, 0, new byte[] { (byte) 0x90, 0 });
        }, connection -> require(connection.transmit(status()).getSW() == 0x9000, "WTX changed response"));
        run("command-and-response-chaining", (input, output) -> {
            byte[] first = readBlock(input);
            require((first[1] & 255) == 0x20 && (first[2] & 255) == 254, "first command block");
            frame(output, 0x90, new byte[0]);
            byte[] last = readBlock(input);
            require((last[1] & 255) == 0x40 && (last[2] & 255) == 7, "last command block");
            byte[] command = new byte[261];
            System.arraycopy(first, 3, command, 0, 254);
            System.arraycopy(last, 3, command, 254, 7);
            require(command[0] == (byte) 0x80 && command[1] == 0x33 && command[4] == (byte) 255
                    && command[260] == 0, "command framing");
            for (int index = 5; index < 260; index++) require(command[index] == (byte) 0x5A, "command data");
            byte[] prefix = new byte[254];
            Arrays.fill(prefix, (byte) 0x5A);
            frame(output, 0x20, prefix);
            require(Arrays.equals(readBlock(input), new byte[] { 0, (byte) 0x90, 0, (byte) 0x90 }), "response ACK");
            frame(output, 0x40, new byte[] { 0x5A, 0x5A, (byte) 0x90, 0 });
        }, connection -> {
            byte[] data = new byte[255];
            Arrays.fill(data, (byte) 0x5A);
            ResponseAPDU response = connection.transmit(new CommandAPDU(0x80, 0x33, 0, 0, data, 256));
            require(response.getSW() == 0x9000 && response.getNr() == 256, "chained response size/SW");
            for (byte item : response.getData()) require(item == (byte) 0x5A, "chained response data");
        });
        for (String failure : new String[] { "bad-lrc", "wrong-sequence", "r-block", "missing-sw", "eof", "timeout" }) {
            run(failure, (input, output) -> {
                readBlock(input);
                switch (failure) {
                    case "bad-lrc" -> output.write(new byte[] { 0, 0, 2, (byte) 0x90, 0, 0 });
                    case "wrong-sequence" -> frame(output, 0x40, new byte[] { (byte) 0x90, 0 });
                    case "r-block" -> frame(output, 0x81, new byte[0]);
                    case "missing-sw" -> frame(output, 0, new byte[] { (byte) 0x90 });
                    case "eof" -> output.close();
                    case "timeout" -> { }
                    default -> throw new AssertionError(failure);
                }
                if (!failure.equals("eof")) require(input.read() == -1, "failed exchange was replayed");
            }, connection -> {
                boolean rejected = false;
                try {
                    connection.transmit(status());
                } catch (CardException expected) {
                    rejected = true;
                }
                require(rejected, "corrupt or incomplete response accepted");
                boolean closed = false;
                try {
                    connection.transmit(status());
                } catch (CardException expected) {
                    closed = true;
                }
                require(closed, "failed connection was reusable");
            });
        }
        gatewayFailures();
        System.out.println("SIMULATOR_CONNECTION cases=15 failed=0");
    }

    private static void gatewayFailures() throws Exception {
        for (String failure : new String[] {"eof", "timeout"}) {
            for (boolean pending : new boolean[] {false, true}) {
                java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("gateway-network-failure");
                PendingJournal journal = new PendingJournal(directory.resolve("pending.txt"));
                try {
                    run(failure, (input, output) -> {
                        readBlock(input);
                        if (failure.equals("eof")) {
                            output.close();
                        } else {
                            require(input.read() == -1, "timeout must close socket without replay");
                        }
                    }, connection -> {
                        CardGateway gateway = new RecoveryCardFixture().gateway();
                        java.lang.reflect.Field transportField = CardGateway.class.getDeclaredField("transport");
                        transportField.setAccessible(true);
                        CardTransport transport = (CardTransport) transportField.get(gateway);
                        java.lang.reflect.Field connectionField = CardTransport.class.getDeclaredField("connection");
                        connectionField.setAccessible(true);
                        connectionField.set(transport, connection);
                        java.lang.reflect.Field channelField = CardTransport.class.getDeclaredField("channel");
                        channelField.setAccessible(true);
                        channelField.set(transport, null);
                        if (pending) {
                            CardOperationResult result = gateway.runStateTransaction(journal,
                                    new CardGateway.TransactionPlan(new ReceiptExpectation(null, 2,
                                            Receipt.OPERATION_GRANT, null), 5, 10),
                                    () -> transport.transmit(new CommandAPDU(0x80, 0x50, 0, 0)), null);
                            require(!result.ok() && journal.load().present()
                                    && gateway.reconciliation().state() == Reconciliation.State.UNKNOWN,
                                    "lost response must keep pending UNKNOWN");
                        } else {
                            try {
                                gateway.transmitStatus();
                                throw new AssertionError("expected network failure");
                            } catch (CardException expected) {
                                require(!journal.load().present(), "read must not invent pending");
                            }
                        }
                        require(!gateway.isConnected() && !gateway.isReady() && gateway.needsReconnect(),
                                "failed socket must invalidate gateway");
                        try {
                            gateway.transmitStatus();
                            throw new AssertionError("broken socket reused");
                        } catch (IllegalStateException expected) {
                            require(expected.getMessage().contains("Kết nối lại"), "reconnect guidance");
                        }
                        gateway.disconnect();
                        require(!gateway.needsReconnect() && journal.load().present() == pending,
                                "disconnect must not delete pending");
                    });
                } finally {
                    java.nio.file.Files.deleteIfExists(journal.file());
                    java.nio.file.Files.delete(directory);
                }
            }
        }
    }

    private static CommandAPDU status() {
        return new CommandAPDU(0x80, 0x10, 0, 0, 256);
    }

    private static void run(String name, ServerCase serverCase, ClientCase clientCase) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(name.equals("timeout") ? 15_000 : 5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    require(Arrays.equals(input.readNBytes(4), new byte[] { (byte) 0xF0, 0, 0, 0 }), "powerup");
                    output.write(new byte[] { (byte) 0xF0, 0, 0, 2, 0x3B, 0 });
                    byte[] pps = { (byte) 0xFF, 0x11, (byte) 0x96, 0x78 };
                    require(Arrays.equals(input.readNBytes(4), pps), "PPS");
                    output.write(pps);
                    require(Arrays.equals(readBlock(input), new byte[] { 0, (byte) 0xC1, 1, (byte) 0xFE, 0x3E }), "IFS");
                    frame(output, 0xE1, new byte[] { (byte) 0xFE });
                    serverCase.run(input, output);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            SimulatorConnection connection = SimulatorConnection.open("127.0.0.1", server.getLocalPort());
            try {
                clientCase.run(connection);
            } finally {
                connection.close();
            }
            served.get(10, TimeUnit.SECONDS);
            System.out.println("TRANSPORT " + name + " | PASS");
        }
    }

    private static byte[] readBlock(DataInputStream input) throws Exception {
        byte[] header = input.readNBytes(3);
        require(header.length == 3, "incomplete header");
        byte[] block = Arrays.copyOf(header, 4 + (header[2] & 255));
        input.readFully(block, 3, block.length - 3);
        return block;
    }

    private static void frame(OutputStream output, int control, byte[] data) throws Exception {
        byte[] bytes = new byte[data.length + 4];
        bytes[1] = (byte) control;
        bytes[2] = (byte) data.length;
        System.arraycopy(data, 0, bytes, 3, data.length);
        for (int index = 0; index < bytes.length - 1; index++) bytes[bytes.length - 1] ^= bytes[index];
        output.write(bytes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface ServerCase {
        void run(DataInputStream input, OutputStream output) throws Exception;
    }

    private interface ClientCase {
        void run(SimulatorConnection connection) throws Exception;
    }
}
