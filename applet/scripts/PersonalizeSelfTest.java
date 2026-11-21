import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import com.oracle.javacard.ams.AMService;
import com.oracle.javacard.ams.AMServiceFactory;
import com.oracle.javacard.ams.AMSession;
import com.oracle.javacard.ams.config.CAPFile;
import com.oracle.javacard.ams.script.ScriptFailedException;
import com.oracle.javacard.ams.script.ScriptVisitor;

public final class PersonalizeSelfTest {

    private static final String ISD_AID = "aid:A000000151000000";
    private static final String ISD_AID_HEX = "A000000151000000";
    private static final String PACKAGE_AID = "F04C414202";
    private static final String APPLET_AID = "F04C4142020101";
    private static final String TEST_AID_HEX = "F04C414202EE01";
    private static final int SW_OK = 0x9000;
    private static final int SW_WRONG_LENGTH = 0x6700;
    private static final int SW_WRONG_DATA = 0x6A80;
    private static final int SW_CONDITIONS = 0x6985;
    private static final int SW_SECURITY = 0x6982;
    private static final int SW_WRONG_P1P2 = 0x6A86;
    private static final int SW_INTERNAL = 0x6F00;
    private static final int SW_RETRY_BASE = 0x63C0;
    private static final int SW_DELETE_NOT_FOUND = 0x6A88;
    private static final int LIFECYCLE_UNPERSONALIZED = 0x00;
    private static final int LIFECYCLE_ISSUED = 0x01;

    private static final byte CLA = (byte) 0x80;
    private static final byte INS_PERSONALIZE = (byte) 0x30;
    private static final byte INS_GET_STATUS = (byte) 0x10;
    private static final byte INS_GET_PUBLIC_KEY = (byte) 0x61;
    private static final byte INS_SIGN_ENROLLMENT = (byte) 0x62;
    private static final byte INS_VERIFY_STUDENT_PIN = (byte) 0x20;
    private static final byte INS_CHANGE_STUDENT_PIN = (byte) 0x21;
    private static final byte INS_UNBLOCK_STUDENT_PIN = (byte) 0x23;
    private static final byte INS_GET_PROFILE = (byte) 0x31;
    private static final byte INS_GET_PROFILE_CRYPTO_META = (byte) 0x40;
    private static final byte INS_PHOTO_BEGIN = (byte) 0x32;
    private static final byte INS_PHOTO_WRITE_CHUNK = (byte) 0x33;
    private static final byte INS_PHOTO_READ_CHUNK = (byte) 0x34;
    private static final byte INS_PHOTO_FINISH = (byte) 0x35;
    private static final int PHOTO_LENGTH = 4096;
    private static final int PHOTO_CHUNK_LENGTH = 128;
    private static final byte INS_GRANT_QUOTA = (byte) 0x50;
    private static final int GRANT_DATA_LENGTH = 10;
    private static final int GRANT_DELTA_OFFSET = 8;
    private static final int QUOTA_RESPONSE_LENGTH = 6;
    private static final int MAX_BALANCE = 9999;
    private static final int FAULT_BEFORE_COMMIT_DELTA = 7777;
    private static final int FAULT_AFTER_COMMIT_DELTA = 8888;
    private static final byte INS_START_VISIT = (byte) 0x51;
    private static final byte INS_END_VISIT = (byte) 0x52;
    private static final byte INS_GET_LAST_RECEIPT = (byte) 0x60;
    private static final int VISIT_ID_LENGTH = 16;
    private static final int STATE_RESPONSE_LENGTH = 6;
    private static final int VISIT_RESPONSE_LENGTH = STATE_RESPONSE_LENGTH;
    private static final int STATE_COUNTER_OFFSET = 2;
    private static final int RECEIPT_PAYLOAD_LENGTH = 34;
    private static final int RECEIPT_RESPONSE_MAX_LENGTH = 107;
    private static final int RECEIPT_SIGNATURE_MIN_LENGTH = 8;
    private static final int RECEIPT_OFFSET_VERSION = 0;
    private static final int RECEIPT_OFFSET_CARD_ID = 1;
    private static final int RECEIPT_OFFSET_COUNTER = 9;
    private static final int RECEIPT_OFFSET_OPERATION = 13;
    private static final int RECEIPT_OFFSET_DELTA = 14;
    private static final int RECEIPT_OFFSET_BALANCE = 16;
    private static final int RECEIPT_OFFSET_VISIT_ID = 18;
    private static final int OPERATION_GRANT_QUOTA = 0x01;
    private static final int OPERATION_START_VISIT = 0x02;
    private static final int OPERATION_END_VISIT = 0x03;
    private static final int FAULT_RECEIPT_BEFORE_COMMIT_DELTA = 7777;
    private static final int FAULT_RECEIPT_AFTER_COMMIT_DELTA = 8888;
    private static final byte VISIT_FAULT_ID_BYTE = (byte) 0xAA;
    private static final List<String> receiptArtifacts = new java.util.ArrayList<>();
    private static String cardPublicKeyHex;
    private static final int PROFILE_META_LENGTH = 65;
    private static final int META_OFFSET_RECORD_VERSION = 0;
    private static final int META_OFFSET_ALGORITHM_ID = 1;
    private static final int META_OFFSET_SCHEMA_VERSION = 2;
    private static final int META_OFFSET_CIPHERTEXT_LENGTH = 3;
    private static final int META_OFFSET_NONCE = 5;
    private static final int META_NONCE_LENGTH = 12;
    private static final int META_OFFSET_TAG = 17;
    private static final int META_TAG_LENGTH = 16;
    private static final int META_OFFSET_CIPHERTEXT_HASH = 33;
    private static final int META_CIPHERTEXT_HASH_LENGTH = 32;
    private static final int PROFILE_PLAINTEXT_LENGTH =
            1 + 1 + "SV2024001".length() + 1 + "Nguyen Van A".length();

    private static final byte[] CARD_ID = hex("A1B2C3D411223344");
    private static final byte[] STUDENT_ID = ascii("SV2024001");
    private static final byte[] DISPLAY_NAME = "Nguyen Van A".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static int failed;

