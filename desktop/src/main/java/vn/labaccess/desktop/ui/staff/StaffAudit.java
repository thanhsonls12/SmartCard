package vn.labaccess.desktop.ui.staff;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import vn.labaccess.desktop.card.CardLifecycle;
import vn.labaccess.desktop.card.CardOperationResult;
import vn.labaccess.desktop.card.CardStatus;

import vn.labaccess.desktop.backend.BackendClient;

import vn.labaccess.desktop.session.CardSession;
import vn.labaccess.desktop.session.EnrollmentFlow;

import vn.labaccess.desktop.ui.profile.ProfileUpdateFlow;

public final class StaffAudit {
    private final CardSession session;
    private final Consumer<String> log;

    public StaffAudit(CardSession session, Consumer<String> log) {
        this.session = session;
        this.log = log;
    }

    public <T> T execute(String label, Callable<T> operation) throws Exception {
        String action = action(label);
        if (action == null) {
            return operation.call();
        }
        String cardId = cardId();
        String id = session.backend().beginAudit(action, cardId, null, null);
        T result;
        try {
            result = operation.call();
        } catch (Exception failure) {
            finish(id, "UNKNOWN", cardId);
            throw failure;
        }
        finish(id, confirmed(result) ? "CLIENT_CONFIRMED" : "UNKNOWN",
                cardId == null ? cardId() : cardId);
        return result;
    }

    private String cardId() {
        CardStatus status = session.gateway().isReady() ? session.gateway().status() : null;
        return status != null && status.lifecycle() == CardLifecycle.ISSUED ? status.cardId() : null;
    }

    private void finish(String id, String outcome, String cardId) {
        try {
            session.backend().finishAudit(id, outcome, cardId);
        } catch (BackendClient.Failure failure) {
            log.accept("Audit chưa xác nhận kết quả, giữ yêu cầu UNKNOWN: " + id);
        }
    }

    private static boolean confirmed(Object result) {
        return result instanceof CardOperationResult card && card.ok()
                || result instanceof EnrollmentFlow.Outcome
                || result instanceof ProfileUpdateFlow.Result profile
                        && profile.state() == ProfileUpdateFlow.State.DONE;
    }

    private static String action(String label) {
        return switch (label) {
            case "PERSONALIZE" -> "PERSONALIZE";
            case "ENROLLMENT" -> "ENROLLMENT";
            case "UNBLOCK_STUDENT_PIN" -> "UNBLOCK";
            case "PHOTO upload" -> "PHOTO_UPLOAD";
            case "PHOTO replace" -> "PHOTO_REPLACE";
            case "PROFILE save" -> "PROFILE_SAVE";
            case "PROFILE reopen" -> "PROFILE_REOPEN";
            case "PROFILE replace" -> "PROFILE_REPLACE";
            default -> null;
        };
    }
}
