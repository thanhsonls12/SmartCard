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
            return;
        }

        byte[] buffer = apdu.getBuffer();
        requireApplicationCla(buffer);

        byte ins = buffer[ISO7816.OFFSET_INS];
        if (ins == Protocol.INS_GET_STATUS) {
            requireGetStatusP1P2(buffer);
            sendStatus(apdu, buffer);
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

    private short receiveCommandData(APDU apdu, byte[] buffer, byte[] target) {
        short received = apdu.setIncomingAndReceive();
        short lc = apdu.getIncomingLength();
        if (lc > MAX_COMMAND_DATA) {
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

    private void handlePersonalize(APDU apdu, byte[] buffer) {
        if (buffer[ISO7816.OFFSET_P1] != Protocol.P1P2_ZERO
                || buffer[ISO7816.OFFSET_P2] != Protocol.P1P2_ZERO) {
            ISOException.throwIt(Sw.WRONG_P1P2);
        }
        state.ensureCommandBuffer();
        try {
            short lc = receiveCommandData(apdu, buffer, state.commandData());
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
