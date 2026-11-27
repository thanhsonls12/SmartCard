package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;

public final class LabAccessApplet extends Applet {


    private final PinStore pins;
    private final CardState state;
    private final Personalization personalization;
    private final PhotoStore photos;
    private final ProfileHandler profiles;
    private final AdminReadHandler adminReads;

    private LabAccessApplet(byte[] installParameters, short aidOffset, byte aidLength,
            PinStore pins, CardState state, Personalization personalization, PhotoStore photos,
            ProfileHandler profiles, AdminReadHandler adminReads) {
        this.pins = pins;
        this.state = state;
        this.personalization = personalization;
        this.photos = photos;
        this.profiles = profiles;
        this.adminReads = adminReads;
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
        PhotoStore photoStore = new PhotoStore();
        Personalization personalization = new Personalization(cardState, store, photoStore);
        ProfileHandler profileHandler = new ProfileHandler(cardState, personalization, store);
        AdminReadHandler adminReadHandler = new AdminReadHandler(cardState, store);
        new LabAccessApplet(installParameters, (short) (offset + 1),
                installParameters[offset], store, cardState, personalization, photoStore,
                profileHandler, adminReadHandler);
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
        boolean keepStudentAuthorization = false;
        boolean keepUpload = false;
        boolean responseSent = false;
        if (ins != Protocol.INS_PHOTO_READ_CHUNK) {
            if (photos.readOffset() != (short) 0) {
                pins.clearStudentAuthorization();
            }
            photos.clearReadStream();
        }
        try {
            requireApplicationCla(buffer);
            if (ins == Protocol.INS_GET_STATUS) {
                StatusHandler.handleGetStatus(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_GET_ADMIN_STATUS) {
                responseSent = adminReads.handleGetAdminStatus(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_GET_ADMIN_LAST_RECEIPT) {
                responseSent = adminReads.handleGetAdminLastReceipt(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_VERIFY_STUDENT_PIN) {
                StudentPinHandler.handleVerifyStudentPin(apdu, buffer, state, pins);
                pins.authorizeStudent();
                keepStudentAuthorization = true;
                ISOException.throwIt(Sw.OK);
            }
            if (ins == Protocol.INS_CHANGE_STUDENT_PIN) {
                StudentPinHandler.handleChangeStudentPin(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_UNBLOCK_STUDENT_PIN) {
                StudentPinHandler.handleUnblockStudentPin(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_PERSONALIZE) {
                personalization.handlePersonalize(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_GET_PROFILE) {
                profiles.handleGetProfile(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_GET_PROFILE_CRYPTO_META) {
                profiles.handleGetProfileCryptoMeta(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_UPDATE_PROFILE) {
                profiles.handleUpdateProfile(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_UPDATE_IDENTITY) {
                profiles.handleUpdateIdentity(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_SIGN_PROFILE_CHALLENGE) {
                profiles.handleSignProfileChallenge(apdu, buffer);
                return;
            }
            if (ins == Protocol.INS_PHOTO_BEGIN) {
                PhotoHandler.handlePhotoBegin(apdu, buffer, state, pins, photos);
                keepUpload = true;
                return;
            }
            if (ins == Protocol.INS_PHOTO_REPLACE_BEGIN) {
                PhotoHandler.handlePhotoReplaceBegin(apdu, buffer, state, pins, photos);
                keepUpload = true;
                return;
            }
            if (ins == Protocol.INS_PHOTO_WRITE_CHUNK) {
                PhotoHandler.handlePhotoWriteChunk(apdu, buffer, state, photos);
                keepUpload = true;
                return;
            }
            if (ins == Protocol.INS_PHOTO_FINISH) {
                PhotoHandler.handlePhotoFinish(apdu, buffer, photos);
                return;
            }
            if (ins == Protocol.INS_PHOTO_READ_CHUNK) {
                if (PhotoHandler.handlePhotoReadChunk(apdu, buffer, pins, photos)) {
                    keepStudentAuthorization = true;
                }
                return;
            }
            if (ins == Protocol.INS_GRANT_QUOTA) {
                StateTransactionHandler.handleGrantQuota(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_START_VISIT) {
                StateTransactionHandler.handleStartVisit(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_END_VISIT) {
                StateTransactionHandler.handleEndVisit(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_GET_LAST_RECEIPT) {
                StateTransactionHandler.handleGetLastReceipt(apdu, buffer, state, pins);
                return;
            }
            if (ins == Protocol.INS_GET_CARD_PUBLIC_KEY) {
                EnrollmentHandler.handleGetCardPublicKey(apdu, buffer, state, personalization);
                return;
            }
            if (ins == Protocol.INS_SIGN_REGISTRATION_CHALLENGE) {
                EnrollmentHandler.handleSignRegistrationChallenge(apdu, buffer, state, personalization);
                keepStudentAuthorization = true;
                return;
            }
            ISOException.throwIt(Sw.INS_NOT_SUPPORTED);
        } finally {
            pins.resetValidatedPins();
            if (!keepStudentAuthorization) {
                pins.clearStudentAuthorization();
                photos.clearReadStream();
            }
            if (!keepUpload) {
                photos.closeUpload();
            }
            if (receivesCommandData(ins) && !responseSent) {
                ApduChecks.clearApduCommandData(buffer);
            }
        }
    }

    private static boolean receivesCommandData(byte ins) {
        return ins == Protocol.INS_PERSONALIZE
                || ins == Protocol.INS_VERIFY_STUDENT_PIN
                || ins == Protocol.INS_CHANGE_STUDENT_PIN
                || ins == Protocol.INS_UNBLOCK_STUDENT_PIN
                || ins == Protocol.INS_PHOTO_BEGIN
                || ins == Protocol.INS_PHOTO_REPLACE_BEGIN
                || ins == Protocol.INS_PHOTO_WRITE_CHUNK
                || ins == Protocol.INS_GET_ADMIN_STATUS
                || ins == Protocol.INS_GET_ADMIN_LAST_RECEIPT
                || ins == Protocol.INS_SIGN_REGISTRATION_CHALLENGE;
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
}