    public static void main(String[] args) throws Exception {
        byte[] adminPin = null;
        byte[] studentPin = null;
        byte[] unblockedPin = null;
        byte[] changedPin = null;
        byte[] metadata = null;
        Card card = null;
        CardChannel channel = null;
        boolean testInstanceMayExist = false;
        boolean testPackageMayExist = false;
        String propertiesPath = args.length == 0 ? null : args[0];
        String testCapPath = args.length < 2 ? null : args[1];
        String variantsRoot = args.length < 3 ? null : args[2];
        String artifactsPath = args.length < 4 ? "applet/build/receipt-artifacts.jsonl" : args[3];
        try {
            adminPin = readPin(8);
            studentPin = readPin(6);
            unblockedPin = readPin(6);
            changedPin = readPin(6);
            card = connect();
            channel = card.getBasicChannel();
            if (!removeTestInstance(propertiesPath, channel)
                    || !removeTestPackage(propertiesPath, channel)) {
                throw new IllegalStateException("test AID cleanup failed");
            }
            testPackageMayExist = true;
            if (!loadTestPackage(propertiesPath, channel, testCapPath)) {
                throw new IllegalStateException("test package LOAD failed");
            }
            testInstanceMayExist = true;
            if (!installTestInstance(propertiesPath, channel, adminPin)) {
                throw new IllegalStateException("test AID install failed");
            }
            selectTestInstance(channel);
            if (Boolean.getBoolean("labaccess.tamperOnly")) {
                byte[] payload = validPayload(adminPin, studentPin);
                try {
                    record("AES-CONTROL-PERSONALIZE", "PERSONALIZE unmodified test CAP", SW_OK,
                            transmit(channel, personalizeCommand(payload)).getSW());
                    record("AES-CONTROL-VERIFY", "VERIFY before control profile", SW_OK,
                            transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
                    ResponseAPDU profile = transmit(channel, profileCommand());
                    recordBoolean("AES-CONTROL-PROFILE", "unmodified record returns exact profile",
                            profile.getSW() == SW_OK && profileMatches(profile.getData()));
                    clear(profile.getData());
                    runTamperCases(propertiesPath, channel, adminPin, studentPin, variantsRoot);
                } finally {
                    clear(payload);
                }
            } else if (Boolean.getBoolean("labaccess.apduErrorsOnly")) {
                byte[] payload = validPayload(adminPin, studentPin);
                CardChannel testChannel = channel;
                byte[] provisioningPin = adminPin;
                try {
                    String transportProbe = System.getProperty("labaccess.transportProbe", "");
                    if (transportProbe.equals("gateway-sw6c")) {
                        ResponseAPDU personalized = channel.transmit(new CommandAPDU(0x80, 0x30, 0, 0, payload, 256));
                        if (personalized.getSW() != SW_OK) {
                            throw new IllegalStateException("gateway test provisioning failed");
                        }
                        card.disconnect(true);
                        try {
                            failed += vn.labaccess.desktop.GatewayTransportCases.run(
                                    Integer.getInteger("labaccess.simulator.port", 9025), adminPin, studentPin,
                                    java.nio.file.Path.of(System.getProperty("labaccess.evidence")));
                        } finally {
                            card = connect();
                            channel = card.getBasicChannel();
                        }
                    } else if (!transportProbe.isEmpty()) {
                        failed += vn.labaccess.desktop.ApduTransportCases.run(channel, adminPin,
                                studentPin, payload, transportProbe);
                    } else {
                        ApduErrorCases[] caseHolder = new ApduErrorCases[1];
                        ApduErrorCases apduCases = new ApduErrorCases(channel, adminPin, studentPin, CARD_ID, () -> {
                            CardChannel current = caseHolder[0].channel();
                            if (!removeTestInstance(propertiesPath, current)
                                    || !installTestInstance(propertiesPath, current, provisioningPin)) {
                                throw new IllegalStateException("fresh test instance failed");
                            }
                            selectTestInstance(current);
                        });
                        caseHolder[0] = apduCases;
                        try {
                            failed += apduCases.run(payload, System.getProperty("labaccess.apduGroups", "all"));
                        } finally {
                            channel = apduCases.channel();
                            card = channel.getCard();
                        }
                    }
                } finally {
                    clear(payload);
                }
            } else {
                runCases(channel, adminPin, studentPin, unblockedPin, changedPin);
                if (Boolean.getBoolean("labaccess.personalizeOnly")) {
                    runProfileCases(channel, studentPin);
                } else {
                    runEnrollmentCases(channel, studentPin);
                    runProfileCases(channel, studentPin);
                    metadata = runMetadataCases(channel, studentPin);
                    runQuotaCases(channel, adminPin);
                    card = runAuthorizationResetCase(card, studentPin);
                    channel = card.getBasicChannel();
                    runTamperCases(propertiesPath, channel, adminPin, studentPin, variantsRoot);
                    runMetadataDifferential(propertiesPath, channel, adminPin, studentPin,
                            testCapPath, metadata);
                    runQuotaVariantCases(propertiesPath, channel, adminPin, studentPin, variantsRoot);
                    card = runVisitCases(propertiesPath, channel, adminPin, studentPin, testCapPath);
                    channel = card.getBasicChannel();
                    card = runVisitVariantCases(propertiesPath, channel, adminPin, studentPin, variantsRoot);
                    channel = card.getBasicChannel();
                    card = runReceiptCases(propertiesPath, channel, adminPin, studentPin, testCapPath);
                    channel = card.getBasicChannel();
                    runReceiptFaultBeforeCommitCase(propertiesPath, channel, adminPin, studentPin,
                            variantsRoot);
                    runReceiptFaultAfterCommitCase(propertiesPath, channel, adminPin, studentPin,
                            variantsRoot);
                    runReceiptSignatureSizeCase(propertiesPath, channel, adminPin, studentPin,
                            variantsRoot);
                    card = runPhotoFinishFaultCase(propertiesPath, card, adminPin, studentPin, variantsRoot);
                    channel = card.getBasicChannel();
                }
                runStudentIdBoundaryCases(propertiesPath, channel, adminPin, studentPin);
            }
        } catch (Exception exception) {
            failed++;
            log("SUITE FAILED: " + exception.getClass().getSimpleName());
        } finally {
            if (testInstanceMayExist && !removeTestInstance(propertiesPath, channel)) {
                failed++;
                log("test AID cleanup failed");
            }
            if (testPackageMayExist && !removeTestPackage(propertiesPath, channel)) {
                failed++;
                log("test package cleanup failed");
            }
            clear(adminPin);
            clear(studentPin);
            clear(unblockedPin);
            clear(changedPin);
            clear(metadata);
            if (card != null) {
                card.disconnect(true);
            }
        }
        if (!Boolean.getBoolean("labaccess.apduErrorsOnly") && !Boolean.getBoolean("labaccess.tamperOnly")) {
            writeReceiptArtifacts(artifactsPath);
        }
        log("KET LUAN: " + (failed == 0 ? "PASS" : "FAIL (" + failed + ")"));
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static boolean installTestInstance(String propertiesPath, CardChannel channel,
            byte[] adminPin) throws Exception {
        byte[] installData = new byte[10];
        installData[0] = 1;
        installData[1] = 8;
        System.arraycopy(adminPin, 0, installData, 2, adminPin.length);
        try {
            AMService service = openService(propertiesPath);
            AMSession session = service.openSession(ISD_AID)
                    .install("aid:" + PACKAGE_AID, "aid:" + APPLET_AID,
                            "aid:" + TEST_AID_HEX, installData).close();
            final int[] lastStatus = { 0 };
            Predicate<ResponseAPDU> stopOnError = response -> {
                lastStatus[0] = response.getSW();
                log(String.format("INSTALL test AID <- SW=%04X", response.getSW()));
                return response.getSW() != SW_OK;
            };
            session.run(channel, command -> {
                log(String.format("INSTALL test AID -> %02X %02X %02X %02X",
                        command.getCLA(), command.getINS(), command.getP1(), command.getP2()));
                return command;
            }, stopOnError);
            return lastStatus[0] == SW_OK;
        } finally {
            clear(installData);
        }
    }

    private static boolean removeTestInstance(String propertiesPath, CardChannel channel) {
        if (channel == null) {
            return false;
        }
        boolean[] deleteTargetsTestAid = { false };
        Predicate<ResponseAPDU> stopOnUnexpectedStatus = response -> {
            boolean allowedAbsent = response.getSW() == SW_DELETE_NOT_FOUND
                    && deleteTargetsTestAid[0];
            log(String.format("DELETE test AID <- SW=%04X", response.getSW()));
            return response.getSW() != SW_OK && !allowedAbsent;
        };
        try {
            AMService service = openService(propertiesPath);
            AMSession session = service.openSession(ISD_AID)
                    .uninstall("aid:" + TEST_AID_HEX).close();
            List<ResponseAPDU> responses = session.run(channel, command -> {
                log(String.format("DELETE test AID -> %02X %02X %02X %02X",
                        command.getCLA(), command.getINS(), command.getP1(), command.getP2()));
                deleteTargetsTestAid[0] = isDeleteForTestAid(command);
                return command;
            }, stopOnUnexpectedStatus);
            return !responses.isEmpty();
        } catch (ScriptFailedException exception) {
            return false;
        } catch (Exception exception) {
            log("test AID cleanup error: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean loadTestPackage(String propertiesPath, CardChannel channel,
            String capPath) throws Exception {
        CAPFile capFile = CAPFile.from(capPath);
        AMSession session = openService(propertiesPath).openSession(ISD_AID)
                .load("aid:" + PACKAGE_AID, capFile.getBytes()).close();
        final int[] lastStatus = { 0 };
        session.run(channel, command -> command, response -> {
            lastStatus[0] = response.getSW();
            log(String.format("LOAD test package <- SW=%04X", response.getSW()));
            return response.getSW() != SW_OK;
        });
        return lastStatus[0] == SW_OK;
    }

    private static boolean removeTestPackage(String propertiesPath, CardChannel channel) {
        if (channel == null) {
            return false;
        }
        boolean[] targetsTestPackage = { false };
        Predicate<ResponseAPDU> stopOnUnexpectedStatus = response -> {
            boolean allowedAbsent = response.getSW() == SW_DELETE_NOT_FOUND
                    && targetsTestPackage[0];
            log(String.format("UNLOAD test package <- SW=%04X", response.getSW()));
            return response.getSW() != SW_OK && !allowedAbsent;
        };
        try {
            AMSession session = openService(propertiesPath).openSession(ISD_AID)
                    .unload("aid:" + PACKAGE_AID).close();
            List<ResponseAPDU> responses = session.run(channel, command -> {
                targetsTestPackage[0] = isDeleteForAid(command, PACKAGE_AID);
                return command;
            }, stopOnUnexpectedStatus);
            return !responses.isEmpty();
        } catch (ScriptFailedException exception) {
            return false;
        } catch (Exception exception) {
            log("test package cleanup error: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isDeleteForTestAid(CommandAPDU command) {
        return isDeleteForAid(command, TEST_AID_HEX);
    }

    private static boolean isDeleteForAid(CommandAPDU command, String aidHex) {
        if (command.getCLA() != 0x80 || command.getINS() != 0xE4
                || command.getP1() != 0 || command.getP2() != 0) {
            return false;
        }
        byte[] aid = hex(aidHex);
        byte[] data = command.getData();
        if (data.length != aid.length + 2 || (data[0] & 0xFF) != 0x4F
                || (data[1] & 0xFF) != aid.length) {
            return false;
        }
        for (int index = 0; index < aid.length; index++) {
            if (data[index + 2] != aid[index]) {
                return false;
            }
        }
        return true;
    }

    private static AMService openService(String propertiesPath) throws Exception {
        AMService service = AMServiceFactory.getInstance("GP2.2");
        if (propertiesPath != null) {
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(propertiesPath)) {
                properties.load(input);
            }
            service.setProperties(properties);
        }
        return service;
    }


    private static void runProfileCases(CardChannel channel, byte[] studentPin) throws Exception {
        record("Q01", "GET_PROFILE truoc VERIFY", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());
        record("Q02", "VERIFY truoc GET_PROFILE", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU profileResponse = transmit(channel, profileCommand());
        record("Q03", "GET_PROFILE tra ho so", SW_OK, profileResponse.getSW());
        byte[] profile = profileResponse.getData();
        recordBoolean("Q04", "profile dung schema va du lieu", profileMatches(profile));
        clear(profile);
        record("Q05", "GET_PROFILE lan hai khong VERIFY", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());

        record("Q06", "VERIFY lai cho ca Le thieu", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Q07", "GET_PROFILE Le nho hon response", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PROFILE, 0, 0, 4)).getSW());
        record("Q08", "quyen bi xoa sau Le thieu", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());

        record("Q09", "VERIFY lai cho ca P1 sai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Q10", "GET_PROFILE P1 sai", SW_WRONG_P1P2,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PROFILE, 1, 0, 256)).getSW());
        record("Q11", "quyen bi xoa sau P1 sai", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());

        record("Q12", "VERIFY lai cho lenh xen giua", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Q13", "lenh xen giua GET_STATUS", SW_OK,
                transmit(channel, new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256)).getSW());
        record("Q14", "quyen bi xoa sau lenh xen giua", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());

        record("Q15", "VERIFY lai cho SELECT", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        selectTestInstance(channel);
        record("Q16", "quyen bi xoa sau SELECT lap lai", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());

        record("Q17", "VERIFY lai cho deselect", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        selectAid(channel, ISD_AID_HEX);
        selectTestInstance(channel);
        record("Q18", "quyen bi xoa sau deselect", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());
    }

    private static byte[] runMetadataCases(CardChannel channel, byte[] studentPin) throws Exception {
        record("S01", "META truoc VERIFY", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());
        record("S02", "VERIFY truoc META", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU metadataResponse = transmit(channel, metaCommand());
        record("S03", "META tra metadata", SW_OK, metadataResponse.getSW());
        byte[] metadata = metadataResponse.getData();
        recordBoolean("S04", "metadata dai " + PROFILE_META_LENGTH + " byte",
                metadata.length == PROFILE_META_LENGTH);
        recordBoolean("S05", "recordVersion/algorithmId/schema deu 01",
                metadata.length == PROFILE_META_LENGTH
                        && metadata[META_OFFSET_RECORD_VERSION] == 1
                        && metadata[META_OFFSET_ALGORITHM_ID] == 1
                        && metadata[META_OFFSET_SCHEMA_VERSION] == 1);
        recordBoolean("S06", "ciphertextLength khop do dai ho so",
                metadata.length == PROFILE_META_LENGTH
                        && metadataCiphertextLength(metadata) == PROFILE_PLAINTEXT_LENGTH);
        recordBoolean("S07", "ciphertextHash 32 byte va khac 0",
                metadata.length == PROFILE_META_LENGTH && metadataHashPresent(metadata));
        record("S08", "META lan hai khong VERIFY", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());

        record("S09", "VERIFY cho ca Le thieu", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("S10", "META Le nho hon response", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PROFILE_CRYPTO_META, 0, 0, 64)).getSW());
        record("S11", "quyen bi xoa sau Le thieu", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());

        record("S12", "VERIFY cho ca P1 sai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("S13", "META P1 sai", SW_WRONG_P1P2,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PROFILE_CRYPTO_META, 1, 0, 256)).getSW());
        record("S14", "quyen bi xoa sau P1 sai", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());

        record("S15", "VERIFY cho lenh xen giua", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("S16", "lenh xen giua GET_STATUS", SW_OK,
                transmit(channel, new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256)).getSW());
        record("S17", "quyen bi xoa sau lenh xen giua", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());

        record("S18", "VERIFY cho ca doi chieu GET_PROFILE", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU profileResponse = transmit(channel, profileCommand());
        recordBoolean("S19", "ciphertextLength khop do dai GET_PROFILE tra ve",
                profileResponse.getSW() == SW_OK
                        && metadata.length == PROFILE_META_LENGTH
                        && metadataCiphertextLength(metadata) == profileResponse.getData().length);
        clear(profileResponse.getData());
        record("S20", "quyen bi tieu thu boi GET_PROFILE", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());
        return metadata;
    }

    private static void runMetadataDifferential(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String testCapPath, byte[] first) throws Exception {
        if (first == null || first.length != PROFILE_META_LENGTH) {
            recordBoolean("S21", "ban ghi thu hai de so sanh", false);
            return;
        }
        if (!removeTestInstance(propertiesPath, channel)
                || !removeTestPackage(propertiesPath, channel)) {
            recordBoolean("S21", "cleanup truoc ban ghi thu hai", false);
            return;
        }
        if (!loadTestPackage(propertiesPath, channel, testCapPath)
                || !installTestInstance(propertiesPath, channel, adminPin)) {
            recordBoolean("S21", "tai lai CAP sach cho ban ghi thu hai", false);
            return;
        }
        selectTestInstance(channel);
        byte[] payload = validPayload(adminPin, studentPin);
        boolean personalized = transmit(channel, personalizeCommand(payload)).getSW() == SW_OK;
        clear(payload);
        recordBoolean("S21", "PERSONALIZE ban ghi thu hai", personalized);
        if (!personalized) {
            return;
        }
        record("S22", "VERIFY cho ban ghi thu hai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU response = transmit(channel, metaCommand());
        byte[] second = response.getData();
        recordBoolean("S23", "META ban ghi thu hai tra 65 byte",
                response.getSW() == SW_OK && second.length == PROFILE_META_LENGTH);
        if (second.length != PROFILE_META_LENGTH) {
            clear(second);
            return;
        }
        recordBoolean("S24", "nonce khac giua hai ban ghi",
                !Arrays.equals(metadataField(first, META_OFFSET_NONCE, META_NONCE_LENGTH),
                        metadataField(second, META_OFFSET_NONCE, META_NONCE_LENGTH)));
        recordBoolean("S25", "ciphertextHash khac giua hai ban ghi",
                !Arrays.equals(metadataField(first, META_OFFSET_CIPHERTEXT_HASH,
                        META_CIPHERTEXT_HASH_LENGTH),
                        metadataField(second, META_OFFSET_CIPHERTEXT_HASH,
                                META_CIPHERTEXT_HASH_LENGTH)));
        recordBoolean("S26", "ciphertextLength on dinh giua hai ban ghi",
                metadataCiphertextLength(first) == metadataCiphertextLength(second));
        record("S27", "quyen bi tieu thu sau doc thanh cong", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());
        clear(second);
    }

    private static int metadataCiphertextLength(byte[] metadata) {
        return ((metadata[META_OFFSET_CIPHERTEXT_LENGTH] & 0xFF) << 8)
                | (metadata[META_OFFSET_CIPHERTEXT_LENGTH + 1] & 0xFF);
    }

    private static boolean metadataHashPresent(byte[] metadata) {
        for (int index = META_OFFSET_CIPHERTEXT_HASH;
                index < META_OFFSET_CIPHERTEXT_HASH + META_CIPHERTEXT_HASH_LENGTH; index++) {
            if (metadata[index] != 0) {
                return true;
            }
        }
        return false;
    }

    private static byte[] metadataField(byte[] metadata, int offset, int length) {
        return Arrays.copyOfRange(metadata, offset, offset + length);
    }

    private static CommandAPDU metaCommand() {
        return new CommandAPDU(CLA, INS_GET_PROFILE_CRYPTO_META, 0, 0, 256);
    }

    private static void runQuotaCases(CardChannel channel, byte[] adminPin) throws Exception {
        byte[] wrongAdminPin = differentPin(adminPin);
        record("U01", "GRANT admin sai lan 1", SW_RETRY_BASE | 4,
                transmit(channel, grantCommand(wrongAdminPin, 5)).getSW());
        record("U02", "GRANT delta 0", SW_WRONG_DATA,
                transmit(channel, grantCommand(adminPin, 0)).getSW());
        recordQuota("U03", "GRANT +5 tu balance 0", transmit(channel, grantCommand(adminPin, 5)),
                5, 1L);
        record("U04", "GRANT delta 0 khi da co balance", SW_WRONG_DATA,
                transmit(channel, grantCommand(adminPin, 0)).getSW());
        record("U05", "GRANT delta 65535 vuot tran", SW_WRONG_DATA,
                transmit(channel, grantCommand(adminPin, 0xFFFF)).getSW());
        record("U06", "GRANT Le nho hon response", SW_WRONG_LENGTH,
                transmit(channel, grantCommand(adminPin, 5, 5)).getSW());
        record("U07", "GRANT P1 sai", SW_WRONG_P1P2,
                transmit(channel, new CommandAPDU(CLA, INS_GRANT_QUOTA, 1, 0,
                        grantData(adminPin, 5), QUOTA_RESPONSE_LENGTH)).getSW());
        byte[] shortData = Arrays.copyOf(grantData(adminPin, 5), GRANT_DATA_LENGTH - 1);
        record("U08", "GRANT data thieu 1 byte", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GRANT_QUOTA, 0, 0, shortData,
                        QUOTA_RESPONSE_LENGTH)).getSW());
        clear(shortData);
        byte[] longData = Arrays.copyOf(grantData(adminPin, 5), GRANT_DATA_LENGTH + 1);
        record("U09", "GRANT data thua 1 byte", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GRANT_QUOTA, 0, 0, longData,
                        QUOTA_RESPONSE_LENGTH)).getSW());
        clear(longData);
        record("U10", "admin retry khong bi tru boi ca loi Le/P1/Lc", SW_RETRY_BASE | 4,
                transmit(channel, grantCommand(wrongAdminPin, 5)).getSW());
        recordQuota("U11", "GRANT +3 sau cac ca loi", transmit(channel, grantCommand(adminPin, 3)),
                8, 2L);
        recordQuota("U12", "GRANT +9991 cham tran 9999",
                transmit(channel, grantCommand(adminPin, MAX_BALANCE - 8)), MAX_BALANCE, 3L);
        record("U13", "GRANT +1 vuot tran", SW_WRONG_DATA,
                transmit(channel, grantCommand(adminPin, 1)).getSW());
        record("U14", "admin sai lan 1 tai tran", SW_RETRY_BASE | 4,
                transmit(channel, grantCommand(wrongAdminPin, 1)).getSW());
        record("U15", "admin sai lan 2 tai tran", SW_RETRY_BASE | 3,
                transmit(channel, grantCommand(wrongAdminPin, 1)).getSW());
        record("U16", "admin sai lan 3 tai tran", SW_RETRY_BASE | 2,
                transmit(channel, grantCommand(wrongAdminPin, 1)).getSW());
        record("U17", "admin sai lan 4 tai tran", SW_RETRY_BASE | 1,
                transmit(channel, grantCommand(wrongAdminPin, 1)).getSW());
        record("U18", "admin sai lan 5 khoa admin", 0x6983,
                transmit(channel, grantCommand(wrongAdminPin, 1)).getSW());
        record("U19", "admin dung sau khi khoa", 0x6983,
                transmit(channel, grantCommand(adminPin, 1)).getSW());
        clear(wrongAdminPin);
    }

    private static void runQuotaVariantCases(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String variantsRoot) throws Exception {
        String[] names = { "counter-near-max", "quota-fault-before-commit",
                "quota-fault-after-commit" };
        String nearMax = variantCap(variantsRoot, names[0]);
        String beforeCommit = variantCap(variantsRoot, names[1]);
        String afterCommit = variantCap(variantsRoot, names[2]);
        if (nearMax == null || beforeCommit == null || afterCommit == null) {
            for (int index = 0; index < names.length; index++) {
                recordBoolean("V0" + (index + 1), "thieu CAP " + names[index], false);
            }
            return;
        }
        runCounterNearMaxCase(propertiesPath, channel, adminPin, studentPin, nearMax);
        runQuotaFaultBeforeCommitCase(propertiesPath, channel, adminPin, studentPin, beforeCommit);
        runQuotaFaultAfterCommitCase(propertiesPath, channel, adminPin, studentPin, afterCommit);
    }

    private static String variantCap(String variantsRoot, String name) {
        if (variantsRoot == null) {
            return null;
        }
        Path candidate = Paths.get(variantsRoot, name + "-deliverables", "vn", "labaccess", "card",
                "javacard", "card.cap");
        return Files.exists(candidate) ? candidate.toString() : null;
    }

    private static Card runVisitCases(String propertiesPath, CardChannel channel, byte[] adminPin,
            byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("Y01", "chuan bi instance sach cho START/END", false);
            return channel.getCard();
        }
        byte[] firstVisit = visitId((byte) 0x11);
        byte[] secondVisit = visitId((byte) 0x22);
        byte[] thirdVisit = visitId((byte) 0x33);
        record("Y01", "START khi chua VERIFY", SW_SECURITY, transmit(channel,
                visitCommand(INS_START_VISIT, firstVisit)).getSW());
        record("Y02", "VERIFY truoc chuoi START/END", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y03", "START visitId toan 00", SW_WRONG_DATA, transmit(channel,
                visitCommand(INS_START_VISIT, new byte[VISIT_ID_LENGTH])).getSW());
        record("Y04", "VERIFY lai sau ca visitId toan 00", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y05", "START khi balance 0", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_START_VISIT, firstVisit)).getSW());
        recordQuota("Y06", "GRANT +5 truoc chuoi START", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("Y07", "VERIFY truoc START hop le", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Y08", "START hop le: balance 5 -> 4", transmit(channel,
                visitCommand(INS_START_VISIT, firstVisit)), 4, 2L);
        record("Y09", "START lan hai khi quyen da tieu", SW_SECURITY, transmit(channel,
                visitCommand(INS_START_VISIT, secondVisit)).getSW());
        record("Y10", "VERIFY truoc START khi da co ca", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y11", "START khi da co active visit", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_START_VISIT, secondVisit)).getSW());
        record("Y12", "VERIFY truoc END sai visitId", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y13", "END sai visitId", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_END_VISIT, secondVisit)).getSW());
        record("Y14", "VERIFY truoc END dung visitId", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Y15", "END dung: balance giu 4, active visit dong", transmit(channel,
                visitCommand(INS_END_VISIT, firstVisit)), 4, 3L);
        record("Y16", "VERIFY truoc END khi khong co ca", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y17", "END khi khong co active visit", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_END_VISIT, firstVisit)).getSW());
        record("Y18", "VERIFY truoc START trung lastClosedVisitId", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y19", "START visitId bang lastClosedVisitId", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_START_VISIT, firstVisit)).getSW());
        record("Y20", "VERIFY truoc START visitId moi", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Y21", "START visitId moi: balance 4 -> 3", transmit(channel,
                visitCommand(INS_START_VISIT, secondVisit)), 3, 4L);
        record("Y22", "VERIFY truoc END visitId moi", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Y23", "END visitId moi: balance giu 3", transmit(channel,
                visitCommand(INS_END_VISIT, secondVisit)), 3, 5L);
        recordQuota("Y24", "GRANT +1 doc counter sau 4 giao dich visit", transmit(channel,
                grantCommand(adminPin, 1)), 4, 6L);
        record("Y25", "VERIFY truoc GET_STATUS xen giua", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y26", "GET_STATUS xen giua", SW_OK,
                transmit(channel, new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256)).getSW());
        record("Y27", "START sau GET_STATUS mat quyen", SW_SECURITY, transmit(channel,
                visitCommand(INS_START_VISIT, thirdVisit)).getSW());
        record("Y28", "VERIFY truoc SELECT lai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        selectTestInstance(channel);
        record("Y29", "START sau SELECT lai mat quyen", SW_SECURITY, transmit(channel,
                visitCommand(INS_START_VISIT, thirdVisit)).getSW());
        record("Y30", "VERIFY truoc Le thieu", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Y31", "START Le 1 nho hon response", SW_WRONG_LENGTH, transmit(channel,
                visitCommand(INS_START_VISIT, thirdVisit, 1)).getSW());
        record("Y32", "START data 15 byte", SW_WRONG_LENGTH, transmit(channel,
                new CommandAPDU(CLA, INS_START_VISIT, 0, 0, new byte[VISIT_ID_LENGTH - 1],
                        VISIT_RESPONSE_LENGTH)).getSW());
        record("Y33", "START P1 sai", SW_WRONG_P1P2, transmit(channel,
                new CommandAPDU(CLA, INS_START_VISIT, 1, 0, thirdVisit,
                        VISIT_RESPONSE_LENGTH)).getSW());
        record("Y34", "VERIFY truoc reset the", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        Card reset = resetCard(channel);
        channel = reset.getBasicChannel();
        selectTestInstance(channel);
        record("Y35", "END sau reset the mat quyen", SW_SECURITY, transmit(channel,
                visitCommand(INS_END_VISIT, secondVisit)).getSW());
        return reset;
    }

    private static Card resetCard(CardChannel channel) throws Exception {
        channel.getCard().disconnect(true);
        return connect();
    }

    private static Card runVisitVariantCases(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String variantsRoot) throws Exception {
        String beforeCommit = variantCap(variantsRoot, "visit-fault-before-commit");
        String afterCommit = variantCap(variantsRoot, "visit-fault-after-commit");
        String nearMax = variantCap(variantsRoot, "visit-counter-near-max");
        String closeRollback = variantCap(variantsRoot, "visit-fault-after-close-before-commit");
        if (beforeCommit == null || afterCommit == null || nearMax == null || closeRollback == null) {
            recordBoolean("Z01", "thieu CAP visit variant", false);
            return channel.getCard();
        }
        runVisitFaultBeforeCommitCase(propertiesPath, channel, adminPin, studentPin, beforeCommit);
        runVisitFaultAfterCommitCase(propertiesPath, channel, adminPin, studentPin, afterCommit);
        runVisitCounterNearMaxCase(propertiesPath, channel, adminPin, studentPin, nearMax);
        return runVisitCloseRollbackCase(propertiesPath, channel.getCard(), adminPin, studentPin,
                closeRollback);
    }

    private static Card runVisitCloseRollbackCase(String propertiesPath, Card card,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, card.getBasicChannel(),
                adminPin, studentPin, capPath)) {
            recordBoolean("Z22", "chuan bi CAP fault sau visits.close", false);
            return card;
        }
        CardChannel channel = card.getBasicChannel();
        byte[] visit = visitId((byte) 0x77);
        byte[] otherVisit = visitId((byte) 0x78);
        recordQuota("Z22", "GRANT +5 truoc END rollback", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("Z23", "VERIFY truoc START de tao ca", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Z24", "START tao activeVisit truoc END fault", transmit(channel,
                visitCommand(INS_START_VISIT, visit)), 4, 2L);
        cardPublicKeyHex = readCardPublicKey(channel);
        record("Z25", "VERIFY truoc END fault", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z26", "END fault sau visit close truoc commit", SW_INTERNAL,
                transmit(channel, visitCommand(INS_END_VISIT, visit)).getSW());

        card.disconnect(true);
        card = connect();
        channel = card.getBasicChannel();
        selectTestInstance(channel);
        record("Z27", "SELECT sau reset giu instance de doc state rollback", SW_OK,
                transmit(channel, new CommandAPDU(0x00, 0xA4, 0x04, 0x00,
                        hex(TEST_AID_HEX), 256)).getSW());
        record("Z28", "VERIFY truoc doc state sau END rollback", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU status = transmit(channel,
                new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256));
        byte[] state = status.getData();
        int publicLength = 11 + (state.length > 10 ? state[10] & 0xFF : 0) + 1 + 8;
        boolean stateRestored = status.getSW() == SW_OK && state.length == publicLength + 23
                && readUnsignedShort(state, publicLength) == 4
                && readCounter(state, publicLength + 2) == 2L
                && state[publicLength + 6] == 1
                && Arrays.equals(Arrays.copyOfRange(state, publicLength + 7,
                        publicLength + 23), visit);
        recordBoolean("Z29", "END abort rollback giu balance/counter/activeVisitId nhat quan",
                stateRestored);
        clear(state);

        record("Z30", "VERIFY truoc START lap khi ca van mo", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z31", "START khac bi chan do ca cu van active", SW_CONDITIONS,
                transmit(channel, visitCommand(INS_START_VISIT, otherVisit)).getSW());
        record("Z32", "VERIFY truoc receipt cu sau rollback", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        byte[] receiptAfter = transmit(channel, receiptCommand()).getData();
        boolean startReceiptKept = receiptAfter.length >= RECEIPT_PAYLOAD_LENGTH + 1
                && (receiptAfter[RECEIPT_OFFSET_OPERATION] & 0xFF) == OPERATION_START_VISIT
                && readCounter(receiptAfter, RECEIPT_OFFSET_COUNTER) == 2L
                && Arrays.equals(Arrays.copyOfRange(receiptAfter, RECEIPT_OFFSET_VISIT_ID,
                        RECEIPT_PAYLOAD_LENGTH), visit);
        recordBoolean("Z33", "END abort rollback giu receipt START cu", startReceiptKept);
        clear(receiptAfter);
        clear(visit);
        clear(otherVisit);
        return card;
    }

    private static int readUnsignedShort(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 8) | (source[offset + 1] & 0xFF);
    }

    private static long readCounter(byte[] source, int offset) {
        long value = 0;
        for (int index = 0; index < 4; index++) {
            value = (value << 8) | (source[offset + index] & 0xFFL);
        }
        return value;
    }

    private static void runVisitFaultBeforeCommitCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("Z01", "chuan bi CAP fault truoc commit cua START", false);
            return;
        }
        byte[] faultVisit = visitId(VISIT_FAULT_ID_BYTE);
        byte[] normalVisit = visitId((byte) 0x44);
        recordQuota("Z01", "GRANT +5 truoc fault START", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("Z02", "VERIFY truoc START fault", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z03", "START fault trong transaction", SW_INTERNAL, transmit(channel,
                visitCommand(INS_START_VISIT, faultVisit)).getSW());
        record("Z04", "VERIFY truoc START sau fault", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Z05", "START sau fault: balance va active visit cung rollback",
                transmit(channel, visitCommand(INS_START_VISIT, normalVisit)), 4, 2L);
        record("Z06", "VERIFY truoc END sau fault", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Z07", "END sau fault: ca mo dung visitId", transmit(channel,
                visitCommand(INS_END_VISIT, normalVisit)), 4, 3L);
    }

    private static void runVisitFaultAfterCommitCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("Z08", "chuan bi CAP fault sau commit cua START", false);
            return;
        }
        byte[] faultVisit = visitId(VISIT_FAULT_ID_BYTE);
        byte[] otherVisit = visitId((byte) 0x55);
        recordQuota("Z08", "GRANT +5 truoc fault sau commit", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("Z09", "VERIFY truoc START mat response", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z10", "START mat response sau commit", SW_INTERNAL, transmit(channel,
                visitCommand(INS_START_VISIT, faultVisit)).getSW());
        record("Z11", "VERIFY truoc START lan hai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z12", "START bi tu choi: commit da mo ca", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_START_VISIT, otherVisit)).getSW());
        record("Z13", "VERIFY truoc END dung visitId da commit", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Z14", "END dung visitId da commit: balance 4", transmit(channel,
                visitCommand(INS_END_VISIT, faultVisit)), 4, 3L);
        record("Z15", "VERIFY truoc END lan hai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z16", "END lai cung visitId", SW_CONDITIONS, transmit(channel,
                visitCommand(INS_END_VISIT, faultVisit)).getSW());
    }

    private static void runVisitCounterNearMaxCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("Z17", "chuan bi CAP counter gan tran cho START/END", false);
            return;
        }
        byte[] visit = visitId((byte) 0x66);
        recordQuota("Z17", "GRANT +5 tren counter FFFFFFFD", transmit(channel,
                grantCommand(adminPin, 5)), 5, 0xFFFFFFFEL);
        record("Z18", "VERIFY truoc START cham tran counter", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("Z19", "START dua counter len FFFFFFFF", transmit(channel,
                visitCommand(INS_START_VISIT, visit)), 4, 0xFFFFFFFFL);
        record("Z20", "VERIFY truoc END khi counter FFFFFFFF", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Z21", "END khi counter da FFFFFFFF", SW_CONDITIONS,
                transmit(channel, visitCommand(INS_END_VISIT, visit)).getSW());
    }

    private static Card runReceiptCases(String propertiesPath, CardChannel channel, byte[] adminPin,
            byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("K01", "chuan bi CAP receipt", false);
            return channel.getCard();
        }
        cardPublicKeyHex = readCardPublicKey(channel);
        byte[] visit = visitId((byte) 0x71);
        byte[] none = new byte[VISIT_ID_LENGTH];
        record("K01", "GET_LAST_RECEIPT khi chua VERIFY", SW_SECURITY,
                transmit(channel, receiptCommand()).getSW());
        record("K02", "VERIFY truoc GET_STATUS", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("K03", "GET_STATUS xen giua tieu quyen", SW_OK,
                transmit(channel, new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256)).getSW());
        record("K04", "GET_LAST_RECEIPT sau lenh xen giua", SW_SECURITY,
                transmit(channel, receiptCommand()).getSW());
        record("K05", "VERIFY truoc khi chua co receipt", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("K06", "GET_LAST_RECEIPT khi chua co receipt", SW_CONDITIONS,
                transmit(channel, receiptCommand()).getSW());
        recordQuota("K07", "GRANT +5 truoc chuoi receipt", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("K08", "VERIFY truoc doc receipt GRANT", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String grantPayload = recordReceipt("K09", "receipt GRANT: payload 34B + chu ky DER",
                transmit(channel, receiptCommand()), OPERATION_GRANT_QUOTA, 5, 5, 1L, none,
                "grant");
        record("K10", "VERIFY truoc Le thieu", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("K11", "GET_LAST_RECEIPT Le thieu", SW_WRONG_LENGTH,
                transmit(channel, receiptCommand(RECEIPT_PAYLOAD_LENGTH + 1)).getSW());
        record("K12", "VERIFY truoc doc lai sau Le thieu", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String grantAgain = recordReceipt("K13", "receipt khong doi sau Le thieu",
                transmit(channel, receiptCommand()), OPERATION_GRANT_QUOTA, 5, 5, 1L, none,
                "grant-again");
        recordBoolean("K14", "payload GRANT giu nguyen sau Le thieu",
                grantPayload.equals(grantAgain));
        record("K15", "VERIFY truoc START", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("K16", "START: balance 5 -> 4", transmit(channel,
                visitCommand(INS_START_VISIT, visit)), 4, 2L);
        record("K17", "VERIFY truoc doc receipt START", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String startPayload = recordReceipt("K18", "receipt START: delta -1, visitId vua mo",
                transmit(channel, receiptCommand()), OPERATION_START_VISIT, -1, 4, 2L, visit,
                "start");
        record("K19", "VERIFY truoc END", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordVisit("K20", "END: balance giu 4", transmit(channel,
                visitCommand(INS_END_VISIT, visit)), 4, 3L);
        record("K21", "VERIFY truoc doc receipt END", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String endPayload = recordReceipt("K22", "receipt END: delta 0, visitId vua dong",
                transmit(channel, receiptCommand()), OPERATION_END_VISIT, 0, 4, 3L, visit, "end");
        recordBoolean("K23", "receipt moi thay receipt cu",
                !grantPayload.equals(endPayload) && !startPayload.equals(endPayload));
        Card reset = resetCard(channel);
        CardChannel fresh = reset.getBasicChannel();
        selectTestInstance(fresh);
        record("K24", "VERIFY sau reconnect", SW_OK,
                transmit(fresh, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String reconnectPayload = recordReceipt("K25", "receipt sau reconnect: con nguyen",
                transmit(fresh, receiptCommand()), OPERATION_END_VISIT, 0, 4, 3L, visit,
                "end-reconnect");
        recordBoolean("K26", "payload sau reconnect giong truoc reconnect",
                endPayload.equals(reconnectPayload));
        return reset;
    }

    private static void runReceiptFaultBeforeCommitCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String variantsRoot) throws Exception {
        String capPath = variantCap(variantsRoot, "receipt-fault-before-commit");
        if (capPath == null
                || !prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("K27", "chuan bi CAP fault truoc commit cua receipt", false);
            return;
        }
        cardPublicKeyHex = readCardPublicKey(channel);
        byte[] none = new byte[VISIT_ID_LENGTH];
        recordQuota("K27", "GRANT +5 truoc fault receipt", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("K28", "VERIFY truoc doc receipt A", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String receiptA = recordReceipt("K29", "receipt A sau GRANT", transmit(channel,
                receiptCommand()), OPERATION_GRANT_QUOTA, 5, 5, 1L, none, "before-commit-A");
        record("K30", "GRANT 7777 fault truoc commit", SW_INTERNAL, transmit(channel,
                grantCommand(adminPin, FAULT_RECEIPT_BEFORE_COMMIT_DELTA)).getSW());
        record("K31", "VERIFY truoc doc lai receipt A", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String receiptKept = recordReceipt("K32", "receipt cu giu nguyen sau fault truoc commit",
                transmit(channel, receiptCommand()), OPERATION_GRANT_QUOTA, 5, 5, 1L, none,
                "before-commit-A-kept");
        recordBoolean("K33", "receipt cu khong doi sau fault truoc commit",
                receiptA.equals(receiptKept));
        recordQuota("K34", "GRANT +1 sau fault: state chua doi", transmit(channel,
                grantCommand(adminPin, 1)), 6, 2L);
        record("K35", "VERIFY truoc receipt sau fault", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        recordReceipt("K36", "receipt sau fault truoc commit", transmit(channel, receiptCommand()),
                OPERATION_GRANT_QUOTA, 1, 6, 2L, none, "before-commit-next");
    }

    private static void runReceiptFaultAfterCommitCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String variantsRoot) throws Exception {
        String capPath = variantCap(variantsRoot, "quota-fault-after-commit");
        if (capPath == null
                || !prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("K37", "chuan bi CAP fault sau commit cua receipt", false);
            return;
        }
        cardPublicKeyHex = readCardPublicKey(channel);
        byte[] none = new byte[VISIT_ID_LENGTH];
        recordQuota("K37", "GRANT +5 truoc fault sau commit", transmit(channel,
                grantCommand(adminPin, 5)), 5, 1L);
        record("K38", "VERIFY truoc doc receipt A", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String receiptA = recordReceipt("K39", "receipt A sau GRANT", transmit(channel,
                receiptCommand()), OPERATION_GRANT_QUOTA, 5, 5, 1L, none, "after-commit-A");
        record("K40", "GRANT 8888 mat response sau commit", SW_INTERNAL, transmit(channel,
                grantCommand(adminPin, FAULT_RECEIPT_AFTER_COMMIT_DELTA)).getSW());
        record("K41", "VERIFY truoc doc receipt moi", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String receiptNew = recordReceipt("K42", "receipt moi da commit du mat response",
                transmit(channel, receiptCommand()), OPERATION_GRANT_QUOTA,
                FAULT_RECEIPT_AFTER_COMMIT_DELTA, 8893, 2L, none, "after-commit-new");
        recordQuota("K43", "GRANT +1 sau fault: chi mot lan tang", transmit(channel,
                grantCommand(adminPin, 1)), 8894, 3L);
        record("K44", "VERIFY truoc receipt ke tiep", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        String receiptNext = recordReceipt("K45", "receipt ke tiep: counter 3, balance 8894",
                transmit(channel, receiptCommand()), OPERATION_GRANT_QUOTA, 1, 8894, 3L, none,
                "after-commit-next");
        recordBoolean("K46", "receipt moi khac receipt A", !receiptA.equals(receiptNew)
                && !receiptNew.equals(receiptNext));
    }

    private static void runReceiptSignatureSizeCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String variantsRoot) throws Exception {
        String capPath = variantCap(variantsRoot, "receipt-signature-too-long");
        if (capPath == null
                || !prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("K47", "chuan bi CAP chu ky qua dai", false);
            return;
        }
        record("K47", "GRANT khi chu ky 73 byte", SW_INTERNAL, transmit(channel,
                grantCommand(adminPin, 5)).getSW());
        record("K48", "VERIFY truoc khi doc receipt", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("K49", "chua co receipt nao duoc commit", SW_CONDITIONS,
                transmit(channel, receiptCommand()).getSW());
        record("K50", "GRANT lan hai van dung truoc commit", SW_INTERNAL, transmit(channel,
                grantCommand(adminPin, 5)).getSW());
        record("K51", "VERIFY truoc lan doc thu hai", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("K52", "van chua co receipt sau hai lan loi", SW_CONDITIONS,
                transmit(channel, receiptCommand()).getSW());
    }

    private static CommandAPDU receiptCommand() {
        return receiptCommand(RECEIPT_RESPONSE_MAX_LENGTH);
    }

    private static CommandAPDU receiptCommand(int le) {
        return new CommandAPDU(CLA, INS_GET_LAST_RECEIPT, 0, 0, le);
    }

    private static String readCardPublicKey(CardChannel channel) throws Exception {
        ResponseAPDU response = transmit(channel, publicKeyCommand());
        if (response.getSW() != SW_OK || response.getData().length != 65) {
            return null;
        }
        return quotaHex(response.getData());
    }

    private static String recordReceipt(String id, String label, ResponseAPDU response,
            int operation, int delta, int balance, long counter, byte[] visitId, String tag)
            throws Exception {
        byte[] data = response.getData();
        boolean swOk = response.getSW() == SW_OK;
        int signatureLength = swOk && data.length > RECEIPT_PAYLOAD_LENGTH
                ? (data[RECEIPT_PAYLOAD_LENGTH] & 0xFF) : -1;
        boolean shapeOk = swOk && signatureLength >= RECEIPT_SIGNATURE_MIN_LENGTH
                && signatureLength <= 72
                && data.length == RECEIPT_PAYLOAD_LENGTH + 1 + signatureLength
                && data[RECEIPT_PAYLOAD_LENGTH + 1] == 0x30;
        String payloadHex = swOk && data.length >= RECEIPT_PAYLOAD_LENGTH
                ? quotaHex(Arrays.copyOfRange(data, 0, RECEIPT_PAYLOAD_LENGTH)) : "-";
        String signatureHex = shapeOk
                ? quotaHex(Arrays.copyOfRange(data, RECEIPT_PAYLOAD_LENGTH + 1, data.length)) : "-";
        boolean fieldsOk = shapeOk
                && (data[RECEIPT_OFFSET_VERSION] & 0xFF) == 1
                && quotaHex(Arrays.copyOfRange(data, RECEIPT_OFFSET_CARD_ID,
                        RECEIPT_OFFSET_CARD_ID + 8)).equals(quotaHex(CARD_ID))
                && counterOfReceipt(data) == counter
                && (data[RECEIPT_OFFSET_OPERATION] & 0xFF) == operation
                && shortAt(data, RECEIPT_OFFSET_DELTA) == delta
                && shortAt(data, RECEIPT_OFFSET_BALANCE) == balance
                && quotaHex(Arrays.copyOfRange(data, RECEIPT_OFFSET_VISIT_ID,
                        RECEIPT_OFFSET_VISIT_ID + VISIT_ID_LENGTH)).equals(quotaHex(visitId));
        log(String.format("    receipt tag=%s payload=%s sigLen=%d", tag, payloadHex, signatureLength));
        recordBoolean(id, label, swOk && shapeOk && fieldsOk);
        if (swOk && data.length >= RECEIPT_PAYLOAD_LENGTH) {
            receiptArtifacts.add(String.format(
                    "{\"tag\":\"%s\",\"label\":\"%s\",\"payloadHex\":\"%s\","
                            + "\"signatureDerHex\":\"%s\",\"signatureLength\":%d,"
                            + "\"publicKeyHex\":\"%s\",\"expect\":{\"version\":1,"
                            + "\"cardIdHex\":\"%s\",\"counter\":%d,\"operation\":%d,"
                            + "\"delta\":%d,\"balance\":%d,\"visitIdHex\":\"%s\"}}",
                    tag, label, payloadHex, signatureHex, signatureLength, cardPublicKeyHex,
                    quotaHex(CARD_ID), counter, operation, delta, balance, quotaHex(visitId)));
        }
        return payloadHex;
    }

    private static long counterOfReceipt(byte[] data) {
        long value = 0;
        for (int index = RECEIPT_OFFSET_COUNTER; index < RECEIPT_OFFSET_OPERATION; index++) {
            value = (value << 8) | (data[index] & 0xFF);
        }
        return value;
    }

    private static int shortAt(byte[] data, int offset) {
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private static void writeReceiptArtifacts(String path) {
        if (path == null || receiptArtifacts.isEmpty()) {
            return;
        }
        try {
            java.nio.file.Path target = java.nio.file.Paths.get(path);
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.write(target, receiptArtifacts,
                    java.nio.charset.StandardCharsets.UTF_8);
            log("    receipt artifacts: " + receiptArtifacts.size() + " -> " + path);
        } catch (Exception exception) {
            failed++;
            log("    receipt artifacts write failed: " + exception.getClass().getSimpleName());
        }
    }

    private static CommandAPDU visitCommand(byte ins, byte[] visitId) {
        return visitCommand(ins, visitId, VISIT_RESPONSE_LENGTH);
    }

    private static CommandAPDU visitCommand(byte ins, byte[] visitId, int le) {
        return new CommandAPDU(CLA, ins, 0, 0, visitId, le);
    }

    private static byte[] visitId(byte seed) {
        byte[] id = new byte[VISIT_ID_LENGTH];
        for (int index = 0; index < VISIT_ID_LENGTH; index++) {
            id[index] = (byte) (seed + index);
        }
        return id;
    }

    private static void recordVisit(String id, String label, ResponseAPDU response,
            int expectedBalance, long expectedCounter) {
        byte[] data = response.getData();
        boolean matches = response.getSW() == SW_OK && data.length == VISIT_RESPONSE_LENGTH
                && balanceOf(data) == expectedBalance && counterOf(data) == expectedCounter;
        log(String.format("    visit balance=%d counter=%d hex=%s",
                data.length == VISIT_RESPONSE_LENGTH ? balanceOf(data) : -1,
                data.length == VISIT_RESPONSE_LENGTH ? counterOf(data) : -1L, quotaHex(data)));
        recordBoolean(id, label + " (balance " + expectedBalance + ", counter " + expectedCounter
                + ")", matches);
    }

    private static void runCounterNearMaxCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("V01", "chuan bi CAP counter gan tran", false);
            return;
        }
        recordQuota("V01", "GRANT +5 tren counter FFFFFFFE",
                transmit(channel, grantCommand(adminPin, 5)), 5, 0xFFFFFFFFL);
        record("V02", "GRANT khi counter da FFFFFFFF", SW_CONDITIONS,
                transmit(channel, grantCommand(adminPin, 5)).getSW());
        record("V03", "GRANT lai van bi tu choi", SW_CONDITIONS,
                transmit(channel, grantCommand(adminPin, 1)).getSW());
    }

    private static void runQuotaFaultBeforeCommitCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("W01", "chuan bi CAP fault truoc commit", false);
            return;
        }
        recordQuota("W01", "GRANT +5 truoc khi fault", transmit(channel, grantCommand(adminPin, 5)),
                5, 1L);
        record("W02", "GRANT +7777 fault trong transaction", SW_INTERNAL,
                transmit(channel, grantCommand(adminPin, FAULT_BEFORE_COMMIT_DELTA)).getSW());
        recordQuota("W03", "GRANT +3 sau fault: balance va counter cung rollback",
                transmit(channel, grantCommand(adminPin, 3)), 8, 2L);
    }

    private static void runQuotaFaultAfterCommitCase(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!prepareVariantInstance(propertiesPath, channel, adminPin, studentPin, capPath)) {
            recordBoolean("X01", "chuan bi CAP fault sau commit", false);
            return;
        }
        recordQuota("X01", "GRANT +5 truoc khi fault", transmit(channel, grantCommand(adminPin, 5)),
                5, 1L);
        record("X02", "GRANT +8888 mat response sau commit", SW_INTERNAL,
                transmit(channel, grantCommand(adminPin, FAULT_AFTER_COMMIT_DELTA)).getSW());
        recordQuota("X03", "GRANT +1: commit da ghi ca balance va counter",
                transmit(channel, grantCommand(adminPin, 1)),
                5 + FAULT_AFTER_COMMIT_DELTA + 1, 3L);
    }

    private static Card runPhotoFinishFaultCase(String propertiesPath, Card card,
            byte[] adminPin, byte[] studentPin, String variantsRoot) throws Exception {
        String capPath = variantCap(variantsRoot, "photo-finish-after-length-fault");
        if (capPath == null || !prepareVariantInstance(propertiesPath, card.getBasicChannel(),
                adminPin, studentPin, capPath)) {
            recordBoolean("P05-01", "chuan bi CAP fault sau photoLength", false);
            return card;
        }

        CardChannel channel = card.getBasicChannel();
        byte[] beginData = new byte[13];
        System.arraycopy(adminPin, 0, beginData, 0, adminPin.length);
        beginData[8] = 0x01;
        beginData[9] = 64;
        beginData[10] = 64;
        beginData[11] = (byte) (PHOTO_LENGTH >> 8);
        beginData[12] = (byte) PHOTO_LENGTH;
        byte[] faultPhoto = new byte[PHOTO_LENGTH];
        byte[] completePhoto = new byte[PHOTO_LENGTH];
        byte[] readBack = new byte[PHOTO_LENGTH];
        for (int index = 0; index < PHOTO_LENGTH; index++) {
            byte value = (byte) ((index * 37 + 11) & 0xFF);
            faultPhoto[index] = value;
            completePhoto[index] = value;
        }
        faultPhoto[0] = (byte) 0xA5;
        completePhoto[0] = (byte) 0x5A;
        try {
            int beginSw = transmit(channel, new CommandAPDU(CLA, INS_PHOTO_BEGIN,
                    0, 0, beginData)).getSW();
            record("P05-02", "lan upload loi: PHOTO_BEGIN tra 9000", SW_OK, beginSw);
            if (beginSw != SW_OK) {
                return card;
            }

            int writtenChunks = 0;
            int writeSw = SW_OK;
            for (int offset = 0; offset < PHOTO_LENGTH; offset += PHOTO_CHUNK_LENGTH) {
                byte[] chunk = Arrays.copyOfRange(faultPhoto, offset,
                        offset + PHOTO_CHUNK_LENGTH);
                ResponseAPDU response = transmit(channel, new CommandAPDU(CLA,
                        INS_PHOTO_WRITE_CHUNK, offset >> 8, offset & 0xFF, chunk));
                clear(chunk);
                writeSw = response.getSW();
                if (writeSw != SW_OK) {
                    break;
                }
                writtenChunks++;
            }
            recordBoolean("P05-03", "lan upload loi da ghi du 4096 byte qua 32 chunk",
                    writtenChunks == PHOTO_LENGTH / PHOTO_CHUNK_LENGTH && writeSw == SW_OK);
            if (writtenChunks != PHOTO_LENGTH / PHOTO_CHUNK_LENGTH || writeSw != SW_OK) {
                return card;
            }

            int interruptedSw = transmit(channel,
                    new CommandAPDU(CLA, INS_PHOTO_FINISH, 0, 0)).getSW();
            record("P05-04", "fault duoc chen sau lan ghi photoLength tra 6F00",
                    SW_INTERNAL, interruptedSw);
            if (interruptedSw != SW_INTERNAL) {
                return card;
            }

            card.disconnect(true);
            card = connect();
            channel = card.getBasicChannel();
            selectTestInstance(channel);
            int statusSw = transmit(channel,
                    new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256)).getSW();
            record("P05-05", "sau reconnect SELECT + GET_STATUS tra 9000", SW_OK, statusSw);
            if (statusSw != SW_OK) {
                return card;
            }

            int verifySw = transmit(channel,
                    pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW();
            record("P05-06", "VERIFY student sau reconnect tra 9000", SW_OK, verifySw);
            int partialReadSw = transmit(channel,
                    new CommandAPDU(CLA, INS_PHOTO_READ_CHUNK, 0, 0, PHOTO_CHUNK_LENGTH)).getSW();
            record("P05-07", "anh sau photoLength fault van khong ready (READ tra 6985)",
                    SW_CONDITIONS, partialReadSw);

            int retryBeginSw = transmit(channel, new CommandAPDU(CLA, INS_PHOTO_BEGIN,
                    0, 0, beginData)).getSW();
            record("P05-08", "PHOTO_BEGIN hop le sau fault tra 9000", SW_OK, retryBeginSw);
            if (retryBeginSw != SW_OK) {
                return card;
            }

            writtenChunks = 0;
            writeSw = SW_OK;
            for (int offset = 0; offset < PHOTO_LENGTH; offset += PHOTO_CHUNK_LENGTH) {
                byte[] chunk = Arrays.copyOfRange(completePhoto, offset,
                        offset + PHOTO_CHUNK_LENGTH);
                ResponseAPDU response = transmit(channel, new CommandAPDU(CLA,
                        INS_PHOTO_WRITE_CHUNK, offset >> 8, offset & 0xFF, chunk));
                clear(chunk);
                writeSw = response.getSW();
                if (writeSw != SW_OK) {
                    break;
                }
                writtenChunks++;
            }
            recordBoolean("P05-09", "upload lai ghi du 4096 byte qua 32 chunk",
                    writtenChunks == PHOTO_LENGTH / PHOTO_CHUNK_LENGTH && writeSw == SW_OK);
            if (writtenChunks != PHOTO_LENGTH / PHOTO_CHUNK_LENGTH || writeSw != SW_OK) {
                return card;
            }

            int finishSw = transmit(channel,
                    new CommandAPDU(CLA, INS_PHOTO_FINISH, 0, 0)).getSW();
            record("P05-10", "FINISH upload lai tra 9000", SW_OK, finishSw);
            if (finishSw != SW_OK) {
                return card;
            }

            verifySw = transmit(channel,
                    pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW();
            record("P05-11", "VERIFY student truoc doc anh hoan chinh tra 9000", SW_OK, verifySw);
            int readChunks = 0;
            int bytesRead = 0;
            int readSw = SW_OK;
            boolean chunkLengthsValid = true;
            for (int offset = 0; offset < PHOTO_LENGTH; offset += PHOTO_CHUNK_LENGTH) {
                ResponseAPDU response = transmit(channel, new CommandAPDU(CLA,
                        INS_PHOTO_READ_CHUNK, offset >> 8, offset & 0xFF, PHOTO_CHUNK_LENGTH));
                readSw = response.getSW();
                if (readSw != SW_OK) {
                    break;
                }
                byte[] data = response.getData();
                chunkLengthsValid &= data.length == PHOTO_CHUNK_LENGTH;
                if (bytesRead + data.length > readBack.length) {
                    chunkLengthsValid = false;
                    break;
                }
                System.arraycopy(data, 0, readBack, bytesRead, data.length);
                bytesRead += data.length;
                readChunks++;
                clear(data);
            }
            recordBoolean("P05-12", "doc lai du 4096 byte qua 32 chunk",
                    readSw == SW_OK && readChunks == PHOTO_LENGTH / PHOTO_CHUNK_LENGTH
                            && bytesRead == PHOTO_LENGTH && chunkLengthsValid);
            recordBoolean("P05-13", "4096 byte doc lai khop tung byte voi anh nguon",
                    readSw == SW_OK && bytesRead == PHOTO_LENGTH
                            && Arrays.equals(completePhoto, readBack));
            return card;
        } finally {
            clear(beginData);
            clear(faultPhoto);
            clear(completePhoto);
            clear(readBack);
        }
    }

    private static boolean prepareVariantInstance(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin, String capPath) throws Exception {
        if (!removeTestInstance(propertiesPath, channel)
                || !removeTestPackage(propertiesPath, channel)) {
            return false;
        }
        if (!loadTestPackage(propertiesPath, channel, capPath)
                || !installTestInstance(propertiesPath, channel, adminPin)) {
            return false;
        }
        selectTestInstance(channel);
        byte[] payload = validPayload(adminPin, studentPin);
        boolean personalized = transmit(channel, personalizeCommand(payload)).getSW() == SW_OK;
        clear(payload);
        return personalized;
    }

    private static CommandAPDU grantCommand(byte[] adminPin, int delta) {
        return grantCommand(adminPin, delta, QUOTA_RESPONSE_LENGTH);
    }

    private static CommandAPDU grantCommand(byte[] adminPin, int delta, int le) {
        return new CommandAPDU(CLA, INS_GRANT_QUOTA, 0, 0, grantData(adminPin, delta), le);
    }

    private static byte[] grantData(byte[] adminPin, int delta) {
        byte[] data = new byte[GRANT_DATA_LENGTH];
        System.arraycopy(adminPin, 0, data, 0, adminPin.length);
        data[GRANT_DELTA_OFFSET] = (byte) ((delta >> 8) & 0xFF);
        data[GRANT_DELTA_OFFSET + 1] = (byte) (delta & 0xFF);
        return data;
    }

    private static void recordQuota(String id, String label, ResponseAPDU response,
            int expectedBalance, long expectedCounter) {
        byte[] data = response.getData();
        boolean matches = response.getSW() == SW_OK && data.length == QUOTA_RESPONSE_LENGTH
                && balanceOf(data) == expectedBalance && counterOf(data) == expectedCounter;
        log(String.format("    balance=%d counter=%d hex=%s",
                data.length == QUOTA_RESPONSE_LENGTH ? balanceOf(data) : -1,
                data.length == QUOTA_RESPONSE_LENGTH ? counterOf(data) : -1L, quotaHex(data)));
        recordBoolean(id, label + " (balance " + expectedBalance + ", counter "
                + expectedCounter + ")", matches);
    }

    private static String quotaHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < data.length; index++) {
            builder.append(String.format("%02X", data[index] & 0xFF));
        }
        return builder.toString();
    }

    private static int balanceOf(byte[] data) {
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    private static long counterOf(byte[] data) {
        long value = 0;
        for (int index = STATE_COUNTER_OFFSET; index < STATE_RESPONSE_LENGTH; index++) {
            value = (value << 8) | (data[index] & 0xFF);
        }
        return value;
    }

    private static Card runAuthorizationResetCase(Card card, byte[] studentPin) throws Exception {
        record("Q19", "VERIFY truoc reset", SW_OK,
                transmit(card.getBasicChannel(),
                        pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        card.disconnect(true);
        Card reset = connect();
        CardChannel channel = reset.getBasicChannel();
        selectTestInstance(channel);
        record("Q20", "quyen bi xoa sau reset", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());
        record("Q21", "VERIFY cho ca thieu Le", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("Q22", "GET_PROFILE thieu Le", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PROFILE, 0, 0)).getSW());
        record("Q23", "quyen bi xoa sau thieu Le", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());
        return reset;
    }

    private static void runTamperCases(String propertiesPath, CardChannel channel, byte[] adminPin,
            byte[] studentPin, String variantsRoot) throws Exception {
        String[] modes = { "ciphertext", "tag", "nonce" };
        String[] ids = { "R01", "R02", "R03" };
        for (int index = 0; index < modes.length; index++) {
            String capPath = variantCap(variantsRoot, modes[index]);
            if (capPath == null) {
                recordBoolean(ids[index], "thieu CAP tamper " + modes[index], false);
                continue;
            }
            runTamperCase(propertiesPath, channel, adminPin, studentPin, capPath,
                    modes[index], ids[index]);
        }
    }

    private static void runTamperCase(String propertiesPath, CardChannel channel, byte[] adminPin,
            byte[] studentPin, String capPath, String mode, String id) throws Exception {
        if (!removeTestInstance(propertiesPath, channel)
                || !removeTestPackage(propertiesPath, channel)) {
            recordBoolean(id, "cleanup truoc CAP tamper " + mode, false);
            return;
        }
        if (!loadTestPackage(propertiesPath, channel, capPath)) {
            recordBoolean(id, "LOAD CAP tamper " + mode, false);
            return;
        }
        if (!installTestInstance(propertiesPath, channel, adminPin)) {
            recordBoolean(id, "INSTALL instance tren CAP tamper " + mode, false);
            return;
        }
        selectTestInstance(channel);
        byte[] payload = validPayload(adminPin, studentPin);
        record(id + "-A", "PERSONALIZE tren CAP " + mode, SW_OK,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);
        record(id + "-B", "GET_PROFILE truoc VERIFY", SW_SECURITY,
                transmit(channel, profileCommand()).getSW());
        record(id + "-C", "VERIFY tren CAP " + mode, SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU tampered = transmit(channel, profileCommand());
        recordBoolean(id + "-D", "GET_PROFILE tu choi ho so bi sua " + mode,
                tampered.getSW() == SW_INTERNAL && tampered.getData().length == 0);
        checkStatus(channel, id + "-E", LIFECYCLE_ISSUED, CARD_ID);
        ResponseAPDU publicKey = transmit(channel, publicKeyCommand());
        recordBoolean(id + "-F", "public key van doc duoc sau " + mode,
                publicKey.getSW() == SW_OK && publicKey.getData().length == 65);
        clear(publicKey.getData());
        record(id + "-G", "VERIFY tren CAP " + mode + " cho META", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        ResponseAPDU tamperedMeta = transmit(channel, metaCommand());
        recordBoolean(id + "-H", "META tu choi ho so bi sua " + mode,
                tamperedMeta.getSW() == SW_INTERNAL && tamperedMeta.getData().length == 0);
        record(id + "-I", "quyen bi xoa sau META loi", SW_SECURITY,
                transmit(channel, metaCommand()).getSW());
    }

    private static boolean profileMatches(byte[] profile) {
        byte[] expected = expectedProfile();
        boolean matches = Arrays.equals(expected, profile);
        clear(expected);
        return matches;
    }

    private static byte[] expectedProfile() {
        byte[] buffer = new byte[1 + 1 + STUDENT_ID.length + 1 + DISPLAY_NAME.length];
        int offset = 0;
        buffer[offset++] = 1;
        buffer[offset++] = (byte) STUDENT_ID.length;
        System.arraycopy(STUDENT_ID, 0, buffer, offset, STUDENT_ID.length);
        offset += STUDENT_ID.length;
        buffer[offset++] = (byte) DISPLAY_NAME.length;
        System.arraycopy(DISPLAY_NAME, 0, buffer, offset, DISPLAY_NAME.length);
        return buffer;
    }

    private static void runCases(CardChannel channel, byte[] adminPin, byte[] studentPin,
            byte[] unblockedPin, byte[] changedPin) throws Exception {
        checkStatus(channel, "T01", LIFECYCLE_UNPERSONALIZED, null);
        record("T02", "GET_PUBLIC_KEY truoc PERSONALIZE", SW_CONDITIONS,
                transmit(channel, publicKeyCommand()).getSW());
        record("T02A", "GRANT_QUOTA truoc PERSONALIZE", SW_CONDITIONS,
                transmit(channel, grantCommand(adminPin, 5)).getSW());
        record("T02B", "SIGN_REGISTRATION_CHALLENGE truoc PERSONALIZE", SW_CONDITIONS,
                transmit(channel, enrollmentCommand(enrollmentNonce(1))).getSW());

        byte[] payload = validPayload(adminPin, studentPin);
        payload[0] = 7;
        record("T03", "adminPinLength sai", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin, studentPin);
        Arrays.fill(payload, 9, 17, (byte) 0);
        record("T04", "cardId toan 00", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin, studentPin);
        payload[19] = (byte) '@';
        record("T05", "studentId co ky tu khong hop le", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        for (String invalid : new String[] {"AB12345", "AB123456789", "SV2024-01",
                "SV2024_01", " SV2024001", "SV2024001 "}) {
            payload = validPayload(adminPin, studentPin, ascii(invalid));
            record("T05-ID", "studentId ngoai quy tac", SW_WRONG_DATA,
                    transmit(channel, personalizeCommand(payload)).getSW());
            clear(payload);
        }

        payload = validPayload(adminPin, studentPin);
        int studentPinOffset = 18 + (payload[17] & 0xFF);
        payload[studentPinOffset] = (byte) 'A';
        record("T06", "student PIN sai dinh dang", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin, studentPin);
        int nameLengthOffset = studentPinOffset + 6;
        payload[nameLengthOffset] = 0;
        record("T07", "displayNameLength bang 0", SW_WRONG_DATA,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);

        payload = validPayload(adminPin, studentPin);
        byte[] truncated = Arrays.copyOf(payload, payload.length - 1);
        record("T08", "payload thieu 1 byte", SW_WRONG_LENGTH,
                transmit(channel, personalizeCommand(truncated)).getSW());
        clear(payload);
        clear(truncated);
        checkStatus(channel, "T09", LIFECYCLE_UNPERSONALIZED, new byte[8]);

        byte[] wrongPin = adminPin.clone();
        wrongPin[0] = wrongPin[0] == (byte) '9' ? (byte) '8' : (byte) (wrongPin[0] + 1);
        payload = validPayload(wrongPin, studentPin);
        clear(wrongPin);
        record("T10", "admin PIN sai lan 1", SW_RETRY_BASE | 4,
                transmit(channel, personalizeCommand(payload)).getSW());
        record("T11", "admin PIN sai lan 2", SW_RETRY_BASE | 3,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);
        checkStatus(channel, "T12", LIFECYCLE_UNPERSONALIZED, new byte[8]);

        byte[] photoBeginData = new byte[13];
        System.arraycopy(adminPin, 0, photoBeginData, 0, adminPin.length);
        photoBeginData[8] = 0x01;
        photoBeginData[9] = 64;
        photoBeginData[10] = 64;
        photoBeginData[11] = (byte) (PHOTO_LENGTH >> 8);
        photoBeginData[12] = (byte) PHOTO_LENGTH;
        record("T12A", "PHOTO_BEGIN hop le truoc PERSONALIZE bi chan", SW_CONDITIONS,
                transmit(channel, new CommandAPDU(CLA, INS_PHOTO_BEGIN, 0, 0,
                        photoBeginData)).getSW());
        byte[] photoChunk = new byte[PHOTO_CHUNK_LENGTH];
        record("T12B", "WRITE ngay sau BEGIN bi chan khong co session", SW_CONDITIONS,
                transmit(channel, new CommandAPDU(CLA, INS_PHOTO_WRITE_CHUNK, 0, 0,
                        photoChunk)).getSW());
        clear(photoBeginData);
        clear(photoChunk);

        payload = validPayload(adminPin, studentPin);
        payload[18] = (byte) 's';
        payload[19] = (byte) 'v';
        record("T13", "PERSONALIZE admin dung", SW_OK,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);
        byte[] photoWriteWithoutBegin = new byte[PHOTO_CHUNK_LENGTH];
        record("T13A", "WRITE sau PERSONALIZE khi chua BEGIN bi chan", SW_CONDITIONS,
                transmit(channel, new CommandAPDU(CLA, INS_PHOTO_WRITE_CHUNK, 0, 0,
                        photoWriteWithoutBegin)).getSW());
        clear(photoWriteWithoutBegin);

        checkStatus(channel, "T14", LIFECYCLE_ISSUED, CARD_ID);
        ResponseAPDU publicKeyResponse = transmit(channel, publicKeyCommand());
        record("T15", "GET_PUBLIC_KEY sau PERSONALIZE", SW_OK, publicKeyResponse.getSW());
        byte[] publicKey = publicKeyResponse.getData();
        boolean keyShape = publicKeyResponse.getSW() == SW_OK
                && publicKey.length == 65 && publicKey[0] == 0x04;
        recordBoolean("T16", "public key 65 byte uncompressed", keyShape);
        if (keyShape) {
            verifyP256Point(publicKey);
        }

        payload = validPayload(adminPin, studentPin);
        record("T17", "PERSONALIZE lan hai", SW_CONDITIONS,
                transmit(channel, personalizeCommand(payload)).getSW());
        clear(payload);
        ResponseAPDU secondKeyResponse = transmit(channel, publicKeyCommand());
        recordBoolean("T18", "public key khong thay doi",
                secondKeyResponse.getSW() == SW_OK
                        && Arrays.equals(publicKey, secondKeyResponse.getData()));
        clear(publicKey);
        clear(secondKeyResponse.getData());

        ResponseAPDU maxLengthResponse = transmit(channel,
                new CommandAPDU(CLA, INS_GET_PUBLIC_KEY, 0, 0, 256));
        byte[] maxLengthKey = maxLengthResponse.getData();
        recordBoolean("T19", "GET_PUBLIC_KEY Le=00 tra 65 byte",
                maxLengthResponse.getSW() == SW_OK && maxLengthKey.length == 65
                        && maxLengthKey[0] == 0x04);
        clear(maxLengthKey);

        record("T20", "GET_PUBLIC_KEY Le=64", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_GET_PUBLIC_KEY, 0, 0, 64)).getSW());

        record("P01", "VERIFY PIN dung", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("P02", "VERIFY P1 sai", 0x6A86,
                transmit(channel, new CommandAPDU(CLA, INS_VERIFY_STUDENT_PIN, 1, 0,
                        studentPin)).getSW());
        byte[] malformedPin = studentPin.clone();
        malformedPin[2] = (byte) 'X';
        record("P03", "VERIFY PIN sai format", SW_WRONG_DATA,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, malformedPin)).getSW());
        clear(malformedPin);
        record("P04", "VERIFY payload thieu", SW_WRONG_LENGTH,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN,
                        Arrays.copyOf(studentPin, 5))).getSW());
        byte[] extraVerifyData = Arrays.copyOf(studentPin, 7);
        record("P05", "VERIFY payload thua", SW_WRONG_LENGTH,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, extraVerifyData)).getSW());
        clear(extraVerifyData);
        record("P06", "VERIFY Case 4 Le=00 bi tu choi", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_VERIFY_STUDENT_PIN, 0, 0,
                        studentPin, 256)).getSW());
        byte[] wrongStudentPin = differentPin(studentPin);
        record("P07", "VERIFY sai lan 1", SW_RETRY_BASE | 2,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, wrongStudentPin)).getSW());
        record("P08", "VERIFY sai lan 2", SW_RETRY_BASE | 1,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, wrongStudentPin)).getSW());
        record("P09", "VERIFY sai lan 3 khoa PIN", 0x6983,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, wrongStudentPin)).getSW());
        record("P10", "VERIFY dung sau khi khoa", 0x6983,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());

        byte[] unblockPayload = combine(adminPin, unblockedPin);
        record("P11", "UNBLOCK payload thieu", SW_WRONG_LENGTH,
                transmit(channel, pinCommand(INS_UNBLOCK_STUDENT_PIN,
                        Arrays.copyOf(unblockPayload, unblockPayload.length - 1))).getSW());
        byte[] extraUnblock = Arrays.copyOf(unblockPayload, unblockPayload.length + 1);
        record("P11A", "UNBLOCK payload thua", SW_WRONG_LENGTH,
                transmit(channel, pinCommand(INS_UNBLOCK_STUDENT_PIN, extraUnblock)).getSW());
        clear(extraUnblock);
        byte[] malformedAdmin = unblockPayload.clone();
        malformedAdmin[0] = (byte) 'X';
        record("P11B", "UNBLOCK admin sai format", SW_WRONG_DATA,
                transmit(channel, pinCommand(INS_UNBLOCK_STUDENT_PIN, malformedAdmin)).getSW());
        clear(malformedAdmin);
        byte[] wrongAdminPin = differentPin(adminPin);
        byte[] wrongUnblockPayload = combine(wrongAdminPin, unblockedPin);
        clear(wrongAdminPin);
        record("P11C", "UNBLOCK admin sai", SW_RETRY_BASE | 4,
                transmit(channel, pinCommand(INS_UNBLOCK_STUDENT_PIN,
                        wrongUnblockPayload)).getSW());
        clear(wrongUnblockPayload);
        record("P12", "student van khoa sau admin sai", 0x6983,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("P13", "UNBLOCK admin dung trong cung APDU", SW_OK,
                transmit(channel, pinCommand(INS_UNBLOCK_STUDENT_PIN,
                        unblockPayload)).getSW());
        clear(unblockPayload);
        record("P14", "VERIFY PIN cu sau UNBLOCK", SW_RETRY_BASE | 2,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
        record("P15", "VERIFY PIN moi sau UNBLOCK", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, unblockedPin)).getSW());

        byte[] changePayload = combine(unblockedPin, changedPin);
        record("P15A", "CHANGE payload thieu", SW_WRONG_LENGTH,
                transmit(channel, pinCommand(INS_CHANGE_STUDENT_PIN,
                        Arrays.copyOf(changePayload, changePayload.length - 1))).getSW());
        byte[] extraChange = Arrays.copyOf(changePayload, changePayload.length + 1);
        record("P15B", "CHANGE payload thua", SW_WRONG_LENGTH,
                transmit(channel, pinCommand(INS_CHANGE_STUDENT_PIN, extraChange)).getSW());
        clear(extraChange);
        record("P15C", "CHANGE P2 sai", 0x6A86,
                transmit(channel, new CommandAPDU(CLA, INS_CHANGE_STUDENT_PIN, 0, 1,
                        changePayload)).getSW());
        record("P16", "CHANGE PIN cu/moi", SW_OK,
                transmit(channel, pinCommand(INS_CHANGE_STUDENT_PIN, changePayload)).getSW());
        clear(changePayload);
        record("P17", "VERIFY PIN cu sau CHANGE", SW_RETRY_BASE | 2,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, unblockedPin)).getSW());
        record("P18", "VERIFY PIN moi sau CHANGE", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, changedPin)).getSW());

        byte[] malformedChange = combine(changedPin, unblockedPin);
        malformedChange[changedPin.length + 1] = (byte) 'X';
        record("P19", "CHANGE PIN moi sai format", SW_WRONG_DATA,
                transmit(channel, pinCommand(INS_CHANGE_STUDENT_PIN,
                        malformedChange)).getSW());
        clear(malformedChange);
        record("P20", "PIN cu van dung sau CHANGE sai format", SW_OK,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, changedPin)).getSW());

        byte[] wrongChange = combine(unblockedPin, studentPin);
        record("P21", "CHANGE PIN cu sai tieu retry", SW_RETRY_BASE | 2,
                transmit(channel, pinCommand(INS_CHANGE_STUDENT_PIN, wrongChange)).getSW());
        clear(wrongChange);
        byte[] finalChange = combine(changedPin, studentPin);
        record("P22", "CHANGE PIN dung reset retry", SW_OK,
                transmit(channel, pinCommand(INS_CHANGE_STUDENT_PIN, finalChange)).getSW());
        clear(finalChange);
        byte[] wrongFinal = differentPin(studentPin);
        record("P23", "retry sau CHANGE thanh cong bat dau tu 3", SW_RETRY_BASE | 2,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, wrongFinal)).getSW());
        clear(wrongFinal);
        clear(wrongStudentPin);
    }

    private static void runEnrollmentCases(CardChannel channel, byte[] studentPin) throws Exception {
        String publicKeyHex = readCardPublicKey(channel);
        byte[] publicKey = publicKeyHex == null ? null : hex(publicKeyHex);
        recordBoolean("E01", "doc duoc public key truoc khi ky", publicKey != null);

        byte[] nonce = enrollmentNonce(1);
        ResponseAPDU response = transmit(channel, enrollmentCommand(nonce));
        record("E02", "SIGN_REGISTRATION_CHALLENGE hop le", SW_OK, response.getSW());
        byte[] signature = response.getSW() == SW_OK ? response.getData() : new byte[0];
        boolean derShape = signature.length >= 8 && signature.length <= 72
                && signature[0] == 0x30 && (signature[1] & 0xFF) == signature.length - 2;
        recordBoolean("E03", "chu ky DER dung hinh dang len=" + signature.length, derShape);

        recordBoolean("E04", "Node verify chu ky that tren Simulator", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(signature),
                "valid", true));
        recordBoolean("E05", "sai nonce bi tu choi", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(signature),
                "nonce", false));
        recordBoolean("E06", "sai cardId bi tu choi", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(signature),
                "cardId", false));
        recordBoolean("E07", "public key khac bi tu choi", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(signature),
                "key", false));
        recordBoolean("E08", "domain khac bi tu choi", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(signature),
                "domain", false));
        recordBoolean("E09", "sua 1 byte chu ky bi tu choi", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(signature),
                "sig", false));

        byte[] otherNonce = enrollmentNonce(2);
        byte[] otherSignature = transmit(channel, enrollmentCommand(otherNonce)).getData();
        recordBoolean("E10", "nonce khac cho chu ky khac",
                !encodeHex(otherSignature).equals(encodeHex(signature)));
        recordBoolean("E11", "chu ky nonce thu hai verify duoc", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(otherNonce),
                encodeHex(otherSignature), "valid", true));
        writeEnrollmentArtifact(publicKeyHex, encodeHex(nonce), encodeHex(signature),
                encodeHex(otherNonce), encodeHex(otherSignature));

        record("E12", "Lc=31 bi tu choi", SW_WRONG_LENGTH,
                transmit(channel, enrollmentCommand(Arrays.copyOf(nonce, 31))).getSW());
        record("E13", "Lc=33 bi tu choi", SW_WRONG_LENGTH,
                transmit(channel, enrollmentCommand(Arrays.copyOf(nonce, 33))).getSW());
        record("E14", "P1 khac 00 bi tu choi", 0x6A86,
                transmit(channel, new CommandAPDU(CLA, INS_SIGN_ENROLLMENT, 1, 0, nonce, 256))
                        .getSW());
        record("E15", "Le=71 bi tu choi", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_SIGN_ENROLLMENT, 0, 0, nonce, 71))
                        .getSW());
        ResponseAPDU minLe = transmit(channel,
                new CommandAPDU(CLA, INS_SIGN_ENROLLMENT, 0, 0, nonce, 72));
        record("E16", "Le=72 duoc chap nhan", SW_OK, minLe.getSW());
        recordBoolean("E17", "Le=72 van verify duoc", nodeVerifyEnrollment(
                publicKeyHex, encodeHex(CARD_ID), encodeHex(nonce), encodeHex(minLe.getData()),
                "valid", true));
        record("E18", "khong gui Le bi tu choi", SW_WRONG_LENGTH,
                transmit(channel, new CommandAPDU(CLA, INS_SIGN_ENROLLMENT, 0, 0, nonce)).getSW());

        record("E19", "khong tao receipt", SW_CONDITIONS,
                transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW() == SW_OK
                        ? transmit(channel, receiptCommand(256)).getSW() : -1);

        clear(publicKey);
        clear(nonce);
        clear(otherNonce);
        clear(otherSignature);
        clear(signature);
    }

    private static CommandAPDU enrollmentCommand(byte[] nonce) {
        return new CommandAPDU(CLA, INS_SIGN_ENROLLMENT, 0, 0, nonce, 256);
    }

    private static void writeEnrollmentArtifact(String publicKeyHex, String nonceHex,
            String signatureHex, String secondNonceHex, String secondSignatureHex) {
        String line = "{\"cardId\":\"" + encodeHex(CARD_ID) + "\",\"publicKey\":\"" + publicKeyHex
                + "\",\"nonce\":\"" + nonceHex + "\",\"signature\":\"" + signatureHex
                + "\",\"secondNonce\":\"" + secondNonceHex + "\",\"secondSignature\":\""
                + secondSignatureHex + "\"}";
        try {
            java.nio.file.Path target = java.nio.file.Paths.get("applet/build/enrollment-artifacts.jsonl");
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.write(target, List.of(line),
                    java.nio.charset.StandardCharsets.UTF_8);
            log("    enrollment artifact -> " + target);
        } catch (Exception exception) {
            failed++;
            log("    enrollment artifact write failed: " + exception.getClass().getSimpleName());
        }
    }

    private static byte[] enrollmentNonce(int seed) {
        byte[] nonce = new byte[32];
        for (int index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) (seed * 0x21 + index + 1);
        }
        return nonce;
    }

    private static boolean nodeVerifyEnrollment(String publicKeyHex, String cardIdHex,
            String nonceHex, String signatureHex, String mode, boolean expected) throws Exception {
        if (publicKeyHex == null || signatureHex == null || signatureHex.isEmpty()) {
            return false;
        }
        String script = "const c=require('crypto');"
                + "const [pk,cardArg,nonceArg,sig,mode]=process.argv.slice(1);"
                + "const point=Buffer.from(pk,'hex');"
                + "const hash=c.createHash('sha256').update(point).digest();"
                + "let cardId=Buffer.from(cardArg,'hex');"
                + "let nonce=Buffer.from(nonceArg,'hex');"
                + "let domain=Buffer.from('LABACCESS-ENROLLMENT-v1','ascii');"
                + "let keyPoint=point;"
                + "if(mode==='cardId'){cardId[0]^=1;}"
                + "if(mode==='nonce'){nonce[0]^=1;}"
                + "if(mode==='domain'){domain=Buffer.from('LABACCESS-ENROLLMENT-v2','ascii');}"
                + "if(mode==='key'){keyPoint=Buffer.from(c.createECDH('prime256v1').generateKeys());}"
                + "let signature=Buffer.from(sig,'hex');"
                + "if(mode==='sig'){signature[signature.length-1]^=1;}"
                + "const key=c.createPublicKey({key:{kty:'EC',crv:'P-256',"
                + "x:keyPoint.subarray(1,33).toString('base64url'),"
                + "y:keyPoint.subarray(33,65).toString('base64url')},format:'jwk'});"
                + "const message=Buffer.concat([domain,cardId,hash,nonce]);"
                + "if(message.length!==95)process.exit(2);"
                + "const ok=c.verify(null,message,{key,dsaEncoding:'der'},signature);"
                + "process.exit(ok===" + (expected ? "true" : "false") + "?0:1);";
        Process process = new ProcessBuilder("node", "-e", script, publicKeyHex, cardIdHex,
                nonceHex, signatureHex, mode).inheritIO().start();
        return process.waitFor() == 0;
    }

    private static void selectTestInstance(CardChannel channel) throws Exception {
        selectAid(channel, TEST_AID_HEX);
    }

    private static void selectAid(CardChannel channel, String aidHex) throws Exception {
        ResponseAPDU response = channel.transmit(
                new CommandAPDU(0x00, 0xA4, 0x04, 0x00, hex(aidHex), 256));
        if (response.getSW() != SW_OK) {
            throw new IllegalStateException("SELECT failed for " + aidHex);
        }
    }

    private static void checkStatus(CardChannel channel, String id, int expectedLifecycle,
            byte[] expectedCardId) throws Exception {
        ResponseAPDU response = transmit(channel,
                new CommandAPDU(CLA, INS_GET_STATUS, 0, 0, 256));
        record(id, "GET_STATUS", SW_OK, response.getSW());
        byte[] data = response.getData();
        int lifecycleOffset = data.length > 10 ? 11 + (data[10] & 0xFF) : data.length;
        boolean valid = response.getSW() == SW_OK && data.length >= lifecycleOffset + 1
                && (data[lifecycleOffset] & 0xFF) == expectedLifecycle;
        if (expectedCardId != null) {
            valid &= data.length >= lifecycleOffset + 1 + expectedCardId.length;
            if (valid) {
                valid &= Arrays.equals(expectedCardId,
                        Arrays.copyOfRange(data, lifecycleOffset + 1,
                                lifecycleOffset + 1 + expectedCardId.length));
            }
        }
        recordBoolean(id + "-STATE", "lifecycle/cardId", valid);
        clear(data);
    }

    private static void verifyP256Point(byte[] point) throws Exception {
        log("    publicKey=" + encodeHex(point));
        if (point.length != 65 || point[0] != 0x04) {
            recordBoolean("T16-P256", "Node crypto chap nhan P-256", false);
            return;
        }
        String script = "const c=require('crypto');const p=Buffer.from(process.argv[1],'hex');"
                + "const k=c.createPublicKey({key:{kty:'EC',crv:'P-256',"
                + "x:p.subarray(1,33).toString('base64url'),"
                + "y:p.subarray(33,65).toString('base64url')},format:'jwk'});"
                + "if(k.asymmetricKeyType!=='ec'||"
                + "k.asymmetricKeyDetails.namedCurve!=='prime256v1')process.exit(1);";
        Process process = new ProcessBuilder("node", "-e", script, encodeHex(point))
                .inheritIO()
                .start();
        recordBoolean("T16-P256", "Node crypto chap nhan P-256", process.waitFor() == 0);
    }

    private static byte[] validPayload(byte[] adminPin, byte[] studentPin) {
        return validPayload(adminPin, studentPin, STUDENT_ID);
    }

    private static byte[] validPayload(byte[] adminPin, byte[] studentPin, byte[] studentId) {
        byte[] payload = new byte[25 + studentId.length + DISPLAY_NAME.length];
        int offset = 0;
        payload[offset++] = 8;
        System.arraycopy(adminPin, 0, payload, offset, 8);
        offset += 8;
        System.arraycopy(CARD_ID, 0, payload, offset, CARD_ID.length);
        offset += CARD_ID.length;
        payload[offset++] = (byte) studentId.length;
        System.arraycopy(studentId, 0, payload, offset, studentId.length);
        offset += studentId.length;
        System.arraycopy(studentPin, 0, payload, offset, studentPin.length);
        offset += studentPin.length;
        payload[offset++] = (byte) DISPLAY_NAME.length;
        System.arraycopy(DISPLAY_NAME, 0, payload, offset, DISPLAY_NAME.length);
        return payload;
    }

    private static void runStudentIdBoundaryCases(String propertiesPath, CardChannel channel,
            byte[] adminPin, byte[] studentPin) throws Exception {
        for (String input : new String[] {"ab123456", "Ab12345678"}) {
            if (!removeTestInstance(propertiesPath, channel)
                    || !installTestInstance(propertiesPath, channel, adminPin)) {
                throw new IllegalStateException("studentId boundary setup failed");
            }
            selectTestInstance(channel);
            byte[] payload = validPayload(adminPin, studentPin, ascii(input));
            try {
                record("ID-ISSUE", "PERSONALIZE studentId length " + input.length(), SW_OK,
                        transmit(channel, personalizeCommand(payload)).getSW());
            } finally {
                clear(payload);
            }
            record("ID-VERIFY", "VERIFY truoc doc studentId", SW_OK,
                    transmit(channel, pinCommand(INS_VERIFY_STUDENT_PIN, studentPin)).getSW());
            ResponseAPDU response = transmit(channel, profileCommand());
            byte[] profile = response.getData();
            String expected = input.length() == 8 ? "AB123456" : "AB12345678";
            recordBoolean("ID-UPPER", "GET_PROFILE studentId in hoa dung bien",
                    response.getSW() == SW_OK && profile.length >= 2 + expected.length()
                            && profile[1] == expected.length()
                            && Arrays.equals(Arrays.copyOfRange(profile, 2, 2 + expected.length()),
                                    ascii(expected)));
            clear(profile);
        }
    }

    private static CommandAPDU personalizeCommand(byte[] data) {
        return new CommandAPDU(CLA, INS_PERSONALIZE, 0, 0, data, 256);
    }

    private static CommandAPDU publicKeyCommand() {
        return new CommandAPDU(CLA, INS_GET_PUBLIC_KEY, 0, 0, 65);
    }

    private static CommandAPDU profileCommand() {
        return new CommandAPDU(CLA, INS_GET_PROFILE, 0, 0, 256);
    }

    private static ResponseAPDU transmit(CardChannel channel, CommandAPDU command) throws Exception {
        log(String.format("    -> %02X %02X %02X %02X dataLen=%d", command.getCLA(),
                command.getINS(), command.getP1(), command.getP2(), command.getData().length));
        ResponseAPDU response = channel.transmit(command);
        log(String.format("    <- SW=%04X dataLen=%d", response.getSW(), response.getData().length));
        return response;
    }

    private static CommandAPDU pinCommand(byte instruction, byte[] data) {
        return new CommandAPDU(CLA, instruction, 0, 0, data);
    }

    private static byte[] combine(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] differentPin(byte[] source) {
        byte[] result = source.clone();
        result[0] = result[0] == (byte) '9' ? (byte) '0' : (byte) (result[0] + 1);
        return result;
    }

    private static byte[] readPin(int length) throws Exception {
        byte[] pin = new byte[length];
        boolean valid = false;
        try {
            int count = 0;
            int value;
            while ((value = System.in.read()) != -1 && value != '\n' && value != '\r') {
                if (count >= pin.length) {
                    throw new IllegalArgumentException("PIN input length invalid");
                }
                pin[count++] = (byte) value;
            }
            if (count != pin.length) {
                throw new IllegalArgumentException("PIN input length invalid");
            }
            for (byte digit : pin) {
                if (digit < '0' || digit > '9') {
                    throw new IllegalArgumentException("PIN input format invalid");
                }
            }
            valid = true;
            return pin;
        } finally {
            if (!valid) {
                clear(pin);
            }
        }
    }

    private static Card connect() throws Exception {
        CardTerminal terminal = TerminalFactory.getInstance(
                "SocketCardTerminalFactoryType",
                List.of(new InetSocketAddress("127.0.0.1",
                        Integer.getInteger("labaccess.simulator.port", 9025))),
                "SocketCardTerminalProvider").terminals().list().get(0);
        if (!terminal.isCardPresent() && !terminal.waitForCardPresent(10_000)) {
            throw new IllegalStateException("no card present");
        }
        return terminal.connect("*");
    }

    private static void record(String id, String label, int expected, int actual) {
        recordBoolean(id, label + " expected=" + String.format("%04X", expected)
                + " actual=" + String.format("%04X", actual), expected == actual);
    }

    private static void recordBoolean(String id, String label, boolean passed) {
        if (!passed) {
            failed++;
        }
        log(id + " | " + label + " | " + (passed ? "PASS" : "FAIL"));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String encodeHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xFF));
        }
        return result.toString();
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void log(String message) {
        System.out.println(message);
    }
}
