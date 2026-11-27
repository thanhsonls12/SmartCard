package vn.labaccess.card;

import javacard.security.ECPrivateKey;
import javacard.security.Signature;

final class ReceiptSigner {

    private final Signature ecdsa;
    private final ECPrivateKey privateKey;

    ReceiptSigner(ECPrivateKey privateKey) {
        this.privateKey = privateKey;
        ecdsa = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
    }

    short sign(byte[] payload, short payloadLength, byte[] target) {
        ecdsa.init(privateKey, Signature.MODE_SIGN);
        ecdsa.update(Protocol.RECEIPT_DOMAIN, (short) 0, Protocol.RECEIPT_DOMAIN_LENGTH);
        return ecdsa.sign(payload, (short) 0, payloadLength, target, (short) 0);
    }
}
