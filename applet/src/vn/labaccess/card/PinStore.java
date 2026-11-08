package vn.labaccess.card;

import javacard.framework.OwnerPIN;

final class PinStore {

    private final OwnerPIN adminPin;
    private final OwnerPIN studentPin;

    private boolean studentPinAssigned;

    PinStore() {
        adminPin = new OwnerPIN(Protocol.ADMIN_PIN_MAX_TRIES, Protocol.ADMIN_PIN_LENGTH);
        studentPin = new OwnerPIN(Protocol.STUDENT_PIN_MAX_TRIES, Protocol.STUDENT_PIN_LENGTH);
        studentPinAssigned = false;
    }

    void provisionAdminPin(byte[] buffer, short offset) {
        adminPin.update(buffer, offset, Protocol.ADMIN_PIN_LENGTH);
    }

    void assignStudentPin(byte[] buffer, short offset) {
        studentPin.update(buffer, offset, Protocol.STUDENT_PIN_LENGTH);
        studentPinAssigned = true;
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
