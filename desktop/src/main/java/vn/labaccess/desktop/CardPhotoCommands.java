package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.IntConsumer;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

final class CardPhotoCommands {

    private static final byte INS_PHOTO_BEGIN = (byte) 0x32;
    private static final byte INS_PHOTO_WRITE_CHUNK = (byte) 0x33;
    private static final byte INS_PHOTO_READ_CHUNK = (byte) 0x34;
    private static final byte INS_PHOTO_FINISH = (byte) 0x35;

    private final CardTransport transport;
    private final PinCheck pinCheck;
    private final IntConsumer responseSwSink;

    private byte[] lastPhoto;
    private boolean photoAbsent;

    CardPhotoCommands(CardTransport transport, PinCheck pinCheck, IntConsumer responseSwSink) {
        this.transport = transport;
        this.pinCheck = pinCheck;
        this.responseSwSink = responseSwSink;
    }

    ResponseAPDU transmitPhotoBegin(byte[] data) throws CardException {
        transport.requireConnected();
        ResponseAPDU begin = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_PHOTO_BEGIN, 0x00, 0x00, data));
        responseSwSink.accept(begin.getSW());
        return begin;
    }

    ResponseAPDU transmitPhotoWriteChunk(int offset, byte[] chunk) throws CardException {
        transport.requireConnected();
        ResponseAPDU write = transport.transmit(new CommandAPDU(CardProtocol.CLA_APPLICATION,
                INS_PHOTO_WRITE_CHUNK, (offset >> 8) & 0xFF, offset & 0xFF, chunk));
        responseSwSink.accept(write.getSW());
        return write;
    }

    ResponseAPDU transmitPhotoFinish() throws CardException {
        transport.requireConnected();
        ResponseAPDU finish = transport.transmit(new CommandAPDU(
                CardProtocol.CLA_APPLICATION, INS_PHOTO_FINISH, 0x00, 0x00));
        responseSwSink.accept(finish.getSW());
        return finish;
    }

    CardOperationResult uploadPhoto(byte[] image, char[] adminPin) throws CardException {
        transport.requireConnected();
        lastPhoto = null;
        photoAbsent = false;
        if (image == null || image.length != PhotoImage.LENGTH) {
            return CardOperationResult.fail("ảnh phải đúng " + PhotoImage.LENGTH
                    + " byte grayscale raw 8-bit (chưa gửi APDU)");
        }
        if (adminPin == null || adminPin.length != PhotoImage.ADMIN_PIN_LENGTH) {
            return CardOperationResult.fail("admin PIN phải đúng " + PhotoImage.ADMIN_PIN_LENGTH
                    + " ký tự (chưa gửi APDU)");
        }
        byte[] beginData = PhotoImage.beginPayload(adminPin);
        try {
            ResponseAPDU begin = transmitPhotoBegin(beginData);
            if (begin.getSW() != Sw.OK) {
                return CardOperationResult.fail(String.format(
                        "PHOTO_BEGIN Case 3S Lc=%d SW=%04X | %s",
                        beginData.length, begin.getSW(), photoStatusText(begin.getSW())));
            }

            int offset = 0;
            int chunks = 0;
            while (offset < PhotoImage.LENGTH) {
                int length = PhotoImage.chunkLength(offset);
                ResponseAPDU write = transmitPhotoWriteChunk(offset,
                        PhotoImage.chunk(image, offset, length));
                if (write.getSW() != Sw.OK) {
                    return CardOperationResult.fail(String.format(
                            "PHOTO_WRITE_CHUNK offset=%d Lc=%d SW=%04X | %s",
                            offset, length, write.getSW(), photoStatusText(write.getSW())));
                }
                offset += length;
                chunks++;
            }

            ResponseAPDU finish = transmitPhotoFinish();
            if (finish.getSW() != Sw.OK) {
                return CardOperationResult.fail(String.format(
                        "PHOTO_FINISH Case 1 SW=%04X | %s",
                        finish.getSW(), photoStatusText(finish.getSW())));
            }
            return CardOperationResult.ok(String.format(
                    "PHOTO_BEGIN SW=9000 | PHOTO_WRITE_CHUNK x%d tổng=%d byte SW=9000"
                            + " | PHOTO_FINISH SW=9000 | photoReady=true | sha256 nguồn=%s",
                    chunks, offset, PhotoImage.fingerprint(image)));
        } finally {
            Arrays.fill(beginData, (byte) 0);
        }
    }

    ResponseAPDU transmitReadChunk(int offset, int le) throws CardException {
        transport.requireConnected();
        ResponseAPDU chunk = transport.transmit(new CommandAPDU(CardProtocol.CLA_APPLICATION,
                INS_PHOTO_READ_CHUNK, (offset >> 8) & 0xFF, offset & 0xFF, le));
        responseSwSink.accept(chunk.getSW());
        return chunk;
    }

    CardOperationResult readPhoto(char[] pin) throws CardException {
        transport.requireConnected();
        lastPhoto = null;
        photoAbsent = false;
        CardOperationResult verified = pinCheck.verify(pin);
        if (!verified.ok()) {
            return CardOperationResult.fail("đọc ảnh không chạy — VERIFY: " + verified.text());
        }
        byte[] image = new byte[PhotoImage.LENGTH];
        int offset = 0;
        int chunks = 0;
        while (offset < PhotoImage.LENGTH) {
            int length = PhotoImage.chunkLength(offset);
            ResponseAPDU chunk = transmitReadChunk(offset, length);
            if (chunk.getSW() != Sw.OK) {
                photoAbsent = offset == 0 && chunk.getSW() == Sw.CONDITIONS_NOT_SATISFIED;
                return CardOperationResult.fail(String.format(
                        "PHOTO_READ_CHUNK offset=%d Le=%d SW=%04X | %s",
                        offset, length, chunk.getSW(), photoStatusText(chunk.getSW())));
            }
            byte[] data = chunk.getData();
            if (data.length == 0 || data.length > length) {
                return CardOperationResult.fail(String.format(
                        "PHOTO_READ_CHUNK offset=%d trả %d byte, vượt Le=%d",
                        offset, data.length, length));
            }
            System.arraycopy(data, 0, image, offset, data.length);
            offset += data.length;
            chunks++;
        }
        lastPhoto = image;
        return CardOperationResult.ok(String.format(
                "VERIFY SW=9000 (quyền tiêu thụ khi hết chuỗi) | PHOTO_READ_CHUNK x%d"
                        + " tổng=%d byte SW=9000 | sha256 đọc lại=%s",
                chunks, offset, PhotoImage.fingerprint(image)));
    }

    byte[] lastPhoto() {
        return lastPhoto;
    }

    boolean photoAbsent() {
        return photoAbsent;
    }

    void reset() {
        lastPhoto = null;
        photoAbsent = false;
    }

    private static String photoStatusText(int sw) {
        if (sw == Sw.CONDITIONS_NOT_SATISFIED) {
            return "photoReady/phiên không cho phép: chưa BEGIN, đã ready, hoặc thiếu dữ liệu";
        }
        if (sw == Sw.SECURITY_NOT_SATISFIED) {
            return "thiếu quyền student — VERIFY ngay trước chuỗi đọc";
        }
        if (sw == Sw.WRONG_P1P2) {
            return "offset không liền mạch hoặc vượt tổng ảnh";
        }
        if (sw == Sw.WRONG_LENGTH) {
            return "Lc/Le sai (chunk 1..128, BEGIN Lc=13, FINISH không data/Le)";
        }
        if (sw == Sw.WRONG_DATA) {
            return "admin PIN sai định dạng hoặc format/kích thước sai";
        }
        if (sw == Sw.AUTH_BLOCKED) {
            return "admin PIN đã bị khóa";
        }
        return String.format("SW=%04X không đọc được ảnh", sw);
    }

    interface PinCheck {
        CardOperationResult verify(char[] pin) throws CardException;
    }
}
