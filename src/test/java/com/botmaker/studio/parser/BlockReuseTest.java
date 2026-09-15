package com.botmaker.studio.parser;

import com.botmaker.studio.core.CodeBlock;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a re-parse and — the part worth testing — what deliberately does not.
 *
 * <p>Headless. {@link BlockReuse} decides and re-points; it draws nothing, so every refusal is assertable
 * with no JavaFX toolkit. Each refusal is a way reuse could hand a widget to the <em>wrong code</em> rather
 * than merely fail to help, which is why they are refusals and not best-effort.
 *
 * <p>See {@code docs/refactor/30-block-reuse.md} §5.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class BlockReuseTest {

    /** A two-statement method, so one statement can change while the other does not. */
    private static String source(String first, String second) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        " + first + "\n"
                + "        " + second + "\n"
                + "    }\n"
                + "}\n";
    }

    /** The block for the statement at {@code index} of {@code run()}'s body. */
    private static CodeBlock statement(EditorFixture fixture, int index) {
        return fixture.body("run").getStatements().get(index);
    }

    private static BlockReuse reuseAll(EditorFixture fixture) {
        return BlockReuse.of(fixture.state.getNodeToBlockMap(), fixture.source, false, block -> true);
    }

    @Test
    void an_unchanged_statement_keeps_its_block_across_the_re_parse() {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 1);

        fixture.reparse(source("int a = 99;", "int b = 2;"), reuseAll(fixture));

        assertSame(before, statement(fixture, 1),
                "the second statement did not change, so its block should have survived");
    }

    @Test
    void a_surviving_block_points_at_the_node_it_now_describes() {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 1);
        var oldNode = before.getAstNode();

        var result = fixture.reparse(source("int a = 99;", "int b = 2;"), reuseAll(fixture));

        assertNotSame(oldNode, before.getAstNode(),
                "adopt should have re-pointed the block at the fresh tree");
        assertSame(result.cu(), before.getAstNode().getRoot(),
                "and the node it points at should belong to the compilation unit just published");
        assertSame(before, fixture.state.getNodeToBlockMap().get(before.getAstNode()),
                "and the registry should reach it through its new node");
    }

    @Test
    void a_statement_whose_text_changed_is_rebuilt() {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 0);

        fixture.reparse(source("int a = 99;", "int b = 2;"), reuseAll(fixture));

        assertNotSame(before, statement(fixture, 0));
    }

    @Test
    void a_statement_that_moved_is_rebuilt() {
        // BlockId is a structural path, so a statement's index in its own body is part of where it is. An
        // insert above therefore rebuilds everything below it -- stated as a limit in BlockId's javadoc, and
        // accepted rather than worked around: those statements really did move.
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 1);

        fixture.reparse(
                "package com.mybot;\n"
                        + "public class Subject {\n"
                        + "    public void run() {\n"
                        + "        int a = 1;\n"
                        + "        int inserted = 0;\n"
                        + "        int b = 2;\n"
                        + "    }\n"
                        + "}\n",
                reuseAll(fixture));

        assertNotSame(before, fixture.body("run").getStatements().get(2));
    }

    @Test
    void the_policy_can_refuse_a_block_that_is_otherwise_reusable() {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 1);

        fixture.reparse(source("int a = 99;", "int b = 2;"),
                BlockReuse.of(fixture.state.getNodeToBlockMap(), fixture.source, false, block -> false));

        assertNotSame(before, statement(fixture, 1),
                "a policy that allows nothing is how the narrow phase keeps its blast radius to one subtree");
    }

    @Test
    void a_parse_whose_lock_verdict_moved_keeps_nothing_at_all() {
        // The one refusal that is about the parse rather than about a block, and the one whose failure is
        // silent: BlockConverter stamps the verdict on a block it BUILDS, and a survivor is never built. A
        // kept block would therefore draw editable over a file the parse had just locked -- reader mode being
        // switched on, or a file becoming bundled library source.
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 1);

        // Reader mode, not the bare flag: a LockResolver re-grants the body of an editable method, so the
        // verdict a statement is actually parsed under is the resolver's. That is the one this compares, and
        // it is the one BlockConverter would have stamped.
        fixture.state.setReaderMode(true);
        fixture.reparse(source("int a = 99;", "int b = 2;"), reuseAll(fixture), true);

        assertNotSame(before, statement(fixture, 1),
                "the file is read-only now and the survivor was parsed editable, so nothing may be kept");
        assertTrue(statement(fixture, 1).isReadOnly(),
                "and the block that replaced it should carry this parse's verdict");
    }

    @Test
    void none_never_takes_anything_which_is_what_every_existing_caller_passes() {
        EditorFixture fixture = new EditorFixture(source("int a = 1;", "int b = 2;"));
        CodeBlock before = statement(fixture, 1);

        fixture.reparse(source("int a = 99;", "int b = 2;"), BlockReuse.NONE);

        assertNotSame(before, statement(fixture, 1));
        assertNull(BlockReuse.NONE.take(null, null));
    }

    @Test
    void a_comment_inside_a_surviving_subtree_is_adopted_with_it() {
        // A Comment hangs off cu.getCommentList() rather than off the tree, so it is nobody's structural
        // child and BlockId falls back to a positional encoding for it. Pairing by id would miss the moment
        // the subtree's offset shifted -- which is exactly what an edit above it does.
        String before = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        int a = 1;\n"
                + "        if (a > 0) {\n"
                + "            // keep me\n"
                + "            int b = 2;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        EditorFixture fixture = new EditorFixture(before);
        CodeBlock ifBlock = statement(fixture, 1);
        CodeBlock comment = fixture.state.getNodeToBlockMap().values().stream()
                .filter(b -> b.getClass().getSimpleName().equals("CommentBlock"))
                .findFirst().orElseThrow();

        fixture.reparse(before.replace("int a = 1;", "int a = 99999;"), reuseAll(fixture));

        assertSame(ifBlock, statement(fixture, 1), "the if did not change, so it should have survived");
        assertSame(comment, fixture.state.getNodeToBlockMap().get(comment.getAstNode()),
                "and the comment inside it should be reachable through the node it was re-pointed at");
    }
}
