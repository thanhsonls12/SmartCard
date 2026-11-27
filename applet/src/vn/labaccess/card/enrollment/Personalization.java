package vn.labaccess.card;

import javacard.framework.APDU;
import javacard.framework.APDUException;
import javacard.framework.ISO7816;
import javacard.framework.JCSystem;
import javacard.framework.ISOException;
import javacard.framework.Util;
import javacard.security.ECPublicKey;

final class Personalization {
    private static final short PERSONALIZE_EXPECTED_NE = (short) 256;

    private final CardState state;
    private final PinStore pins;
    private final PhotoStore photos;
    private ProfileCrypto crypto;
    private EcKeys ecKeys;

    Personalization(CardState state, PinStore pins, PhotoStore photos) {
        this.state = state;
        this.pins = pins;
        this.photos = photos;
    }

    void handlePersonalize(APDU apdu, byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
        try {
            short lc = ApduChecks.receiveCommandData(apdu, buffer, state.commandData(),
                    PersonalizePayload.MAX_LENGTH);
            short ne = apdu.setOutgoing();
            if (ne != PERSONALIZE_EXPECTED_NE) {
                ISOException.throwIt(Sw.WRONG_LENGTH);
            }
            process(state.commandData(), lc);
            ISOException.throwIt(Sw.OK);
        } catch (APDUException exception) {
            ISOException.throwIt(Sw.WRONG_LENGTH);
        } finally {
            ApduChecks.clearApduCommandData(buffer);
            state.clearCommandData();
        }
    }

