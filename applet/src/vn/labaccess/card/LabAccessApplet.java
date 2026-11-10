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
    private final PhotoStore photos;

    private LabAccessApplet(byte[] installParameters, short aidOffset, byte aidLength,
            PinStore pins, CardState state, Personalization personalization, PhotoStore photos) {
        this.pins = pins;
        this.state = state;
        this.personalization = personalization;
        this.photos = photos;
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
        PhotoStore photoStore = new PhotoStore();
        new LabAccessApplet(installParameters, (short) (offset + 1),
                installParameters[offset], store, cardState, personalization, photoStore);
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            pins.clearStudentAuthorization();
            pins.resetValidatedPins();
            photos.clearReadStream();
            photos.closeUpload();
            return;
        }

        byte[] buffer = apdu.getBuffer();
        byte ins = buffer[ISO7816.OFFSET_INS];
        boolean authorizationGranted = false;
        if (ins != Protocol.INS_PHOTO_READ_CHUNK) {
            photos.clearReadStream();
        }
        if (ins != Protocol.INS_PHOTO_BEGIN && ins != Protocol.INS_PHOTO_WRITE_CHUNK
                && ins != Protocol.INS_PHOTO_FINISH) {
            photos.closeUpload();
        }
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
            if (ins == Protocol.INS_GET_PROFILE) {
                handleGetProfile(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_GET_PROFILE_CRYPTO_META) {
                handleGetProfileCryptoMeta(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_PHOTO_BEGIN) {
                handlePhotoBegin(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_PHOTO_WRITE_CHUNK) {
                handlePhotoWriteChunk(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_PHOTO_FINISH) {
                handlePhotoFinish(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_PHOTO_READ_CHUNK) {
                if (handlePhotoReadChunk(apdu, buffer)) {
                    authorizationGranted = true;
                }
                return;
            }
            if (ins == Protocol.INS_GRANT_QUOTA) {
                handleGrantQuota(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_START_VISIT) {
                handleStartVisit(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_END_VISIT) {
                handleEndVisit(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_GET_LAST_RECEIPT) {
                handleGetLastReceipt(apdu, buffer);
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
            if (receivesCommandData(ins)) {
                clearApduCommandData(buffer);
            }
        }
    }

    private static boolean receivesCommandData(byte ins) {
        return ins == Protocol.INS_PERSONALIZE
                || ins == Protocol.INS_VERIFY_STUDENT_PIN
                || ins == Protocol.INS_CHANGE_STUDENT_PIN
                || ins == Protocol.INS_UNBLOCK_STUDENT_PIN
                || ins == Protocol.INS_PHOTO_BEGIN
                || ins == Protocol.INS_PHOTO_WRITE_CHUNK;
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
        short publicLength = Protocol.statusResponseLength(aidLength);
        boolean includeStudentState = state.isIssued() && pins.isStudentAuthorized();
        short responseLength = includeStudentState
                ? Protocol.authenticatedStatusResponseLength(aidLength)
                : publicLength;

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

        if (includeStudentState) {
            Util.setShort(buffer, publicLength, state.quota().balance());
            state.quota().writeCounter(buffer,
                    (short) (publicLength + Protocol.STATUS_AUTH_OFFSET_COUNTER));
            VisitLedger visits = state.visits();
            buffer[(short) (publicLength + Protocol.STATUS_AUTH_OFFSET_ACTIVE_VISIT)] =
                    visits.hasActiveVisit() ? Protocol.STATUS_ACTIVE_VISIT_OPEN
                            : Protocol.STATUS_ACTIVE_VISIT_CLOSED;
            visits.writeActiveVisitId(buffer,
                    (short) (publicLength + Protocol.STATUS_AUTH_OFFSET_ACTIVE_VISIT_ID));
        }

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

    private void requireZeroP1P2(byte[] buffer) {
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
            requireZeroP1P2(buffer);
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
            requireZeroP1P2(buffer);
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
            requireZeroP1P2(buffer);
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

    private void handleGetProfile(APDU apdu, byte[] buffer) {
        try {
            requireZeroP1P2(buffer);
            short expectedLength = (short) (state.profileCiphertextLength()
                    - ProfileCrypto.RECORD_HEADER_LENGTH - ProfileCrypto.GCM_TAG_LENGTH);
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < expectedLength) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            byte[] plaintext = state.profilePlaintext();
            short plaintextLength = personalization.readProfilePlaintext(plaintext);
            ProfileCodec.requireSchemaV1(plaintext, plaintextLength);
            Util.arrayCopyNonAtomic(plaintext, (short) 0, buffer, (short) 0, plaintextLength);
            apdu.setOutgoingLength(plaintextLength);
            apdu.sendBytes((short) 0, plaintextLength);
        } finally {
            state.clearWorkingBuffers();
            pins.clearStudentAuthorization();
        }
    }

    private void handleGetProfileCryptoMeta(APDU apdu, byte[] buffer) {
        try {
            requireZeroP1P2(buffer);
            short ne = apdu.setOutgoing();
            if (ne == (short) 0 || ne < Protocol.PROFILE_META_RESPONSE_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (!pins.isStudentAuthorized()) {
                ISOException.throwIt(Sw.SECURITY_STATUS_NOT_SATISFIED);
            }
            short metadataLength = personalization.readProfileMetadata(buffer);
            apdu.setOutgoingLength(metadataLength);
            apdu.sendBytes((short) 0, metadataLength);
        } finally {
            state.clearWorkingBuffers();
            pins.clearStudentAuthorization();
        }
    }

    private void handlePhotoBegin(APDU apdu, byte[] buffer) {
        try {
            requireZeroP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.PHOTO_BEGIN_DATA_LENGTH);
            requireCase3(apdu);
            if (lc != Protocol.PHOTO_BEGIN_DATA_LENGTH) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            if (!state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (photos.ready() || photos.uploadOpen()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            requirePhotoFormat(state.commandData());
            PinFormat.requireDigits(state.commandData(), Protocol.PHOTO_BEGIN_OFFSET_ADMIN_PIN,
                    Protocol.ADMIN_PIN_LENGTH);
            AdminPinVerifier.verify(pins.admin(), state.commandData(),
                    Protocol.PHOTO_BEGIN_OFFSET_ADMIN_PIN);
            photos.openUpload();
            ISOException.throwIt(Sw.OK);
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

    private void handlePhotoWriteChunk(APDU apdu, byte[] buffer) {
        boolean accepted = false;
        try {
            short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
            if (!photos.uploadOpen()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
                    Protocol.PHOTO_CHUNK_MAX_LENGTH);
            requireCase3(apdu);
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
            accepted = true;
            ISOException.throwIt(Sw.OK);
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            state.clearCommandData();
            if (!accepted) {
                photos.closeUpload();
            }
        }
    }

    private void handlePhotoFinish(APDU apdu, byte[] buffer) {
        try {
            requireZeroP1P2(buffer);
            requireNoDataNoLe(apdu);
            if (!photos.uploadOpen()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (photos.ready()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            if (photos.uploadOffset() < photos.capacity()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            photos.commitPhoto(photos.capacity());
            ISOException.throwIt(Sw.OK);
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            photos.closeUpload();
        }
    }

    private void requireNoDataNoLe(APDU apdu) {
        short received = apdu.setIncomingAndReceive();
        if (received != (short) 0 || apdu.getIncomingLength() != (short) 0) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
        if (apdu.setOutgoing() != (short) 0) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        }
    }

    private boolean handlePhotoReadChunk(APDU apdu, byte[] buffer) {
        boolean advanced = false;
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
            short remaining = (short) (length - offset);
            short chunk = ne < remaining ? ne : remaining;
            photos.copyPhoto(offset, buffer, (short) 0, chunk);
            apdu.setOutgoingLength(chunk);
            apdu.sendBytes((short) 0, chunk);
            photos.setReadOffset((short) (offset + chunk));
            advanced = true;
            if (photos.readOffset() == length) {
                photos.clearReadStream();
                pins.clearStudentAuthorization();
                return false;
            }
            return true;
        } catch (ISOException exception) {
            throw exception;
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } catch (RuntimeException exception) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            if (!advanced) {
                photos.clearReadStream();
                pins.clearStudentAuthorization();
            }
        }
        return false;
    }

    private void handleGrantQuota(APDU apdu, byte[] buffer) {
        boolean responseSent = false;
        try {
            requireZeroP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
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
            commitState(Protocol.OPERATION_GRANT_QUOTA, delta, Protocol.UNASSIGNED_VISIT_ID,
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
                clearApduCommandData(buffer);
            }
        }
    }

    private void commitState(byte operation, short delta, byte[] visitId, short visitIdOffset) {
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

    private void handleGetLastReceipt(APDU apdu, byte[] buffer) {
        try {
            requireZeroP1P2(buffer);
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

    private void handleStartVisit(APDU apdu, byte[] buffer) {
        boolean responseSent = false;
        try {
            requireZeroP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
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
            commitState(Protocol.OPERATION_START_VISIT, Protocol.START_VISIT_DELTA, visitId,
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
                clearApduCommandData(buffer);
            }
        }
    }

    private void handleEndVisit(APDU apdu, byte[] buffer) {
        boolean responseSent = false;
        try {
            requireZeroP1P2(buffer);
            state.ensureCommandBuffer();
            short lc = receiveCommandData(apdu, buffer, state.commandData(),
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
            commitState(Protocol.OPERATION_END_VISIT, Protocol.END_VISIT_DELTA, state.commandData(),
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
                clearApduCommandData(buffer);
            }
        }
    }

    private static void writeStateResponse(QuotaLedger ledger, byte[] buffer) {
        Util.setShort(buffer, Protocol.STATE_OFFSET_BALANCE, ledger.balance());
        ledger.writeCounter(buffer, Protocol.STATE_OFFSET_COUNTER);
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
