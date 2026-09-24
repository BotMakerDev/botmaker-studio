package com.botmaker.studio.ui.dnd;

import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * Where a block can go between two others, now that blocks stack touching. It takes no room: a zero-height
 * pane on the seam, with an 8px hit strip centred on it that reaches 4px into the block above and 4px into
 * the one below. Hovering the strip draws a line along the seam and a round "+" under the pointer; a drag over
 * it lights the same line in the drop colour.
 *
 * <p>It replaced a 12px gap between every pair of statements, which was the reason blocks never looked glued:
 * the gap was the insertion target, so it could not go without the target going too. Laid over the seam
 * instead of between the blocks, the target stays and the gap does not.
 *
 * <p>Everything but the zero-height pane is unmanaged and placed by hand, so nothing here moves a block.
 */
public final class InsertionSeam {

    /** How far the hit strip reaches, in total, across the seam. */
    public static final double HIT_HEIGHT = 8.0;
    /** The "+" is a circle this wide. */
    public static final double BUTTON_SIZE = 22.0;
    /** A seam's view order at rest: ahead of the blocks it lies across, so its strip is what the pointer finds. */
    public static final double RESTING_VIEW_ORDER = -1.0;
    /** A hovered seam goes further forward, so the "+" is drawn over whatever it overhangs. */
    public static final double HOVERED_VIEW_ORDER = -100.0;

    private InsertionSeam() {}

    /**
     * A seam. With {@code withInsertButton} false — a read-only body — it has no strip and no "+": nothing to
     * hover, and a strip that took the pointer from a locked block for nothing.
     */
    static Pane create(boolean withInsertButton) {
        Pane seam = new Pane();
        seam.getStyleClass().add("block-seam");
        seam.setMinHeight(0);
        seam.setPrefHeight(0);
        seam.setMaxHeight(0);
        seam.setViewOrder(RESTING_VIEW_ORDER);
        if (!withInsertButton) return seam;

        Region hit = new Region();
        hit.getStyleClass().add("seam-hit");
        hit.setManaged(false);
        hit.setPickOnBounds(true);

        Region line = new Region();
        line.getStyleClass().add("seam-line");
        line.setManaged(false);
        line.setMouseTransparent(true);

        seam.widthProperty().addListener((obs, was, width) -> {
            hit.resizeRelocate(0, -HIT_HEIGHT / 2, width.doubleValue(), HIT_HEIGHT);
            line.resizeRelocate(0, -1, width.doubleValue(), 2);
        });

        Button plus = new Button("+");
        plus.getStyleClass().add("separator-insert-button");
        plus.setFocusTraversable(false);
        plus.setManaged(false);
        plus.setVisible(false);
        plus.resize(BUTTON_SIZE, BUTTON_SIZE);
        plus.setLayoutY(-BUTTON_SIZE / 2);
        Tooltip.install(plus, new Tooltip("Insert a block here"));

        seam.setOnMouseEntered(e -> {
            if (e.isPrimaryButtonDown()) return; // a drag paints the line itself
            show(seam, plus, e.getX());
        });
        seam.setOnMouseMoved(e -> {
            if (plus.isVisible() && !plus.isHover()) place(plus, seam.getWidth(), e.getX());
        });
        seam.setOnMouseExited(e -> {
            // Its menu open, the "+" stays: the pointer is in the menu, and the "+" is what it hangs from.
            if (plus.getUserData() instanceof ContextMenu menu && menu.isShowing()) return;
            hide(seam, plus);
        });

        seam.getChildren().addAll(hit, line, plus);
        return seam;
    }

    static void show(Pane seam, Button plus, double x) {
        plus.setVisible(true);
        seam.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("seam-hover"), true);
        seam.setViewOrder(HOVERED_VIEW_ORDER);
        place(plus, seam.getWidth(), x);
    }

    static void hide(Pane seam, Button plus) {
        plus.setVisible(false);
        seam.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("seam-hover"), false);
        seam.setViewOrder(RESTING_VIEW_ORDER);
    }

    /** Under the pointer, kept inside the seam's width. */
    private static void place(Button plus, double width, double x) {
        double left = Math.max(0, Math.min(x - BUTTON_SIZE / 2, width - BUTTON_SIZE));
        plus.setLayoutX(left);
    }

    /** The seam's "+", or null for a read-only seam. */
    static Button plusOf(Pane seam) {
        for (javafx.scene.Node child : seam.getChildren()) {
            if (child instanceof Button button) return button;
        }
        return null;
    }
}
