package vn.labaccess.desktop;

import java.util.Arrays;

import javax.smartcardio.ResponseAPDU;

final class CardStatusCodec {

    record Parsed(CardStatus status, String problem) {
        boolean ok() {
            return status != null;
        }
    }

    private static final int PROTOCOL_VERSION = 0x01;
    private static final int OFFSET_PROTOCOL_VERSION = 9;
    private static final int OFFSET_AID_LENGTH = 10;
    private static final int OFFSET_INSTANCE_AID = 11;
    private static final int CARD_ID_LENGTH = 8;
    private static final int BALANCE_LENGTH = 2;
    private static final int COUNTER_LENGTH = 4;
    private static final int ACTIVE_FLAG_LENGTH = 1;
    private static final int VISIT_ID_LENGTH = 16;
    private static final int AUTH_STATE_LENGTH = BALANCE_LENGTH + COUNTER_LENGTH
            + ACTIVE_FLAG_LENGTH + VISIT_ID_LENGTH;
    private static final byte[] MAGIC = {
            'L', 'A', 'B', 'A', 'C', 'C', 'E', 'S', 'S'
    };

    private CardStatusCodec() {
    }

    static Parsed parse(ResponseAPDU response, byte[] requestedAid, CardStatus.StateSource readSource) {
        byte[] data = response.getData();
        int sw = response.getSW();
        if (sw != Sw.OK) {
            return fail(String.format("GET_STATUS SW=%04X | kết luận: CHƯA SẴN SÀNG", sw));
        }
        if (data.length < OFFSET_INSTANCE_AID) {
            return fail(String.format("GET_STATUS SW=%04X nhưng chỉ có %d byte"
                    + " | kết luận: CHƯA SẴN SÀNG", sw, data.length));
        }
        if (!matchesMagic(data)) {
            return fail("GET_STATUS sai magic | kết luận: CHƯA SẴN SÀNG");
        }
        int version = data[OFFSET_PROTOCOL_VERSION] & 0xFF;
        if (version != PROTOCOL_VERSION) {
            return fail(String.format("GET_STATUS protocol=%02X không hỗ trợ"
                    + " | kết luận: CHƯA SẴN SÀNG", version));
        }

        int aidLength = data[OFFSET_AID_LENGTH] & 0xFF;
        int publicLength = OFFSET_INSTANCE_AID + aidLength + 1 + CARD_ID_LENGTH;
        boolean stateIncluded = data.length == publicLength + AUTH_STATE_LENGTH;
        if (data.length != publicLength && !stateIncluded) {
            return fail(String.format("GET_STATUS %d byte, theo aidLen=%d phải là %d public hoặc %d authenticated"
                    + " | kết luận: CHƯA SẴN SÀNG", data.length, aidLength,
                    publicLength, publicLength + AUTH_STATE_LENGTH));
        }

        byte[] returnedAid = Arrays.copyOfRange(data, OFFSET_INSTANCE_AID,
                OFFSET_INSTANCE_AID + aidLength);
        if (!Arrays.equals(returnedAid, requestedAid)) {
            return fail("GET_STATUS trả AID khác AID đã yêu cầu | kết luận: CHƯA SẴN SÀNG");
        }

        int lifecycle = data[OFFSET_INSTANCE_AID + aidLength] & 0xFF;
        byte[] cardId = Arrays.copyOfRange(data, OFFSET_INSTANCE_AID + aidLength + 1,
                publicLength);
        String lifecycleProblem = checkLifecycle(lifecycle, cardId);
        if (lifecycleProblem != null) {
            return fail(lifecycleProblem);
        }

        int balance = -1;
        long counter = -1;
        boolean activeVisit = false;
        String activeVisitId = "";
        if (stateIncluded) {
            if (lifecycle != 0x01) {
                return fail("GET_STATUS state nhạy cảm nhưng lifecycle không phải ISSUED");
            }
            int offset = publicLength;
            balance = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            if (balance > Receipt.MAX_BALANCE) {
                return fail("GET_STATUS balance ngoài miền hợp lệ");
            }
            counter = readUnsignedInt(data, offset + BALANCE_LENGTH);
            int activeFlag = data[offset + BALANCE_LENGTH + COUNTER_LENGTH] & 0xFF;
            byte[] visitId = Arrays.copyOfRange(data, offset + BALANCE_LENGTH
                    + COUNTER_LENGTH + ACTIVE_FLAG_LENGTH, offset + AUTH_STATE_LENGTH);
            boolean visitIdAssigned = !isAllZero(visitId);
            if ((activeFlag != 0 && activeFlag != 1) || (activeFlag == 1) != visitIdAssigned) {
                return fail("GET_STATUS activeVisit flag/id không nhất quán");
            }
            activeVisit = activeFlag == 1;
            activeVisitId = CardGateway.hex(visitId);
        }
        if (!stateIncluded && readSource != CardStatus.StateSource.PUBLIC) {
            return fail("GET_STATUS không trả đủ state xác thực");
        }
        CardStatus status = new CardStatus(version, lifecycle, CardGateway.hex(returnedAid),
                CardGateway.hex(cardId), readSource, balance, counter, activeVisit, activeVisitId);
        return new Parsed(readSource == CardStatus.StateSource.PUBLIC ? status.publicView() : status, null);
    }

    private static String checkLifecycle(int lifecycle, byte[] cardId) {
        if (lifecycle != 0x00 && lifecycle != 0x01) {
            return String.format("GET_STATUS lifecycle=%02X chưa được giao thức định nghĩa"
                    + " | kết luận: CHƯA SẴN SÀNG", lifecycle);
        }
        boolean assigned = !isAllZero(cardId);
        if (lifecycle == 0x00 && assigned) {
            return "lifecycle=00 UNPERSONALIZED nhưng cardId đã gán | kết luận: CHƯA SẴN SÀNG";
        }
        if (lifecycle == 0x01 && !assigned) {
            return "lifecycle=01 ISSUED nhưng cardId toàn 00 | kết luận: CHƯA SẴN SÀNG";
        }
        return null;
    }

    private static boolean matchesMagic(byte[] data) {
        for (int index = 0; index < MAGIC.length; index++) {
            if (data[index] != MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        long value = 0;
        for (int index = 0; index < COUNTER_LENGTH; index++) {
            value = (value << 8) | (data[offset + index] & 0xFFL);
        }
        return value;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private static Parsed fail(String problem) {
        return new Parsed(null, problem);
    }
}
