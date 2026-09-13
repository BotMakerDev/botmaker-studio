package com.botmaker.studio.blocks;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.component.BlockComponent;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.parser.EditorFixture;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
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

    // ---- The blocks with bodies ----

    @Test
    void a_while_declares_its_keyword_its_condition_its_add_button_and_its_body() {
        ComponentSpec spec = blockOf(inRun("while (true) { int a = 1; }"), "WhileBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.PICKER,
                        BlockComponent.Kind.BODY),
                kinds(spec));
    }

    @Test
    void a_do_while_declares_its_body_before_its_condition() {
        // The declaration order is the on-screen order, so this is how "do … while (…)" is expressible at all:
        // the keyword, then the body, then the closing row. The canvas breaks its rows at the body for exactly
        // this reason.
        ComponentSpec spec = blockOf(inRun("do { int d = 1; } while (false);"), "DoWhileBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.BODY,
                        BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.PICKER),
                kinds(spec));
    }

    @Test
    void a_for_each_declares_its_name_field_its_collection_and_its_body() {
        ComponentSpec spec = blockOf(inRun("for (String s : new String[0]) { int c = 1; }"), "ForBlock")
                .componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.CUSTOM,
                        BlockComponent.Kind.LABEL,
                        BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.BODY),
                kinds(spec));
    }

    @Test
    void an_assignment_declares_its_target_its_operator_and_its_value() {
        ComponentSpec spec = blockOf(inRun("int x = 1;\nx = 2;"), "AssignmentBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.PICKER,
                        BlockComponent.Kind.EXPRESSION_SLOT,
                        BlockComponent.Kind.PICKER),
                kinds(spec));
    }

    @Test
    void an_increment_declares_no_value_because_it_has_none() {
        ComponentSpec spec = blockOf(inRun("int x = 1;\nx++;"), "AssignmentBlock").componentSpec(null);

        assertEquals(2, spec.components().size(), "target and operator only: " + kinds(spec));
    }

    @Test
    void every_block_with_children_declares_exactly_one_body() {
        // What the two densities then do with it differs — the canvas draws it between rows, the HUD drops it
        // because its tree already shows the branches — and that rule is asserted on suppliers in
        // CompactSpecRowTest, which is where it can be checked without a JavaFX toolkit. Here the point is the
        // declaration: a block whose body were left out of its spec would lose it on the canvas and keep it in
        // the HUD, which is the one divergence a single schema is supposed to make impossible.
        assertAll(
                () -> assertEquals(1, bodies("while (true) { int a = 1; }", "WhileBlock"), "while"),
                () -> assertEquals(1, bodies("do { int d = 1; } while (false);", "DoWhileBlock"), "do/while"),
                () -> assertEquals(1, bodies("for (String s : new String[0]) { int c = 1; }", "ForBlock"), "for"));
    }

    private static long bodies(String body, String simpleName) {
        return kinds(blockOf(inRun(body), simpleName).componentSpec(null)).stream()
                .filter(k -> k == BlockComponent.Kind.BODY)
                .count();
    }

    // ---- The call block ----

    /** The ids of {@code spec}'s components, in declaration order. */
    private static List<String> ids(ComponentSpec spec) {
        return spec.components().stream().map(BlockComponent::id).toList();
    }

    @Test
    void a_call_declares_its_sentence_with_one_component_per_argument() {
        ComponentSpec spec = blockOf(inRun("String s = \"a\";\ns.substring(1, 2);"), "MethodInvocationBlock")
                .componentSpec(null);

        assertEquals(List.of("kind", "scope", "dot", "method", "signature", "open",
                        "arg0", "arg0-remove", "arg1", "arg1-remove",
                        "images", "varargs-add", "close", "returns", "info"),
                ids(spec));
    }

    @Test
    void a_calls_argument_ids_survive_a_re_parse() {
        // The property the overlay editor needs: it keeps focus on "the argument being edited", and the block
        // behind that row is thrown away and rebuilt on every keystroke that changes the file. An id derived
        // from position is stable in a way an object identity is not.
        String source = inRun("String s = \"a\";\ns.substring(1, 2);");

        assertEquals(ids(blockOf(source, "MethodInvocationBlock").componentSpec(null)),
                ids(blockOf(source, "MethodInvocationBlock").componentSpec(null)));
    }

    @Test
    void a_call_with_no_arguments_declares_no_argument_components() {
        ComponentSpec spec = blockOf(inRun("String s = \"a\";\ns.trim();"), "MethodInvocationBlock")
                .componentSpec(null);

        assertTrue(ids(spec).stream().noneMatch(id -> id.startsWith("arg")), ids(spec).toString());
        // The picture-run row and the varargs ＋ are still declared: whether either exists is a question about
        // the resolved overload, and resolving one is exactly what declaring a spec must not do.
        assertTrue(spec.find("images").isPresent());
        assertTrue(spec.find("varargs-add").isPresent());
    }

    @Test
    void declaring_a_calls_spec_resolves_nothing() {
        // The strong version of "builds no widget": this block's scope selector, method list and overload
        // lookup are a knot that has to be untied before a single argument can be typed, and none of it may
        // happen until something actually draws. A null context proves it — every supplier here would throw
        // on one.
        ComponentSpec spec = blockOf(inRun("String s = \"a\";\ns.substring(1, 2);"), "MethodInvocationBlock")
                .componentSpec(null);

        assertEquals(15, spec.components().size());
        for (BlockComponent component : spec.components()) assertNotNull(component.node());
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
    void every_declared_component_has_an_id_that_is_its_own() {
        // A duplicate id is refused by the spec's own constructor, so reaching here at all is the assertion;
        // finding each component back by name is what the HUD does to keep focus on the one being edited.
        ComponentSpec print = blockOf(inRun("System.out.println(\"hi\");"), "PrintBlock").componentSpec(null);

        for (BlockComponent component : print.components()) {
            assertTrue(print.find(component.id()).isPresent(), component.id() + " cannot be found by name");
        }
    }
}
