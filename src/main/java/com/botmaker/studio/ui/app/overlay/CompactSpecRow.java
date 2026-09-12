package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentResolver;
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
 * <p>Visibility and the lock are {@link ComponentResolver}'s, asked exactly as the canvas asks, so a
 * component hidden on one surface cannot be visible on the other.
 */
public final class CompactSpecRow {

    private CompactSpecRow() {}

    /** The nodes for {@code spec} at HUD density, in declaration order. Empty for an empty spec. */
    public static List<Node> nodes(ComponentSpec spec, Audience audience, boolean locked) {
        if (spec == null || spec.components().isEmpty()) return List.of();

        List<Node> out = new ArrayList<>();
        for (BlockComponent component : spec.components()) {
            // Before the verdict, and deliberately: a body is dropped whatever the verdict would have been,
            // so its supplier is never called on this surface.
            if (component.kind() == BlockComponent.Kind.BODY) continue;

            ComponentResolver.Verdict verdict = ComponentResolver.resolve(component, audience, locked);
            if (!verdict.isVisible()) continue;

            Node node = component.node().get();
            // Null is "this affordance does not exist" — the convention the canvas builder documents.
            if (node == null) continue;

            if (verdict.isReadOnly()) {
                node.pseudoClassStateChanged(ReadOnlyDecorator.READ_ONLY, true);
            }
            out.add(node);
        }
        return out;
    }
}
