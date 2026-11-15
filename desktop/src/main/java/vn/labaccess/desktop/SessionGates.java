package vn.labaccess.desktop;

import java.util.Locale;

final class SessionGates {

    private static final String[] SESSION_LEVEL_REASONS = {"chưa kết nối thẻ", "chưa nhận diện thẻ",
        "cần đối soát giao dịch", "có giao dịch đang chờ đối soát", "nhật ký cần xử lý thủ công"};

    record State(boolean busy, boolean connected, boolean ready, boolean snapshotKnown,
            boolean transactionAllowed, boolean journalPresent, boolean validPending,
            boolean issued, String enrollmentProblem, CardStatus card) {
    }

    record Reasons(String connect, String identify, String disconnect, String readReceipt,
            String grant, String start, String end, String reconcile, String personalize,
            String enroll, String profile, String photo, String pin, String history) {
    }

    record NextStep(String text, int tab) {
    }

    private SessionGates() {
    }

    static Reasons of(State state) {
        String transaction = transaction(state);
        return new Reasons(connect(state), identify(state), identify(state), receipt(state),
                transaction, start(state, transaction), end(state, transaction),
                reconcile(state), personalize(state), enroll(state),
                access(state, "Thiếu quyền đọc hồ sơ — thẻ chưa phát hành."),
                access(state, "Thiếu quyền ảnh — thẻ chưa phát hành."),
                access(state, "Thiếu quyền PIN — thẻ chưa phát hành."),
                history(state));
    }

