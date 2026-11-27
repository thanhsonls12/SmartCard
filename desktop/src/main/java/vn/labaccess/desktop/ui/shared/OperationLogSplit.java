package vn.labaccess.desktop.ui.shared;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import vn.labaccess.desktop.ui.foundation.UiScale;

public final class OperationLogSplit {
    private final SplitPane workSplit;

    private final VBox logColumn;

    private static final double NARROW_WIDTH = 920;

    private static final double SPLIT_BUSINESS = 0.65;

    private double horizontalSplit = SPLIT_BUSINESS;

    private double verticalSplit = 0.68;


    public OperationLogSplit(Node content, TitledPane logPane, ToggleButton logToggle) {
        logToggle.selectedProperty().bindBidirectional(logPane.expandedProperty());
        logPane.setExpanded(false);

        VBox business = new VBox(content);
        business.setMinHeight(260);
        business.setPadding(UiScale.centerInsets());

        logColumn = new VBox(logPane);
        logColumn.setMinWidth(320);
        business.setMinWidth(0);
        logColumn.setPadding(UiScale.centerInsets());
        VBox.setVgrow(logPane, Priority.ALWAYS);

        workSplit = new SplitPane(business);
        workSplit.getStyleClass().add("la-split");
        SplitPane.setResizableWithParent(logColumn, false);
        VBox.setVgrow(workSplit, Priority.ALWAYS);
        logPane.expandedProperty().addListener((observable, was, now) -> showLogColumn(now));
    }

    public SplitPane node() {
        return workSplit;
    }

    public void layoutForWidth(double width) {
        Orientation orientation = width < NARROW_WIDTH ? Orientation.VERTICAL : Orientation.HORIZONTAL;
        if (orientation == workSplit.getOrientation()) {
            return;
        }
        rememberLogSplit();
        workSplit.setOrientation(orientation);
        logColumn.setMinHeight(orientation == Orientation.VERTICAL ? UiScale.LOG_MIN_HEIGHT : 0);
        restoreLogSplit();
    }

    private void showLogColumn(boolean expanded) {
        boolean shown = workSplit.getItems().contains(logColumn);
        if (expanded == shown) {
            return;
        }
        if (expanded) {
            workSplit.getItems().add(logColumn);
            restoreLogSplit();
            return;
        }
        rememberLogSplit();
        workSplit.getItems().remove(logColumn);
        workSplit.setDividerPositions(1);
    }

    private void rememberLogSplit() {
        if (workSplit.getDividers().isEmpty()) {
            return;
        }
        double position = workSplit.getDividerPositions()[0];
        if (workSplit.getOrientation() == Orientation.HORIZONTAL) {
            horizontalSplit = position;
        } else {
            verticalSplit = position;
        }
    }

    private void restoreLogSplit() {
        workSplit.setDividerPositions(workSplit.getOrientation() == Orientation.HORIZONTAL
                ? horizontalSplit : verticalSplit);
    }
}
