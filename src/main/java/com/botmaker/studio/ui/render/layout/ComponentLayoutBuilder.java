package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentResolver;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.core.render.ReadOnlyDecorator;
import javafx.geometry.Pos;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link ComponentSpec} — the schema-driven counterpart to {@link SentenceLayoutBuilder}, which
 * every un-migrated block still uses.
 *
 * <p>It adds no styling, no spacing rule and no container of its own: the result is the same
 * {@link WrappingSentencePane} a sentence layout produces, so CSS, {@code styleContainer} and every caller that
 * reaches into {@code getChildren()} are unaffected. The only thing this builder does that the sentence
 * builder does not is <em>ask</em> — {@link ComponentResolver} decides, per component, between drawing it,
 * drawing it read-only, and not building it at all.
 *
 * <p>A hidden component's {@code node} supplier is never invoked, so its widget never enters the scene graph.
 */
public final class ComponentLayoutBuilder {

    private final ComponentSpec spec;
    private final Audience audience;
    private final boolean locked;
    private double spacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;

    ComponentLayoutBuilder(ComponentSpec spec, Audience audience, boolean locked) {
        this.spec = spec == null ? ComponentSpec.empty() : spec;
        this.audience = audience == null ? Audience.EDITOR : audience;
        this.locked = locked;
    }

    public ComponentLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    public ComponentLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    public WrappingSentencePane build() {
        List<Node> nodes = new ArrayList<>();
        for (Rendered rendered : render(spec, audience, locked)) nodes.add(rendered.node());
        return pane(nodes, spacing, alignment);
    }

    /** One component that survived the verdict, paired with the node it built. */
    record Rendered(BlockComponent component, Node node) {}

    /**
     * Every component of {@code spec} that is drawn, in declaration order, each already stamped read-only if
     * the verdict said so.
     *
     * <p>Shared with {@link StackLayoutBuilder}, which needs the components as well as their nodes so it can
     * break a block into rows at each {@link BlockComponent.Kind#BODY}. Two callers, one filter: a second
     * place deciding what is visible is a canvas and a HUD disagreeing about the same component, which is the
     * thing {@link ComponentResolver} exists to stop.
     */
    static List<Rendered> render(ComponentSpec spec, Audience audience, boolean locked) {
        List<Rendered> out = new ArrayList<>();
        if (spec == null) return out;
        for (BlockComponent component : spec.components()) {
            ComponentResolver.Verdict verdict = ComponentResolver.resolve(component, audience, locked);
            if (!verdict.isVisible()) continue;

            Node node = component.node().get();
            // Null is "this affordance does not exist", the convention SentenceLayoutBuilder.addNode
            // documents — a read-only block's buttons return null rather than a disabled control.
            if (node == null) continue;

            if (verdict.isReadOnly()) {
                node.pseudoClassStateChanged(ReadOnlyDecorator.READ_ONLY, true);
            }
            out.add(new Rendered(component, node));
        }
        return out;
    }

    /** The sentence row itself — the same {@link WrappingSentencePane} a hand-assembled sentence produces. */
    static WrappingSentencePane pane(List<Node> nodes, double spacing, Pos alignment) {
        WrappingSentencePane container = new WrappingSentencePane(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(nodes);
        return container;
    }
}
