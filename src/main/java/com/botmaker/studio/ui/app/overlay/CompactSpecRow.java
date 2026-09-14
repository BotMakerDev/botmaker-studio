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
 * <p><b>For a {@link com.botmaker.studio.core.BranchingBlock} the whole tail after the first body is dropped,
 * not only the bodies in it.</b> That is the same rule read one level up rather than a second rule: an
 * {@code if}'s spec continues past its {@code then} with the word {@code Else}, the ⊕ that turns it into an
 * {@code else if} and the ✕ that removes it, and a branch chain's continues with one row per link — every one
 * of which is chrome <em>about a branch</em>, and the tree is already drawing those branches as captioned rows
 * of its own. A block that is not branching keeps its tail, which is why a {@code do/while} still reads
 * {@code do … while ⟨condition⟩} here: what follows its body is the loop's own sentence, not a branch.
 *
 * <p>Everything else is asked exactly as the canvas asks it — a null supplier answer is an affordance that
 * does not exist, and a locked block's components are stamped read-only — so a component drawn on one surface
 * is drawn on the other.
 */
public final class CompactSpecRow {

    private CompactSpecRow() {}

    /** The nodes for {@code spec} at HUD density, in declaration order. Empty for an empty spec. */
    public static List<Node> nodes(ComponentSpec spec, boolean locked) {
        return nodes(spec, locked, false);
    }

    /**
     * The nodes for {@code spec} at HUD density.
     *
     * @param branching whether the block declaring this spec is a
     *                  {@link com.botmaker.studio.core.BranchingBlock} — if it is, its spec stops at the first
     *                  body, because everything after that describes a branch the tree already draws.
     */
    public static List<Node> nodes(ComponentSpec spec, boolean locked, boolean branching) {
        if (spec == null || spec.components().isEmpty()) return List.of();

        List<Node> out = new ArrayList<>();
        for (BlockComponent component : spec.components()) {
            if (component == null) continue;
            // A body is dropped before its supplier is reached, so it is never built on this surface.
            if (component.kind() == BlockComponent.Kind.BODY) {
                if (branching) break;
                continue;
            }

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
