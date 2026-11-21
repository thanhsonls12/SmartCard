package vn.labaccess.card;

import javacard.framework.AID;
import javacard.framework.APDU;
import javacard.framework.APDUException;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

final class AdminReadHandler {

    private final CardState state;
    private final PinStore pins;

    AdminReadHandler(CardState state, PinStore pins) {
        this.state = state;
        this.pins = pins;
    }

    boolean handleGetAdminStatus(APDU apdu, byte[] buffer) {
        try {
            byte aidLength = adminAidLength();
            short responseLength = StatusResponse.length(aidLength, true);
            authenticate(apdu, buffer, responseLength);
            Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA, Protocol.ADMIN_PIN_LENGTH,
                    (byte) 0);
            Util.arrayCopyNonAtomic(state.commandData(), Protocol.ADMIN_PIN_LENGTH, buffer,
                    Protocol.OFFSET_INSTANCE_AID, aidLength);
            StatusResponse.write(state, buffer, aidLength, true);
            apdu.setOutgoingLength(responseLength);
            apdu.sendBytes((short) 0, responseLength);
            return true;
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
        return false;
    }

    boolean handleGetAdminLastReceipt(APDU apdu, byte[] buffer) {
        try {
            authenticate(apdu, buffer, Protocol.RECEIPT_RESPONSE_MAX_LENGTH);
            Receipt receipt = state.receipt();
            if (!receipt.isStored()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            short responseLength = receipt.responseLength();
            Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA, Protocol.ADMIN_PIN_LENGTH,
                    (byte) 0);
            receipt.writeResponse(buffer, (short) 0);
            apdu.setOutgoingLength(responseLength);
            apdu.sendBytes((short) 0, responseLength);
            return true;
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
        return false;
    }

    private void authenticate(APDU apdu, byte[] buffer, short minimumLe) {
        ApduChecks.requireZeroP1P2(buffer);
        short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                Protocol.ADMIN_PIN_LENGTH);
        if (lc != Protocol.ADMIN_PIN_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short ne = apdu.setOutgoing();
        if (ne == (short) 0 || ne < minimumLe) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        PinFormat.requireDigits(state.commandData(), (short) 0, Protocol.ADMIN_PIN_LENGTH);
        if (!state.isIssued()) {
            ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
        }
        AdminPinVerifier.verify(pins.admin(), state.commandData(), (short) 0);
    }

    private byte adminAidLength() {
        AID instanceAid = JCSystem.getAID();
        if (instanceAid == null) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }
        return instanceAid.getBytes(state.commandData(), Protocol.ADMIN_PIN_LENGTH);
    }
}
