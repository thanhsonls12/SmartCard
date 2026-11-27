package vn.labaccess.desktop.virtualreader.protocol;

import java.io.IOException;
import javax.smartcardio.CardException;

public final class VirtualReaderSession {
    public static final class Unavailable extends CardException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    private final VirtualReaderProtocol protocol;
    private VirtualReaderProtocol.Lease lease;

    public VirtualReaderSession(VirtualReaderProtocol protocol) {
        this.protocol = protocol;
    }

    public boolean isEnabled() {
        return protocol != null;
    }

    public void acquire() throws CardException {
        if (protocol == null) {
            return;
        }
        if (lease != null) {
            throw new CardException("Phiên thẻ cũ chưa được giải phóng.");
        }
        try {
            lease = protocol.tryAcquire();
            if (lease == null) {
                throw new Unavailable("Đầu đọc này đang đổi thẻ hoặc đang có phiên kết nối.");
            }
            if (protocol.read() == null) {
                throw new Unavailable("Hãy chọn thẻ trong Virtual Card Reader trước.");
            }
        } catch (IOException | CardException failure) {
            if (lease != null) {
                try {
                    release();
                } catch (CardException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof CardException cardFailure) {
                throw cardFailure;
            }
            throw new CardException(failure.getMessage(), failure);
        }
    }

    public void release() throws CardException {
        if (lease != null) {
            try {
                lease.close();
                lease = null;
            } catch (IOException failure) {
                throw new CardException("Không giải phóng được phiên Virtual Card Reader.", failure);
            }
        }
    }
}
