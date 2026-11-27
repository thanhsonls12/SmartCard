package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.APDUException;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;

final class StudentPinHandler {
    private static final short MAX_PIN_COMMAND_DATA = (short) 14;

    private StudentPinHandler() {
    }

    private static void requireIssuedStudentPin(CardState state, PinStore pins) {
        if (!state.isIssued() || !pins.isStudentPinAssigned()) {
            ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
        }
    }

    private static void checkStudentPin(PinStore pins, byte[] buffer, short offset) {
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

    static void handleVerifyStudentPin(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    MAX_PIN_COMMAND_DATA);
            ApduChecks.requireCase3(apdu);
            if (lc != Protocol.VERIFY_STUDENT_PIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), (short) 0,
                    Protocol.STUDENT_PIN_LENGTH);
            requireIssuedStudentPin(state, pins);
            checkStudentPin(pins, state.commandData(), (short) 0);
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } finally {
            pins.student().reset();
            state.clearCommandData();
        }
    }

    static void handleChangeStudentPin(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        boolean transactionStarted = false;
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    MAX_PIN_COMMAND_DATA);
            ApduChecks.requireCase3(apdu);
            if (lc != Protocol.CHANGE_STUDENT_PIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), Protocol.CHANGE_OLD_PIN_OFFSET,
                    Protocol.STUDENT_PIN_LENGTH);
            PinFormat.requireDigits(state.commandData(), Protocol.CHANGE_NEW_PIN_OFFSET,
                    Protocol.STUDENT_PIN_LENGTH);
            requireIssuedStudentPin(state, pins);
            checkStudentPin(pins, state.commandData(), Protocol.CHANGE_OLD_PIN_OFFSET);
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

    static void handleUnblockStudentPin(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        boolean transactionStarted = false;
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    MAX_PIN_COMMAND_DATA);
            ApduChecks.requireCase3(apdu);
            if (lc != Protocol.UNBLOCK_STUDENT_PIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), Protocol.UNBLOCK_ADMIN_PIN_OFFSET,
                    Protocol.ADMIN_PIN_LENGTH);
            PinFormat.requireDigits(state.commandData(),
                    Protocol.UNBLOCK_NEW_STUDENT_PIN_OFFSET, Protocol.STUDENT_PIN_LENGTH);
            requireIssuedStudentPin(state, pins);
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

    private static void abortTransaction(boolean transactionStarted) {
        if (transactionStarted) {
            JCSystem.abortTransaction();
        }
    }
}
