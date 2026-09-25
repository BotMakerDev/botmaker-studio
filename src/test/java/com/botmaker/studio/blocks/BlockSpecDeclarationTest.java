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

    // ---- The leaf statements ----

    @Test
    void break_and_continue_are_one_word_and_declare_exactly_that() {
        // The smallest spec there is, and worth a test for one reason: a block with nothing in its sentence
        // but a keyword is the case where it is tempting to leave the spec empty — and an empty spec means
        // "declares nothing", which sends the HUD back to drawing a line of source text.
        ComponentSpec brk = blockOf(inRun("while (true) { break; }"), "BreakBlock").componentSpec(null);
        ComponentSpec cont = blockOf(inRun("while (true) { continue; }"), "ContinueBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL), kinds(brk));
        assertEquals(List.of(BlockComponent.Kind.LABEL), kinds(cont));
    }

    @Test
    void a_variable_declaration_declares_its_type_name_value_and_the_two_buttons() {
        ComponentSpec spec = blockOf(inRun("int attempts = 3;"), "VariableDeclarationBlock").componentSpec(null);

        assertEquals(List.of(BlockComponent.Kind.LABEL,       // the type
                        BlockComponent.Kind.CUSTOM,           // the name, shown not edited
                        BlockComponent.Kind.LABEL,            // =
                        BlockComponent.Kind.EXPRESSION_SLOT,  // the starting value
                        BlockComponent.Kind.PICKER,           // ⊕
                        BlockComponent.Kind.PICKER),          // ✎, to the Variables screen
                kinds(spec));
    }

    @Test
    void a_variable_with_no_starting_value_still_declares_a_slot() {
        // `int x;` is a real state — the slot is the dashed hole the value goes into, so it has to be
        // declared even when there is nothing in it.
        ComponentSpec spec = blockOf(inRun("int later;"), "VariableDeclarationBlock").componentSpec(null);

        assertTrue(spec.find("value").isPresent(), "the empty starting value is still a slot: " + kinds(spec));
    }

    @Test
    void a_variables_name_is_custom_rather_than_a_slot_because_it_is_not_edited_here() {
        // Renaming on the block itself rewrote the declaration and left every use pointing at the old name.
        // The name is shown here and changed on the Variables screen, so it must not read as an editable slot
        // to anything drawing this spec.
        ComponentSpec spec = blockOf(inRun("int attempts = 3;"), "VariableDeclarationBlock").componentSpec(null);

        assertEquals(BlockComponent.Kind.CUSTOM, spec.find("name").orElseThrow().kind());
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

    // ---- The branching blocks ----

    /**
     * An {@code if} is three different sentences depending on what follows it, so its spec is the first here
     * whose <em>shape</em> varies rather than only its contents.
     */
    @Test
    void an_if_with_no_else_declares_one_body_and_the_offer_of_one() {
        ComponentSpec spec = blockOf(inRun("if (true) { int a = 1; }"), "IfBlock").componentSpec(null);

        assertEquals(List.of("kw", "condition", "change", "then", "add-else"), ids(spec));
    }

    @Test
    void an_if_with_an_else_declares_the_else_row_and_the_else_body() {
        ComponentSpec spec = blockOf(inRun("if (true) { int a = 1; } else { int b = 2; }"), "IfBlock")
                .componentSpec(null);

        assertEquals(List.of("kw", "condition", "change", "then",
                        "else-kw", "else-to-else-if", "else-spacer", "else-delete", "else-body"),
                ids(spec));
        // The row is chrome about a branch: everything after "then" is what CompactSpecRow drops for a
        // BranchingBlock, because the HUD's tree already draws that branch out of branches().
        assertTrue(blockOf(inRun("if (true) { int a = 1; } else { int b = 2; }"), "IfBlock")
                instanceof com.botmaker.studio.core.BranchingBlock);
    }

    @Test
    void an_else_if_is_one_body_holding_the_block_that_continues_the_chain() {
        // Not a row plus a body: the nested IfBlock draws its own "Else If" sentence, which is what keeps a
        // chain flat on screen instead of stepping right once per link.
        ComponentSpec spec = blockOf(
                inRun("if (true) { int a = 1; } else if (false) { int b = 2; }"), "IfBlock").componentSpec(null);

        assertEquals(List.of("kw", "condition", "change", "then", "else-if"), ids(spec));
        assertEquals(2, bodies("if (true) { int a = 1; } else if (false) { int b = 2; }", "IfBlock"));
    }

    @Test
    void a_branch_chain_declares_one_row_and_one_body_per_link() {
        String chain = "Object found = null;\n"
                + "found.when(m -> m.hasAny(), () -> { int a = 1; }).otherwise(() -> { int b = 2; });";
        ComponentSpec spec = blockOf(inRun(chain), "BranchChainBlock").componentSpec(null);

        assertEquals(List.of("kw", "subject",
                        "link0", "link0-condition", "link0-change", "link0-spacer", "link0-add", "link0-delete",
                        "link0-body",
                        // The fallback has nothing to test, so no condition slot and no ⊕ of its own.
                        "link1", "link1-spacer", "link1-add", "link1-delete", "link1-body"),
                ids(spec));
    }

    @Test
    void a_switch_declares_all_of_its_cases_as_one_body() {
        // Not one body per case. A SwitchCaseBlock is a structural node — branches() skips it and hands a
        // compact renderer the body the case runs — so no surface draws a case as a row of its own, and the
        // five facts a case needs to render (its index, the count, the switch's type, its siblings' labels,
        // the statement) are all this block's. Declaring them per case would be describing a row nobody draws.
        ComponentSpec spec = blockOf(inRun("int k = 1;\nswitch (k) { case 1: break; default: break; }"),
                "SwitchBlock").componentSpec(null);

        assertEquals(List.of("kw", "expression", "change", "cases", "add-case"), ids(spec));
    }

    @Test
    void declaring_a_switchs_spec_resolves_no_binding() {
        // The switch's type comes from the expression's own ITypeBinding, and resolving one is exactly what a
        // declaration must not do: a headless caller asks for a spec with no bindings in hand. Every supplier
        // below would reach one; none is called.
        ComponentSpec spec = blockOf(inRun("int k = 1;\nswitch (k) { case 1: break; }"), "SwitchBlock")
                .componentSpec(null);

        for (BlockComponent component : spec.components()) assertNotNull(component.node());
    }

    @Test
    void a_branch_chains_component_ids_survive_a_re_parse() {
        String chain = inRun("Object found = null;\n"
                + "found.when(m -> m.hasAny(), () -> { int a = 1; }).otherwise(() -> { int b = 2; });");

        assertEquals(ids(blockOf(chain, "BranchChainBlock").componentSpec(null)),
                ids(blockOf(chain, "BranchChainBlock").componentSpec(null)));
    }

    // ---- The blocks whose spec is their sentence and whose chrome is not ----

    @Test
    void a_body_call_declares_a_slot_per_leading_argument_and_the_body_last() {
        String call = "Object finder = null;\n"
                + "finder.each(1, 2, found -> { int a = 1; });";
        ComponentSpec spec = blockOf(inRun(call), "BodyCallBlock").componentSpec(null);

        assertEquals(List.of("scope", "method",
                        "arg0", "arg0-change", "arg1", "arg1-change",
                        "body"),
                ids(spec));
    }

    @Test
    void declaring_a_body_calls_spec_asks_the_analyzer_nothing() {
        // The slot types come from the bot's own resolved classpath, which a headless caller has not got. A
        // null context proves the lookup is behind the suppliers: it would throw if the declaration ran it.
        ComponentSpec spec = blockOf(
                inRun("Object finder = null;\nfinder.each(1, found -> { int a = 1; });"), "BodyCallBlock")
                .componentSpec(null);

        for (BlockComponent component : spec.components()) assertNotNull(component.node());
    }

    @Test
    void a_field_declares_its_sentence_and_not_its_caption() {
        // "Private Static Field" and the cross beside it are chrome on a row no compact renderer draws.
        String field = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    private static int attempts = 3;\n"
                + "}\n";
        ComponentSpec spec = blockOf(field, "DeclareClassVariableBlock").componentSpec(null);

        assertEquals(List.of("type", "name", "eq", "value", "change"), ids(spec));
    }

    @Test
    void a_field_with_no_value_declares_the_button_that_gives_it_one() {
        String field = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    private int attempts;\n"
                + "}\n";
        ComponentSpec spec = blockOf(field, "DeclareClassVariableBlock").componentSpec(null);

        assertEquals(List.of("type", "name", "set-value"), ids(spec));
    }

    @Test
    void an_enum_declares_its_sentence_and_leaves_its_constants_as_chrome() {
        // The constants are not a BodyBlock, nothing nests them and no compact renderer draws them, so
        // describing them component by component would declare rows nobody reads.
        String decl = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    enum Outcome { WON, LOST }\n"
                + "}\n";
        ComponentSpec spec = blockOf(decl, "DeclareEnumBlock").componentSpec(null);

        assertEquals(List.of("kind", "name", "add-value"), ids(spec));
    }

    @Test
    void an_initializer_declares_two_labels_and_nothing_to_press() {
        // It is there to be read rather than typed in, and the spec is what says so.
        String decl = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    static { int a = 1; }\n"
                + "}\n";
        ComponentSpec spec = blockOf(decl, "InitializerBlock").componentSpec(null);

        assertEquals(List.of("kw", "hint"), ids(spec));
        assertEquals(List.of(BlockComponent.Kind.LABEL, BlockComponent.Kind.LABEL), kinds(spec));
    }

    // ---- The call block ----

    /** The ids of {@code spec}'s components, in declaration order. */
    private static List<String> ids(ComponentSpec spec) {
        return spec.components().stream().map(BlockComponent::id).toList();
    }

    @Test
    void a_call_declares_its_sentence_with_one_component_per_argument() {
        ComponentSpec spec = blockOf(inRun("String s = \"a\";\ns.substring(1, 2);"), "ExternalCallBlock")
                .componentSpec(null);

        assertEquals(List.of("kind", "owner", "scope", "method", "signature",
                        "arg0", "arg0-remove", "arg1", "arg1-remove",
                        "images", "varargs-add", "returns", "info"),
                ids(spec));
    }

    @Test
    void a_call_to_the_bots_own_method_is_a_project_call_and_a_jdk_call_an_external_one() {
        String source = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    static int helper() { return 1; }\n"
                + "    public void run() {\n"
                + "        helper();\n"
                + "        Math.max(1, 2);\n"
                + "    }\n"
                + "}\n";
        List<CodeBlock> blocks = flatten(new EditorFixture(source).root);
        assertTrue(blocks.stream().anyMatch(b -> b instanceof com.botmaker.studio.blocks.func.ProjectCallBlock p
                && p.getMethodName().equals("helper")), "helper() is the bot's own");
        assertTrue(blocks.stream().anyMatch(b -> b instanceof com.botmaker.studio.blocks.func.ExternalCallBlock e
                && e.getMethodName().equals("max")), "Math.max is Java's");
    }

    @Test
    void a_calls_argument_ids_survive_a_re_parse() {
        // The property the overlay editor needs: it keeps focus on "the argument being edited", and the block
        // behind that row is thrown away and rebuilt on every keystroke that changes the file. An id derived
        // from position is stable in a way an object identity is not.
        String source = inRun("String s = \"a\";\ns.substring(1, 2);");

        assertEquals(ids(blockOf(source, "ExternalCallBlock").componentSpec(null)),
                ids(blockOf(source, "ExternalCallBlock").componentSpec(null)));
    }

    @Test
    void a_call_with_no_arguments_declares_no_argument_components() {
        ComponentSpec spec = blockOf(inRun("String s = \"a\";\ns.trim();"), "ExternalCallBlock")
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
        ComponentSpec spec = blockOf(inRun("String s = \"a\";\ns.substring(1, 2);"), "ExternalCallBlock")
                .componentSpec(null);

        assertEquals(13, spec.components().size());
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
