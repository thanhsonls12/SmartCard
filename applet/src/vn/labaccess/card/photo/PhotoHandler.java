package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.APDUException;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

final class PhotoHandler {
    private PhotoHandler() {
    }

    static void handlePhotoBegin(APDU apdu, byte[] buffer,
            CardState state, PinStore pins, PhotoStore photos) {
        beginPhotoUpload(apdu, buffer, false, state, pins, photos);
    }

    static void handlePhotoReplaceBegin(APDU apdu, byte[] buffer,
            CardState state, PinStore pins, PhotoStore photos) {
        beginPhotoUpload(apdu, buffer, true, state, pins, photos);
    }

    private static void beginPhotoUpload(APDU apdu, byte[] buffer, boolean replace,
            CardState state, PinStore pins, PhotoStore photos) {
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.PHOTO_BEGIN_DATA_LENGTH);
            ApduChecks.requireCase3(apdu);
            if (lc != Protocol.PHOTO_BEGIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (replace != photos.ready() || photos.uploadOpen()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            requirePhotoFormat(state.commandData());
            PinFormat.requireDigits(state.commandData(), Protocol.PHOTO_BEGIN_OFFSET_ADMIN_PIN,
                    Protocol.ADMIN_PIN_LENGTH);
            AdminPinVerifier.verify(pins.admin(), state.commandData(),
                    Protocol.PHOTO_BEGIN_OFFSET_ADMIN_PIN);
            photos.openUpload();
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            pins.admin().reset();
            state.clearCommandData();
        }
    }

    private static void requirePhotoFormat(byte[] data) {
        short total = Util.getShort(data, Protocol.PHOTO_BEGIN_OFFSET_TOTAL);
        if (data[Protocol.PHOTO_BEGIN_OFFSET_FORMAT] != Protocol.PHOTO_FORMAT_GRAYSCALE_RAW8
                || data[Protocol.PHOTO_BEGIN_OFFSET_WIDTH] != (byte) Protocol.PHOTO_WIDTH
                || data[Protocol.PHOTO_BEGIN_OFFSET_HEIGHT] != (byte) Protocol.PHOTO_HEIGHT
                || total != Protocol.PHOTO_LENGTH) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
    }

    static void handlePhotoWriteChunk(APDU apdu, byte[] buffer, CardState state, PhotoStore photos) {
        try {
            short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
            if (!photos.uploadOpen()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.PHOTO_CHUNK_MAX_LENGTH);
            ApduChecks.requireCase3(apdu);
            if (lc <= (short) 0 || lc > Protocol.PHOTO_CHUNK_MAX_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (offset != photos.uploadOffset()) {
                ISOException.throwIt(Sw.WRONG_P1P2);
            }
            if ((short) (offset + lc) > photos.capacity()) {
                ISOException.throwIt(Sw.WRONG_P1P2);
            }
            photos.appendUploaded(state.commandData(), (short) 0, lc);
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            state.clearCommandData();
        }
    }

    static void handlePhotoFinish(APDU apdu, byte[] buffer, PhotoStore photos) {
        try {
            ApduChecks.requireZeroP1P2(buffer);
            requireNoDataNoLe(apdu);
            if (!photos.uploadOpen()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (photos.uploadOffset() < photos.capacity()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            photos.commitPhoto(photos.capacity());
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }

    private static void requireNoDataNoLe(APDU apdu) {
        short received = apdu.setIncomingAndReceive();
        if (received != (short) 0 || apdu.getIncomingLength() != (short) 0) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        if (apdu.setOutgoing() != (short) 0) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
    }

    static boolean handlePhotoReadChunk(APDU apdu, byte[] buffer, PinStore pins, PhotoStore photos) {
        try {
            short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
            short ne = apdu.setOutgoing();
            if (ne <= (short) 0 || ne > Protocol.PHOTO_READ_MAX_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!photos.ready()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            photos.checkRecord();
            short length = photos.length();
            if (offset > length) {
                ISOException.throwIt(Sw.WRONG_P1P2);
            }
            if (offset == length) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (offset != photos.readOffset()) {
                ISOException.throwIt(Sw.WRONG_P1P2);
            }
            if (offset == (short) 0) {
                photos.loadForRead();
            }
            short remaining = (short) (length - offset);
            short chunk = ne < remaining ? ne : remaining;
            photos.copyPhoto(offset, buffer, (short) 0, chunk);
            apdu.setOutgoingLength(chunk);
            apdu.sendBytes((short) 0, chunk);
            photos.setReadOffset((short) (offset + chunk));
            if (photos.readOffset() == length) {
                return false;
            }
            return true;
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        return false;
    }
}
