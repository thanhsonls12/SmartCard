package vn.labaccess.desktop;

import java.util.Arrays;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import vn.labaccess.desktop.transport.CardTransport;

public final class ApduTransportCases {
    private ApduTransportCases() {
    }

    public static int run(CardChannel channel, byte[] admin, byte[] student, byte[] payload,
            String mode) throws Exception {
        CardTransport transport = new CardTransport(channel);
        if (mode.equals("short-input")) {
            byte[] before = expect(transport, new CommandAPDU(0x80, 0x10, 0, 0, 256), 0x9000);
            ResponseAPDU response = transport.transmit(new CommandAPDU(0x80, 0x30, 0, 0, payload, 256));
            System.out.printf("G7-SHORT-INPUT observedSW=%04X responseLength=%d%n", response.getSW(), response.getNr());
            require(response.getSW() == 0x6700, "short input was not rejected with 6700");
            byte[] trace = expect(transport, new CommandAPDU(0x80, 0x7E, 0, 0, 256), 0x9000);
            System.out.println("G7-SHORT-INPUT receiveEntered=" + trace[64] + " receiveReturns=" + trace[0]);
            require(Arrays.equals(before, expect(transport, new CommandAPDU(0x80, 0x10, 0, 0, 256), 0x9000)),
                    "short input changed state");
            expect(transport, new CommandAPDU(0x80, 0x20, 0, 0, student), 0x6985);
            expect(transport, new CommandAPDU(0x80, 0x61, 0, 0, 65), 0x6985);
            System.out.println("G7-SHORT-INPUT | PASS | unissued-state-unchanged");
            System.out.println("APDU_ERRORS cases=1 failed=0 skipped=0");
            return 0;
        }
        byte[] profilePayload = payload;
        if (mode.equals("receive")) {
            System.out.println("G7 simulator APDU buffer holds the whole Lc, so one receive call is expected");
            profilePayload = new byte[131];
            System.arraycopy(payload, 0, profilePayload, 0, 17);
            profilePayload[17] = 10;
            byte[] id = "SV20240001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(id, 0, profilePayload, 18, id.length);
            System.arraycopy(student, 0, profilePayload, 28, student.length);
            profilePayload[34] = 96;
            Arrays.fill(profilePayload, 35, 131, (byte) 'A');
        }
        try {
            expect(transport, new CommandAPDU(0x80, 0x30, 0, 0, profilePayload, 256), 0x9000);
            if (mode.equals("receive")) {
                byte[] trace = expect(transport, new CommandAPDU(0x80, 0x7E, 0, 0, 256), 0x9000);
                int calls = trace[0] & 255;
                int total = 0;
                for (int index = 0; index < calls; index++) {
                    int received = ((trace[1 + index * 2] & 255) << 8) | (trace[2 + index * 2] & 255);
                    total += received;
                    System.out.println("G7-RECEIVE call=" + (index + 1) + " received=" + received);
                }
                require(total == 131, "received byte total mismatch");
                require(trace[64] == 1, "receive entry not observed");
                expect(transport, new CommandAPDU(0x80, 0x20, 0, 0, student), 0x9000);
                byte[] profile = expect(transport, new CommandAPDU(0x80, 0x31, 0, 0, 256), 0x9000);
                require(profile.length == 109 && profile[1] == 10 && profile[12] == 96,
                        "fragmented PERSONALIZE profile framing");
                for (int index = 13; index < profile.length; index++) require(profile[index] == 'A', "profile bytes");
                System.out.println("G7-RECEIVE-TOTAL | PASS | calls=" + calls + " total=" + total);
                System.out.println("APDU_ERRORS cases=1 failed=0 skipped=0");
                return 0;
            }
            byte[] grant = Arrays.copyOf(admin, 10);
            grant[9] = 5;
            expect(transport, new CommandAPDU(0x80, 0x50, 0, 0, grant, 6), 0x9000);
            Arrays.fill(grant, (byte) 0);
            expect(transport, new CommandAPDU(0x80, 0x20, 0, 0, student), 0x9000);
            byte[] before = expect(transport, new CommandAPDU(0x80, 0x10, 0, 0, 256), 0x9000);
            byte[] visit = new byte[16];
            Arrays.fill(visit, (byte) 0x39);
            expect(transport, new CommandAPDU(0x80, 0x20, 0, 0, student), 0x9000);
            ResponseAPDU startResponse = transport.transmit(new CommandAPDU(0x80, 0x51, 0, 0, visit, 6));
            System.out.printf("G7-SW6C callerSW=%04X%n", startResponse.getSW());
            expect(transport, new CommandAPDU(0x80, 0x20, 0, 0, student), 0x9000);
            byte[] after = expect(transport, new CommandAPDU(0x80, 0x10, 0, 0, 256), 0x9000);
            require(before[28] == 5 && after[28] == 4 && before[32] == 1
                    && after[32] == 2 && after[33] == 1, "START state did not commit exactly once");
            expect(transport, new CommandAPDU(0x80, 0x20, 0, 0, student), 0x9000);
            byte[] receipt = expect(transport, new CommandAPDU(0x80, 0x60, 0, 0, 256), 0x9000);
            require(receipt[12] == 2 && receipt[13] == 2, "START receipt counter/operation");
            boolean preserved = startResponse.getSW() == 0x6C06;
            System.out.println("G7-SW6C-TRANSPORT | " + (preserved ? "PASS" : "FAIL")
                    + " | counterBefore=1 counterAfter=2 balanceBefore=5 balanceAfter=4");
            System.out.println("APDU_ERRORS cases=1 failed=" + (preserved ? 0 : 1) + " skipped=0");
            return preserved ? 0 : 1;
        } finally {
            if (profilePayload != payload) Arrays.fill(profilePayload, (byte) 0);
        }
    }

    private static byte[] expect(CardTransport transport, CommandAPDU command, int sw) throws Exception {
        ResponseAPDU response = transport.transmit(command);
        System.out.printf("G7 APDU ins=%02X Lc=%d Le=%d expected=%04X actual=%04X responseLength=%d payload=<redacted>%n",
                command.getINS(), command.getNc(), command.getNe(), sw, response.getSW(), response.getNr());
        require(response.getSW() == sw, "unexpected transport status");
        return response.getData();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
