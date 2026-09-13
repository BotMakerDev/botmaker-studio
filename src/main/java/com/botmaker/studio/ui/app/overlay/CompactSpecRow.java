package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.core.render.ReadOnlyDecorator;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * A block's declared {@link ComponentSpec} at <b>HUD density</b> — the second renderer of one schema.
 *
 * <p>{@code ui/render/layout/ComponentLayoutBuilder} is the first: the canvas density, wrapping into a
 * {@code WrappingSentencePane}. This one draws the same components on one line inside a 340px panel. That
 * there are two is the entire point of the spec existing — several places in this application render the same
 * vocabulary at different densities, and every one of them was written by hand.
 *
 * <p><b>Bodies are dropped</b>, which is the one rule that differs from the canvas rather than merely looking
 * different: the HUD shows a block's branches as its own nested rows ({@code BlockTree.flatten}), so drawing
 * one inline would draw the program twice.
 *
 * <p>Everything else is asked exactly as the canvas asks it — a null supplier answer is an affordance that
 * does not exist, and a locked block's components are stamped read-only — so a component drawn on one surface
 * is drawn on the other.
 */
public final class CompactSpecRow {

    private CompactSpecRow() {}

    /** The nodes for {@code spec} at HUD density, in declaration order. Empty for an empty spec. */
    public static List<Node> nodes(ComponentSpec spec, boolean locked) {
        if (spec == null || spec.components().isEmpty()) return List.of();

        List<Node> out = new ArrayList<>();
        for (BlockComponent component : spec.components()) {
            if (component == null) continue;
            // A body is dropped before its supplier is reached, so it is never built on this surface.
            if (component.kind() == BlockComponent.Kind.BODY) continue;

            Node node = component.node().get();
            // Null is "this affordance does not exist" — the convention the canvas builder documents.
            if (node == null) continue;

            if (locked) {
                node.pseudoClassStateChanged(ReadOnlyDecorator.READ_ONLY, true);
            }
            out.add(node);
        }
        return out;
    }
}
