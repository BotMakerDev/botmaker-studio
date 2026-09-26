package com.botmaker.studio.ui.dnd;

import javafx.animation.AnimationTimer;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * One "+" per body that follows the pointer over the whole stack (2026-09-26), gliding to the seam nearest
 * it — above or below the statement under the pointer — and along it, so a block can be inserted between any
 * two without first finding the 8px strip on their seam.
 *
 * <p><b>It glides rather than jumps.</b> Where the pointer is only sets a target; an {@link AnimationTimer}
 * moves the "+" a fraction of the way there each frame, so crossing from one seam to the next is a slide, and
 * the "+" stops the moment it arrives. The seam it points at is lit as it would be under the strip.
 *
 * <p><b>The innermost body answers.</b> A statement inside a loop is inside two bodies; the one the pointer is
 * over is the nearest body around the event's target, and showing its "+" hides whichever was showing before.
 *
 * <p>What a click does is the seam's own insert — {@link #ACTION} on the seam, put there by
 * {@code BlockDragAndDropManager.enableSeparatorClick} — anchored on this "+". The seam's own strip stays for
 * drag and drop; its own "+" stays for a seam outside a body that glides.
 */
public final class InsertGlide {

    /** Marks a body that has a glide, so its seams leave their own "+" hidden. */
    static final String KEY = "botmaker.insert-glide";
    /** On a seam: what inserting there does, given the node the menu hangs from. */
    static final String ACTION = "botmaker.seam-insert";
    /** On a seam: what the "+" says there, when it inserts somewhere other than under the pointer. */
    static final String HINT = "botmaker.seam-hint";

    private static final PseudoClass SEAM_HOVER = PseudoClass.getPseudoClass("seam-hover");
    /** The share of the remaining distance covered each frame: high enough to keep up, low enough to glide. */
    private static final double EASE = 0.28;
    private static final double SIZE = InsertionSeam.BUTTON_SIZE;

    /** The glide showing now, anywhere on the canvas. There is one pointer, so there is one "+". */
    private static InsertGlide showing;

    private final VBox body;
    private final Button plus = new Button("+");
    private final Tooltip tip = new Tooltip("Insert a block here");
    private Pane target;
    private double x;
    private double y;
    private double toX;
    private double toY;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            x += (toX - x) * EASE;
            y += (toY - y) * EASE;
            if (Math.abs(toX - x) < 0.5 && Math.abs(toY - y) < 0.5) {
                x = toX;
                y = toY;
                stop();
            }
            plus.relocate(x, y);
        }
    };

    private InsertGlide(VBox body) {
        this.body = body;
        plus.getStyleClass().addAll("separator-insert-button", "insert-glide");
        plus.setFocusTraversable(false);
        plus.setManaged(false);
        plus.setVisible(false);
        plus.resize(SIZE, SIZE);
        plus.setViewOrder(InsertionSeam.HOVERED_VIEW_ORDER - 1);
        plus.setTooltip(tip);
        plus.setOnAction(e -> {
            if (target != null && target.getProperties().get(ACTION) instanceof Consumer<?> action) {
                @SuppressWarnings("unchecked")
                Consumer<Button> open = (Consumer<Button>) action;
                open.accept(plus);
            }
            e.consume();
        });
    }

    /** Gives {@code body} — a column of statements and the seams between them — a gliding "+". */
    public static void install(VBox body) {
        InsertGlide glide = new InsertGlide(body);
        body.getProperties().put(KEY, glide);
        body.getChildren().add(glide.plus);
        body.addEventHandler(MouseEvent.MOUSE_MOVED, glide::moved);
        body.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (!glide.menuOpen()) glide.hide();
        });
        body.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getTarget() != glide.plus && !glide.menuOpen()) glide.hide();
        });
    }

    /** Whether {@code seam} sits in a body whose glide draws the "+" for it. */
    static boolean glides(Pane seam) {
        return seam.getParent() != null && seam.getParent().getProperties().containsKey(KEY);
    }

    private void moved(MouseEvent e) {
        if (e.isPrimaryButtonDown() || menuOpen() || plus.isHover()) return;
        if (!(e.getTarget() instanceof Node node) || innermostBody(node) != body) return;
        Pane nearest = nearestSeam(e.getY());
        if (nearest == null) {
            hide();
            return;
        }
        if (showing != this) {
            if (showing != null) showing.hide();
            showing = this;
        }
        light(nearest);
        toX = InsertionSeam.xFor(nearest, e.getX() - nearest.getLayoutX()) + nearest.getLayoutX();
        toY = nearest.getLayoutY() - SIZE / 2;
        if (!plus.isVisible()) {
            x = toX;
            y = toY;
            plus.relocate(x, y);
            plus.setVisible(true);
        } else {
            timer.start();
        }
    }

    /** The seam, among this body's own children, whose line is closest to {@code pointerY}. */
    private Pane nearestSeam(double pointerY) {
        Pane best = null;
        double distance = Double.MAX_VALUE;
        for (Node child : body.getChildren()) {
            if (!(child instanceof Pane seam) || !child.getStyleClass().contains("block-seam")) continue;
            if (!seam.getProperties().containsKey(ACTION)) continue;
            double d = Math.abs(seam.getLayoutY() - pointerY);
            if (d < distance) {
                distance = d;
                best = seam;
            }
        }
        return best;
    }

    private void light(Pane seam) {
        if (seam == target) return;
        if (target != null) {
            target.pseudoClassStateChanged(SEAM_HOVER, false);
            target.setViewOrder(InsertionSeam.RESTING_VIEW_ORDER);
        }
        target = seam;
        seam.pseudoClassStateChanged(SEAM_HOVER, true);
        seam.setViewOrder(InsertionSeam.HOVERED_VIEW_ORDER);
        tip.setText(seam.getProperties().get(HINT) instanceof String hint ? hint : "Insert a block here");
    }

    private void hide() {
        timer.stop();
        plus.setVisible(false);
        if (target != null) {
            target.pseudoClassStateChanged(SEAM_HOVER, false);
            target.setViewOrder(InsertionSeam.RESTING_VIEW_ORDER);
            target = null;
        }
        if (showing == this) showing = null;
    }

    private boolean menuOpen() {
        return plus.getUserData() instanceof ContextMenu menu && menu.isShowing();
    }

    /** Called when the "+"'s menu closes: the "+" stays only while the pointer is still over the body. */
    static void menuClosed(Button plus) {
        if (plus.getParent() != null && plus.getParent().getProperties().get(KEY) instanceof InsertGlide glide
                && glide.plus == plus && !glide.body.isHover()) {
            glide.hide();
        }
    }

    private static Node innermostBody(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at.getProperties().containsKey(KEY)) return at;
        }
        return null;
    }
}
