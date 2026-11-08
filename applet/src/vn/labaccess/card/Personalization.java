package vn.labaccess.card;

import javacard.framework.JCSystem;
import javacard.framework.ISOException;
import javacard.framework.Util;

final class Personalization {

    private final CardState state;
    private final PinStore pins;
    private ProfileCrypto crypto;
    private EcKeys ecKeys;

    Personalization(CardState state, PinStore pins) {
        this.state = state;
        this.pins = pins;
    }

    void process(byte[] buffer, short lc) {
        try {
            if (state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            PersonalizePayload.validate(buffer, lc);
            AdminPinVerifier.verify(pins.admin(), buffer, PersonalizePayload.OFFSET_ADMIN_PIN);
            personalize(buffer);
        } finally {
            pins.admin().reset();
            clearCommandSecrets(buffer, lc);
            state.clearCommandData();
        }
    }

    private void personalize(byte[] buffer) {
        state.resetUnissued();
        boolean transactionStarted = false;
        try {
            state.ensureWorkingBuffers();
            if (crypto == null) {
                crypto = new ProfileCrypto();
            }
            if (ecKeys == null) {
                ecKeys = new EcKeys();
            }
            crypto.generateAesKey();
            ecKeys.generatePair();
            buildProfilePlaintext(buffer);
            short recordLength = crypto.encryptRecord(
                    state.profilePlaintext(), state.profilePlaintextLength(),
                    state.scratch());

            JCSystem.beginTransaction();
            transactionStarted = true;
            state.setCardId(buffer, PersonalizePayload.OFFSET_CARD_ID);
            state.setProfileCiphertext(state.scratch(), (short) 0, recordLength);
            pins.assignStudentPin(buffer, PersonalizePayload.studentPinOffset(buffer));
            state.setKeysReady();
            state.markIssued();
            JCSystem.commitTransaction();
            transactionStarted = false;
        } catch (ISOException exception) {
            rollback(transactionStarted);
            throw exception;
        } catch (RuntimeException exception) {
            rollback(transactionStarted);
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        } finally {
            state.clearWorkingBuffers();
        }
    }

    private void rollback(boolean transactionStarted) {
        if (transactionStarted) {
            JCSystem.abortTransaction();
        }
        state.resetUnissued();
        if (crypto != null) {
            crypto.clearAesKey();
        }
        if (ecKeys != null) {
            ecKeys.clearKeys();
        }
    }

    private void buildProfilePlaintext(byte[] buffer) {
        byte[] plaintext = state.profilePlaintext();
        short studentIdLength = PersonalizePayload.studentIdLength(buffer);
        short displayNameLength = PersonalizePayload.displayNameLength(buffer);
        short offset = (short) 0;
        plaintext[offset++] = ProfileCrypto.PROFILE_SCHEMA_VERSION;
        plaintext[offset++] = (byte) studentIdLength;
        offset = Util.arrayCopyNonAtomic(buffer, PersonalizePayload.OFFSET_STUDENT_ID,
                plaintext, offset, studentIdLength);
        plaintext[offset++] = (byte) displayNameLength;
        offset = Util.arrayCopyNonAtomic(buffer, PersonalizePayload.displayNameOffset(buffer),
                plaintext, offset, displayNameLength);
        state.setProfilePlaintextLength(offset);
    }

    private static void clearCommandSecrets(byte[] buffer, short lc) {
        Util.arrayFillNonAtomic(buffer, (short) 0, lc, (byte) 0);
    }

    void readPublicKey(byte[] target, short offset) {
        ecKeys.publicKey().getW(target, offset);
    }
}
