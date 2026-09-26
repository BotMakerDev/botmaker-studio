package com.botmaker.studio.core.render;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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

        // A declaration is not a line the bot stops on: no strip, no dot, no double-click.
        if (!block.canHoldBreakpoint()) return;

        if (node instanceof Pane pane) {
            // Transparent click target spanning the reserved gutter strip: single left-click toggles the
            // breakpoint IDE-style (works even when no circle is showing yet, so it can *add* one).
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
            dot.visibleProperty().bind(block.breakpointActiveProperty());
            pane.getChildren().add(dot);

            // Double-click anywhere on the block also toggles (per user request). Added as an event handler (not
            // setOnMouseClicked) so it layers on top of any click handler the block itself installed in
            // createUINode (e.g. BooleanLiteralBlock's single-click value toggle) instead of clobbering it.
            if (!block.isReadOnly()) {
                node.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                        block.toggleBreakpoint();
                        e.consume();
                    }
                });
            }
        }
    }
}
