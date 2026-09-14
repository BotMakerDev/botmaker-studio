package com.botmaker.studio.core.component;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a re-parse, and — the part worth testing — what deliberately does not.
 *
 * <p>Headless: {@link SpecReconciler} decides, it does not draw, so every refusal below is assertable with no
 * JavaFX toolkit. Each of the four is a way a carry could edit the <em>wrong</em> code rather than merely fail
 * to help, which is why they are refusals and not best-effort.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class SpecReconcilerTest {

    /** A component that may be carried, recording whether the reconciler handed its widget back. */
    private static ComponentSpec.Builder withCarried(ComponentSpec.Builder builder, String id, AtomicInteger rebinds) {
        return builder.carried(id, () -> null, node -> rebinds.incrementAndGet());
    }

    @Test
    void a_carried_component_still_declared_with_the_same_shape_keeps_its_widget() {
        ComponentSpec drawn = withCarried(ComponentSpec.builder(), "name", new AtomicInteger()).build();
        ComponentSpec fresh = withCarried(ComponentSpec.builder(), "name", new AtomicInteger()).build();

        assertTrue(SpecReconciler.carry(drawn, fresh, "name").isPresent());
    }

    @Test
    void nothing_is_carried_when_nothing_had_focus() {
        ComponentSpec spec = withCarried(ComponentSpec.builder(), "name", new AtomicInteger()).build();

        assertTrue(SpecReconciler.carry(spec, spec, null).isEmpty());
        assertTrue(SpecReconciler.carry(spec, spec, "  ").isEmpty());
    }

    @Test
    void a_component_the_new_spec_no_longer_declares_is_not_carried() {
        // A spec's shape varies with the code it describes: a call that lost an argument drops arg2 entirely,
        // and an if that lost its else drops the whole else row. The widget has nothing to point at.
        ComponentSpec drawn = withCarried(ComponentSpec.builder(), "arg2", new AtomicInteger()).build();
        ComponentSpec fresh = ComponentSpec.builder().label("kw", () -> null).build();

        assertTrue(SpecReconciler.carry(drawn, fresh, "arg2").isEmpty());
    }

    @Test
    void a_component_whose_kind_changed_under_the_same_id_is_not_carried() {
        // Ids are positional in the blocks that repeat them (arg0, link1-condition), so one edit can leave an
        // id in place over a different shape. Carrying a picker's widget into an expression slot is a category
        // error the id alone cannot see.
        ComponentSpec drawn = withCarried(ComponentSpec.builder(), "arg0", new AtomicInteger()).build();
        ComponentSpec fresh = ComponentSpec.builder()
                .slot("arg0", () -> null)
                .build();

        assertTrue(SpecReconciler.carry(drawn, fresh, "arg0").isEmpty());
    }

    @Test
    void a_component_that_does_not_ask_to_be_carried_is_rebuilt() {
        // The default, and the whole safety of the design: a widget built in a supplier closes over the block
        // and the ASTNode it came from, both dead after a re-parse. Carrying one without a rebind hook would
        // leave it writing into a discarded tree. Declaring nothing means "rebuild me", which is what every
        // component did before this existed.
        ComponentSpec drawn = ComponentSpec.builder().custom("name", () -> null).build();
        ComponentSpec fresh = ComponentSpec.builder().custom("name", () -> null).build();

        assertTrue(SpecReconciler.carry(drawn, fresh, "name").isEmpty());
    }

    @Test
    void rebinding_hands_the_old_widget_to_the_block_that_exists_now() {
        AtomicInteger rebinds = new AtomicInteger();
        ComponentSpec fresh = withCarried(ComponentSpec.builder(), "name", rebinds).build();
        BlockComponent component = fresh.find("name").orElseThrow();

        assertTrue(SpecReconciler.rebind(component, new javafx.scene.Group()));
        assertEquals(1, rebinds.get());
    }

    @Test
    void a_rebind_that_throws_loses_the_carry_and_nothing_else() {
        // The rule every pass over block code here follows: a block that misbehaves costs itself its own
        // affordance, never the render pass. The caller rebuilds, which is what it would have done anyway.
        ComponentSpec fresh = ComponentSpec.builder()
                .carried("name", () -> null, node -> { throw new IllegalStateException("boom"); })
                .build();

        assertFalse(SpecReconciler.rebind(fresh.find("name").orElseThrow(), new javafx.scene.Group()));
    }

    @Test
    void a_component_declaring_no_rebind_cannot_be_rebound() {
        ComponentSpec spec = ComponentSpec.builder().custom("name", () -> null).build();

        assertFalse(SpecReconciler.rebind(spec.find("name").orElseThrow(), new javafx.scene.Group()));
    }
}