    static boolean sessionLevel(String reason) {
        if (reason == null) {
            return false;
        }
        String text = reason.toLowerCase(Locale.ROOT);
        if (text.equals(App.BUSY_WHY) || text.equals(App.BUSY_NOTE.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String prefix : SESSION_LEVEL_REASONS) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String panelNote(String reason) {
        return sessionLevel(reason) ? null : reason;
    }

    static NextStep nextStep(boolean closing, State state, String enrollment,
            boolean inputProblem, int activeTab) {
        if (closing) {
            return new NextStep("chờ cửa sổ đóng", -1);
        }
        if (state.busy()) {
            return new NextStep("chờ thao tác đang chạy xong", -1);
        }
        if (state.journalPresent() && !state.validPending()) {
            return new NextStep("mở Hướng dẫn khôi phục ở đầu cửa sổ", -1);
        }
        if (inputProblem) {
            return new NextStep("kiểm tra thông tin vừa nhập rồi thử lại", activeTab);
        }
        if (!state.connected()) {
            return new NextStep("kết nối đầu đọc", -1);
        }
        if (!state.ready()) {
            return new NextStep("nhận diện thẻ", -1);
        }
        if (state.journalPresent()) {
            return new NextStep("đối soát giao dịch đang chờ", App.TAB_OPERATIONS);
        }
        if (!state.transactionAllowed()) {
            return new NextStep("kiểm tra lỗi đối soát trong Nhật ký", -1);
        }
        if (!state.issued()) {
            return new NextStep("phát hành thẻ (tab Cấp thẻ & bảo mật)", App.TAB_CARD);
        }
        if ("chua-dang-ky".equals(enrollment)) {
            return new NextStep("đăng ký thẻ", App.TAB_ENROLL);
        }
        if (!"da-dang-ky".equals(enrollment)) {
            return new NextStep("kiểm tra Backend rồi nhận diện lại thẻ", -1);
        }
        if (!state.snapshotKnown() || !authenticated(state)) {
            return new NextStep("nhập PIN học viên và đọc trạng thái thẻ", App.TAB_OPERATIONS);
        }
        if (state.card().hasActiveVisit()) {
            return new NextStep("đóng ca khi kết thúc", App.TAB_OPERATIONS);
        }
        return new NextStep(state.card().balance() <= 0 ? "cấp lượt để mở ca"
                : "nhập PIN học viên và mở ca", App.TAB_OPERATIONS);
    }

    private static String connect(State state) {
        if (state.busy()) {
            return App.BUSY_WHY;
        }
        return state.connected() ? "đã kết nối thẻ này" : null;
    }

    private static String identify(State state) {
        if (state.busy()) {
            return App.BUSY_WHY;
        }
        return state.connected() ? null : "chưa kết nối thẻ";
    }

    private static String receipt(State state) {
        if (state.busy()) {
            return App.BUSY_WHY;
        }
        if (!state.ready()) {
            return state.connected() ? "chưa nhận diện thẻ" : "chưa kết nối thẻ";
        }
        if (!state.issued()) {
            return "thẻ chưa phát hành — phát hành thẻ trước";
        }
        if (state.journalPresent()) {
            return journalReason(state);
        }
        return state.transactionAllowed() ? null : "cần đối soát giao dịch trước";
    }

    private static String transaction(State state) {
        if (state.busy()) {
            return App.BUSY_WHY;
        }
        if (!state.connected()) {
            return "chưa kết nối thẻ";
        }
        if (!state.ready()) {
            return "chưa nhận diện thẻ";
        }
        if (state.journalPresent()) {
            return journalReason(state);
        }
        if (!state.transactionAllowed()) {
            return "cần đối soát giao dịch trước";
        }
        if (!state.snapshotKnown()) {
            return "chưa đọc được trạng thái thẻ — bấm Đọc trạng thái thẻ";
        }
        if (state.enrollmentProblem() != null) {
            return state.enrollmentProblem()
                    + " — kiểm tra đăng ký thẻ trước khi giao dịch";
        }
        return authenticated(state) ? null : "cần PIN học viên đúng để đọc trạng thái thẻ";
    }

    private static boolean authenticated(State state) {
        return state.card() != null && state.card().studentStateAvailable();
    }

    private static String start(State state, String base) {
        if (base != null) {
            return base;
        }
        if (state.card().hasActiveVisit()) {
            return "thẻ đang có ca mở";
        }
        return state.card().balance() <= 0 ? "hết lượt — cấp lượt trước" : null;
    }

    private static String end(State state, String base) {
        if (base != null) {
            return base;
        }
        return state.card().hasActiveVisit() ? null : "thẻ không có ca đang mở";
    }

    private static String reconcile(State state) {
        if (state.busy()) {
            return App.BUSY_WHY;
        }
        if (state.journalPresent() && !state.validPending()) {
            return "nhật ký cần xử lý thủ công — mở Hướng dẫn khôi phục";
        }
        if (!state.ready()) {
            return "chưa nhận diện thẻ";
        }
        return state.validPending() ? null : "không có giao dịch nào đang chờ đối soát";
    }

    private static String journalReason(State state) {
        return state.validPending() ? "có giao dịch đang chờ đối soát"
                : "nhật ký cần xử lý thủ công — mở Hướng dẫn khôi phục";
    }

    private static String personalize(State state) {
        if (state.busy()) {
            return App.BUSY_NOTE;
        }
        if (!state.connected()) {
            return "Chưa kết nối thẻ.";
        }
        if (state.journalPresent()) {
            return journalReason(state);
        }
        return state.issued() ? "Thẻ đã phát hành — không phát hành lại." : null;
    }

    private static String enroll(State state) {
        if (state.busy()) {
            return App.BUSY_NOTE;
        }
        if (!state.connected()) {
            return "Chưa kết nối thẻ.";
        }
        return state.issued() ? null : "Thẻ chưa phát hành — phát hành thẻ trước khi đăng ký.";
    }

    private static String access(State state, String missing) {
        if (!state.connected()) {
            return "Chưa kết nối thẻ.";
        }
        if (!state.ready()) {
            return "Chưa nhận diện thẻ.";
        }
        if (state.busy()) {
            return App.BUSY_NOTE;
        }
        return state.issued() ? null : missing;
    }

    private static String history(State state) {
        if (!state.connected()) {
            return "Chưa kết nối thẻ.";
        }
        if (!state.ready()) {
            return "Chưa nhận diện thẻ.";
        }
        if (state.busy()) {
            return App.BUSY_NOTE;
        }
        String cardId = state.card().cardId();
        return cardId == null || cardId.isBlank() ? "Chưa có mã thẻ để đọc lịch sử." : null;
    }
}
