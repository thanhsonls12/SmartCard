package vn.labaccess.desktop;

import javafx.geometry.Orientation;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

final class UiColumns extends Pane {

    private final double breakpoint;
    private final double firstRatio;
    private final Region first;
    private final Region second;

    UiColumns(double breakpoint, double firstPercent, Region first, Region second) {
        this.breakpoint = breakpoint;
        this.firstRatio = firstPercent / 100;
        this.first = first;
        this.second = second;
        setMinWidth(0);
        first.setMinWidth(0);
        second.setMinWidth(0);
        getChildren().addAll(first, second);
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computePrefWidth(double height) {
        return first.prefWidth(-1) + UiScale.SPACE_4 + second.prefWidth(-1);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        double available = width < 0 ? computePrefWidth(-1) : width;
        if (available < breakpoint) {
            return first.prefHeight(available) + UiScale.SPACE_4 + second.prefHeight(available);
        }
        double firstWidth = firstWidth(available);
        return Math.max(first.prefHeight(firstWidth),
                second.prefHeight(available - UiScale.SPACE_4 - firstWidth));
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        if (width < breakpoint) {
            double firstHeight = first.prefHeight(width);
            first.resizeRelocate(0, 0, width, firstHeight);
            second.resizeRelocate(0, firstHeight + UiScale.SPACE_4, width, second.prefHeight(width));
            return;
        }
        double firstWidth = firstWidth(width);
        double secondWidth = width - UiScale.SPACE_4 - firstWidth;
        first.resizeRelocate(0, 0, firstWidth, first.prefHeight(firstWidth));
        second.resizeRelocate(firstWidth + UiScale.SPACE_4, 0, secondWidth, second.prefHeight(secondWidth));
    }

    private double firstWidth(double width) {
        return Math.floor((width - UiScale.SPACE_4) * firstRatio);
    }
}
