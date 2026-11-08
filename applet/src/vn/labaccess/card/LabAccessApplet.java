package vn.labaccess.card;

import javacard.framework.AID;
import javacard.framework.APDU;
import javacard.framework.APDUException;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

public final class LabAccessApplet extends Applet {

    private static final short MAX_COMMAND_DATA = (short) 153;
    private static final short MAX_PIN_COMMAND_DATA = (short) 14;
    private static final short PUBLIC_KEY_LENGTH = (short) 65;
    private static final short PERSONALIZE_EXPECTED_NE = (short) 256;
    private static final byte PUBLIC_POINT_PREFIX = (byte) 0x04;

    private final PinStore pins;
    private final CardState state;
    private final Personalization personalization;

    private LabAccessApplet(byte[] installParameters, short aidOffset, byte aidLength,
            PinStore pins, CardState state, Personalization personalization) {
        this.pins = pins;
        this.state = state;
        this.personalization = personalization;
        register(installParameters, aidOffset, aidLength);
    }

    public static void install(byte[] installParameters, short offset, byte length) {
        short pinOffset = Provisioning.adminPinOffset(installParameters, offset, length);
        PinStore store = new PinStore();
        store.provisionAdminPin(installParameters, pinOffset);
        Util.arrayFillNonAtomic(installParameters, pinOffset,
                Protocol.ADMIN_PIN_LENGTH, (byte) 0x00);
        CardState cardState = new CardState();
        cardState.resetUnissued();
        Personalization personalization = new Personalization(cardState, store);
        new LabAccessApplet(installParameters, (short) (offset + 1),
                installParameters[offset], store, cardState, personalization);
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            pins.clearStudentAuthorization();
            pins.resetValidatedPins();
            return;
        }

        byte[] buffer = apdu.getBuffer();
        byte ins = buffer[ISO7816.OFFSET_INS];
        boolean authorizationGranted = false;
        try {
            requireApplicationCla(buffer);
            if (ins == Protocol.INS_GET_STATUS) {
                requireGetStatusP1P2(buffer);
                sendStatus(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_VERIFY_STUDENT_PIN) {
                handleVerifyStudentPin(apdu, buffer);
                pins.authorizeStudent();
                authorizationGranted = true;
                ISOException.throwIt(Sw.OK);
            }
            if (ins == Protocol.INS_CHANGE_STUDENT_PIN) {
                handleChangeStudentPin(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_UNBLOCK_STUDENT_PIN) {
                handleUnblockStudentPin(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_PERSONALIZE) {
                handlePersonalize(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_GET_CARD_PUBLIC_KEY) {
                handleGetCardPublicKey(apdu, buffer);
                return;
            }
            ISOException.throwIt(Sw.INS_NOT_SUPPORTED);
        } finally {
            pins.resetValidatedPins();
            if (!authorizationGranted) {
                pins.clearStudentAuthorization();
            }
            if (ins != Protocol.INS_GET_STATUS
                    && ins != Protocol.INS_GET_CARD_PUBLIC_KEY) {
                clearApduCommandData(buffer);
            }
        }
    }

    public void deselect() {
        pins.clearStudentAuthorization();
        pins.resetValidatedPins();
    }

    private void requireApplicationCla(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_CLA] != Protocol.CLA_APPLICATION) {
            ISOException.throwIt(Sw.CLA_NOT_SUPPORTED);
        }
    }

    private void requireGetStatusP1P2(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1_GET_STATUS
                || buffer[ISO7816.OFFSET_P2] != Protocol.P2_GET_STATUS) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
    }

    private void sendStatus(APDU apdu, byte[] buffer) {
        AID instanceAid = JCSystem.getAID();
        if (instanceAid == null) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }

        byte aidLength = instanceAid.getBytes(buffer, Protocol.OFFSET_INSTANCE_AID);
        short responseLength = Protocol.statusResponseLength(aidLength);

        short ne = apdu.setOutgoing();
        if (ne == (short) 0 || ne < responseLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }

        Util.arrayCopyNonAtomic(Protocol.MAGIC, (short) 0, buffer,
                Protocol.OFFSET_MAGIC, Protocol.MAGIC_LENGTH);
        buffer[Protocol.OFFSET_PROTOCOL_VERSION] = Protocol.PROTOCOL_VERSION;
        buffer[Protocol.OFFSET_AID_LENGTH] = aidLength;

        short lifecycleOffset = Protocol.lifecycleOffset(aidLength);
        buffer[lifecycleOffset] = state.lifecycle();
        Util.arrayCopyNonAtomic(state.cardId(), (short) 0, buffer,
                Protocol.cardIdOffset(aidLength), Protocol.CARD_ID_LENGTH);

