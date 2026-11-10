package vn.labaccess.card;

import javacard.framework.JCSystem;
import javacard.framework.Util;

final class PhotoStore {

    private static final short SESSION_OPEN = (short) 0;
    private static final short SESSION_UPLOAD_OFFSET = (short) 1;
    private static final short SESSION_READ_OFFSET = (short) 3;
    private static final short SESSION_LENGTH = (short) 5;
    private static final byte OPEN = (byte) 1;
    private static final byte CLOSED = (byte) 0;

    private final byte[] buffer = new byte[Protocol.PHOTO_LENGTH];
    private final byte[] session;

    private short photoLength;
    private boolean photoReady;

    PhotoStore() {
        session = JCSystem.makeTransientByteArray(SESSION_LENGTH, JCSystem.CLEAR_ON_DESELECT);
    }

    short capacity() {
        return Protocol.PHOTO_LENGTH;
    }

    boolean ready() {
        return photoReady;
    }

    short length() {
        return photoLength;
    }

    boolean uploadOpen() {
        return session[SESSION_OPEN] == OPEN;
    }

    short uploadOffset() {
        return Util.getShort(session, SESSION_UPLOAD_OFFSET);
    }

    void openUpload() {
        session[SESSION_OPEN] = OPEN;
        Util.setShort(session, SESSION_UPLOAD_OFFSET, (short) 0);
    }

    void closeUpload() {
        session[SESSION_OPEN] = CLOSED;
        Util.setShort(session, SESSION_UPLOAD_OFFSET, (short) 0);
    }

    void appendUploaded(byte[] source, short sourceOffset, short length) {
        short offset = uploadOffset();
        Util.arrayCopyNonAtomic(source, sourceOffset, buffer, offset, length);
        Util.setShort(session, SESSION_UPLOAD_OFFSET, (short) (offset + length));
    }

    void commitPhoto(short total) {
        photoLength = total;
        photoReady = true;
    }

    short readOffset() {
        return Util.getShort(session, SESSION_READ_OFFSET);
    }

    void setReadOffset(short offset) {
        Util.setShort(session, SESSION_READ_OFFSET, offset);
    }

    void clearReadStream() {
        Util.setShort(session, SESSION_READ_OFFSET, (short) 0);
    }

    void copyPhoto(short offset, byte[] target, short targetOffset, short length) {
        Util.arrayCopyNonAtomic(buffer, offset, target, targetOffset, length);
    }
}
