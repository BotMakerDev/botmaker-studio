package com.botmaker.studio.core.component;

import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The ordered list of {@link BlockComponent}s a block declares — its rendering schema.
 *
 * <p>Order here is the order on screen, so a spec reads as the sentence the block will draw. A block that has
 * not declared one keeps rendering through {@code createUINode} exactly as before; the two coexist by design,
 * which is what lets the migration proceed a block at a time rather than as one change across every block
 * class.
 *
 * <p>Pure data and JavaFX-free apart from the {@link Node} suppliers it never calls, so a spec can be asserted
 * on in a headless test — the same reason {@code ui/app/overlay/BlockTree} was kept free of JavaFX.
 */
public record ComponentSpec(List<BlockComponent> components) {

    private static final ComponentSpec EMPTY = new ComponentSpec(List.of());

    public ComponentSpec {
        components = List.copyOf(components);
        long distinct = components.stream().map(BlockComponent::id).distinct().count();
        if (distinct != components.size()) {
            throw new IllegalArgumentException("duplicate component id in spec: " + components);
        }
    }

    /** A block with no declared components — the starting point for a builder, and a valid spec. */
    public static ComponentSpec empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The component declared under {@code id}, for callers that need to reach one by name. */
    public Optional<BlockComponent> find(String id) {
        return components.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Fluent assembly, mirroring the existing {@code BlockLayout} builders so blocks read the same way. */
    public static final class Builder {
        private final List<BlockComponent> components = new ArrayList<>();

        private Builder() {}

        public Builder add(BlockComponent component) {
            components.add(component);
            return this;
        }

        /** A keyword or connecting word everyone sees. */
        public Builder label(String id, Supplier<Node> node) {
            return add(BlockComponent.of(id, BlockComponent.Kind.LABEL, node));
        }

        public Builder slot(String id, Supplier<Node> node) {
            return add(BlockComponent.of(id, BlockComponent.Kind.EXPRESSION_SLOT, node));
        }

        public Builder picker(String id, Supplier<Node> node) {
            return add(BlockComponent.of(id, BlockComponent.Kind.PICKER, node));
        }

        /** Something the block builds itself that the vocabulary does not model — a text field, a selector. */
        public Builder custom(String id, Supplier<Node> node) {
            return add(BlockComponent.of(id, BlockComponent.Kind.CUSTOM, node));
        }

        /**
         * A {@code CUSTOM} component whose widget may be <b>carried across a re-parse</b> instead of rebuilt —
         * see {@link BlockComponent#carried}. Declaring {@code rebind} is a promise to re-point every handler
         * on the given node at this block's own AST node; declaring nothing is always safe.
         */
        public Builder carried(String id, Supplier<Node> node, java.util.function.Consumer<Node> rebind) {
            return add(BlockComponent.carried(id, BlockComponent.Kind.CUSTOM, node, rebind));
        }

        public Builder body(String id, Supplier<Node> node) {
            return add(BlockComponent.of(id, BlockComponent.Kind.BODY, node));
        }

        public ComponentSpec build() {
            return components.isEmpty() ? EMPTY : new ComponentSpec(components);
        }
    }
}
