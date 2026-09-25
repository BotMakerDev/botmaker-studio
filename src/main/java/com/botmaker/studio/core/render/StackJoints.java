package com.botmaker.studio.core.render;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * The notch at the top of a statement block and the tab under it, which is what makes a stack of blocks read
 * as pieces that fit together. The tab hangs below the block's bottom edge; the next block down is drawn
 * behind it (see {@code BodyBlock}), so it lies over that block's notch — the join is two shapes, one over the
 * other, and neither block needs to know about its neighbour.
 *
 * <p>Both are small unmanaged regions: they add nothing to the block's size and nothing moves for them. Their
 * outline is {@code -fx-shape} in {@code blocks.css}, their colour the block's own tokens, and the outlined
 * style hides them — a flat seam is that style's whole look.
 */
public final class StackJoints {

    /** Style class of the dent at the top of a block. */
    public static final String NOTCH = "block-notch";
    /** Style class of the tab below a block. */
    public static final String TAB = "block-tab";

    /** Distance of the joint from the block's left edge, past the breakpoint gutter. */
    public static final double LEFT = 22;
    public static final double WIDTH = 18;
    static final double DEPTH = 4;

    private StackJoints() {}

    /** Adds a notch and a tab to {@code block}, kept on its top and bottom edges as it resizes. */
    public static void attach(Pane block) {
        Region notch = joint(NOTCH);
        Region tab = joint(TAB);
        notch.resizeRelocate(LEFT, 0, WIDTH, DEPTH);
        block.heightProperty().addListener((obs, was, height) ->
                tab.resizeRelocate(LEFT, height.doubleValue(), WIDTH, DEPTH));
        block.getChildren().addAll(notch, tab);
    }

    private static Region joint(String styleClass) {
        Region joint = new Region();
        joint.getStyleClass().add(styleClass);
        joint.setManaged(false);
        joint.setMouseTransparent(true);
        return joint;
    }
}
