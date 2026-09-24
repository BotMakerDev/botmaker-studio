package com.botmaker.studio.core.component;

import com.botmaker.studio.core.AbstractCodeBlock;
import javafx.scene.Node;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One declared field of a block — a keyword, an expression slot, a picker, a body.
 *
 * <p><b>Why declared rather than built.</b> Blocks assemble their fields imperatively inside
 * {@code createUINode}, so every rule about drawing one has to be re-implemented per block, and the copies
 * drift — the same failure the flat template folder and the duplicated id→name switches produced elsewhere in
 * this codebase. Declaring the fields makes the rule one pass over one list, and makes it testable without a
 * JavaFX toolkit.
 *
 * <p>{@code node} is a {@link Supplier}, not a {@link Node}, on purpose: a component a renderer drops never
 * has its widget constructed. That matters for the expensive ones — an image-template picker builds
 * thumbnails — and it is what keeps the schema answerable headlessly.
 *
 * <p>A supplier may return {@code null}, which means "this affordance does not exist" and is simply skipped —
 * the same null-is-absence convention the layout builders already use for a read-only block's add/delete/change
 * buttons (see {@code SentenceLayoutBuilder.addNode}). That convention is the whole of the read-only rendering
 * rule: a locked block gets no button rather than a button that refuses.
 *
 * <p><b>What is deliberately not here.</b> A component carried two further axes until 2026-09-13 — a
 * {@code Visibility} naming the {@code Audience} it was drawn for, and a {@code WhenLocked} saying whether a
 * locked one was dimmed or dropped. Both are deleted. Every component ever constructed declared
 * {@code EVERYONE} + {@code SHOW}, so each enum had exactly one live value; the audience axis had been built
 * to separate an editor from a runner, and the runner draws no blocks at all. See
 * {@code docs/refactor/29-block-layer.md} §2 for the measurements and for what would justify bringing either
 * back — a second surface drawing blocks for somebody who did not write them, arriving with its own needs
 * rather than a guess at them.
 */
public record BlockComponent(String id, Kind kind, Supplier<Node> node, Consumer<Node> rebind) {

    /** What the component is, for styling and for callers that want to find one by shape rather than by id. */
    public enum Kind {
        /** Static text: a keyword or a connecting word. */
        LABEL,
        /** A slot holding a value-producing expression. */
        EXPRESSION_SLOT,
        /** A typed chooser: an image template, a rect, an enum constant, a capture target. */
        PICKER,
        /** A nested container of statements. */
        BODY,
        /** Anything a block builds itself and the schema does not model. */
        CUSTOM;

        /**
         * The CSS class a component of this kind carries — {@code bc-label}, {@code bc-slot}, … — so
         * {@code blocks.css} styles a part of a block by what it is rather than by which block drew it.
         * Stamped by the layout builders, which are the only places a component becomes a node.
         */
        public String styleClass() {
            return switch (this) {
                case LABEL -> "bc-label";
                case EXPRESSION_SLOT -> "bc-slot";
                case PICKER -> "bc-picker";
                case BODY -> "bc-body";
                case CUSTOM -> "bc-custom";
            };
        }

        /**
         * Adds this kind's class to {@code node}, once — unless {@code node} is a block's own root. A component
         * can <em>be</em> a block: the value filling a slot, or the next link of an else-if chain, which is
         * declared as a {@link #BODY} so it gets a row of its own. Such a node is styled as the block it is;
         * stamped {@code bc-body}, the chain link was painted as the hole in a C rather than as the C.
         */
        public void stamp(Node node) {
            if (node == null) return;
            List<String> classes = node.getStyleClass();
            if (classes.contains(AbstractCodeBlock.BLOCK_STYLE_CLASS) || classes.contains(styleClass())) return;
            classes.add(styleClass());
        }
    }

    public BlockComponent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("component id is required");
        if (kind == null || node == null) {
            throw new IllegalArgumentException("component " + id + " is missing a required field");
        }
    }

    /** A component, by id and shape. Rebuilt from scratch whenever the file is re-parsed. */
    public static BlockComponent of(String id, Kind kind, Supplier<Node> node) {
        return new BlockComponent(id, kind, node, null);
    }

    /**
     * A component whose widget may be <b>carried across a re-parse</b> rather than rebuilt, by re-pointing it
     * at the block that exists now.
     *
     * <p>Every edit re-parses the file and builds a whole new block tree, so a widget the user is typing in is
     * thrown away between keystrokes — which is why the overlay HUD keeps a pending-focus field and the canvas
     * restores its scroll position by hand. {@code rebind} is what lets one survive: the reconciler hands the
     * <em>old</em> node to the <em>new</em> block, and the block re-points its listeners at its own AST node.
     *
     * <p><b>Declaring it is a promise, and an unkept one is a bot that edits the wrong code.</b> A widget
     * built inside a supplier closes over the block and the {@code ASTNode} it was built from, and after a
     * re-parse both are dead objects: a {@code TextField} carried over without rebinding would go on writing
     * into a discarded tree, which is the failure {@code HostSlotContext} exists to prevent (it holds the
     * <em>slot</em> and never the expression, so a popup outliving its own re-parse writes into the new node).
     * So {@code rebind} must replace <em>every</em> handler that names the old block's state — not merely
     * refresh what is displayed.
     *
     * <p>Declaring nothing is always safe and is what {@link #of(String, Kind, Supplier)} does: the component
     * is rebuilt, exactly as every component was before this existed.
     */
    public static BlockComponent carried(String id, Kind kind, Supplier<Node> node, Consumer<Node> rebind) {
        return new BlockComponent(id, kind, node, rebind);
    }

    /** Whether this component's widget may be carried across a re-parse rather than rebuilt. */
    public boolean isCarried() {
        return rebind != null;
    }
}
