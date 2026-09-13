package com.botmaker.studio.parser;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block id has exactly one job that spans a re-parse — carrying a breakpoint — and every edit is followed
 * by a re-parse, so the property to assert is that the id of an untouched block does not move when something
 * else in the file does.
 *
 * <p>Driven end to end through {@link EditorFixture} rather than over a hand-built AST, because what is being
 * asserted is the id a block <em>actually gets</em> from {@code BlockConverter}, not the output of a function
 * in isolation. The positional encoding this replaced passed every isolated test of itself and still lost
 * breakpoints.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class BlockIdStabilityTest {

    private static List<CodeBlock> flatten(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren parent) {
            for (CodeBlock child : parent.getChildren()) out.addAll(flatten(child));
        }
        return out;
    }

    /** The id of the first block of class {@code simpleName} in the tree parsed from {@code source}. */
    private static String idOf(String source, String simpleName) {
        CodeBlock found = flatten(new EditorFixture(source).root).stream()
                .filter(b -> b.getClass().getSimpleName().equals(simpleName))
                .findFirst()
                .orElse(null);
        assertNotNull(found, "fixture produced no " + simpleName);
        return found.getId();
    }

    /** Two methods; the print sits in the second one, so edits to the first are edits "elsewhere". */
    private static String twoMethods(String firstBody) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void first() {\n"
                + firstBody.indent(8)
                + "    }\n"
                + "    public void second() {\n"
                + "        System.out.println(\"target\");\n"
                + "    }\n"
                + "}\n";
    }

    @Test
    void an_edit_in_another_method_does_not_move_the_id() {
        // This is the case the positional encoding got wrong: everything below the edit shifted, so a
        // breakpoint anywhere later in the file was silently dropped on the next keystroke.
        String before = idOf(twoMethods(""), "PrintBlock");
        String after = idOf(twoMethods("int x = 1;\nint y = 2;"), "PrintBlock");

        assertEquals(before, after, "the print did not move; neither may its id");
    }

    @Test
    void reformatting_and_comments_do_not_move_the_id() {
        String plain = idOf(twoMethods(""), "PrintBlock");
        String padded = idOf(twoMethods("\n\n// a note about nothing in particular\n\n"), "PrintBlock");

        assertEquals(plain, padded, "whitespace and comments are not structure");
    }

    @Test
    void inserting_a_preceding_sibling_does_move_the_id() {
        // Stated as a test rather than left to be discovered: a block's index in its own list is part of
        // where it is, so a statement pushed down really does become a different position. Following it
        // through the edit is a rewriter's job, not an id scheme's — see BlockId's javadoc.
        String first = idOf("package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        System.out.println(\"target\");\n"
                + "    }\n"
                + "}\n", "PrintBlock");

        String pushedDown = idOf("package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        int x = 1;\n"
                + "        System.out.println(\"target\");\n"
                + "    }\n"
                + "}\n", "PrintBlock");

        assertNotEquals(first, pushedDown);
    }

    @Test
    void every_block_in_one_file_has_its_own_id() {
        // Uniqueness is what the drag payload rests on: a drop resolves a block by the id it carries, so two
        // blocks answering to one id would move the wrong statement.
        List<CodeBlock> all = flatten(new EditorFixture("package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        int x = 1;\n"
                + "        int y = 2;\n"
                + "        if (x < y) {\n"
                + "            System.out.println(\"a\");\n"
                + "            System.out.println(\"b\");\n"
                + "        }\n"
                + "        while (x < y) {\n"
                + "            x = x + 1;\n"
                + "        }\n"
                + "    }\n"
                + "}\n").root);

        Set<String> ids = new HashSet<>();
        for (CodeBlock block : all) {
            assertTrue(ids.add(block.getId()),
                    block.getClass().getSimpleName() + " reuses the id " + block.getId());
        }
        assertTrue(all.size() > 8, "fixture should produce a tree worth checking, got " + all.size());
    }

    @Test
    void sibling_statements_differ_only_by_their_index() {
        List<String> printIds = flatten(new EditorFixture("package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        System.out.println(\"a\");\n"
                + "        System.out.println(\"b\");\n"
                + "    }\n"
                + "}\n").root).stream()
                .filter(b -> b.getClass().getSimpleName().equals("PrintBlock"))
                .map(CodeBlock::getId)
                .toList();

        assertEquals(2, printIds.size());
        assertNotEquals(printIds.get(0), printIds.get(1));
        // The path is a path: both live in the same body, so everything up to the index is shared.
        String sharedPrefix = printIds.get(0).substring(0, printIds.get(0).lastIndexOf('['));
        assertTrue(printIds.get(1).startsWith(sharedPrefix),
                printIds.get(1) + " should share a parent path with " + printIds.get(0));
    }
}
