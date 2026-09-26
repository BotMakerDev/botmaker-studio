package com.botmaker.studio.project.vcs;

import com.botmaker.studio.project.vcs.BlockDiff.FileDiff;
import com.botmaker.studio.project.vcs.BlockDiff.Mark;
import com.botmaker.studio.project.vcs.BlockDiff.MethodChange;
import com.botmaker.studio.project.vcs.BlockDiff.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two versions of a file compared function by function, block by block ({@code 39} §5). */
class BlockDiffTest {

    private static final String V1 = """
            class Bot {
                static int speed = 5;
                void mine() {
                    wait(500);
                    for (int i = 0; i < 3; i++) {
                        click(1);
                        wait(500);
                    }
                    log("done");
                }
                void home() { log("home"); }
                void go(int x) { log("int"); }
                void go(String s) { log("string"); }
            }
            """;

    /** The marked source text on one side: what the canvas finds its block by. */
    private static Map<String, Mark> marked(String source, List<Span> spans) {
        return spans.stream().collect(Collectors.toMap(
                s -> source.substring(s.start(), s.start() + s.length()), Span::mark));
    }

    private static MethodChange only(FileDiff diff) {
        assertEquals(1, diff.methods().size(), diff.methods().toString());
        return diff.methods().getFirst();
    }

    @Test
    void anIdenticalFileHasNoChangeAndCountsItsFunctions() {
        FileDiff diff = BlockDiff.of(V1, V1);
        assertTrue(diff.readable());
        assertTrue(diff.methods().isEmpty());
        assertTrue(diff.fields().isEmpty());
        assertEquals(4, diff.unchanged());
    }

    @Test
    void formattingAndCommentsAreNotAChange() {
        String v2 = V1.replace("void home() { log(\"home\"); }", "void home() {\n    // hi\n    log( \"home\" );\n}");
        assertTrue(BlockDiff.of(V1, v2).methods().isEmpty());
    }

    @Test
    void aChangeInsideALoopMarksThatBlockAndNotTheLoop() {
        String v2 = V1.replace("wait(500);\n        }", "wait(200);\n        }");
        MethodChange change = only(BlockDiff.of(V1, v2));
        assertEquals("Bot#mine()", change.signature());
        assertEquals(Mark.CHANGED, change.kind());
        assertFalse(change.header());
        assertEquals(Map.of("wait(500);", Mark.CHANGED), marked(V1, change.before()));
        assertEquals(Map.of("wait(200);", Mark.CHANGED), marked(v2, change.after()));
    }

    @Test
    void anInsertedStatementIsAddedAndTheRestStaysMatched() {
        String v2 = V1.replace("        log(\"done\");", "        log(\"almost\");\n        log(\"done\");");
        MethodChange change = only(BlockDiff.of(V1, v2));
        assertTrue(change.before().isEmpty());
        assertEquals(Map.of("log(\"almost\");", Mark.ADDED), marked(v2, change.after()));
    }

    @Test
    void aRemovedStatementIsRemoved() {
        String v2 = V1.replace("        wait(500);\n        for", "        for");
        MethodChange change = only(BlockDiff.of(V1, v2));
        assertEquals(Map.of("wait(500);", Mark.REMOVED), marked(V1, change.before()));
        assertTrue(change.after().isEmpty());
    }

    @Test
    void aLoopWhoseHeaderChangedIsOneChangedBlock() {
        String v2 = V1.replace("i < 3", "i < 4");
        MethodChange change = only(BlockDiff.of(V1, v2));
        assertEquals(1, change.before().size());
        assertTrue(marked(V1, change.before()).keySet().iterator().next().startsWith("for (int i = 0; i < 3"));
    }

    @Test
    void overloadsAreDifferentFunctionsAndARenameIsOneOfEach() {
        String v2 = V1.replace("void go(String s) { log(\"string\"); }", "void go(String s) { log(\"text\"); }")
                .replace("void home()", "void back()");
        FileDiff diff = BlockDiff.of(V1, v2);
        Map<String, Mark> kinds = diff.methods().stream()
                .collect(Collectors.toMap(MethodChange::signature, MethodChange::kind));
        assertEquals(Map.of("Bot#go(String)", Mark.CHANGED, "Bot#home()", Mark.REMOVED, "Bot#back()", Mark.ADDED), kinds);
        assertEquals(2, diff.unchanged());
    }

    @Test
    void aSignatureChangeIsAHeaderChange() {
        String v2 = V1.replace("void home()", "public void home()");
        MethodChange change = only(BlockDiff.of(V1, v2));
        assertTrue(change.header());
        assertTrue(change.before().isEmpty() && change.after().isEmpty());
    }

    @Test
    void fieldsCompareAsRows() {
        String v2 = V1.replace("static int speed = 5;", "static int speed = 8;\n    static String name = \"x\";");
        FileDiff diff = BlockDiff.of(V1, v2);
        assertEquals(List.of(new BlockDiff.FieldChange("Bot.speed", "5", "8", false),
                new BlockDiff.FieldChange("Bot.name", null, "\"x\"", false)),
                diff.fields().stream().map(f -> new BlockDiff.FieldChange(f.name(), f.was(), f.now(), f.parameter()))
                        .toList());
    }

    /** Each side says where its declaration starts, so the Versions tab can draw it as blocks. */
    @Test
    void aFieldChangeKnowsWhereEachSideIs() {
        String a = "class P { @Param static java.util.List<String> names = java.util.List.of(); }";
        String b = "class P {\n  @Param static java.util.Map<String, Integer> names = java.util.Map.of(); }";
        BlockDiff.FieldChange change = BlockDiff.of(a, b).fields().getFirst();
        assertEquals(a.indexOf("@Param"), change.beforeStart());
        assertEquals(b.indexOf("@Param"), change.afterStart());
        assertTrue(change.was().startsWith("java.util.List<String>"), "the type is shown when it changed: " + change);
    }

    @Test
    void aParamFieldSaysSo() {
        String a = "class P { @Param static int rest = 1; }";
        String b = "class P { @Param static int rest = 2; }";
        assertTrue(BlockDiff.of(a, b).fields().getFirst().parameter());
    }

    @Test
    void aNewFileIsAllAdded() {
        FileDiff diff = BlockDiff.of(null, V1);
        assertEquals(4, diff.methods().size());
        assertTrue(diff.methods().stream().allMatch(m -> m.kind() == Mark.ADDED && m.beforeStart() < 0));
    }

    @Test
    void aSideThatDoesNotParseFallsBackToText() {
        FileDiff diff = BlockDiff.of(V1, "class Bot { void mine( { }");
        assertFalse(diff.readable());
        assertNotNull(diff.problem());
        assertTrue(diff.problem().contains("after"), diff.problem());
    }
}
