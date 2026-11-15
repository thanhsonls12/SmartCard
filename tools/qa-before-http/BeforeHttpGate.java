import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ByteValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public final class BeforeHttpGate {
    private static final String APP = "vn.labaccess.desktop.App";
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path journal = Path.of(args[1]).toAbsolutePath();
        String wantedCard = args[2];
        Path evidence = Path.of(args[3]);
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors()
                .stream().filter(value -> value.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow();
        var arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue("10000");
        VirtualMachine vm = connector.attach(arguments);
        var prepared = vm.eventRequestManager().createClassPrepareRequest();
        prepared.addClassFilter(APP);
        prepared.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        prepared.enable();
        boolean held = false;
        System.out.println(Instant.now() + " DEBUGGER_ATTACHED loopback=true");
        vm.resume();
        try {
            while (true) {
                EventSet events = vm.eventQueue().remove(300000);
                if (events == null) {
                    throw new IllegalStateException("Timed out before app disconnection");
                }
                boolean resume = true;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent ready) {
                        var method = ready.referenceType().methodsByName("saveReceiptToLedger").getFirst();
                        var breakpoint = vm.eventRequestManager().createBreakpointRequest(method.location());
                        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                        breakpoint.enable();
                        prepared.disable();
                        System.out.println(Instant.now() + " BREAKPOINT_ARMED " + method.location());
                    } else if (event instanceof BreakpointEvent stopped) {
                        ObjectReference receipt = (ObjectReference) stopped.thread().frame(0)
                                .getArgumentValues().getFirst();
                        if (number(receipt, "operation") != 2) {
                            System.out.println(Instant.now() + " BASELINE_RECEIPT_ALLOWED operation="
                                    + number(receipt, "operation"));
                            continue;
                        }
                        ObjectReference app = stopped.thread().frame(0).thisObject();
                        ObjectReference gateway = (ObjectReference) field(app, "gateway");
                        require(((BooleanValue) field(gateway, "lastReceiptSignatureOk")).value(),
                                "Gateway has not verified the receipt signature");
                        require(stopped.thread().frames().stream().anyMatch(frame -> frame.location()
                                .method().name().equals("runStateTransaction")), "Not a state transaction");
                        byte[] payload = bytes(receipt, "payload");
                        byte[] signature = bytes(receipt, "signature");
                        byte[] point = bytes(gateway, "cardPublicKeyBytes");
                        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                        parameters.init(new ECGenParameterSpec("secp256r1"));
                        ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
                        var publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                                new ECPoint(new BigInteger(1, java.util.Arrays.copyOfRange(point, 1, 33)),
                                        new BigInteger(1, java.util.Arrays.copyOfRange(point, 33, 65))), curve));
                        Signature verifier = Signature.getInstance("SHA256withECDSA");
                        verifier.initVerify(publicKey);
                        verifier.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
                        verifier.update(payload);
                        require(verifier.verify(signature), "Independent signature verification failed");
                        byte[] tampered = payload.clone();
                        tampered[17] ^= 1;
                        verifier.initVerify(publicKey);
                        verifier.update("LABACCESS-RECEIPT-v1".getBytes(StandardCharsets.US_ASCII));
                        verifier.update(tampered);
                        require(!verifier.verify(signature), "Negative signature control did not fail");
                        byte[] journalBytes = Files.readAllBytes(journal);
                        String[] fields = new String(journalBytes, StandardCharsets.UTF_8).strip().split("\\|", -1);
                        String card = HEX.formatHex(bytes(receipt, "cardId")).toUpperCase();
                        String visit = HEX.formatHex(bytes(receipt, "visitId"));
                        long counter = number(receipt, "counterAfter");
                        long balance = number(receipt, "balanceAfter");
                        require(fields.length == 9 && fields[0].equals("v1") && fields[1].equals("PENDING"),
                                "Journal is not valid PENDING");
                        require(card.equalsIgnoreCase(wantedCard) && card.equalsIgnoreCase(fields[2])
                                && counter == Long.parseLong(fields[3]) && fields[4].equals("2")
                                && number(receipt, "delta") == Integer.parseInt(fields[5])
                                && balance == Integer.parseInt(fields[6]) && visit.equalsIgnoreCase(fields[7]),
                                "Receipt does not match the pending journal");
                        ObjectReference appJournal = (ObjectReference) field(app, "journal");
                        ObjectReference appPath = (ObjectReference) field(appJournal, "file");
                        var pathToString = appPath.referenceType().methodsByName("toString", "()Ljava/lang/String;").getFirst();
                        String actualJournal = ((StringReference) appPath.invokeMethod(stopped.thread(), pathToString,
                                List.of(), ObjectReference.INVOKE_SINGLE_THREADED)).value();
                        require(Path.of(actualJournal).toAbsolutePath().equals(journal), "App uses a different journal");
                        String digest = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(journalBytes));
                        String marker = "QA_BEFORE_HTTP_GATED time=" + Instant.now() + " cardId=" + card
                                + " counter=" + counter + " balance=" + balance + " operation=START visitId=" + visit
                                + " ECDSA=PASS independentECDSA=PASS tamperedECDSA=REJECT journal=PENDING"
                                + " journalSha256=" + digest + " thread=" + stopped.thread().name()
                                + " boundary=App.saveReceiptToLedger before=BackendClient.verifyReceipt";
                        var logMethod = vm.classesByName(APP).getFirst().methodsByName("log", "(Ljava/lang/String;)V").getFirst();
                        app.invokeMethod(stopped.thread(), logMethod, List.of(vm.mirrorOf(marker)),
                                ObjectReference.INVOKE_SINGLE_THREADED);
                        Files.writeString(evidence.resolve("verified-receipt.json"), "{\n"
                                + "  \"cardId\": \"" + card + "\",\n"
                                + "  \"counterAfter\": " + counter + ",\n"
                                + "  \"balanceAfter\": " + balance + ",\n"
                                + "  \"visitId\": \"" + visit + "\",\n"
                                + "  \"payloadHex\": \"" + HEX.formatHex(payload) + "\",\n"
                                + "  \"signatureHex\": \"" + HEX.formatHex(signature) + "\",\n"
                                + "  \"publicPointHex\": \"" + HEX.formatHex(point) + "\"\n}\n");
                        Files.write(evidence.resolve("journal-pending.txt"), journalBytes);
                        Files.writeString(evidence.resolve("gate-held.txt"), marker + "\n");
                        System.out.println(marker);
                        held = true;
                        resume = false;
                    }
                }
                if (resume && !held) {
                    events.resume();
                }
            }
        } catch (VMDisconnectedException disconnected) {
            require(held, "App disconnected before the required boundary");
            System.out.println(Instant.now() + " APP_DISCONNECTED gateWasHeld=true gateNeverResumed=true");
        }
    }

    private static Value field(ObjectReference object, String name) {
        return object.getValue(object.referenceType().allFields().stream()
                .filter(value -> value.name().equals(name)).findFirst().orElseThrow());
    }

    private static byte[] bytes(ObjectReference object, String name) {
        ArrayReference array = (ArrayReference) field(object, name);
        byte[] result = new byte[array.length()];
        for (int index = 0; index < result.length; index++) {
            result[index] = ((ByteValue) array.getValue(index)).value();
        }
        return result;
    }

    private static long number(ObjectReference object, String name) {
        Value value = field(object, name);
        return value instanceof LongValue number ? number.value() : ((IntegerValue) value).value();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
