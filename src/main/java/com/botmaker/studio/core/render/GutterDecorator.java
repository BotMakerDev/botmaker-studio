package com.botmaker.studio.core.render;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Reserves a left gutter on the block and, when the node can host children and the block is a line the bot
 * can stop on, adds the floating breakpoint dot whose visibility tracks the block's breakpoint state.
 */
public final class GutterDecorator implements BlockDecorator {

    private static final double DOT_SIZE = 13.0;
    /** Roughly one header row: the dot sits in the middle of it, or of the whole block if it is shorter. */
    private static final double FIRST_LINE_HEIGHT = 30.0;

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        if (!(node instanceof Region region)) return;
        // Expressions are not lines, so they get neither. A breakpoint stops on a statement — there is nothing
        // to stop on inside `count + 1` — and the 12px strip reserved for its circle was padding every inline
        // pill in the editor, which is what made an expression slot's drag outline reach well to the left of
        // the expression it belongs to. The outline now hugs the slot because the slot is now its own size.
        if (block instanceof com.botmaker.studio.core.ExpressionBlock) return;
        // Nor is a body: it is the list of lines, each with its own gutter. Its strip was a 12px gap between a
        // C's arm and the stack it holds.
        if (block instanceof com.botmaker.studio.core.BodyBlock) return;

        double gutter = BlockTheme.current().spacing().gutter();
        Insets existing = region.getPadding();
        region.setPadding(new Insets(
                existing.getTop(),
                existing.getRight(),
                existing.getBottom(),
                existing.getLeft() + gutter
        ));

        // A declaration is not a line the bot stops on: no strip, no dot.
        if (!block.canHoldBreakpoint()) return;
        node.getProperties().put(BREAKPOINT_ROOT, block);

        // One way to set a breakpoint on the canvas, as in an IDE: the gutter strip, which shows a faint dot
        // while the pointer is over it and toggles on a single click — plus the block's right-click menu. The
        // double-click anywhere on a block (2026-09-26) is gone (2026-09-27): it fired on double-clicks meant
        // for the block's own controls, and a breakpoint set by accident is one nobody knows to look for.
        if (node instanceof Pane pane) {
            Rectangle hitStrip = new Rectangle();
            hitStrip.setManaged(false);
            hitStrip.setFill(Color.TRANSPARENT);
            hitStrip.setLayoutX(existing.getLeft());
            hitStrip.setWidth(gutter);
            hitStrip.heightProperty().bind(pane.heightProperty());
            hitStrip.setCursor(Cursor.HAND);
            if (!block.isReadOnly()) {
                hitStrip.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) {
                        block.toggleBreakpoint();
                        e.consume();
                    }
                });
            }
            pane.getChildren().add(hitStrip);

            // Styled by .breakpoint-dot in blocks.css, so it follows the theme's danger colour. Centred on the
            // block's first line rather than its middle: on an if or a loop the middle is somewhere in the body.
            Region dot = new Region();
            dot.getStyleClass().add("breakpoint-dot");
            dot.setManaged(false);
            dot.setMouseTransparent(true); // clicks fall through to the hit strip below
            dot.resize(DOT_SIZE, DOT_SIZE);
            dot.setLayoutX(existing.getLeft() + (gutter - DOT_SIZE) / 2);
            dot.layoutYProperty().bind(Bindings.min(pane.heightProperty(), FIRST_LINE_HEIGHT)
                    .subtract(DOT_SIZE).divide(2));
            // Shown when set, and as a ghost (:ghost, blocks.css) while the pointer is over an editable strip —
            // the answer to "where do I click", which an empty gutter never gave.
            javafx.beans.value.ObservableBooleanValue hovering = block.isReadOnly()
                    ? new javafx.beans.property.SimpleBooleanProperty(false) : hitStrip.hoverProperty();
            dot.visibleProperty().bind(block.breakpointActiveProperty().or(hovering));
            // A binding listens to the block weakly, so a block that outlives this render (blocks are reused
            // across re-parses) does not keep every old dot alive; the dot keeps the binding.
            javafx.beans.binding.BooleanBinding unset = block.breakpointActiveProperty().not();
            dot.getProperties().put(GHOST, unset);
            unset.addListener((obs, was, now) -> dot.pseudoClassStateChanged(GHOST, now));
            dot.pseudoClassStateChanged(GHOST, unset.get());
            pane.getChildren().add(dot);
        }
    }

    /** A dot drawn under the pointer where no breakpoint is set yet. */
    static final javafx.css.PseudoClass GHOST = javafx.css.PseudoClass.getPseudoClass("ghost");

    /** Marks a block's root as one a breakpoint can be set on — how a test finds the block a node draws. */
    private static final String BREAKPOINT_ROOT = "botmaker.breakpoint-root";
}
