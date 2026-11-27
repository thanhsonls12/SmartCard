package vn.labaccess.desktop.ui.foundation;

import javafx.geometry.Orientation;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

public final class UiColumns extends Pane {

    private final double breakpoint;
    private final double firstRatio;
    private final double fixedFirstWidth;
    private final boolean equalHeights;
    private final Region first;
    private final Region second;

    public UiColumns(double breakpoint, double firstPercent, Region first, Region second) {
        this(breakpoint, firstPercent / 100, -1, false, first, second);
    }

    private UiColumns(double breakpoint, double firstRatio, double fixedFirstWidth, boolean equalHeights,
            Region first, Region second) {
        this.breakpoint = breakpoint;
        this.firstRatio = firstRatio;
        this.fixedFirstWidth = fixedFirstWidth;
        this.equalHeights = equalHeights;
        this.first = first;
        this.second = second;
        setMinWidth(0);
        first.setMinWidth(0);
        second.setMinWidth(0);
        getChildren().addAll(first, second);
    }

    public static UiColumns fixed(double breakpoint, double firstWidth, boolean equalHeights,
            Region first, Region second) {
        return new UiColumns(breakpoint, 0, firstWidth, equalHeights, first, second);
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computePrefWidth(double height) {
        double firstWidth = fixedFirstWidth > 0 ? fixedFirstWidth : first.prefWidth(-1);
        return firstWidth + UiScale.SPACE_4 + second.prefWidth(-1);
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
        double firstHeight = first.prefHeight(firstWidth);
        double secondHeight = second.prefHeight(available - UiScale.SPACE_4 - firstWidth);
        return Math.max(firstHeight, secondHeight);
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
        double secondWidth = secondWidth(width, firstWidth);
        double firstHeight = first.prefHeight(firstWidth);
        double secondHeight = second.prefHeight(secondWidth);
        double height = equalHeights ? Math.max(firstHeight, secondHeight) : firstHeight;
        first.resizeRelocate(0, 0, firstWidth, height);
        second.resizeRelocate(firstWidth + UiScale.SPACE_4, 0, secondWidth,
                equalHeights ? height : secondHeight);
    }

    private double firstWidth(double width) {
        return fixedFirstWidth > 0 ? fixedFirstWidth : Math.floor((width - UiScale.SPACE_4) * firstRatio);
    }

    private double secondWidth(double width, double firstWidth) {
        double available = width - UiScale.SPACE_4 - firstWidth;
        double max = second.maxWidth(-1);
        return max == Double.MAX_VALUE ? available : Math.min(available, max);
    }
}
