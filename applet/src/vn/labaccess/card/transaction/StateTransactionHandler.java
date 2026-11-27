package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.APDUException;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

final class StateTransactionHandler {
    private StateTransactionHandler() {
    }

    static void handleGrantQuota(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        boolean responseSent = false;
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.GRANT_QUOTA_DATA_LENGTH);
            if (lc != Protocol.GRANT_QUOTA_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < Protocol.STATE_RESPONSE_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            PinFormat.requireDigits(state.commandData(), Protocol.GRANT_ADMIN_PIN_OFFSET,
                    Protocol.ADMIN_PIN_LENGTH);
            short delta = Util.getShort(state.commandData(), Protocol.GRANT_DELTA_OFFSET);
            if (delta <= (short) 0) {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            AdminPinVerifier.verify(pins.admin(), state.commandData(),
                    Protocol.GRANT_ADMIN_PIN_OFFSET);
            QuotaLedger ledger = state.quota();
            if (ledger.counterExhausted()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (ledger.exceedsCapacity(delta)) {
                ISOException.throwIt(Sw.WRONG_DATA);
            }
            Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA, lc, (byte) 0);
            commitState(state, Protocol.OPERATION_GRANT_QUOTA, delta, Protocol.UNASSIGNED_VISIT_ID,
                    (short) 0);
            writeStateResponse(ledger, buffer);
            apdu.setOutgoingLength(Protocol.STATE_RESPONSE_LENGTH);
            apdu.sendBytes((short) 0, Protocol.STATE_RESPONSE_LENGTH);
            responseSent = true;
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            pins.admin().reset();
            state.clearCommandData();
            if (!responseSent) {
                ApduChecks.clearApduCommandData(buffer);
            }
        }
    }

    private static void commitState(CardState state, byte operation, short delta,
            byte[] visitId, short visitIdOffset) {
        QuotaLedger ledger = state.quota();
        VisitLedger visits = state.visits();
        Receipt receipt = state.receipt();
        short balanceAfter = (short) (ledger.balance() + delta);
        receipt.stage(operation, delta, balanceAfter, state.cardId(), visitId, visitIdOffset,
                state.receiptSigner(), ledger);
        boolean started = false;
        try {
            JCSystem.beginTransaction();
            started = true;
            ledger.setBalance(balanceAfter);
            ledger.setCounter(receipt.stagedPayload(), Protocol.RECEIPT_OFFSET_COUNTER);
            if (operation == Protocol.OPERATION_START_VISIT) {
                visits.open(visitId, visitIdOffset);
            } else if (operation == Protocol.OPERATION_END_VISIT) {
                visits.close();
            }
            receipt.store();
            JCSystem.commitTransaction();
            started = false;
        } finally {
            if (started) {
                JCSystem.abortTransaction();
            }
        }
    }

    static void handleStartVisit(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        boolean responseSent = false;
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.VISIT_ID_LENGTH);
            if (lc != Protocol.VISIT_ID_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < Protocol.STATE_RESPONSE_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            byte[] visitId = state.commandData();
            VisitLedger.requireNotAllZero(visitId, (short) 0);
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            VisitLedger visits = state.visits();
            if (visits.hasActiveVisit()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            QuotaLedger ledger = state.quota();
            if (ledger.balance() <= (short) 0) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (ledger.counterExhausted()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (visits.matchesLastClosedVisit(visitId, (short) 0)) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA, lc, (byte) 0);
            commitState(state, Protocol.OPERATION_START_VISIT, Protocol.START_VISIT_DELTA, visitId,
                    (short) 0);
            writeStateResponse(ledger, buffer);
            apdu.setOutgoingLength(Protocol.STATE_RESPONSE_LENGTH);
            apdu.sendBytes((short) 0, Protocol.STATE_RESPONSE_LENGTH);
            responseSent = true;
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            pins.clearStudentAuthorization();
            state.clearCommandData();
            if (!responseSent) {
                ApduChecks.clearApduCommandData(buffer);
            }
        }
    }

    static void handleEndVisit(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        boolean responseSent = false;
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.VISIT_ID_LENGTH);
            if (lc != Protocol.VISIT_ID_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < Protocol.STATE_RESPONSE_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            VisitLedger visits = state.visits();
            if (!visits.hasActiveVisit()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!visits.matchesActiveVisit(state.commandData(), (short) 0)) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            QuotaLedger ledger = state.quota();
            if (ledger.counterExhausted()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            Util.arrayFillNonAtomic(buffer, ISO7816.OFFSET_CDATA, lc, (byte) 0);
            commitState(state, Protocol.OPERATION_END_VISIT, Protocol.END_VISIT_DELTA, state.commandData(),
                    (short) 0);
            writeStateResponse(ledger, buffer);
            apdu.setOutgoingLength(Protocol.STATE_RESPONSE_LENGTH);
            apdu.sendBytes((short) 0, Protocol.STATE_RESPONSE_LENGTH);
            responseSent = true;
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            pins.clearStudentAuthorization();
            state.clearCommandData();
            if (!responseSent) {
                ApduChecks.clearApduCommandData(buffer);
            }
        }
    }

    static void handleGetLastReceipt(APDU apdu, byte[] buffer, CardState state, PinStore pins) {
        try {
            ApduChecks.requireZeroP1P2(buffer);
            short ne = apdu.setOutgoing();
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            Receipt receipt = state.receipt();
            if (!receipt.isStored()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            short responseLength = receipt.responseLength();
            if (ne == (short) 0 || ne < responseLength) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            receipt.writeResponse(buffer, (short) 0);
            apdu.setOutgoingLength(responseLength);
            apdu.sendBytes((short) 0, responseLength);
        } finally {
            pins.clearStudentAuthorization();
        }
    }

    private static void writeStateResponse(QuotaLedger ledger, byte[] buffer) {
        Util.setShort(buffer, Protocol.STATE_OFFSET_BALANCE, ledger.balance());
        ledger.writeCounter(buffer, Protocol.STATE_OFFSET_COUNTER);
    }
}
