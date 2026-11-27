package vn.labaccess.card;

import javacard.framework.ISOException;

final class Utf8Format {

    private static final short UNSIGNED_BYTE_MASK = (short) 0x00FF;
    private static final short CONTINUATION_MIN = (short) 0x80;
    private static final short CONTINUATION_MAX = (short) 0xBF;
    private static final short ASCII_MIN = (short) 0x20;
    private static final short ASCII_MAX = (short) 0x7E;
    private static final short TWO_BYTE_MIN = (short) 0xC2;
    private static final short TWO_BYTE_MAX = (short) 0xDF;
    private static final short THREE_BYTE_MIN = (short) 0xE0;
    private static final short THREE_BYTE_MAX = (short) 0xEF;
    private static final short THREE_BYTE_SURROGATE_LEAD = (short) 0xED;
    private static final short FOUR_BYTE_MIN = (short) 0xF0;
    private static final short FOUR_BYTE_MAX = (short) 0xF4;
    private static final short THREE_BYTE_SECOND_MIN = (short) 0xA0;
    private static final short SURROGATE_SECOND_MAX = (short) 0x9F;
    private static final short FOUR_BYTE_SECOND_MIN = (short) 0x90;
    private static final short MAX_CODE_POINT_SECOND_MAX = (short) 0x8F;
    private static final short TWO_BYTE_LENGTH = (short) 2;
    private static final short THREE_BYTE_LENGTH = (short) 3;
    private static final short FOUR_BYTE_LENGTH = (short) 4;

    private Utf8Format() {
    }

    static void requireValid(byte[] buffer, short offset, short length) {
        short end = (short) (offset + length);
        short index = offset;
        while (index < end) {
            short lead = (short) (buffer[index] & UNSIGNED_BYTE_MASK);
            if (lead >= ASCII_MIN && lead <= ASCII_MAX) {
                index++;
                continue;
            }

            short minimum = CONTINUATION_MIN;
            short maximum = CONTINUATION_MAX;
            short sequenceLength;
            if (lead >= TWO_BYTE_MIN && lead <= TWO_BYTE_MAX) {
                sequenceLength = TWO_BYTE_LENGTH;
            } else if (lead >= THREE_BYTE_MIN && lead <= THREE_BYTE_MAX) {
                sequenceLength = THREE_BYTE_LENGTH;
                if (lead == THREE_BYTE_MIN) {
                    minimum = THREE_BYTE_SECOND_MIN;
                } else if (lead == THREE_BYTE_SURROGATE_LEAD) {
                    maximum = SURROGATE_SECOND_MAX;
                }
            } else if (lead >= FOUR_BYTE_MIN && lead <= FOUR_BYTE_MAX) {
                sequenceLength = FOUR_BYTE_LENGTH;
                if (lead == FOUR_BYTE_MIN) {
                    minimum = FOUR_BYTE_SECOND_MIN;
                } else if (lead == FOUR_BYTE_MAX) {
                    maximum = MAX_CODE_POINT_SECOND_MAX;
                }
            } else {
                ISOException.throwIt(Sw.WRONG_DATA);
                return;
            }

            requireContinuation(buffer, (short) (index + 1), end, minimum, maximum);
            for (short step = (short) 2; step < sequenceLength; step++) {
                requireContinuation(buffer, (short) (index + step), end, CONTINUATION_MIN, CONTINUATION_MAX);
            }
            index += sequenceLength;
        }
    }

    private static void requireContinuation(byte[] buffer, short position, short end,
            short minimum, short maximum) {
        if (position >= end) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
        short value = (short) (buffer[position] & UNSIGNED_BYTE_MASK);
        if (value < minimum || value > maximum) {
            ISOException.throwIt(Sw.WRONG_DATA);
        }
    }
}
