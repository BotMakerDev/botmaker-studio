package com.botmaker.studio.ui.dnd;

import com.botmaker.studio.core.render.StackJoints;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a block can go between two others, now that blocks stack touching. It takes no room: a zero-height
 * pane on the seam, with an 8px hit strip centred on it that reaches 4px into the block above and 4px into
 * the one below. Hovering the strip draws a line along the seam and a round "+" beside the joint; a drag over
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
    /** Where the "+" sits: just right of the notch and tab that join two blocks. */
    public static final double PLUS_X = StackJoints.LEFT + StackJoints.WIDTH + 8;

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
            show(seam, plus);
            follow(seam, plus, e.getX());
        });
        seam.setOnMouseMoved(e -> {
            if (!plus.isVisible() || e.getTarget() == plus || plus.isHover()) return;
            if (plus.getUserData() instanceof ContextMenu menu && menu.isShowing()) return;
            follow(seam, plus, e.getX());
        });
        seam.setOnMouseExited(e -> {
            // Its menu open, the "+" stays: the pointer is in the menu, and the "+" is what it hangs from.
            if (plus.getUserData() instanceof ContextMenu menu && menu.isShowing()) return;
            hide(seam, plus);
        });

        seam.getChildren().addAll(hit, line, plus);
        return seam;
    }

    static void show(Pane seam, Button plus) {
        plus.setVisible(true);
        seam.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("seam-hover"), true);
        seam.setViewOrder(HOVERED_VIEW_ORDER);
        place(plus, seam.getWidth());
    }

    static void hide(Pane seam, Button plus) {
        plus.setVisible(false);
        seam.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("seam-hover"), false);
        seam.setViewOrder(RESTING_VIEW_ORDER);
    }

    /** Beside the joint, kept inside the seam's width: where the "+" rests before the pointer moves. */
    private static void place(Button plus, double width) {
        plus.setLayoutX(Math.max(0, Math.min(PLUS_X, width - BUTTON_SIZE)));
    }

    /**
     * Under the pointer, and never on a control of the two blocks the seam joins (2026-09-26). It followed the
     * pointer before 2026-09-25 and landed on the delete button of the block below, so it was pinned beside the
     * joint — which put it far from wherever the user was looking. Now it follows, and steps aside: the
     * controls of the neighbours that reach into the "+"'s band are read off the scene and the "+" takes the
     * free spot nearest the pointer.
     */
    static void follow(Pane seam, Button plus, double pointerX) {
        double width = seam.getWidth();
        List<double[]> blocked = new ArrayList<>();
        if (seam.getParent() != null) {
            List<Node> siblings = seam.getParent().getChildrenUnmodifiable();
            int at = siblings.indexOf(seam);
            Bounds band = seam.localToScene(new BoundingBox(0, -BUTTON_SIZE / 2, width, BUTTON_SIZE));
            for (int i : new int[]{at - 1, at + 1}) {
                if (i >= 0 && i < siblings.size()) collectControls(siblings.get(i), band, seam, blocked);
            }
        }
        plus.setLayoutX(freeX(pointerX - BUTTON_SIZE / 2, Math.min(PLUS_X, width - BUTTON_SIZE),
                width - BUTTON_SIZE, BUTTON_SIZE, blocked));
    }

    /** The controls under {@code node} that cross {@code band}, as [minX, maxX] in the seam's coordinates. */
    private static void collectControls(Node node, Bounds band, Pane seam, List<double[]> out) {
        if (!node.isVisible()) return;
        Bounds scene = node.localToScene(node.getBoundsInLocal());
        if (scene == null || !scene.intersects(band)) return;
        if (node instanceof Control && !(node instanceof Label)) {
            Bounds local = seam.sceneToLocal(scene);
            out.add(new double[]{local.getMinX(), local.getMaxX()});
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectControls(child, band, seam, out);
        }
    }

    /** The gap kept between the "+" and a control it steps aside for. */
    static final double MARGIN = 4;

    /**
     * The x nearest {@code want}, inside {@code [min, max]}, where a {@code size}-wide button overlaps none of
     * {@code blocked} (each [minX, maxX], with a {@link #MARGIN}). Falls back to {@code min} when nothing is free.
     */
    static double freeX(double want, double min, double max, double size, List<double[]> blocked) {
        if (max < min) return Math.max(0, max);
        List<Double> candidates = new ArrayList<>(List.of(clamp(want, min, max)));
        for (double[] b : blocked) {
            candidates.add(clamp(b[0] - size - MARGIN, min, max));
            candidates.add(clamp(b[1] + MARGIN, min, max));
        }
        return candidates.stream()
                .filter(x -> blocked.stream().noneMatch(b -> x < b[1] + MARGIN && x + size > b[0] - MARGIN))
                .min(Comparator.comparingDouble(x -> Math.abs(x - want)))
                .orElse(min);
    }

    private static double clamp(double x, double min, double max) {
        return Math.max(min, Math.min(max, x));
    }

    /** The seam's "+", or null for a read-only seam. */
    static Button plusOf(Pane seam) {
        for (javafx.scene.Node child : seam.getChildren()) {
            if (child instanceof Button button) return button;
        }
        return null;
    }
}
