package vn.labaccess.desktop;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.smartcardio.ATR;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

final class CardTransport {

    record Opened(String terminalName, byte[] atr) {
    }

    private static final int CARD_IO_TIMEOUT_MS = 10_000;
    private static final int CARD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int CARD_CONNECT_REAP_MS = 60_000;

    private static final ExecutorService CARD_TRANSMIT = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "card-transmit");
        thread.setDaemon(true);
        return thread;
    });

    private final String host;
    private final int port;

    private TerminalFactory factory;
    private CardTerminal terminal;
    private Card card;
    private CardChannel channel;
    private volatile boolean connectedFlag;
    private volatile String ioTimeoutDetail;
    private volatile java.util.function.Consumer<String> diagnostics = text -> {
    };

    CardTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    CardTransport(CardChannel channel) {
        this.host = null;
        this.port = 0;
        this.channel = channel;
        this.connectedFlag = channel != null;
    }

    boolean isOpen() {
        return card != null;
    }

    boolean isConnected() {
        return connectedFlag;
    }

    void setDiagnostics(java.util.function.Consumer<String> sink) {
        this.diagnostics = sink;
    }

    private void reportSessionProblem(Attempt attempt, String detail) {
        attempt.recordProblem(detail);
        diagnostics.accept("dọn phiên thẻ: " + detail);
    }

    Opened connect() throws CardException, java.security.NoSuchAlgorithmException,
            java.security.NoSuchProviderException {

        Attempt attempt = new Attempt();
        Future<Session> opening = CARD_TRANSMIT.submit(() -> openSession(attempt));
        Session session;
        try {
            session = opening.get(CARD_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            attempt.abandon();
            reapLateSession(opening, attempt);
            throw new CardException(connectTimeoutMessage(attempt));
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CardException cardFailure) {
                throw cardFailure;
            }
            if (cause instanceof java.security.NoSuchAlgorithmException missingAlgorithm) {
                throw missingAlgorithm;
            }
            if (cause instanceof java.security.NoSuchProviderException missingProvider) {
                throw missingProvider;
            }
            throw new CardException("lỗi khi kết nối thẻ: " + cause, cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CardException("bị ngắt khi chờ kết nối thẻ");
        }
        return publish(session);
    }

    private Session openSession(Attempt attempt) throws CardException,
            java.security.NoSuchAlgorithmException, java.security.NoSuchProviderException {

        TerminalFactory created = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress(host, port)),
                "SocketCardTerminalProvider");

        List<CardTerminal> terminals = created.terminals().list();
        if (terminals.isEmpty()) {
            throw new CardException("Không có terminal nào từ socket provider");
        }
        CardTerminal opened = terminals.get(0);

        if (!opened.isCardPresent() && !opened.waitForCardPresent(10_000)) {
            throw new CardException("Không có thẻ trên terminal " + opened.getName());
        }

        Card connected = opened.connect("*");
        attempt.record(connected);
        if (attempt.isAbandoned()) {
            String problem = closeProblem(connected);
            if (problem != null) {
                reportSessionProblem(attempt, problem);
            }
            throw new CardException("lần mở phiên đã bị bỏ vì quá hạn chờ");
        }
        ATR atr = connected.getATR();
        return new Session(created, opened, connected, connected.getBasicChannel(),
                opened.getName(), atr == null ? null : atr.getBytes());
    }

    private Opened publish(Session session) {
        factory = session.factory();
        terminal = session.terminal();
        card = session.card();
        channel = session.channel();
        ioTimeoutDetail = null;
        connectedFlag = true;
        return new Opened(session.terminalName(), session.atr());
    }

    private void reapLateSession(Future<Session> opening, Attempt attempt) {
        CARD_TRANSMIT.submit(() -> {
            try {
                String problem = closeProblem(
                        opening.get(CARD_CONNECT_REAP_MS, TimeUnit.MILLISECONDS).card());
                if (problem != null) {
                    reportSessionProblem(attempt, problem);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reportSessionProblem(attempt, "bị ngắt khi chờ đóng phiên mở muộn");
            } catch (ExecutionException failure) {
                reportSessionProblem(attempt,
                        "lần mở phiên muộn kết thúc lỗi nên không nhận được Session để đóng: "
                                + failure.getCause());
            } catch (TimeoutException stillOpening) {
                reportSessionProblem(attempt, "phiên mở muộn vẫn chưa xong sau "
                        + CARD_CONNECT_REAP_MS + " ms — simulator có thể còn giữ một phiên thẻ");
            } catch (CancellationException cancelled) {
                reportSessionProblem(attempt, "lần mở phiên muộn đã bị huỷ trước khi kịp đóng");
            }
        });
    }

    private String closeProblem(Card opened) {
        if (opened == null) {
            return null;
        }
        try {
            opened.disconnect(true);
            return null;
        } catch (CardException failed) {
            return "đóng phiên thẻ thất bại: " + failed.getMessage();
        }
    }

    private String connectTimeoutMessage(Attempt attempt) {
        String base = "Simulator không trả lời bước mở phiên thẻ trong "
                + CARD_CONNECT_TIMEOUT_MS + " ms — chưa có kết nối nào được mở";
        String problem = attempt.problem();
        return problem == null ? base : base + " | " + problem;
    }

    private record Session(TerminalFactory factory, CardTerminal terminal, Card card,
            CardChannel channel, String terminalName, byte[] atr) {
    }

    private final class Attempt {
        private volatile boolean abandoned;
        private final AtomicReference<Card> opened = new AtomicReference<>();
        private volatile String problem;

        void record(Card connected) {
            opened.set(connected);
        }

        boolean isAbandoned() {
            return abandoned;
        }

        String problem() {
            return problem;
        }

        synchronized void recordProblem(String detail) {
            if (problem == null) {
                problem = detail;
            }
        }

        void abandon() {
            abandoned = true;
            String closeFailure = closeProblem(opened.getAndSet(null));
            if (closeFailure != null) {
                reportSessionProblem(this, closeFailure);
            }
        }
    }

    ResponseAPDU transmit(CommandAPDU command) throws CardException {
        CardChannel channelNow = channel;
        if (channelNow == null) {
            throw new CardException("Chưa kết nối");
        }
        Future<ResponseAPDU> pending = CARD_TRANSMIT.submit(() -> channelNow.transmit(command));
        try {
            return pending.get(CARD_IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            ioTimeoutDetail = "không có phản hồi APDU trong " + CARD_IO_TIMEOUT_MS + " ms";
            throw new CardException("Simulator không phản hồi: " + ioTimeoutDetail);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof CardException cardFailure) {
                throw cardFailure;
            }
            throw new CardException("lỗi truyền APDU: " + cause, cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CardException("bị ngắt khi chờ phản hồi APDU");
        }
    }

    void requireConnected() {
        if (ioTimeoutDetail != null) {
            throw new IllegalStateException("APDU trước bị timeout (" + ioTimeoutDetail
                    + ") — kênh thẻ không dùng lại được, phải Ngắt kết nối rồi Kết nối lại");
        }
        if (channel == null) {
            throw new IllegalStateException("Chưa kết nối");
        }
    }

    boolean disconnect() throws CardException {
        ioTimeoutDetail = null;
        channel = null;
        terminal = null;
        factory = null;
        boolean wasConnected = card != null;
        try {
            if (card != null) {
                card.disconnect(true);
            }
        } finally {
            card = null;
            connectedFlag = false;
        }
        return wasConnected;
    }
}
