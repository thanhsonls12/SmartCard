package vn.labaccess.desktop;

import java.util.Arrays;
import java.util.function.Consumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import vn.labaccess.desktop.ui.enrollment.EnrollmentPanel;
import vn.labaccess.desktop.ui.enrollment.PersonalizePanel;

import vn.labaccess.desktop.ui.photo.PhotoPanel;

import vn.labaccess.desktop.ui.pin.PinPanel;

import vn.labaccess.desktop.ui.profile.ProfilePanel;

final class DemoScenario implements DemoScript {

    private final App app;
    private final PinPanel pinPanel;
    private final PersonalizePanel personalizePanel;
    private final EnrollmentPanel enrollmentPanel;
    private final ProfilePanel profilePanel;
    private final PhotoPanel photoPanel;
    private final Consumer<String> log;

    private static final String DEMO_CLASS_CODE = "SV24A";

    private Timeline timeline;
    private final DemoPhotoScenario photoScenario;
    private volatile boolean stopped;
    private Thread pinReader;
    private Runnable[] queuedSteps;
    private int nextStep;
    private long stepInterval;
    private java.util.List<Object> reconcileSteps;
    private int nextReconcileStep;
    private char[] demoAdminPin;
    private char[] demoStudentPin;
    private char[] demoChangedPin;
    private char[] demoUnblockedPin;
    private char[] demoLockedPin;

    DemoScenario(App app, PinPanel pinPanel, PersonalizePanel personalizePanel,
            EnrollmentPanel enrollmentPanel, ProfilePanel profilePanel, PhotoPanel photoPanel,
            Consumer<String> log) {
        this.app = app;
        this.pinPanel = pinPanel;
        this.personalizePanel = personalizePanel;
        this.enrollmentPanel = enrollmentPanel;
        this.profilePanel = profilePanel;
        this.photoPanel = photoPanel;
        this.log = log;
        this.photoScenario = new DemoPhotoScenario(app, photoPanel, log);
    }

    static DemoScript create(App app) {
        return new DemoScenario(app, app.pinPanel(), app.personalizePanel(), app.enrollmentPanel(),
                app.profilePanel(), app.photoPanel(), app::log);
    }

    public void start() {
        log.accept("DEMO: tự động bấm nút theo thứ tự (cùng handler với người dùng)");
        String reconnectMode = System.getProperty("labaccess.demo.reconnect", "");
        if (!reconnectMode.isEmpty() && !"false".equals(reconnectMode)) {
            startReconnectDemo(reconnectMode);
            return;
        }
        String restoreMode = System.getProperty("labaccess.demo.restore", "");
        if (!restoreMode.isEmpty() && !"false".equals(restoreMode)) {
            startRestoreDemo(restoreMode);
            return;
        }
        String reconcileMode = System.getProperty("labaccess.demo.reconcile", "");
        if (!reconcileMode.isEmpty() && !"false".equals(reconcileMode)) {
            startReconcileDemo(reconcileMode);
            return;
        }
        if (Boolean.getBoolean("labaccess.demo.enroll")) {
            startEnrollDemo();
            return;
        }
        if (Boolean.getBoolean("labaccess.demo.history")) {
            startHistoryDemo();
            return;
        }
        if (Boolean.getBoolean("labaccess.demo.transactions")) {
            startTransactionDemo();
            return;
        }
        if (Boolean.getBoolean("labaccess.demo.personalize")) {
            startPersonalizeDemo();
            return;
        }
        if (Boolean.getBoolean("labaccess.demo.profile")) {
            startProfileDemo();
            return;
        }
        app.showTab(App.TAB_OPERATIONS);
        run(new Runnable[] {
                () -> fire(app.pressConnect(), "Connect"),
                () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"),
                () -> personalizeRejected("ZZZZ", "SV2024001", "Nguyễn Văn Ánh",
                        "cardId không phải hex"),
                () -> personalizeRejected("0000000000000000", "SV2024@01", "",
                        "cardId toàn 00, studentId có ký tự đặc biệt, tên rỗng"),
                () -> fire(profilePanel.pressProfile(),
                        "GET_PROFILE (thẻ chưa ISSUED — kỳ vọng nút bị khoá)"),
                () -> fire(app.pressReadReceipt(), "GET_LAST_RECEIPT"),
                () -> fire(app.pressGrant(), "GRANT_QUOTA (kỳ vọng bị chặn vì thiếu VERIFY)"),
                () -> fire(app.pressDisconnect(), "Disconnect"),
        }, 800, 5000);
    }

    public void stop() {
        stopped = true;
        if (pinReader != null) {
            pinReader.interrupt();
            pinReader = null;
        }
        queuedSteps = null;
        reconcileSteps = null;
        if (timeline != null) {
            timeline.stop();
            timeline = null;
            log.accept("DEMO: đã dừng Timeline");
        }
        photoScenario.disposeSample();
        wipe();
    }

    private void startHistoryDemo() {
        log.accept("DEMO UI-08: PERSONALIZE, đăng ký, GRANT, START, END rồi tải lịch sử");
        app.showTab(App.TAB_OPERATIONS);
        readDemoPins(this::buildHistoryDemo);
    }