        apdu.setOutgoingLength(responseLength);
        apdu.sendBytes((short) 0, responseLength);
    }

    private short receiveCommandData(APDU apdu, byte[] buffer, byte[] target,
            short maximumLength) {
        short received = apdu.setIncomingAndReceive();
        short lc = apdu.getIncomingLength();
        if (lc > maximumLength) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        short cdataOffset = apdu.getOffsetCdata();
        short total = (short) 0;
        while (total < lc) {
            if (received <= (short) 0 || (short) (total + received) > lc) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            Util.arrayCopyNonAtomic(buffer, cdataOffset, target, total, received);
            total += received;
            if (total < lc) {
                received = apdu.receiveBytes(cdataOffset);
            }
        }
        return lc;
    }

    private void requirePinCommandP1P2(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
    }

    private void requireCase3(APDU apdu) {
        if (apdu.setOutgoing() != (short) 0) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
    }

    private void requireIssuedStudentPin() {
        if (!state.isIssued() || !pins.isStudentPinAssigned()) {
            ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
        }
    }

    private void checkStudentPin(byte[] buffer, short offset) {
        if (pins.student().getTriesRemaining() == (byte) 0) {
            ISOException.throwIt(Sw.PIN_BLOCKED);
        }
        if (!pins.student().check(buffer, offset, Protocol.STUDENT_PIN_LENGTH)) {
            byte triesRemaining = pins.student().getTriesRemaining();
            if (triesRemaining == (byte) 0) {
                ISOException.throwIt(Sw.PIN_BLOCKED);
            }
            ISOException.throwIt((short) (Sw.VERIFY_FAIL_WITH_RETRIES + triesRemaining));
        }
    }

    private void handleVerifyStudentPin(APDU apdu, byte[] buffer) {
        try {
            requirePinCommandP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
                    MAX_PIN_COMMAND_DATA);
            requireCase3(apdu);
            if (lc != Protocol.VERIFY_STUDENT_PIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), (short) 0,
                    Protocol.STUDENT_PIN_LENGTH);
            requireIssuedStudentPin();
            checkStudentPin(state.commandData(), (short) 0);
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } finally {
            pins.student().reset();
            state.clearCommandData();
        }
    }

    private void handleChangeStudentPin(APDU apdu, byte[] buffer) {
        boolean transactionStarted = false;
        try {
            requirePinCommandP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
                    MAX_PIN_COMMAND_DATA);
            requireCase3(apdu);
            if (lc != Protocol.CHANGE_STUDENT_PIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), Protocol.CHANGE_OLD_PIN_OFFSET,
                    Protocol.STUDENT_PIN_LENGTH);
            PinFormat.requireDigits(state.commandData(), Protocol.CHANGE_NEW_PIN_OFFSET,
                    Protocol.STUDENT_PIN_LENGTH);
            requireIssuedStudentPin();
            checkStudentPin(state.commandData(), Protocol.CHANGE_OLD_PIN_OFFSET);
            JCSystem.beginTransaction();
            transactionStarted = true;
            pins.updateStudentPin(state.commandData(), Protocol.CHANGE_NEW_PIN_OFFSET);
            JCSystem.commitTransaction();
            transactionStarted = false;
            ISOException.throwIt(Sw.OK);
        } catch (ISOException exception) {
            abortTransaction(transactionStarted);
            throw exception;
        } catch (APDUException exception) {
            abortTransaction(transactionStarted);
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            abortTransaction(transactionStarted);
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            pins.student().reset();
            state.clearCommandData();
        }
    }

    private void handleUnblockStudentPin(APDU apdu, byte[] buffer) {
        boolean transactionStarted = false;
        try {
            requirePinCommandP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
                    MAX_PIN_COMMAND_DATA);
            requireCase3(apdu);
            if (lc != Protocol.UNBLOCK_STUDENT_PIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), Protocol.UNBLOCK_ADMIN_PIN_OFFSET,
                    Protocol.ADMIN_PIN_LENGTH);
            PinFormat.requireDigits(state.commandData(),
                    Protocol.UNBLOCK_NEW_STUDENT_PIN_OFFSET, Protocol.STUDENT_PIN_LENGTH);
            requireIssuedStudentPin();
            AdminPinVerifier.verify(pins.admin(), state.commandData(),
                    Protocol.UNBLOCK_ADMIN_PIN_OFFSET);
            JCSystem.beginTransaction();
            transactionStarted = true;
            pins.updateStudentPin(state.commandData(),
                    Protocol.UNBLOCK_NEW_STUDENT_PIN_OFFSET);
            JCSystem.commitTransaction();
            transactionStarted = false;
            pins.unblockStudentPin();
            ISOException.throwIt(Sw.OK);
        } catch (ISOException exception) {
            abortTransaction(transactionStarted);
            throw exception;
        } catch (APDUException exception) {
            abortTransaction(transactionStarted);
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            abortTransaction(transactionStarted);
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            pins.admin().reset();
            state.clearCommandData();
        }
    }

    private void abortTransaction(boolean transactionStarted) {
        if (transactionStarted) {
            JCSystem.abortTransaction();
        }
    }

    private static void clearApduCommandData(byte[] buffer) {
        short length = (short) (buffer.length - ISO7816.OFFSET_CDATA);
        Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA, length, (byte) 0);
    }

    private void handlePersonalize(APDU apdu, byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
        state.ensureCommandBuffer();
        try {
            short lc = receiveCommandData(apdu, buffer, state.commandData(), MAX_COMMAND_DATA);
            short ne = apdu.setOutgoing();
            if (ne != PERSONALIZE_EXPECTED_NE) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            personalization.process(state.commandData(), lc);
            ISOException.throwIt(Sw.OK);
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } finally {
            Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA,
                    MAX_COMMAND_DATA, (byte) 0);
            state.clearCommandData();
        }
    }

    private void handleGetCardPublicKey(APDU apdu, byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
        if (!state.isIssued()) {
            ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
        }
        short ne = apdu.setOutgoing();
        if (ne == (short) 0 || ne < PUBLIC_KEY_LENGTH) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        personalization.readPublicKey(buffer, (short) 0);
        if (buffer[0] != PUBLIC_POINT_PREFIX) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        apdu.setOutgoingLength(PUBLIC_KEY_LENGTH);
        apdu.sendBytes((short) 0, PUBLIC_KEY_LENGTH);
    }
}
