package vn.labaccess.card;

import javacard.framework.AID;
import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;

public final class LabAccessApplet extends Applet {

    private final PinStore pins;

    private LabAccessApplet(PinStore pins) {
        this.pins = pins;
        register();
    }

    public static void install(byte[] installParameters, short offset, byte length) {
        short pinOffset = Provisioning.adminPinOffset(installParameters, offset, length);
        PinStore store = new PinStore();
        store.provisionAdminPin(installParameters, pinOffset);
        Util.arrayFillNonAtomic(installParameters, pinOffset,
                Protocol.ADMIN_PIN_LENGTH, (byte) 0x00);
        new LabAccessApplet(store);
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }

        byte[] buffer = apdu.getBuffer();
        requireApplicationCla(buffer);
        requireGetStatusIns(buffer);
        requireGetStatusP1P2(buffer);
        sendStatus(apdu, buffer);
    }

    private void requireApplicationCla(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_CLA] != Protocol.CLA_APPLICATION) {
            ISOException.throwIt(Sw.CLA_NOT_SUPPORTED);
        }
    }

    private void requireGetStatusIns(byte[] buffer) {
        if (buffer[ISO7816.OFFSET_INS] != Protocol.INS_GET_STATUS) {
            ISOException.throwIt(Sw.INS_NOT_SUPPORTED);
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
        buffer[lifecycleOffset] = Protocol.LIFECYCLE_UNPERSONALIZED;
        Util.arrayFillNonAtomic(buffer, Protocol.cardIdOffset(aidLength),
                Protocol.CARD_ID_LENGTH, Protocol.CARD_ID_UNASSIGNED);

        apdu.setOutgoingLength(responseLength);
        apdu.sendBytes((short) 0, responseLength);
    }
}
