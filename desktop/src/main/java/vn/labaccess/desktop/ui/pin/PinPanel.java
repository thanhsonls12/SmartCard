package vn.labaccess.desktop.ui.pin;

import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiScale;
import vn.labaccess.desktop.ui.foundation.UiSection;

import vn.labaccess.desktop.card.CardGateway;

import vn.labaccess.desktop.ui.shared.CardOpRunner;
import vn.labaccess.desktop.ui.shared.UiLabels;
import vn.labaccess.desktop.ui.shared.UiRows;

public final class PinPanel {

    private final Consumer<String> log;
    private final StudentPinPanel student;
    private final StudentPinUnblockPanel unblock;
    private final Label valueState = new Label();
    private final Label why = UiLabels.whyNote();
    private final VBox node;

    public PinPanel(CardGateway gateway, CardOpRunner runner, Consumer<String> log) {
        this.log = log;
        student = new StudentPinPanel(gateway, runner, log, this::showState);
        unblock = new StudentPinUnblockPanel(gateway, runner, log, this::showState);
        valueState.getStyleClass().add("la-note");
        valueState.setWrapText(true);
        UiLabels.applyNoteTone(valueState, UiLabels.Tone.NEUTRAL);
        VBox groups = new VBox(UiScale.SPACE_4,
                group("Kiểm tra PIN", student.verifyNode()), group("Đổi PIN", student.changeNode()),
                group("Mở khóa PIN", unblock.node()));
        node = UiSection.of("Bảo mật PIN sinh viên", groups, why,
                UiRows.valueBlock("Trạng thái PIN", valueState));
    }

    private static VBox group(String title, Node controls) {
        Label label = new Label(title);
        label.getStyleClass().add("la-title");
        return new VBox(UiScale.SPACE_2, label, controls);
    }

    public VBox node() {
        return node;
    }

    public void setEnabled(boolean enabled, String whyDisabled) {
        student.setEnabled(enabled);
        unblock.setEnabled(enabled);
        UiLabels.setWhy(why, whyDisabled);
    }

    public void clearInputs() {
        student.clearInputs();
        unblock.clearInputs();
    }

    public void clearState() {
        clearInputs();
        showValue(PinState.unknown().text(), UiLabels.Tone.NEUTRAL);
    }

    public void resetState(String reason) {
        clearInputs();
        PinState state = PinState.unknown();
        showValue(state.text(), UiLabels.Tone.NEUTRAL);
        log.accept("GUI hiển thị PIN = " + state.text() + " | " + reason);
    }

    private void showValue(String text, UiLabels.Tone tone) {
        valueState.setText(text);
        UiLabels.applyNoteTone(valueState, tone);
    }

    public boolean pressVerify() {
        return student.pressVerify();
    }

    public boolean pressChange() {
        return student.pressChange();
    }

    public boolean pressUnblock() {
        return unblock.pressUnblock();
    }

    public void fillInputs(char[] pin, char[] newPin, char[] adminPin) {
        if (pin != null) {
            student.fillCurrentPin(pin);
        }
        if (newPin != null) {
            student.fillNewPin(newPin);
            unblock.fillNewPin(newPin);
            student.fillConfirmation(newPin);
            unblock.fillConfirmation(newPin);
        }
        if (adminPin != null) {
            unblock.fillAdminPin(adminPin);
        }
    }

    private void showState(PinState.Outcome outcome) {
        PinState state = PinState.of(outcome.operation(), outcome.ok(), outcome.sw());
        showValue(state.text(), PinState.tone(outcome.ok(), outcome.sw()));
        log.accept("GUI hiển thị PIN = " + state.text());
    }
}
