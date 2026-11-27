package vn.labaccess.card;

import javacard.framework.JCSystem;
import javacard.framework.OwnerPIN;

final class PinStore {

    private final OwnerPIN adminPin;
    private final OwnerPIN studentPin;
    private final byte[] studentAuthorization;

    private boolean studentPinAssigned;

    PinStore() {
        adminPin = new OwnerPIN(Protocol.ADMIN_PIN_MAX_TRIES, Protocol.ADMIN_PIN_LENGTH);
        studentPin = new OwnerPIN(Protocol.STUDENT_PIN_MAX_TRIES, Protocol.STUDENT_PIN_LENGTH);
        studentAuthorization = JCSystem.makeTransientByteArray((short) 1,
                JCSystem.CLEAR_ON_DESELECT);
        studentPinAssigned = false;
    }

    void provisionAdminPin(byte[] buffer, short offset) {
        adminPin.update(buffer, offset, Protocol.ADMIN_PIN_LENGTH);
    }

    void assignStudentPin(byte[] buffer, short offset) {
        studentPin.update(buffer, offset, Protocol.STUDENT_PIN_LENGTH);
        studentPinAssigned = true;
    }

    void updateStudentPin(byte[] buffer, short offset) {
        studentPin.update(buffer, offset, Protocol.STUDENT_PIN_LENGTH);
    }

    void unblockStudentPin() {
        studentPin.resetAndUnblock();
    }

    void authorizeStudent() {
        studentAuthorization[0] = (byte) 1;
    }

    boolean isStudentAuthorized() {
        return studentAuthorization[0] != (byte) 0;
    }

    void clearStudentAuthorization() {
        studentAuthorization[0] = (byte) 0;
    }

    void resetValidatedPins() {
        adminPin.reset();
        studentPin.reset();
    }

    OwnerPIN admin() {
        return adminPin;
    }

    OwnerPIN student() {
        return studentPin;
    }

    boolean isStudentPinAssigned() {
        return studentPinAssigned;
    }
}
