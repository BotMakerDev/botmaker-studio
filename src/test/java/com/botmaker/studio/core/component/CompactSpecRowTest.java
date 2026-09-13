package com.botmaker.studio.core.component;

import com.botmaker.studio.ui.app.overlay.CompactSpecRow;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What the HUD keeps and what it drops from a block's declared spec.
 *
 * <p>Headless: {@link ComponentSpec} holds {@code Supplier<Node>}s that a dropped component's renderer never
 * calls, so the rule worth asserting — a body is not drawn inline, and its widget is never built — is
 * assertable without a JavaFX toolkit. That property is why the spec was designed with suppliers, and it is
 * the same reason {@code BlockTree} was kept free of JavaFX.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class CompactSpecRowTest {

    /** A supplier that records whether the render path asked it for a node. */
    private static final class Probe implements java.util.function.Supplier<javafx.scene.Node> {
        boolean asked;

        @Override
        public javafx.scene.Node get() {
            asked = true;
            return null;
        }
    }

    @Test
    void a_body_is_not_drawn_inline_because_the_tree_already_nests_it() {
        Probe body = new Probe();
        ComponentSpec spec = ComponentSpec.builder()
                .label("kw", () -> null)
                .body("body", body)
                .build();

        CompactSpecRow.nodes(spec, false);

        assertFalse(body.asked, "the HUD shows bodies as nested rows, never inline");
    }

    @Test
    void a_component_whose_supplier_answers_null_is_skipped_rather_than_drawn() {
        // Null is "this affordance does not exist" — a locked block's buttons answer it rather than coming
        // back disabled, which is the whole of the read-only rendering rule on both surfaces.
        ComponentSpec spec = ComponentSpec.builder()
                .label("kw", () -> null)
                .picker("change", () -> null)
                .build();

        assertEquals(List.of(), CompactSpecRow.nodes(spec, true));
    }

    @Test
    void a_block_with_no_declared_spec_yields_no_nodes() {
        assertEquals(List.of(), CompactSpecRow.nodes(ComponentSpec.empty(), false));
    }
}