    private void process(byte[] buffer, short lc) {
        try {
            PersonalizePayload.requireLength(buffer, lc);
            if (state.isIssued()) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
            PersonalizePayload.validateFormat(buffer);
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
            photos.generateKey();
            ecKeys.generatePair();
            state.setReceiptSigner(new ReceiptSigner(ecKeys.privateKey()));
            state.setCardSigner(new CardSigner(ecKeys.privateKey()));
            buildProfilePlaintext(buffer);
            short recordLength = crypto.encryptRecord(
                    state.profilePlaintext(), state.profilePlaintextLength(),
                    state.scratch());

            JCSystem.beginTransaction();
            transactionStarted = true;
            state.setCardId(buffer, PersonalizePayload.OFFSET_CARD_ID);
            state.setProfileCiphertext(state.scratch(), (short) 0, recordLength);
            state.setProfileNonce(state.scratch(), ProfileCrypto.RECORD_HEADER_OFFSET_NONCE);
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
        state.setReceiptSigner(null);
        state.setCardSigner(null);
        if (crypto != null) {
            crypto.clearAesKey();
        }
        photos.clearKey();
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

    ECPublicKey publicKey() {
        return ecKeys.publicKey();
    }

    short readProfilePlaintext(byte[] plaintext) {
        return crypto.decryptStoredRecord(state.profileCiphertext(),
                state.profileCiphertextLength(), plaintext);
    }

    short readProfileMetadata(byte[] target) {
        return crypto.writeMetadata(state.profileCiphertext(),
                state.profileCiphertextLength(), state.profilePlaintext(), target);
    }

    void updateDisplayName(byte[] data, short nameOffset, short nameLength) {
        byte[] plaintext = state.profilePlaintext();
        short storedLength = crypto.decryptStoredRecord(state.profileCiphertext(),
                state.profileCiphertextLength(), plaintext);
        ProfileCodec.requireSchemaV1(plaintext, storedLength);
        short studentIdLength = ProfileCodec.studentIdLength(plaintext);
        short nameLengthOffset = Protocol.profileDisplayNameLengthOffset(studentIdLength);
        plaintext[nameLengthOffset] = (byte) nameLength;
        Util.arrayCopyNonAtomic(data, nameOffset, plaintext, (short) (nameLengthOffset + 1),
                nameLength);
        short plaintextLength = (short) (nameLengthOffset + 1 + nameLength);
        state.setProfilePlaintextLength(plaintextLength);

        reserveNextNonce();
        short recordLength = crypto.encryptRecordWithNonce(plaintext, plaintextLength,
                state.scratch(), state.profileNonce());
        boolean transactionStarted = false;
        try {
            JCSystem.beginTransaction();
            transactionStarted = true;
            state.setProfileCiphertextAtomic(state.scratch(), (short) 0, recordLength);
            JCSystem.commitTransaction();
            transactionStarted = false;
        } catch (RuntimeException exception) {
            if (transactionStarted) {
                JCSystem.abortTransaction();
            }
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }

    private void reserveNextNonce() {
        boolean transactionStarted = false;
        try {
            JCSystem.beginTransaction();
            transactionStarted = true;
            state.advanceProfileNonce();
            JCSystem.commitTransaction();
            transactionStarted = false;
        } catch (RuntimeException exception) {
            if (transactionStarted) {
                JCSystem.abortTransaction();
            }
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }

    void updateIdentity(byte[] data, short expectedVersionOffset, short nameLength) {
        requireCurrentVersion(data, expectedVersionOffset);
        byte[] plaintext = state.profilePlaintext();
        short storedLength = crypto.decryptStoredRecord(state.profileCiphertext(),
                state.profileCiphertextLength(), plaintext);
        ProfileCodec.requireSchemaV1(plaintext, storedLength);
        short studentIdLength = UpdateIdentityPayload.studentIdLength(data);
        short offset = (short) 0;
        plaintext[offset++] = ProfileCrypto.PROFILE_SCHEMA_VERSION;
        plaintext[offset++] = (byte) studentIdLength;
        offset = Util.arrayCopyNonAtomic(data, Protocol.UPDATE_IDENTITY_OFFSET_STUDENT_ID,
                plaintext, offset, studentIdLength);
        plaintext[offset++] = (byte) nameLength;
        offset = Util.arrayCopyNonAtomic(data, UpdateIdentityPayload.nameOffset(data),
                plaintext, offset, nameLength);
        state.setProfilePlaintextLength(offset);

        reserveNextNonce();
        short recordLength = crypto.encryptRecordWithNonce(plaintext, offset,
                state.scratch(), state.profileNonce());
        boolean transactionStarted = false;
        try {
            JCSystem.beginTransaction();
            transactionStarted = true;
            state.setProfileCiphertextAtomic(state.scratch(), (short) 0, recordLength);
            JCSystem.commitTransaction();
            transactionStarted = false;
        } catch (RuntimeException exception) {
            if (transactionStarted) {
                JCSystem.abortTransaction();
            }
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
    }

    short signProfileChallenge(byte[] data, short nonceOffset) {
        byte[] plaintext = state.profilePlaintext();
        short plaintextLength = crypto.decryptStoredRecord(state.profileCiphertext(),
                state.profileCiphertextLength(), plaintext);
        ProfileCodec.requireSchemaV1(plaintext, plaintextLength);
        CardSigner signer = state.cardSigner();
        if (signer == null) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        short length = signer.signProfile(data, nonceOffset, state.cardId(), publicKey(),
                plaintext, plaintextLength);
        if (length <= (short) 0 || length > Protocol.ENROLLMENT_SIGNATURE_MAX_LENGTH) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        return length;
    }

    byte[] signature() {
        return state.cardSigner().signature();
    }

    private void requireCurrentVersion(byte[] data, short expectedVersionOffset) {
        byte[] record = state.profileCiphertext();
        if (state.profileCiphertextLength() < ProfileCrypto.RECORD_MIN_LENGTH) {
            ISOException.throwIt(Sw.INTERNAL_ERROR);
        }
        for (short index = (short) 0; index < ProfileCrypto.GCM_NONCE_LENGTH; index++) {
            if (record[(short) (ProfileCrypto.RECORD_HEADER_OFFSET_NONCE + index)]
                    != data[(short) (expectedVersionOffset + index)]) {
                ISOException.throwIt(Sw.CONDITIONS_NOT_SATISFIED);
            }
        }
    }
}
