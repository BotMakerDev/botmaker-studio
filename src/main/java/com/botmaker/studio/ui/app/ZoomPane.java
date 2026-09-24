package com.botmaker.studio.ui.app;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Orientation;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

/**
 * Draws one region {@code zoom} times its size while keeping it <em>responsive</em>: the content is laid out
 * at the width it would have at 100% in this space — {@code width / zoom} — and then scaled, so text wraps to
 * the column it actually gets rather than overflowing it (zoomed in) or wrapping at a column a fraction of
 * the screen wide (zoomed out).
 *
 * <p><b>Why a transform and not {@code em}.</b> Converting the canvas's CSS to {@code em} and moving
 * {@code -fx-font-size} would scale text and CSS padding, and nothing else: the block layer sets ~60 gaps,
 * insets and minimum widths in Java, and inline {@code -fx-padding} strings besides, all in pixels. Those
 * would stay put while the text grew around them. A {@link Scale} reaches every pixel the content draws,
 * and the width compensation below is what a bare scale would have lost — reflow.
 *
 * <p>Everything that measures in scene or screen space (drag-and-drop, a popup anchored to a slot,
 * {@code localToScene}) goes through the transform already. Only code comparing a child's <em>local</em>
 * bounds with this pane's size has to multiply by {@link #zoomProperty()}.
 */
final class ZoomPane extends Region {

    private final Region content;
    private final DoubleProperty zoom = new SimpleDoubleProperty(1.0);

    ZoomPane(Region content) {
        this.content = content;
        Scale scale = new Scale(1, 1, 0, 0);
        scale.xProperty().bind(zoom);
        scale.yProperty().bind(zoom);
        content.getTransforms().add(scale);
        getChildren().add(content);
        zoom.addListener((obs, was, is) -> requestLayout());
    }

    DoubleProperty zoomProperty() {
        return zoom;
    }

    private double z() {
        double value = zoom.get();
        return value > 0 ? value : 1.0;
    }

    /** Height depends on width — the content wraps — so the scroll pane must ask with the width it has. */
    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected void layoutChildren() {
        double z = z();
        double width = getWidth() / z;
        // Filling the viewport's height too, so the canvas background and its drop target reach the bottom
        // of the window however little program there is.
        double height = Math.max(content.prefHeight(width), getHeight() / z);
        content.resizeRelocate(0, 0, width, height);
    }

    @Override
    protected double computeMinWidth(double height) {
        return content.minWidth(-1) * z();
    }

    @Override
    protected double computePrefWidth(double height) {
        return content.prefWidth(-1) * z();
    }

    @Override
    protected double computeMinHeight(double width) {
        return content.minHeight(width < 0 ? -1 : width / z()) * z();
    }

    @Override
    protected double computePrefHeight(double width) {
        return content.prefHeight(width < 0 ? -1 : width / z()) * z();
    }
}
