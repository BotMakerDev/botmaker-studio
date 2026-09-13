package com.botmaker.studio.core.component;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a spec refuses to be built out of, and what can be found in one.
 *
 * <p>These two rules were asserted inside {@code ComponentResolverTest} until 2026-09-13; they are about
 * {@link ComponentSpec} and {@link BlockComponent} rather than about the resolver, so they outlived it. The
 * rest of that class tested an audience axis and a when-locked axis that no component ever declared — see
 * {@code docs/refactor/29-block-layer.md} §2.
 *
 * <p>No JavaFX toolkit is started, and none is needed: a component holds a {@code Supplier<Node>} and nothing
 * here calls one.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ComponentSpecTest {

    /** A component whose supplier fails loudly, so any test that accidentally builds a node says so. */
    private static BlockComponent component() {
        return new BlockComponent("field", BlockComponent.Kind.PICKER,
                () -> { throw new AssertionError("nothing here may build a node"); });
    }

    @Test
    void a_spec_rejects_duplicate_ids_so_a_component_can_always_be_found_by_name() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentSpec(java.util.List.of(component(), component())));

        ComponentSpec spec = ComponentSpec.builder().add(component()).build();
        assertTrue(spec.find("field").isPresent());
        assertTrue(spec.find("absent").isEmpty());
        assertTrue(ComponentSpec.empty().components().isEmpty());
    }

    @Test
    void a_component_must_declare_every_field() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockComponent(" ", BlockComponent.Kind.LABEL, () -> null));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockComponent("id", BlockComponent.Kind.LABEL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockComponent("id", null, () -> null));
    }
}
