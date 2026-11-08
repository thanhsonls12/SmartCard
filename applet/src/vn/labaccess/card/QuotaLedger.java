package vn.labaccess.card;

final class QuotaLedger {

    private static final byte COUNTER_MAX_BYTE = (byte) 0xFF;

    private short balance;
    private byte counter0;
    private byte counter1;
    private byte counter2;
    private byte counter3;

    void reset() {
        balance = (short) 0;
        counter0 = (byte) 0;
        counter1 = (byte) 0;
        counter2 = (byte) 0;
        counter3 = (byte) 0;
    }

    short balance() {
        return balance;
    }

    void writeCounter(byte[] target, short offset) {
        short position = offset;
        target[position] = counter0;
        position += 1;
        target[position] = counter1;
        position += 1;
        target[position] = counter2;
        position += 1;
        target[position] = counter3;
    }

    boolean counterExhausted() {
        return counter0 == COUNTER_MAX_BYTE
                && counter1 == COUNTER_MAX_BYTE
                && counter2 == COUNTER_MAX_BYTE
                && counter3 == COUNTER_MAX_BYTE;
    }

    boolean exceedsCapacity(short delta) {
        return delta > (short) (Protocol.MAX_BALANCE - balance);
    }

    void setBalance(short value) {
        balance = value;
    }

    void setCounter(byte[] source, short offset) {
        short position = offset;
        counter0 = source[position];
        position += 1;
        counter1 = source[position];
        position += 1;
        counter2 = source[position];
        position += 1;
        counter3 = source[position];
    }

    static void addOneInPlace(byte[] target, short offset) {
        short index = (short) (offset + Protocol.COUNTER_LENGTH - 1);
        while (index >= offset) {
            byte value = (byte) (target[index] + 1);
            target[index] = value;
            if (value != (byte) 0) {
                return;
            }
            index -= 1;
        }
    }
}
