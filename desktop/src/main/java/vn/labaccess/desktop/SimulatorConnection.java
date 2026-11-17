package vn.labaccess.desktop;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

final class SimulatorConnection {
    private static final int IO_TIMEOUT_MS = 10_000;
    private static final int MAX_SHORT_RESPONSE = 258;
    private static final int MAX_INFORMATION_LENGTH = 254;
    private static final int MORE_BLOCKS = 0x20;
    private static final int RECEIVE_READY = 0x80;
    private static final int WAIT_EXTENSION = 0xC3;
    private static final int WAIT_EXTENSION_RESPONSE = 0xE3;
    private final Socket socket = new Socket();
    private DataInputStream input;
    private OutputStream output;
    private byte[] atr;
    private int sendSequence;
    private int receiveSequence;
    private int informationLength;

    static SimulatorConnection open(String host, int port) throws CardException {
        SimulatorConnection connection = new SimulatorConnection();
        try {
            connection.socket.connect(new InetSocketAddress(host, port), IO_TIMEOUT_MS);
            connection.socket.setSoTimeout(IO_TIMEOUT_MS);
            connection.socket.setTcpNoDelay(true);
            connection.input = new DataInputStream(connection.socket.getInputStream());
            connection.output = connection.socket.getOutputStream();
            connection.powerUp();
            return connection;
        } catch (IOException failure) {
            connection.closeAfterFailure(failure);
            throw new CardException("Không mở được kết nối Simulator T=1", failure);
        }
    }

    byte[] atr() {
        return atr.clone();
    }

    private void powerUp() throws IOException {
        output.write(new byte[] { (byte) 0xF0, 0, 0, 0 });
        byte[] header = read(4);
        int length = ((header[2] & 255) << 8) | (header[3] & 255);
        if (length < 2 || length > 33) {
            throw new IOException("Độ dài ATR không hợp lệ");
        }
        atr = read(length);
        byte[] pps = { (byte) 0xFF, 0x11, (byte) 0x96, 0x78 };
        output.write(pps);
        if (!Arrays.equals(pps, read(pps.length))) {
            throw new IOException("Simulator không chấp nhận PPS T=1");
        }
        writeBlock(0xC1, new byte[] { (byte) MAX_INFORMATION_LENGTH });
        Block response = readBlock();
        if (response.control() != 0xE1 || response.data().length != 1) {
            throw new IOException("Phản hồi IFS không hợp lệ");
        }
        informationLength = response.data()[0] & 255;
        if (informationLength < 1 || informationLength > MAX_INFORMATION_LENGTH) {
            throw new IOException("Kích thước IFS không hợp lệ");
        }
    }

    synchronized ResponseAPDU transmit(CommandAPDU command) throws CardException {
        if (command.getNc() > 255 || command.getNe() > 256 || command.getCLA() != (command.getCLA() & 0xFC)) {
            throw new CardException("Chỉ hỗ trợ short APDU trên basic channel");
        }
        byte[] data = command.getBytes();
        try {
            Block response = null;
            for (int offset = 0; offset < data.length; offset += informationLength) {
                int end = Math.min(offset + informationLength, data.length);
                boolean more = end < data.length;
                writeBlock((sendSequence << 6) | (more ? MORE_BLOCKS : 0), Arrays.copyOfRange(data, offset, end));
                response = receiveBlock();
                sendSequence ^= 1;
                if (more && (response.control() != (RECEIVE_READY | (sendSequence << 4))
                        || response.data().length != 0)) {
                    throw new IOException("ACK của command không hợp lệ; không phát lại APDU");
                }
            }
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            while (true) {
                int control = response.control();
                if ((control & 0x9F) != 0 || (control >> 6) != receiveSequence) {
                    throw new IOException("Sequence hoặc loại response T=1 không hợp lệ");
                }
                receiveSequence ^= 1;
                received.write(response.data());
                if (received.size() > MAX_SHORT_RESPONSE) {
                    throw new IOException("Response vượt giới hạn short APDU");
                }
                if ((control & MORE_BLOCKS) == 0) {
                    byte[] bytes = received.toByteArray();
                    if (bytes.length < 2) {
                        throw new IOException("Response thiếu status word");
                    }
                    return new ResponseAPDU(bytes);
                }
                writeBlock(RECEIVE_READY | (receiveSequence << 4), new byte[0]);
                response = receiveBlock();
            }
        } catch (IOException failure) {
            closeAfterFailure(failure);
            throw new CardException("Trao đổi T=1 thất bại; không phát lại APDU", failure);
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private Block receiveBlock() throws IOException {
        Block block = readBlock();
        while (block.control() == WAIT_EXTENSION) {
            if (block.data().length != 1 || (block.data()[0] & 255) < 1 || (block.data()[0] & 255) > 59) {
                throw new IOException("WTX không hợp lệ");
            }
            writeBlock(WAIT_EXTENSION_RESPONSE, block.data());
            block = readBlock();
        }
        return block;
    }

    private Block readBlock() throws IOException {
        byte[] header = read(3);
        int length = header[2] & 255;
        if (header[0] != 0 || length > MAX_INFORMATION_LENGTH) {
            throw new IOException("Header T=1 không hợp lệ");
        }
        byte[] body = read(length + 1);
        if ((checksum(header) ^ checksum(body)) != 0) {
            throw new IOException("LRC không hợp lệ; không phát lại APDU");
        }
        return new Block(header[1] & 255, Arrays.copyOf(body, length));
    }

    private void writeBlock(int control, byte[] data) throws IOException {
        byte[] frame = new byte[data.length + 4];
        frame[1] = (byte) control;
        frame[2] = (byte) data.length;
        System.arraycopy(data, 0, frame, 3, data.length);
        frame[frame.length - 1] = (byte) checksum(frame);
        try {
            output.write(frame);
        } finally {
            Arrays.fill(data, (byte) 0);
            Arrays.fill(frame, (byte) 0);
        }
    }

    private static int checksum(byte[] bytes) {
        int value = 0;
        for (byte element : bytes) {
            value ^= element & 255;
        }
        return value;
    }

    private byte[] read(int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    void close() throws CardException {
        try {
            socket.close();
        } catch (IOException failure) {
            throw new CardException("Không đóng được kết nối Simulator", failure);
        }
    }

    void disconnect() throws CardException {
        try {
            if (!socket.isClosed()) {
                output.write(new byte[] { (byte) 0xFE, 0, 0, 0 });
            }
        } catch (IOException failure) {
            throw new CardException("Không tắt được phiên Simulator", failure);
        } finally {
            close();
        }
    }

    private void closeAfterFailure(IOException failure) {
        try {
            socket.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private record Block(int control, byte[] data) {
    }
}
