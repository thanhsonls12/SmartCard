package vn.labaccess.desktop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

final class RecoveryCardFixture extends CardChannel implements CardGateway.ReceiptSink {
    static final String CARD_ID = "A1B2C3D411223344";
    static final byte[] AID = HexFormat.of().parseHex("F04C4142010101");
    final KeyPair keys;
    String cardId = CARD_ID;
    int counter = 1;
    int balance = 5;
    int stateCommands;
    int pinCommands;
    int receiptMode;
    int pinSw = Sw.OK;
    int statusSw = Sw.OK;
    boolean failTransport;
    boolean hold;
    boolean failRefreshAfterSave;
    boolean ledgerAvailable = true;
    int ledgerOffset;
    String ledgerVisit;
    final CountDownLatch release = new CountDownLatch(1);

    RecoveryCardFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
    }

    CardGateway gateway() throws Exception {
        CardGateway gateway = new CardGateway(this);
        if (!gateway.identify().ok()) throw new AssertionError("fixture identify");
        gateway.setReceiptSink(this);
        gateway.applyCardEnrollment(new CardGateway.EnrollmentCheck(true, true, "fixture"));
        return gateway;
    }

    static char[] pin(int length) {
        char[] value = new char[length];
        var random = new java.security.SecureRandom();
        for (int i = 0; i < length; i++) value[i] = (char) ('0' + random.nextInt(10));
        return value;
    }

    @Override
    public ResponseAPDU transmit(CommandAPDU command) throws CardException {
        if (hold) {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CardException("fixture interrupted");
            }
        }
        if (failTransport) throw new CardException("fixture transport failure");
        return switch (command.getINS()) {
            case 0xA4 -> response(new byte[0], Sw.OK);
            case 0x10 -> response(statusBytes(), statusSw);
            case 0x20 -> {
                pinCommands++;
                yield response(new byte[0], pinSw);
            }
            case 0x61 -> response(ReceiptVerifier.encodePoint(keys.getPublic()), Sw.OK);
            case 0x60 -> response(receiptBytes(), receiptMode == 4 ? Sw.CONDITIONS_NOT_SATISFIED : Sw.OK);
            case 0x31 -> response(new byte[] {1, 1, 'A', 1, 'B'}, Sw.OK);
            case 0x34 -> response(new byte[0], Sw.CONDITIONS_NOT_SATISFIED);
            case 0x50 -> {
                stateCommands++;
                counter++;
                balance += 5;
                yield response(ByteBuffer.allocate(6).putShort((short) balance).putInt(counter).array(), Sw.OK);
            }
            default -> throw new AssertionError("unexpected command " + command.getINS());
        };
    }

    private byte[] statusBytes() {
        return ByteBuffer.allocate(50).put("LABACCESS".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1).put((byte) AID.length).put(AID).put((byte) 1)
                .put(HexFormat.of().parseHex(cardId)).putShort((short) balance).putInt(counter)
                .put((byte) 0).put(new byte[16]).array();
    }

    byte[] receiptBytes() {
        if (receiptMode == 1) return new byte[] {1};
        try {
            byte[] card = HexFormat.of().parseHex(receiptMode == 2 ? "0011223344556677" : cardId);
            byte[] payload = ByteBuffer.allocate(34).put((byte) 1).put(card).putInt(counter)
                    .put((byte) Receipt.OPERATION_GRANT).putShort((short) 5).putShort((short) balance)
                    .put(new byte[16]).array();
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keys.getPrivate());
            signer.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
            signer.update(payload);
            byte[] signature = signer.sign();
            if (receiptMode == 3) signature[signature.length - 1] ^= 1;
            return ByteBuffer.allocate(35 + signature.length).put(payload)
                    .put((byte) signature.length).put(signature).array();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static ResponseAPDU response(byte[] data, int sw) {
        byte[] bytes = Arrays.copyOf(data, data.length + 2);
        bytes[data.length] = (byte) (sw >> 8);
        bytes[data.length + 1] = (byte) sw;
        return new ResponseAPDU(bytes);
    }

    @Override
    public CardGateway.ReceiptSave save(Receipt receipt) {
        if (failRefreshAfterSave) statusSw = Sw.INTERNAL_ERROR;
        return new CardGateway.ReceiptSave(true, "fixture ledger saved");
    }

    @Override
    public CardGateway.LedgerCheck ledger(String cardIdHex) {
        return new CardGateway.LedgerCheck(ledgerAvailable, "fixture ledger", cardIdHex,
                counter + ledgerOffset, balance, ledgerVisit);
    }

    @Override public Card getCard() { return null; }
    @Override public int getChannelNumber() { return 0; }
    @Override public int transmit(ByteBuffer command, ByteBuffer response) { throw new UnsupportedOperationException(); }
    @Override public void close() { release.countDown(); }
}
