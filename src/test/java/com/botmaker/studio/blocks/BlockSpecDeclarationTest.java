package com.botmaker.studio.blocks;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.parser.EditorFixture;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first two blocks to declare a {@link ComponentSpec}, asserted on the declaration rather than on pixels.
 *
 * <p>The spec is the thing two render paths read — {@code ComponentLayoutBuilder} at canvas density,
 * {@code CompactSpecRow} at the overlay HUD's — so a component missing from it is a component missing from
 * <em>both</em> surfaces, which is the failure worth catching here rather than by looking at one of them.
 *
 * <p>No JavaFX toolkit is started and none is needed: {@link BlockComponent} holds a {@code Supplier<Node>},
 * and nothing below calls one. That is the property that makes the schema testable at all, and it is asserted
 * outright in {@link #declaring_a_spec_builds_no_widget}.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class BlockSpecDeclarationTest {

    private static String inRun(String body) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + body.indent(8)
                + "    }\n"
                + "}\n";
    }

    private static List<CodeBlock> flatten(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren parent) {
            for (CodeBlock child : parent.getChildren()) out.addAll(flatten(child));
        }
        return out;
    }

    /** The first block of class {@code simpleName} in the tree built from {@code source}. */
    private static CodeBlock blockOf(String source, String simpleName) {
        CodeBlock found = flatten(new EditorFixture(source).root).stream()
                .filter(b -> b.getClass().getSimpleName().equals(simpleName))
                .findFirst()
                .orElse(null);
        assertNotNull(found, "fixture produced no " + simpleName);
        return found;
    }

    /** The kinds of {@code spec}, in declaration order — the order they are drawn in. */
    private static List<BlockComponent.Kind> kinds(ComponentSpec spec) {
        return spec.components().stream().map(BlockComponent::kind).toList();
    }

    // ---- Print ----

    @Test
    void print_declares_its_keyword_then_its_argument_then_the_add_button() {
        // A null context: nothing below builds a widget, so the session is never reached.
        ComponentSpec spec = blockOf(inRun("System.out.println(\"hi\");"), "PrintBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.PICKER),
                kinds(spec));
        assertTrue(spec.find("add").isPresent(), "the add button is a declared component, not a tail");
    }

    @Test
    void a_print_with_no_argument_still_declares_a_slot() {
        // The empty slot is the one a dragged-out value leaves behind, and it has to stay a slot: it is where
        // the value goes back.
        ComponentSpec spec = blockOf(inRun("System.out.println();"), "PrintBlock").componentSpec(null);

        assertTrue(kinds(spec).contains(BlockComponent.Kind.EXPRESSION_SLOT),
                "an argumentless print declared " + kinds(spec));
    }

    // ---- Return ----

    @Test
    void a_return_with_a_value_declares_the_keyword_the_slot_and_the_change_button() {
        String source = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public int total() {\n"
                + "        return 7;\n"
                + "    }\n"
                + "}\n";

        ComponentSpec spec = blockOf(source, "ReturnBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.PICKER),
                kinds(spec));
    }

    @Test
    void a_void_return_declares_its_keyword_and_the_void_note_and_no_slot() {
        ComponentSpec spec = blockOf(inRun("return;"), "ReturnBlock").componentSpec(null);

        assertFalse(kinds(spec).contains(BlockComponent.Kind.EXPRESSION_SLOT),
                "a void return has nothing to put in a slot: " + kinds(spec));
        assertTrue(spec.find("void").isPresent(), "the (void) note is declared like anything else");
    }

    // ---- The property both of them rest on ----

    @Test
    void declaring_a_spec_builds_no_widget() {
        // Every supplier in both specs is left uncalled here, and the test passes with no JavaFX toolkit
        // started — which is the whole reason a component holds a supplier rather than a node. A block that
        // built its widgets while describing itself would fail this on the first construction.
        ComponentSpec print = blockOf(inRun("System.out.println(\"hi\");"), "PrintBlock").componentSpec(null);
        ComponentSpec ret = blockOf(inRun("return;"), "ReturnBlock").componentSpec(null);

        assertFalse(print.components().isEmpty());
        assertFalse(ret.components().isEmpty());
        for (BlockComponent component : print.components()) assertNotNull(component.node());
        for (BlockComponent component : ret.components()) assertNotNull(component.node());
    }

    @Test
    void every_declared_component_is_shown_to_both_audiences() {
        // Neither block has anything scaffolding-ish in it, so nothing here may be EDITOR_ONLY: a reader of
        // someone else's bot must see the whole print and the whole return.
        ComponentSpec print = blockOf(inRun("System.out.println(\"hi\");"), "PrintBlock").componentSpec(null);

        for (BlockComponent component : print.components()) {
            assertTrue(com.botmaker.studio.core.component.ComponentResolver
                            .isVisibleTo(component.visibility(), Audience.USER),
                    component.id() + " is hidden from a reader");
        }
    }
}
