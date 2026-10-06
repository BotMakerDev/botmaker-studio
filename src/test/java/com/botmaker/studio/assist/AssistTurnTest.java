package com.botmaker.studio.assist;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectTemplate;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant's tool layer, headless: what it lets through, what it refuses, and that a refusal leaves the
 * working copy byte-identical. No model is involved — each test is the call a model would make.
 */
class AssistTurnTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public void run() {
                    int count = 3;
                    String name = "a";
                    System.out.println(count);
                }
            }
            """;

    private static AssistWorkspace workspace() {
        return new AssistWorkspace(
                ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")),
                Paths.get("Subject.java").toAbsolutePath(),
                List.of(System.getProperty("java.class.path").split(File.pathSeparator)),
                Paths.get("src", "main", "java").toAbsolutePath(),
                ProjectTemplate.GAME_BOT, null, null,
                RefusalJournal.in(Path.of(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")),
                ValueGrammar.empty(), List.of());
    }

    private static AssistTurn turn(String source) {
        return new AssistTurn(workspace(), source);
    }

    private static BlockView.Body runBody(AssistTurn turn) {
        return turn.tree().stream().filter(m -> m.name().equals("run")).findFirst().orElseThrow().body();
    }

    private static BlockView.Statement statement(AssistTurn turn, String textStart) {
        return runBody(turn).statements().stream().filter(s -> s.text().startsWith(textStart)).findFirst()
                .orElseThrow(() -> new AssertionError("no statement starting " + textStart));
    }

    // ---- reading ----

    @Test
    void treeListsStatementsWithIdsAndTypedSlots() {
        AssistTurn turn = turn(SOURCE);
        BlockView.Body body = runBody(turn);
        assertEquals(3, body.statements().size());

        BlockView.Slot printed = statement(turn, "System.out.println").slots().getFirst();
        assertEquals("int", printed.type(), "println(count) resolves to println(int)");
        assertEquals("count", printed.value());

        BlockView.Slot named = statement(turn, "String name").slots().getFirst();
        assertEquals("java.lang.String", named.type());
        assertTrue(named.writable());
    }

    @Test
    void paletteOffersStatementBlocksById() {
        List<PaletteEntry> palette = turn(SOURCE).palette();
        assertTrue(palette.stream().anyMatch(e -> e.id().equals("block:PRINT")));
        assertTrue(palette.stream().anyMatch(e -> e.id().equals("block:IF")));
    }

    // ---- what goes through ----

    @Test
    void insertingAPaletteBlockCompilesAndLands() {
        AssistTurn turn = turn(SOURCE);
        Outcome outcome = turn.insert(runBody(turn).id(), 3, "block:PRINT");

        assertInstanceOf(Outcome.Accepted.class, outcome, outcome.toString());
        assertEquals(4, runBody(turn).statements().size());
        assertTrue(turn.errors().isEmpty(), turn.errors().toString());
    }

    @Test
    void aSlotTakesAValueOfItsTypeWrittenByTheHost() {
        AssistTurn turn = turn(SOURCE);
        String slot = statement(turn, "System.out.println").slots().getFirst().id();

        assertInstanceOf(Outcome.Accepted.class, turn.setSlot(slot, "1_000"));
        assertTrue(turn.source().contains("System.out.println(1000)"),
                "the grammar's canonical spelling is written, not the text given:\n" + turn.source());
    }

    @Test
    void aSlotTakesOneOfTheBotsConstantsByName() {
        String source = SOURCE.replace("public class Subject {", "public class Subject {\n    public static final int LIMIT = 5;");
        List<com.botmaker.studio.project.managed.ManagedConstants.Constant> constants = List.of(
                new com.botmaker.studio.project.managed.ManagedConstants.Constant("com.mybot.Subject", "LIMIT", "5"),
                new com.botmaker.studio.project.managed.ManagedConstants.Constant("com.mybot.Subject", "NAME", "\"a\""));
        AssistWorkspace base = workspace();
        AssistTurn turn = new AssistTurn(new AssistWorkspace(base.config(), base.file(), base.classpath(),
                base.sourceRoot(), base.template(), base.settings(), base.index(), base.journal(), base.grammar(),
                constants), source);
        String slot = statement(turn, "System.out.println").slots().getFirst().id();

        assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "Subject.NAME"), "a String is not an int");
        assertInstanceOf(Outcome.Accepted.class, turn.setSlot(slot, "Subject.LIMIT"));
        assertTrue(turn.source().contains("System.out.println(Subject.LIMIT)"), turn.source());
    }

    // ---- what is refused, and leaves nothing behind ----

    @Test
    void anIdOutsideThePaletteIsRefused() {
        AssistTurn turn = turn(SOURCE);
        Outcome outcome = turn.insert(runBody(turn).id(), 0, "call:Runtime.exec(String)");

        assertInstanceOf(Outcome.Refused.class, outcome);
        assertEquals(SOURCE, turn.source());
    }

    @Test
    void javaIsNotAValue() {
        AssistTurn turn = turn(SOURCE);
        String slot = statement(turn, "System.out.println").slots().getFirst().id();

        assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "Runtime.getRuntime().hashCode()"));
        assertEquals(SOURCE, turn.source());
    }

    @Test
    void aValueOfTheWrongTypeIsRefused() {
        AssistTurn turn = turn(SOURCE);
        String slot = statement(turn, "System.out.println").slots().getFirst().id();

        assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "\"hello\""));
        assertEquals(SOURCE, turn.source());
    }

    @Test
    void anEditThatWouldNotCompileIsRolledBackWithTheError() {
        AssistTurn turn = turn(SOURCE);
        Outcome outcome = turn.delete(statement(turn, "int count").id());

        Outcome.Refused refused = assertInstanceOf(Outcome.Refused.class, outcome);
        assertTrue(refused.reasons().stream().anyMatch(r -> r.contains("count")), refused.reasons().toString());
        assertEquals(SOURCE, turn.source());
    }

    @Test
    void anErrorAlreadyInTheFileDoesNotBlockOtherEdits() {
        String broken = SOURCE.replace("System.out.println(count);", "System.out.println(count);\n        missing();");
        AssistTurn turn = turn(broken);
        assertFalse(turn.errors().isEmpty());

        assertInstanceOf(Outcome.Accepted.class, turn.insert(runBody(turn).id(), 0, "block:PRINT"));
    }

    // ---- shaping the code ----

    @Test
    void aMethodIsAddedOfTypesStudioOffers() {
        AssistTurn turn = turn(SOURCE);
        Outcome added = turn.addMethod("", "rest", "void", List.of(Map.of("name", "seconds", "type", "int")));

        assertInstanceOf(Outcome.Accepted.class, added, added.toString());
        assertTrue(turn.source().contains("rest(int seconds)"), turn.source());
        assertTrue(turn.tree().stream().anyMatch(m -> m.name().equals("rest")), "it has a body to insert into");

        Outcome.Refused unknown = assertInstanceOf(Outcome.Refused.class,
                turn.addMethod("", "other", "Runtime", List.of()));
        assertTrue(unknown.reasons().getFirst().contains("no type Runtime"), unknown.reasons().toString());
        assertInstanceOf(Outcome.Refused.class, turn.addMethod("", "rest", "void",
                List.of(Map.of("name", "seconds", "type", "int"))), "the same signature twice");
    }

    @Test
    void aStatementMovesWithinItsFile() {
        AssistTurn turn = turn(SOURCE);
        Outcome moved = turn.moveBlock(statement(turn, "String name").id(), runBody(turn).id(), 0);

        assertInstanceOf(Outcome.Accepted.class, moved, moved.toString());
        assertTrue(runBody(turn).statements().getFirst().text().startsWith("String name"), turn.source());
    }

    @Test
    void aStatementCannotMoveIntoItself() {
        String source = SOURCE.replace("System.out.println(count);",
                "if (count > 1) {\n            System.out.println(count);\n        }");
        AssistTurn turn = turn(source);
        BlockView.Statement branch = statement(turn, "if (count");
        Outcome moved = turn.moveBlock(branch.id(), branch.bodies().getFirst().id(), 0);

        assertInstanceOf(Outcome.Refused.class, moved, moved.toString());
        assertEquals(source, turn.source());
    }

    @Test
    void nothingByItsLabelIsAReturnType() {
        AssistTurn turn = turn(SOURCE);
        assertInstanceOf(Outcome.Accepted.class, turn.addMethod("", "idle", "Nothing", List.of()));
        assertInstanceOf(Outcome.Refused.class,
                turn.addMethod("", "idle2", "void", List.of(Map.of("name", "x", "type", "Nothing"))));
    }

    @Test
    void aSignatureChangeCarriesTheFilesCalls() {
        String source = """
                package com.mybot;
                public class Subject {
                    public void run() {
                        int count = 3;
                        System.out.println(helper(count));
                    }
                    static int helper(int n) {
                        return n;
                    }
                }
                """;
        AssistTurn turn = turn(source);
        Outcome changed = turn.editSignature("Subject.helper", "twice", null, List.of(
                Map.of("name", "n", "type", "int"), Map.of("name", "times", "type", "int")));

        assertInstanceOf(Outcome.Accepted.class, changed, changed.toString());
        assertTrue(turn.source().contains("twice(int n, int times)"), turn.source());
        assertTrue(turn.source().contains("twice(count, "), "the call keeps n and gains a default:\n" + turn.source());
        assertInstanceOf(Outcome.Refused.class, turn.editSignature("absent", null, null, null));
    }

    // ---- committing ----

    @Test
    void aTurnCommitsAsOneEditFromWhereItStarted() {
        AssistTurn turn = turn(SOURCE);
        turn.insert(runBody(turn).id(), 0, "block:PRINT");
        turn.setSlot(statement(turn, "String name").slots().getFirst().id(), "\"bot\"");
        assertEquals(2, turn.acceptedEdits());

        CoreApplicationEvents.CodeUpdatedEvent event = turn.commit(SOURCE, "Assistant").orElseThrow();
        assertEquals(SOURCE, event.previousCode());
        assertEquals(turn.source(), event.newCode());
    }

    @Test
    void aTurnDoesNotCommitOverAnEditTheUserMadeMeanwhile() {
        AssistTurn turn = turn(SOURCE);
        turn.insert(runBody(turn).id(), 0, "block:PRINT");

        Optional<CoreApplicationEvents.CodeUpdatedEvent> event = turn.commit(SOURCE + "// typed\n", "Assistant");
        assertTrue(event.isEmpty());
    }

    @Test
    void aTurnThatKeptNothingCommitsNothing() {
        AssistTurn turn = turn(SOURCE);
        turn.insert(runBody(turn).id(), 0, "block:NOPE");
        assertTrue(turn.commit(SOURCE, "Assistant").isEmpty());
    }
}