    private void buildHistoryDemo(char[] adminPin, char[] studentPin) {
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF11");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024011");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van UI08");
        java.util.List<Runnable> steps = new java.util.ArrayList<>();
        boolean ready = Boolean.getBoolean("labaccess.demo.history.ready");
        boolean view = Boolean.getBoolean("labaccess.demo.history.view");
        steps.add(() -> fire(app.pressConnect(), "Connect"));
        steps.add(() -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        if (view) {
            steps.add(() -> {
                app.showTab(App.TAB_OPERATIONS);
                app.revealHistory();
                log.accept("DEMO UI-08 bấm Tải lịch sử");
                fire(app.pressLoadHistory(), "LICH_SU");
            });
            steps.add(() -> {
                app.revealHistory();
                log.accept("DEMO UI-08 so dong bang=" + app.historyRowCount());
                try {
                    app.captureScene(java.nio.file.Path.of("..", "artifacts", "desktop-ui", "ui08-history.png"));
                } catch (java.io.IOException exception) {
                    log.accept("[FAIL] DEMO UI-08 ảnh: " + exception);
                }
            });
            steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
            demoAdminPin = adminPin;
            demoStudentPin = studentPin;
            run(steps.toArray(new Runnable[0]), 1000, 400);
            return;
        }
        if (!ready) {
            steps.add(() -> {
                personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
                fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
            });
            steps.add(() -> {
                app.showTab(App.TAB_ENROLL);
                enrollmentPanel.fillInputs(studentId, DEMO_CLASS_CODE, studentPin);
                fire(enrollmentPanel.pressEnroll(), "ENROLLMENT");
            });
        }
        steps.add(() -> {
            app.showTab(App.TAB_OPERATIONS);
            app.fillTransactionInputs(studentPin, null, 5);
            fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS state");
        });
        steps.add(() -> {
            app.showTab(App.TAB_OPERATIONS);
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept("DEMO UI-08 GRANT +5");
            fire(app.pressGrant(), "GRANT_QUOTA +5");
        });
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-08 START");
            fire(app.pressStart(), "START_VISIT");
        });
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-08 END");
            fire(app.pressEnd(), "END_VISIT");
        });
        steps.add(() -> {
            app.showTab(App.TAB_OPERATIONS);
            log.accept("DEMO UI-08 bấm Tải lịch sử");
            fire(app.pressLoadHistory(), "LICH_SU");
        });
        steps.add(() -> {
            log.accept("DEMO UI-08 so dong bang=" + app.historyRowCount());
            try {
                app.captureScene(java.nio.file.Path.of("..", "artifacts", "desktop-ui", "ui08-history.png"));
            } catch (java.io.IOException exception) {
                log.accept("[FAIL] DEMO UI-08 ảnh: " + exception);
            }
        });
        steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        run(steps.toArray(new Runnable[0]), 1000, 400);
    }

    private void startTransactionDemo() {
        log.accept("DEMO UI-06: PERSONALIZE và chạy hai chuỗi giao dịch qua JavaFX buttons");
        app.showTab(App.TAB_OPERATIONS);
        readDemoPins(this::buildTransactionDemo);
    }

    private void buildTransactionDemo(char[] adminPin, char[] studentPin) {
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF06");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024006");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van UI06");
        java.util.List<Runnable> steps = new java.util.ArrayList<>();
        steps.add(() -> fire(app.pressConnect(), "Connect"));
        steps.add(() -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        steps.add(() -> {
            personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
            log.accept("DEMO UI-06: PERSONALIZE fresh card");
            fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
        });
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS state baseline");
        });
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept("DEMO UI-06 A1: GRANT +5 — expected balance=5 counter=1");
            fire(app.pressGrant(), "GRANT_QUOTA +5");
        });
        steps.add(() -> app.logTransactionState("A1 after GRANT +5"));
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-06 A2: START — expected balance=4 counter=2, active visit");
            fire(app.pressStart(), "START_VISIT");
        });
        steps.add(() -> app.logTransactionState("A2 after START"));
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-06 A3: END — expected balance=4 counter=3, visit cleared");
            fire(app.pressEnd(), "END_VISIT");
        });
        steps.add(() -> app.logTransactionState("A3 after END"));
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-06 B1: START — expected balance=3 counter=4, active visit");
            fire(app.pressStart(), "START_VISIT second sequence");
        });
        steps.add(() -> app.logTransactionState("B1 after START"));
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept("DEMO UI-06 B2: GRANT +5 during open visit — expected balance=8 counter=5");
            fire(app.pressGrant(), "GRANT_QUOTA +5 while visit open");
        });
        steps.add(() -> app.logTransactionState("B2 after GRANT while visit open"));
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-06 B3: END after GRANT — expected balance=8 counter=6, visit cleared");
            fire(app.pressEnd(), "END_VISIT after GRANT");
        });
        steps.add(() -> app.logTransactionState("B3 after END"));
        steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        run(steps.toArray(new Runnable[0]), 1000, 250);
    }

    private void startReconcileDemo(String mode) {
        log.accept("DEMO SIGN-08: đối soát pending journal qua nút thật trên UI (mode=" + mode + ")");
        app.showTab(App.TAB_OPERATIONS);
        readDemoPins((adminPin, studentPin) -> buildReconcileDemo(mode, adminPin, studentPin));
    }

    private void buildReconcileDemo(String mode, char[] adminPin, char[] studentPin) {
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        boolean priorGrant = mode.equals("c4a") || mode.equals("c4b");
        boolean staysLocked = mode.equals("c3") || mode.equals("c4b");
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF08");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024008");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van Sign08");

        java.util.List<Object> steps = new java.util.ArrayList<>();
        steps.add((Runnable) () -> fire(app.pressConnect(), "Connect"));
        steps.add((Runnable) () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        steps.add((Runnable) () -> {
            app.showTab(App.TAB_CARD);
            personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
            log.accept("DEMO SIGN-08: PERSONALIZE thẻ mới");
            fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
        });
        steps.add((Runnable) () -> {
            app.showTab(App.TAB_ENROLL);
            enrollmentPanel.fillInputs(studentId, DEMO_CLASS_CODE, studentPin);
            log.accept("DEMO SIGN-08: đăng ký thẻ — tạo students + card_ledgers 0/0 trên Backend");
            fire(enrollmentPanel.pressEnroll(), "ENROLLMENT");
        });
        steps.add(new Wait("enrolled", "đăng ký thẻ xong", enrollmentPanel::enrolled, 120_000));
        steps.add((Runnable) () -> app.showTab(App.TAB_OPERATIONS));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO SIGN-08: đọc mốc state thẻ trước giao dịch");
            fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS");
        });
        if (priorGrant) {
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, adminPin, 5);
                log.accept("DEMO SIGN-08: GRANT +5 thành công để có receipt/mốc cũ counter=1 balance=5");
                fire(app.pressGrant(), "GRANT_QUOTA +5");
            });
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, null, 5);
                fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS sau GRANT");
            });
        }
        steps.add(new Wait("go-fail", "ngoại cảnh đã dựng xong (go-fail.flag)",
                () -> signalPresent("go-fail.flag"), 240_000));
        steps.add((Runnable) () -> {
            if (priorGrant) {
                char[] wrongAdmin = shiftDigits(adminPin, 1);
                app.fillTransactionInputs(studentPin, wrongAdmin, 5);
                Arrays.fill(wrongAdmin, '\0');
                log.accept("DEMO SIGN-08 " + mode
                        + ": GRANT +5 với admin PIN sai — kỳ vọng APDU bị từ chối, journal PENDING,"
                        + " state thẻ không đổi");
            } else {
                app.fillTransactionInputs(studentPin, adminPin, 5);
                log.accept("DEMO SIGN-08 " + mode
                        + ": GRANT +5 — kỳ vọng thẻ 9000 nhưng lưu ledger thất bại, journal PENDING");
            }
            fire(app.pressGrant(), "GRANT_QUOTA (ca " + mode + ")");
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("EVID1-" + mode + " sau giao dịch lỗi"));
        steps.add(new Wait("go-reconcile", "harness chụp ảnh pending xong (go-reconcile.flag)",
                () -> signalPresent("go-reconcile.flag"), 240_000));
        steps.add(new Async(() -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO SIGN-08: bấm nút Đối soát thật rồi tự xác nhận hộp thoại");
            scheduleConfirmDialog(40);
            boolean pressed = app.pressReconcile();
            log.accept("DEMO: đã bấm nút Đối soát=" + pressed);
        }));
        steps.add((Runnable) () -> app.logReconcileEvidence("EVID2-" + mode + " sau đối soát"));
        steps.add(new Wait("go-next", "harness chụp ảnh sau đối soát xong (go-next.flag)",
                () -> signalPresent("go-next.flag"), 240_000));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept(staysLocked
                    ? "DEMO SIGN-08: thử giao dịch kế tiếp — kỳ vọng nút bị khoá"
                    : "DEMO SIGN-08: thao tác kế tiếp sau khi journal đóng — GRANT +5");
            fire(app.pressGrant(), staysLocked ? "GRANT_QUOTA (kỳ vọng bị khoá)" : "GRANT_QUOTA kế tiếp");
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("EVID3-" + mode + " sau thao tác kế tiếp"));
        steps.add((Runnable) () -> fire(app.pressDisconnect(), "Disconnect"));
        steps.add((Runnable) () -> log.accept("DEMO SIGN-08: xong ca " + mode));
        runReconcileSteps(steps);
    }

    private void startRestoreDemo(String mode) {
        log.accept("DEMO UI-10: khôi phục pending journal khi mở lại ứng dụng (mode=" + mode + ")");
        app.showTab(App.TAB_OPERATIONS);
        readDemoPins((adminPin, studentPin) -> buildRestoreDemo(mode, adminPin, studentPin));
    }

    private void buildRestoreDemo(String mode, char[] adminPin, char[] studentPin) {
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF10");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024010");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van Ui10");
        java.util.List<Object> steps = new java.util.ArrayList<>();

        if (mode.equals("pending")) {
            steps.add((Runnable) () -> fire(app.pressConnect(), "Connect"));
            steps.add((Runnable) () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, null, 5);
                log.accept("DEMO UI-10: đọc mốc state thẻ trước giao dịch sẽ mất phản hồi");
                fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS");
            });
            steps.add(new Wait("go-fail", "proxy đã chuyển sang cắt phản hồi (go-fail.flag)",
                    () -> signalPresent("go-fail.flag"), 300_000));
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, adminPin, 5);
                log.accept("DEMO UI-10: GRANT +5 — Backend commit nhưng phản hồi bị cắt,"
                        + " kỳ vọng journal PENDING");
                fire(app.pressGrant(), "GRANT_QUOTA (mất phản hồi)");
            });
            steps.add((Runnable) () -> app.logUi10Evidence("PENDING-" + mode + " sau khi mất phản hồi"));
            steps.add(new Wait("go-kill", "harness đã chụp ảnh journal PENDING (go-kill.flag)",
                    () -> signalPresent("go-kill.flag"), 300_000));
            steps.add((Runnable) () -> log.accept(
                    "DEMO UI-10: journal PENDING đã ghi — chờ harness kill tiến trình (không ngắt kết nối)"));
            runReconcileSteps(steps);
            return;
        }

        if (mode.equals("unenrolled")) {
            steps.add((Runnable) () -> fire(app.pressConnect(), "Connect"));
            steps.add((Runnable) () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
            steps.add((Runnable) () -> {
                app.showTab(App.TAB_CARD);
                personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
                log.accept("DEMO UI-10 c5: PERSONALIZE thẻ mới nhưng KHÔNG đăng ký thẻ");
                fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
            });
            steps.add((Runnable) () -> {
                app.showTab(App.TAB_OPERATIONS);
                log.accept("DEMO UI-10 c5: nhận diện lại để kiểm tra đăng ký thẻ trên Backend");
                fire(app.pressIdentify(), "SELECT + GET_STATUS sau PERSONALIZE");
            });
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, null, 5);
                log.accept("DEMO UI-10 c5: đọc mốc state thẻ (VERIFY + GET_LAST_RECEIPT + GET_STATUS)");
                fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS");
            });
            steps.add((Runnable) () -> log.accept("DEMO UI-10 c5: enrollmentPanel.enrolled()="
                    + enrollmentPanel.enrolled()));
            steps.add(new Wait("go-txn", "harness đã chụp ảnh trước khi thử giao dịch (go-txn.flag)",
                    () -> signalPresent("go-txn.flag"), 300_000));
            steps.add((Runnable) () -> app.logUi10Evidence("C5-TRUOC-KHI-THU-" + mode));
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, adminPin, 5);
                log.accept("DEMO UI-10 c5: bấm GRANT +5 trên thẻ chưa đăng ký — kỳ vọng bị chặn");
                fire(app.pressGrant(), "GRANT_QUOTA (thẻ chưa đăng ký)");
            });
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, null, 5);
                log.accept("DEMO UI-10 c5: bấm START trên thẻ chưa đăng ký — kỳ vọng bị chặn");
                fire(app.pressStart(), "START_VISIT (thẻ chưa đăng ký)");
            });
            steps.add((Runnable) () -> {
                app.fillTransactionInputs(studentPin, null, 5);
                log.accept("DEMO UI-10 c5: bấm END trên thẻ chưa đăng ký — kỳ vọng bị chặn");
                fire(app.pressEnd(), "END_VISIT (thẻ chưa đăng ký)");
            });
            steps.add((Runnable) () -> app.logUi10Evidence("C5-SAU-KHI-THU-" + mode));
            steps.add((Runnable) () -> app.logTransactionState("UI10-C5 sau khi thử giao dịch"));
            steps.add((Runnable) () -> log.accept(
                    "DEMO UI-10 c5 PROBE tang gateway: " + app.probeTransactionGate("GRANT bang gateway")));
            steps.add(new Wait("go-shot", "harness đã chụp ảnh sau khi thử (go-shot.flag)",
                    () -> signalPresent("go-shot.flag"), 300_000));
            steps.add((Runnable) () -> app.logUi10Evidence("C5-CUOI-" + mode));
            steps.add((Runnable) () -> {
                fire(app.pressDisconnect(), "Disconnect");
                log.accept("DEMO UI-10: xong ca " + mode);
            });
            runReconcileSteps(steps);
            return;
        }

        if (mode.equals("mismatch")) {
            steps.add((Runnable) () -> fire(app.pressConnect(), "Connect"));
            steps.add((Runnable) () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
            steps.add((Runnable) () -> {
                app.showTab(App.TAB_CARD);
                personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
                log.accept("DEMO UI-10: PERSONALIZE thẻ thật cardId=" + cardId
                        + " (journal đang thuộc cardId khác)");
                fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
            });
            steps.add((Runnable) () -> {
                app.showTab(App.TAB_OPERATIONS);
                log.accept("DEMO UI-10: bấm Nhận diện thẻ thật — kỳ vọng KHÔNG mở hộp thoại đối soát");
                fire(app.pressIdentify(), "SELECT + GET_STATUS thẻ khác journal");
            });
            steps.add((Runnable) () -> app.logUi10Evidence("MISMATCH-SAU-NHAN-DIEN-" + mode));
            steps.add(new Wait("go-shot", "harness đã chụp ảnh (go-shot.flag)",
                    () -> signalPresent("go-shot.flag"), 300_000));
            steps.add((Runnable) () -> app.logUi10Evidence("MISMATCH-CUOI-" + mode));
            steps.add((Runnable) () -> {
                fire(app.pressDisconnect(), "Disconnect");
                log.accept("DEMO UI-10: xong ca " + mode);
            });
            runReconcileSteps(steps);
            return;
        }

        steps.add(new Wait("go-connect", "harness đã chụp ảnh lúc mở app, chưa kết nối (go-connect.flag)",
                () -> signalPresent("go-connect.flag"), 300_000));
        steps.add((Runnable) () -> app.logUi10Evidence("MO-LAI-APP-CHUA-KET-NOI-" + mode));
        steps.add((Runnable) () -> {
            log.accept("DEMO UI-10: bấm Kết nối thật");
            fire(app.pressConnect(), "Connect");
        });
        steps.add((Runnable) () -> app.logUi10Evidence("SAU-KET-NOI-" + mode));
        steps.add((Runnable) () -> {
            log.accept("DEMO UI-10: bấm Nhận diện thẻ thật — hộp thoại đối soát phải tự hiện"
                    + " khi journal khớp cardId của thẻ");
            fire(app.pressIdentify(), "SELECT + GET_STATUS sau khi mở lại app");
        });
        steps.add(new Wait("dialog", "hộp thoại đối soát tự hiện sau khi nhận diện",
                this::reconcileDialogVisible, 90_000));
        steps.add((Runnable) this::logReconcileDialog);
        steps.add((Runnable) () -> app.logUi10Evidence("HOP-THOAI-DOI-SOAT-" + mode));
        steps.add(new Wait("go-ok", "harness chụp ảnh hộp thoại xong (go-ok.flag)",
                () -> signalPresent("go-ok.flag"), 300_000));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO UI-10: điền PIN sinh viên rồi bấm OK trên hộp thoại đang hiện");
            log.accept("DEMO UI-10: đã bấm OK trên hộp thoại thật=" + pressReconcileDialogOk());
        });
        steps.add((Runnable) () -> app.logUi10Evidence("SAU-DOI-SOAT-" + mode));
        steps.add(new Wait("go-next", "harness chụp ảnh sau đối soát xong (go-next.flag)",
                () -> signalPresent("go-next.flag"), 300_000));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept("DEMO UI-10: thao tác kế tiếp sau đối soát — GRANT +5");
            fire(app.pressGrant(), "GRANT_QUOTA kế tiếp sau đối soát");
        });
        steps.add((Runnable) () -> app.logUi10Evidence("SAU-THAO-TAC-KE-" + mode));
        steps.add((Runnable) () -> {
            fire(app.pressDisconnect(), "Disconnect");
            log.accept("DEMO UI-10: xong ca " + mode);
        });
        runReconcileSteps(steps);
    }

    private void startReconnectDemo(String mode) {
        log.accept("DEMO SIGN-08 RECONNECT: mất phản hồi Backend, ngắt kết nối rồi nối lại (mode=" + mode + ")");
        app.showTab(App.TAB_OPERATIONS);
        readDemoPins((adminPin, studentPin) -> buildReconnectDemo(mode, adminPin, studentPin));
    }

    private void buildReconnectDemo(String mode, char[] adminPin, char[] studentPin) {
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        boolean staysLocked = mode.equals("backenddown");
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF08");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024008");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van Sign08");

        java.util.List<Object> steps = new java.util.ArrayList<>();
        steps.add((Runnable) () -> fire(app.pressConnect(), "Connect"));
        steps.add((Runnable) () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        steps.add((Runnable) () -> {
            app.showTab(App.TAB_CARD);
            personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
            log.accept("DEMO SIGN-08 RECONNECT: PERSONALIZE thẻ mới");
            fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
        });
        steps.add((Runnable) () -> {
            app.showTab(App.TAB_ENROLL);
            enrollmentPanel.fillInputs(studentId, DEMO_CLASS_CODE, studentPin);
            log.accept("DEMO SIGN-08 RECONNECT: đăng ký thẻ — tạo students + card_ledgers 0/0 trên Backend");
            fire(enrollmentPanel.pressEnroll(), "ENROLLMENT");
        });
        steps.add(new Wait("enrolled", "đăng ký thẻ xong", enrollmentPanel::enrolled, 120_000));
        steps.add((Runnable) () -> app.showTab(App.TAB_OPERATIONS));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO SIGN-08 RECONNECT: đọc mốc state thẻ trước giao dịch");
            fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS");
        });
        steps.add(new Wait("go-fail", "proxy đã chuyển sang cắt phản hồi (go-fail.flag)",
                () -> signalPresent("go-fail.flag"), 300_000));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept("DEMO SIGN-08 RECONNECT: GRANT +5 — Backend đã commit nhưng phản hồi bị cắt,"
                    + " kỳ vọng journal PENDING");
            fire(app.pressGrant(), "GRANT_QUOTA (mất phản hồi)");
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("EVID1-" + mode + " sau khi mất phản hồi"));
        steps.add(new Wait("go-reconnect", "harness chụp ảnh pending xong (go-reconnect.flag)",
                () -> signalPresent("go-reconnect.flag"), 300_000));
        steps.add((Runnable) () -> {
            log.accept("DEMO SIGN-08 RECONNECT: bấm Ngắt kết nối thật khi journal còn PENDING");
            fire(app.pressDisconnect(), "Disconnect");
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("AFTER-DISCONNECT-" + mode));
        steps.add(new Wait("go-connect", "harness chụp ảnh lúc đã ngắt kết nối (go-connect.flag)",
                () -> signalPresent("go-connect.flag"), 300_000));
        steps.add((Runnable) () -> {
            log.accept("DEMO SIGN-08 RECONNECT: bấm Kết nối lại");
            fire(app.pressConnect(), "Connect");
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("AFTER-CONNECT-" + mode));
        steps.add((Runnable) () -> {
            log.accept("DEMO SIGN-08 RECONNECT: bấm nút Nhận diện thẻ thật —"
                    + " hộp thoại đối soát phải tự hiện, demo không gọi hộ đối soát");
            fire(app.pressIdentify(), "SELECT + GET_STATUS sau khi nối lại");
        });
        steps.add(new Wait("dialog", "hộp thoại đối soát tự hiện sau khi nhận diện",
                this::reconcileDialogVisible, 60_000));
        steps.add((Runnable) this::logReconcileDialog);
        steps.add(new Wait("go-ok", "harness chụp ảnh hộp thoại xong (go-ok.flag)",
                () -> signalPresent("go-ok.flag"), 300_000));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO SIGN-08 RECONNECT: điền PIN sinh viên rồi bấm OK trên hộp thoại đang hiện");
            log.accept("DEMO SIGN-08 RECONNECT: đã bấm OK trên hộp thoại thật="
                    + pressReconcileDialogOk());
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("EVID2-" + mode + " sau đối soát"));
        steps.add(new Wait("go-next", "harness chụp ảnh sau đối soát xong (go-next.flag)",
                () -> signalPresent("go-next.flag"), 300_000));
        steps.add((Runnable) () -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept(staysLocked
                    ? "DEMO SIGN-08 RECONNECT: thử giao dịch kế tiếp — kỳ vọng nút bị khoá"
                    : "DEMO SIGN-08 RECONNECT: thao tác kế tiếp sau khi journal đóng — GRANT +5");
            fire(app.pressGrant(), staysLocked ? "GRANT_QUOTA (kỳ vọng bị khoá)" : "GRANT_QUOTA kế tiếp");
        });
        steps.add((Runnable) () -> app.logReconcileEvidence("EVID3-" + mode + " sau thao tác kế tiếp"));
        steps.add((Runnable) () -> fire(app.pressDisconnect(), "Disconnect"));
        steps.add((Runnable) () -> log.accept("DEMO SIGN-08 RECONNECT: xong ca " + mode));
        runReconcileSteps(steps);
    }

    private javafx.scene.control.DialogPane visibleReconcileDialog() {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (window.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    private boolean reconcileDialogVisible() {
        return visibleReconcileDialog() != null;
    }

    private void logReconcileDialog() {
        javafx.scene.control.DialogPane pane = visibleReconcileDialog();
        if (pane == null) {
            log.accept("[FAIL] DEMO SIGN-08 RECONNECT: không thấy hộp thoại đối soát đang hiện");
            return;
        }
        boolean raised = raiseReconcileDialog(pane);
        javafx.scene.Node ok = pane.lookupButton(javafx.scene.control.ButtonType.OK);
        log.accept("DIALOG-TU-HIEN sau khi reconnect: tiêu đề=\"" + oneLine(dialogWindowTitle(pane))
                + "\" | câu hỏi=\"" + oneLine(pane.getHeaderText())
                + "\" | nội dung=\"" + oneLine(pane.getContentText())
                + "\" | nút OK=\"" + (ok instanceof javafx.scene.control.Button button
                        ? button.getText() : "không có")
                + "\" khoá=" + (ok == null || ok.isDisable())
                + " | demo không gọi offerPendingReconciliation/reconcilePending"
                + " | cửa sổ demo luôn nổi nên đưa hộp thoại lên trên=" + raised);
    }

    private boolean raiseReconcileDialog(javafx.scene.control.DialogPane pane) {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window.getScene() == null || window.getScene().getRoot() != pane) {
                continue;
            }
            if (window instanceof javafx.stage.Stage stage) {
                stage.setAlwaysOnTop(true);
                stage.toFront();
                return true;
            }
        }
        return false;
    }

    private static String dialogWindowTitle(javafx.scene.control.DialogPane pane) {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window.getScene() != null && window.getScene().getRoot() == pane
                    && window instanceof javafx.stage.Stage stage) {
                return stage.getTitle();
            }
        }
        return "";
    }

    private boolean pressReconcileDialogOk() {
        javafx.scene.control.DialogPane pane = visibleReconcileDialog();
        if (pane == null) {
            return false;
        }
        javafx.scene.Node ok = pane.lookupButton(javafx.scene.control.ButtonType.OK);
        if (ok instanceof javafx.scene.control.Button button && !button.isDisable()) {
            button.fire();
            return true;
        }
        return false;
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private record Wait(String slug, String label, java.util.function.BooleanSupplier condition, long timeoutMillis) {
    }

    private record Async(Runnable task) {
    }

    private void runReconcileSteps(java.util.List<Object> steps) {
        reconcileSteps = steps;
        nextReconcileStep = 0;
        scheduleReconcileStep(1200);
    }

    private void scheduleReconcileStep(long delay) {
        if (stopped || reconcileSteps == null) {
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(delay), event -> {
            if (stopped || reconcileSteps == null) {
                return;
            }
            if (nextReconcileStep == reconcileSteps.size()) {
                reconcileSteps = null;
                timeline = null;
                log.accept("DEMO: xong");
                app.refreshButtons();
                return;
            }
            Object step = reconcileSteps.get(nextReconcileStep++);
            if (step instanceof Wait wait) {
                log.accept("DEMO WAIT " + wait.slug() + " — " + wait.label());
                awaitSignal(wait, System.currentTimeMillis() + wait.timeoutMillis());
                return;
            }
            if (step instanceof Async async) {
                javafx.application.Platform.runLater(() -> {
                    async.task().run();
                    awaitWorkerIdle();
                });
                return;
            }
            ((Runnable) step).run();
            awaitWorkerIdle();
        }));
        timeline.play();
    }

    private void awaitSignal(Wait wait, long deadline) {
        if (stopped || reconcileSteps == null) {
            return;
        }
        if (wait.condition().getAsBoolean()) {
            log.accept("DEMO GO " + wait.slug());
            scheduleReconcileStep(200);
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            reconcileSteps = null;
            timeline = null;
            log.accept("[FAIL] DEMO: hết thời gian chờ " + wait.label() + " — dừng kịch bản");
            app.refreshButtons();
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(200), event -> awaitSignal(wait, deadline)));
        timeline.play();
    }

    private void awaitWorkerIdle() {
        if (stopped || reconcileSteps == null) {
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(120), event -> {
            if (stopped || reconcileSteps == null) {
                return;
            }
            if (app.isBusy()) {
                awaitWorkerIdle();
            } else {
                scheduleReconcileStep(400);
            }
        }));
        timeline.play();
    }

    private void scheduleConfirmDialog(int attempts) {
        if (stopped) {
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(250),
                event -> confirmReconcileDialog(attempts)));
        timeline.play();
    }

    private void confirmReconcileDialog(int attempts) {
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (!window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (!(window.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane)) {
                continue;
            }
            javafx.scene.Node ok = pane.lookupButton(javafx.scene.control.ButtonType.OK);
            if (ok instanceof javafx.scene.control.Button button && !button.isDisable()) {
                log.accept("DEMO: tự bấm OK trên hộp thoại xác nhận đối soát");
                button.fire();
                return;
            }
        }
        if (attempts <= 1) {
            log.accept("[FAIL] DEMO: không tìm thấy hộp thoại xác nhận để bấm OK");
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(250),
                event -> confirmReconcileDialog(attempts - 1)));
        timeline.play();
    }

    private boolean signalPresent(String name) {
        String given = System.getProperty("labaccess.demo.signalDir", "");
        java.nio.file.Path base = given.isEmpty()
                ? java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "labaccess-signals")
                : java.nio.file.Path.of(given);
        return java.nio.file.Files.exists(base.resolve(name));
    }

    private void startEnrollDemo() {
        log.accept("DEMO UI-03: PERSONALIZE thẻ mới rồi chạy luồng Đăng ký thẻ sáu bước");
        app.showTab(App.TAB_CARD);
        readDemoPins(this::buildEnrollDemo);
    }

    private void buildEnrollDemo(char[] adminPin, char[] studentPin) {
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF07");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024007");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van UI03");
        java.util.List<Runnable> steps = new java.util.ArrayList<>();
        steps.add(() -> fire(app.pressConnect(), "Connect"));
        steps.add(() -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        steps.add(() -> {
            personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
            fire(personalizePanel.pressPersonalize(), "PERSONALIZE thẻ mới");
        });
        steps.add(() -> {
            app.showTab(App.TAB_ENROLL);
            enrollmentPanel.fillInputs(studentId, DEMO_CLASS_CODE, studentPin);
            log.accept("DEMO UI-03 PRE-ENROLL: bấm Đăng ký thẻ — một nút, sáu bước");
            fire(enrollmentPanel.pressEnroll(), "ENROLLMENT");
        });
        steps.add(() -> log.accept("DEMO UI-03 kết quả: " + enrollmentPanel.statusText()));
        steps.add(() -> log.accept("DEMO UI-03 enrolled=" + enrollmentPanel.enrolled()));

        steps.add(() -> {
            if (enrollmentPanel.enrolled()) {
                return;
            }
            enrollmentPanel.fillInputs("SVEDIT001", DEMO_CLASS_CODE, studentPin);
            log.accept("DEMO UI-03 EDIT-AFTER-FAIL sau khi sửa Mã sinh viên + nhập lại PIN: "
                    + enrollmentPanel.statusText());
        });

        steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
        steps.add(() -> log.accept("DEMO UI-03 AFTER-DISCONNECT: " + enrollmentPanel.statusText()));
        steps.add(() -> fire(app.pressConnect(), "Connect"));
        steps.add(() -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        steps.add(() -> log.accept("DEMO UI-03 AFTER-RECONNECT: " + enrollmentPanel.statusText()));

        run(steps.toArray(new Runnable[0]), 8000, 5000);
    }

    private void startProfileDemo() {
        log.accept("DEMO REFACTOR: PERSONALIZE, đọc hồ sơ + metadata có VERIFY, đọc lại bằng nút thật với"
                + " PIN sai hợp định dạng (kỳ vọng 63Cx, panel xoá kết quả cũ), rồi GRANT để kiểm đường journal");
        app.showTab(App.TAB_CARD);
        readDemoPins(this::buildProfileDemo);
    }

    private void buildProfileDemo(char[] adminPin, char[] studentPin) {
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        char[] wrongStudentPin = shiftDigits(studentPin, 5);
        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EF21");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024021");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyen Van Refactor");
        java.util.List<Runnable> steps = new java.util.ArrayList<>();
        steps.add(() -> fire(app.pressConnect(), "Connect"));
        steps.add(() -> fire(app.pressIdentify(), "SELECT + GET_STATUS"));
        steps.add(() -> {
            personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
            log.accept("DEMO REFACTOR: PERSONALIZE thẻ mới");
            fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
        });
        steps.add(() -> {
            app.showTab(App.TAB_ENROLL);
            enrollmentPanel.fillInputs(studentId, DEMO_CLASS_CODE, studentPin);
            log.accept("DEMO REFACTOR: đăng ký thẻ trên Backend để GRANT không bị chặn");
            fire(enrollmentPanel.pressEnroll(), "ENROLLMENT");
        });
        steps.add(() -> app.showTab(App.TAB_RECORDS));
        steps.add(() -> readProfile(studentPin,
                "GET_PROFILE — VERIFY ngay trước, kỳ vọng 9000 + UTF-8 + hex"));
        steps.add(() -> capture("01-ho-so-9000"));
        steps.add(() -> readMeta(studentPin,
                "GET_PROFILE_CRYPTO_META — VERIFY ngay trước, kỳ vọng 9000 + ciphertextLen"));
        steps.add(() -> capture("02-metadata-9000"));
        steps.add(() -> readProfile(wrongStudentPin,
                "GET_PROFILE với PIN sai nhưng hợp định dạng — kỳ vọng 63Cx, panel xoá kết quả cũ"));
        steps.add(() -> capture("03-pin-sai-63cx"));
        steps.add(() -> {
            app.showTab(App.TAB_OPERATIONS);
            app.fillTransactionInputs(studentPin, null, 5);
            log.accept("DEMO REFACTOR: đọc mốc state thẻ trước GRANT");
            fire(app.pressReadReceipt(), "VERIFY + GET_LAST_RECEIPT + GET_STATUS state");
        });
        steps.add(() -> {
            app.fillTransactionInputs(studentPin, adminPin, 5);
            log.accept("DEMO REFACTOR: GRANT +5 — journal PENDING trước APDU, RESOLVED sau receipt");
            fire(app.pressGrant(), "GRANT_QUOTA +5");
        });
        steps.add(() -> app.logTransactionState("sau GRANT"));
        steps.add(() -> capture("04-grant-journal"));
        steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
        run(steps.toArray(new Runnable[0]), 2500, 4500);
    }

    private void capture(String name) {
        try {
            app.captureScene(java.nio.file.Path.of("..", "artifacts", "desktop-ui",
                    "refactor-desktop-" + name + "-" + runStamp() + ".png"));
        } catch (java.io.IOException exception) {
            log.accept("[FAIL] DEMO REFACTOR ảnh: " + exception);
        }
    }

    private static String runStamp() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }

    private void startPersonalizeDemo() {
        log.accept("DEMO: PERSONALIZE qua handler GUI — PIN đọc từ stdin, không qua -D và không vào log");
        app.showTab(App.TAB_CARD);
        readDemoPins(this::buildPersonalizeDemo);
    }

    private void buildPersonalizeDemo(char[] adminPin, char[] studentPin) {
        demoAdminPin = adminPin;
        demoStudentPin = studentPin;
        char[] wrongStudentPin = shiftDigits(studentPin, 5);
        char[] wrongAdmin = shiftDigits(adminPin, 1);

        String cardId = System.getProperty("labaccess.demo.cardId", "A1B2C3D41122EE01");
        String studentId = System.getProperty("labaccess.demo.studentId", "SV2024002");
        String displayName = System.getProperty("labaccess.demo.displayName", "Nguyễn Văn Bình");
        char[] changedPin = shiftDigits(studentPin, 1);
        char[] unblockedPin = shiftDigits(studentPin, 2);
        char[] lockedPin = shiftDigits(changedPin, 5);
        demoChangedPin = changedPin;
        demoUnblockedPin = unblockedPin;
        demoLockedPin = lockedPin;

        java.util.List<Runnable> steps = new java.util.ArrayList<>(java.util.Arrays.asList(
                () -> fire(app.pressConnect(), "Connect"),
                () -> fire(app.pressIdentify(), "SELECT + GET_STATUS"),
                () -> fire(personalizePanel.pressPublicKey(),
                        "GET_CARD_PUBLIC_KEY (trước PERSONALIZE — kỳ vọng 6985)"),
                () -> personalize(cardId, studentId, displayName, wrongAdmin, studentPin,
                        "admin PIN sai — kỳ vọng bị từ chối"),
                () -> personalize(cardId, studentId, displayName, adminPin, studentPin,
                        "dữ liệu hợp lệ — kỳ vọng 9000 + ISSUED"),
                () -> fire(personalizePanel.pressPublicKey(),
                        "GET_CARD_PUBLIC_KEY (sau PERSONALIZE)"),
                () -> fire(personalizePanel.pressPublicKey(),
                        "GET_CARD_PUBLIC_KEY (đọc lại — kỳ vọng cùng key)"),
                () -> app.showTab(App.TAB_RECORDS),
                () -> readMeta(studentPin,
                        "GET_PROFILE_CRYPTO_META — VERIFY ngay trước, kỳ vọng 9000 + 65 byte"),
                () -> readProfile(studentPin,
                        "GET_PROFILE — VERIFY ngay trước, kỳ vọng 9000 + UTF-8"),
                () -> readProfile(wrongStudentPin,
                        "GET_PROFILE với PIN sai — kỳ vọng 63C2, xoá kết quả cũ trên UI"),
                () -> readMeta(studentPin,
                        "GET_PROFILE_CRYPTO_META lại để panel có metadata trước ca identify lỗi"),
                () -> readProfile(studentPin,
                        "GET_PROFILE lại để panel có hồ sơ trước ca identify lỗi"),
                () -> identify(
                        "SELECT lại sau khi panel đang hiện hồ sơ — panel phải xoá bất kể kết quả"),
                () -> app.showTab(App.TAB_CARD),
                () -> pinOp(studentPin, "VERIFY PIN hiện tại — kỳ vọng 9000"),
                () -> pinOp(wrongStudentPin, "VERIFY PIN sai — kỳ vọng 63C2"),
                () -> pinOp(studentPin, "VERIFY PIN đúng lại — kỳ vọng 9000"),
                () -> changePin(studentPin, changedPin),
                () -> pinOp(studentPin, "VERIFY PIN cũ sau CHANGE — kỳ vọng 63Cx"),
                () -> pinOp(changedPin, "VERIFY PIN mới — kỳ vọng 9000"),
                () -> pinOp(lockedPin, "VERIFY sai lần 1/3 — kỳ vọng 63C2"),
                () -> pinOp(lockedPin, "VERIFY sai lần 2/3 — kỳ vọng 63C1"),
                () -> pinOp(lockedPin, "VERIFY sai lần 3/3 — kỳ vọng 6983 khóa"),
                () -> pinOp(changedPin, "VERIFY PIN đúng khi đang khóa — kỳ vọng 6983"),
                () -> unblockPin(wrongAdmin, unblockedPin,
                        "UNBLOCK admin PIN sai — kỳ vọng 63Cx"),
                () -> unblockPin(adminPin, unblockedPin,
                        "UNBLOCK admin PIN đúng — kỳ vọng 9000"),
                () -> pinOp(unblockedPin, "VERIFY PIN sau UNBLOCK — kỳ vọng 9000")));
        if (Boolean.getBoolean("labaccess.demo.photo")) {
            steps.add(() -> app.showTab(App.TAB_RECORDS));
            steps.addAll(photoSteps(adminPin, unblockedPin));
        }
        steps.add(() -> fire(app.pressDisconnect(), "Disconnect"));
        if (Boolean.getBoolean("labaccess.demo.photo")) {
            steps.add(() -> photoScenario.afterDisconnect());
        }
        run(steps.toArray(new Runnable[0]), 1200, 4500);
    }

    private void run(Runnable[] steps, long firstDelay, long interval) {
        queuedSteps = steps;
        nextStep = 0;
        stepInterval = interval;
        scheduleStep(firstDelay);
    }

    private void scheduleStep(long delay) {
        if (stopped || queuedSteps == null) {
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(delay), event -> {
            if (stopped || queuedSteps == null) {
                return;
            }
            if (nextStep == queuedSteps.length) {
                queuedSteps = null;
                timeline = null;
                log.accept("DEMO: xong");
                app.refreshButtons();
                return;
            }
            queuedSteps[nextStep++].run();
            waitForOperation();
        }));
        timeline.play();
    }

    private void waitForOperation() {
        if (stopped || queuedSteps == null) {
            return;
        }
        timeline = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            if (app.isBusy()) {
                waitForOperation();
            } else {
                scheduleStep(stepInterval);
            }
        }));
        timeline.play();
    }

    private void readDemoPins(java.util.function.BiConsumer<char[], char[]> ready) {
        Thread reader = new Thread(() -> {
            char[] adminPin = readDemoPin("Admin PIN (8 digits): ", 8);
            char[] studentPin = readDemoPin("Student PIN (6 digits): ", 6);
            try {
                javafx.application.Platform.runLater(() -> {
                    pinReader = null;
                    if (stopped) {
                        wipePins(adminPin, studentPin);
                    } else if (adminPin == null || studentPin == null) {
                        wipePins(adminPin, studentPin);
                        log.accept("DEMO: không đọc được PIN đúng độ dài — dừng demo");
                    } else {
                        ready.accept(adminPin, studentPin);
                    }
                });
            } catch (IllegalStateException exception) {
                wipePins(adminPin, studentPin);
            }
        }, "demo-pin-input");
        reader.setDaemon(true);
        pinReader = reader;
        reader.start();
    }

    private static void wipePins(char[] adminPin, char[] studentPin) {
        if (adminPin != null) {
            Arrays.fill(adminPin, '\0');
        }
        if (studentPin != null) {
            Arrays.fill(studentPin, '\0');
        }
    }

    private void fire(boolean pressed, String name) {
        if (!pressed) {
            log.accept("DEMO: bỏ qua bước " + name + " (nút đang bị khoá)");
        }
    }

    private void personalize(String cardId, String studentId, String displayName,
            char[] adminPin, char[] studentPin, String expectation) {
        personalizePanel.fillInputs(cardId, studentId, displayName, DEMO_CLASS_CODE, adminPin, studentPin);
        log.accept("DEMO: PERSONALIZE — " + expectation);
        fire(personalizePanel.pressPersonalize(), "PERSONALIZE");
    }

    private void personalizeRejected(String cardId, String studentId, String name,
            String expectation) {
        personalizePanel.fillInputs(cardId, studentId, name, DEMO_CLASS_CODE,
                "1".repeat(8).toCharArray(), "1".repeat(6).toCharArray());
        log.accept("DEMO: PERSONALIZE kỳ vọng bị chặn — " + expectation);
        fire(personalizePanel.pressPersonalize(), "PERSONALIZE (input sai)");
    }

    private void readProfile(char[] pin, String expectation) {
        profilePanel.fillPin(pin);
        log.accept("DEMO: " + expectation);
        fire(profilePanel.pressProfile(), "GET_PROFILE");
    }

    private void identify(String expectation) {
        log.accept("DEMO: " + expectation);
        fire(app.pressIdentify(), "SELECT + GET_STATUS");
    }

    private void readMeta(char[] pin, String expectation) {
        profilePanel.fillPin(pin);
        log.accept("DEMO: " + expectation);
        fire(profilePanel.pressMeta(), "GET_PROFILE_CRYPTO_META");
    }

    private void pinOp(char[] pin, String expectation) {
        pinPanel.fillInputs(pin, null, null);
        log.accept("DEMO: " + expectation);
        fire(pinPanel.pressVerify(), "VERIFY_STUDENT_PIN");
    }

    private void changePin(char[] oldPin, char[] newPin) {
        pinPanel.fillInputs(oldPin, newPin, null);
        log.accept("DEMO: CHANGE PIN cũ sang PIN mới — kỳ vọng 9000");
        fire(pinPanel.pressChange(), "CHANGE_STUDENT_PIN");
    }

    private void unblockPin(char[] adminPin, char[] newPin, String expectation) {
        pinPanel.fillInputs(null, newPin, adminPin);
        log.accept("DEMO: " + expectation);
        fire(pinPanel.pressUnblock(), "UNBLOCK_STUDENT_PIN");
    }

    private java.util.List<Runnable> photoSteps(char[] adminPin, char[] studentPin) {
        return photoScenario.steps(adminPin, studentPin, shiftDigits(studentPin, 5));
    }

    private static char[] shiftDigits(char[] pin, int delta) {
        char[] shifted = new char[pin.length];
        for (int index = 0; index < pin.length; index++) {
            shifted[index] = (char) ('0' + (pin[index] - '0' + delta) % 10);
        }
        return shifted;
    }

    private char[] readDemoPin(String prompt, int expectedLength) {
        System.out.print(prompt);
        System.out.flush();
        try {
            StringBuilder line = new StringBuilder();
            int read;
            while ((read = System.in.read()) != -1 && read != '\n') {
                if (read != '\r') {
                    line.append((char) read);
                }
            }
            String value = line.toString().strip();
            if (value.length() != expectedLength) {
                return null;
            }
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                    return null;
                }
            }
            return value.toCharArray();
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private void wipe() {
        if (demoAdminPin != null) {
            Arrays.fill(demoAdminPin, '\0');
            demoAdminPin = null;
        }
        if (demoStudentPin != null) {
            Arrays.fill(demoStudentPin, '\0');
            demoStudentPin = null;
        }
        if (demoChangedPin != null) {
            Arrays.fill(demoChangedPin, '\0');
            demoChangedPin = null;
        }
        if (demoUnblockedPin != null) {
            Arrays.fill(demoUnblockedPin, '\0');
            demoUnblockedPin = null;
        }
        if (demoLockedPin != null) {
            Arrays.fill(demoLockedPin, '\0');
            demoLockedPin = null;
        }
    }
}
