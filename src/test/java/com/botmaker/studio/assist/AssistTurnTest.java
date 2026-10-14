package com.botmaker.studio.assist;

import com.botmaker.plugin.api.value.Drawn;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.managed.ManagedConstants.Constant;
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
public class AssistTurnTest {

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

    /**
     * A step of a turn sees the bot's other files, so an entry point inserted in main is handed the bot's holder
     * of values (Bot.runFlow(Sdk.class)); staged alone, it was handed null (2026-10-14).
     */
    @Test
    void aStagedStepSeesTheBotsOtherFilesAndItsOwnSource() {
        AssistWorkspace base = workspace();
        Path sdk = Paths.get("Sdk.java").toAbsolutePath();
        AssistWorkspace workspace = new AssistWorkspace(base.config(), base.file(), base.classpath(), base.sourceRoot(),
                base.template(), base.settings(), base.index(), base.journal(), base.grammar(), base.constants(),
                List.of(new ProjectFile(sdk, "public final class Sdk {}"),
                        new ProjectFile(base.file(), "stale")));

        ProjectState staged = workspace.stage(SOURCE);

        assertEquals("public final class Sdk {}", staged.getFile(sdk).orElseThrow().getContent());
        assertEquals(SOURCE, staged.getFile(base.file()).orElseThrow().getContent(), "the turn's own copy wins");
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
        List<Constant> constants = List.of(
                new Constant("com.mybot.Subject", "LIMIT", "5"),
                new Constant("com.mybot.Subject", "NAME", "\"a\""));
        AssistWorkspace base = workspace();
        AssistTurn turn = new AssistTurn(new AssistWorkspace(base.config(), base.file(), base.classpath(),
                base.sourceRoot(), base.template(), base.settings(), base.index(), base.journal(), base.grammar(),
                constants), source);
        String slot = statement(turn, "System.out.println").slots().getFirst().id();

        assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "Subject.NAME"), "a String is not an int");
        assertInstanceOf(Outcome.Accepted.class, turn.setSlot(slot, "Subject.LIMIT"));
        assertTrue(turn.source().contains("System.out.println(Subject.LIMIT)"), turn.source());
    }

    /** A picture, as the SDK's {@code ImageTemplate} is written. */
    public static final class Pic {
        private final String path;

        public Pic(String path) { this.path = path; }

        public String path() { return path; }

        @Override public boolean equals(Object o) { return o instanceof Pic p && p.path.equals(path); }

        @Override public int hashCode() { return path.hashCode(); }
    }

    /** Several pictures, as the SDK's {@code ImageTemplateGroup} is written. */
    public static final class Pics {
        private final List<Pic> all;

        private Pics(List<Pic> all) { this.all = all; }

        public static Pics of(Pic... pics) { return new Pics(List.of(pics)); }

        public List<Pic> all() { return all; }

        public static void take(Pics pics) {}
    }

    private static final Drawn NO_EDITOR = () -> ctx -> null;

    @Test
    void aSlotReadsTheBotsConstantsInsideAValue() {
        String pic = Pic.class.getCanonicalName();
        String source = SOURCE
                .replace("public class Subject {", "public class Subject {\n    public static final " + pic
                        + " ORE = new " + pic + "(\"ore.png\");\n    public static final " + pic + " GEM = new "
                        + pic + "(\"gem.png\");")
                .replace("System.out.println(count);", Pics.class.getCanonicalName() + ".take(null);");
        ValueGrammar grammar = ValueGrammar.of(List.of(
                PluginType.value(Pic.class).fresh(() -> new Pic("default.png"))
                        .editor(NO_EDITOR).writtenAs(Pic::new, Pic::path),
                PluginType.value(Pics.class).fresh(() -> Pics.of(new Pic("default.png")))
                        .editor(NO_EDITOR).writtenAsEach(Pics::of, Pics::all)), List.of());
        List<Constant> constants = List.of(
                new Constant("com.mybot.Subject", "ORE",
                        "new " + pic + "(\"ore.png\")"),
                new Constant("com.mybot.Subject", "GEM",
                        "new " + pic + "(\"gem.png\")"));
        AssistWorkspace base = workspace();
        AssistTurn turn = new AssistTurn(new AssistWorkspace(base.config(), base.file(), base.classpath(),
                base.sourceRoot(), base.template(), base.settings(), base.index(), base.journal(), grammar,
                constants), source);
        String slot = statement(turn, Pics.class.getCanonicalName()).slots().getFirst().id();

        Outcome.Refused near = assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "Pics.none()"));
        assertTrue(near.reasons().stream().anyMatch(r -> r.contains("It is written like `") && r.contains("Pics.of(")),
                "a refusal names the type's own spelling: " + near.reasons());
        Outcome set = turn.setSlot(slot, "Pics.of(Subject.ORE, Subject.GEM)");
        assertInstanceOf(Outcome.Accepted.class, set, set + " " + turn.errors());
        assertTrue(turn.source().contains("Pics.of(Subject.ORE, Subject.GEM)"), "each part is the bot's constant:\n"
                + turn.source());
    }

    @Test
    void aTestIsBuiltInItsSlotFromAnOperatorWhoseOperandsAreSlotsInTurn() {
        String source = SOURCE.replace("System.out.println(count);",
                "if (count > 1) {\n            System.out.println(count);\n        }");
        AssistTurn turn = turn(source);
        String condition = statement(turn, "if (count").slots().getFirst().id();

        Outcome negated = turn.setSlot(condition, "op:NOT");
        assertInstanceOf(Outcome.Accepted.class, negated, negated.toString());
        assertTrue(turn.source().contains("if (!"), turn.source());
        List<BlockView.Slot> slots = statement(turn, "if (!").slots();
        assertTrue(slots.size() > 1, "the operand is a slot of its own: " + slots);
        assertTrue(slots.stream().allMatch(s -> s.type().equals("boolean")), slots.toString());
    }

    @Test
    void anOperatorOrCallThatDoesNotFitTheSlotIsRefused() {
        AssistTurn turn = turn(SOURCE);
        String slot = statement(turn, "System.out.println").slots().getFirst().id();

        Outcome.Refused logic = assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "op:AND"));
        assertTrue(logic.reasons().getFirst().contains("does not give a int"), logic.reasons().toString());
        Outcome.Refused unknown = assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "op:EXEC"));
        assertTrue(unknown.reasons().getFirst().contains("op:NOT"), "it lists the operators: " + unknown.reasons());
        assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "call:Runtime.exec(String)"));
        assertEquals(SOURCE, turn.source());
    }

    @Test
    void aCodeSlotTakesALambdaWhoseBodyIsABody() {
        AssistTurn turn = turn(SOURCE.replace("System.out.println(count);", "java.util.List.of(name).forEach(null);"));
        String slot = statement(turn, "java.util.List").slots().getFirst().id();

        Outcome set = turn.setSlot(slot, "op:LAMBDA");
        assertInstanceOf(Outcome.Accepted.class, set, set.toString());
        assertEquals(1, statement(turn, "java.util.List").bodies().size(), turn.source());
        assertInstanceOf(Outcome.Accepted.class,
                turn.insert(statement(turn, "java.util.List").bodies().getFirst().id(), 0, "block:PRINT"));
    }

    @Test
    void aSlotTakesAVariableInScopeOfItsType() {
        AssistTurn turn = turn(SOURCE.replace("System.out.println(count);",
                "java.util.List.of(name).forEach(each -> {\n            System.out.println(count);\n        });"));
        String printed = statement(turn, "java.util.List").bodies().getFirst().statements().getFirst()
                .slots().getFirst().id();

        assertInstanceOf(Outcome.Refused.class, turn.setSlot(printed, "var:missing"), "out of scope");
        assertInstanceOf(Outcome.Refused.class, turn.setSlot(printed, "var:count + 1"), "a name, not Java");
        Outcome set = turn.setSlot(printed, "var:each");
        assertInstanceOf(Outcome.Accepted.class, set, set.toString());
        assertTrue(turn.source().contains("System.out.println(each)"), turn.source());
    }

    @Test
    void aVariableIsRenamedAndRetypedAsTheDialogDoes() {
        AssistTurn turn = turn(SOURCE);
        String declared = statement(turn, "String name").id();

        Outcome renamed = turn.editVariable(declared, "label", "");
        assertInstanceOf(Outcome.Accepted.class, renamed, renamed.toString());
        assertTrue(turn.source().contains("String label = \"a\""), turn.source());

        String counted = statement(turn, "int count").id();
        Outcome retyped = turn.editVariable(counted, "", "double");
        assertInstanceOf(Outcome.Accepted.class, retyped, retyped.toString());
        assertTrue(turn.source().contains("double count"), turn.source());
        assertInstanceOf(Outcome.Refused.class, turn.editVariable(counted, "", "Runtime"));
        assertInstanceOf(Outcome.Refused.class, turn.editVariable(counted, "class", ""));
    }

    @Test
    void aRetypeIsNotKeptWhenTheRenameAfterItIsRefused() {
        AssistTurn turn = turn(SOURCE);
        Outcome both = turn.editVariable(statement(turn, "int count").id(), "name", "double");

        assertInstanceOf(Outcome.Refused.class, both, "name is taken: " + both);
        assertEquals(SOURCE, turn.source());
        assertEquals(0, turn.acceptedEdits());
    }

    @Test
    void aVarargsCallTakesOneMoreArgumentAndANestedCallsArgumentsAreSlots() {
        AssistTurn turn = turn(SOURCE.replace("System.out.println(count);",
                "System.out.println(String.format(\"%d\", Math.max(count, 2)));"));
        List<BlockView.Slot> slots = statement(turn, "System.out.println").slots();
        assertTrue(slots.stream().anyMatch(s -> s.value().equals("2")), "a nested call's arguments are slots: " + slots);

        String max = slots.stream().filter(s -> s.value().startsWith("Math.max")).findFirst().orElseThrow().id();
        assertInstanceOf(Outcome.Refused.class, turn.addArgument(max), "Math.max takes two");
        Outcome added = turn.addArgument(slots.getFirst().id());
        assertInstanceOf(Outcome.Accepted.class, added, added.toString());
        assertTrue(turn.source().contains("Math.max(count, 2), "), turn.source());
    }

    @Test
    void aManagedValueTakesNoOperator() {
        String source = SOURCE.replace("public class Subject {", """
                public class Subject {
                    @com.botmaker.plugin.api.managed.ManagedMarker @interface Mark { }
                    @Mark public static boolean flag() { return true; }
                """);
        AssistTurn turn = turn(source);
        String slot = turn.tree().stream().filter(m -> m.name().equals("flag")).findFirst().orElseThrow()
                .body().statements().getFirst().slots().getFirst().id();

        Outcome.Refused refused = assertInstanceOf(Outcome.Refused.class, turn.setSlot(slot, "op:NOT"));
        assertTrue(refused.reasons().getFirst().contains("managed value"), refused.reasons().toString());
        assertEquals(source, turn.source());
    }

    @Test
    void aCallStatementMovesByTheIdTheTreeGivesItButAConditionIsNoStatement() {
        String source = SOURCE.replace("System.out.println(count);",
                "if (count > 1) {\n            System.out.println(count);\n        }");
        AssistTurn turn = turn(source);
        BlockView.Statement branch = statement(turn, "if (count");
        assertInstanceOf(Outcome.Refused.class, turn.delete(branch.slots().getFirst().id()));

        BlockView.Statement printed = branch.bodies().getFirst().statements().getFirst();
        Outcome moved = turn.moveBlock(printed.id(), runBody(turn).id(), 1);
        assertInstanceOf(Outcome.Accepted.class, moved, moved.toString());
        assertTrue(runBody(turn).statements().get(1).text().startsWith("System.out.println"), turn.source());
    }

    @Test
    void aLibraryCallStatementListsItsArgumentsAsSlots() {
        AssistTurn turn = turn(SOURCE.replace("System.out.println(count);", "Math.max(count, 2);"));
        List<BlockView.Slot> slots = statement(turn, "Math.max").slots();

        assertEquals(List.of("count", "2"), slots.stream().map(BlockView.Slot::value).toList());
    }

    @Test
    void aCallStatementIsDeletedByTheIdTheTreeGivesIt() {
        AssistTurn turn = turn(SOURCE);
        Outcome deleted = turn.delete(statement(turn, "System.out.println").id());

        assertInstanceOf(Outcome.Accepted.class, deleted, deleted.toString());
        assertFalse(turn.source().contains("println"), turn.source());
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

    /** An activity's body returns its own enum, which no plugin declares (2026-10-13). */
    @Test
    void aMethodReturnsATypeTheBotDeclares() {
        AssistTurn turn = turn("""
                package com.bot.flow;

                public class Subject {
                    public enum Outcomes { NEXT, DONE }

                    void run() {
                    }
                }
                """);
        Outcome added = turn.addMethod("", "body", "Subject.Outcomes", List.of());

        assertInstanceOf(Outcome.Accepted.class, added, added.toString());
        assertTrue(turn.source().contains("Subject.Outcomes body()"), turn.source());
        assertFalse(turn.source().contains("com.bot.flow.Subject.Outcomes"), "written as the file names it");

        Outcome changed = turn.editSignature("body", null, "void", null);
        assertInstanceOf(Outcome.Accepted.class, changed, changed.toString());
        Outcome back = turn.editSignature("body", null, "Outcomes", null);
        assertInstanceOf(Outcome.Accepted.class, back, "a member type by its own name too: " + back);

        // Its seeded `return null;` takes one of the enum's constants, by the method's return type.
        BlockView.Statement returned = turn.tree().stream().filter(m -> m.name().equals("body")).findFirst()
                .orElseThrow().body().statements().getFirst();
        BlockView.Slot result = returned.slots().getFirst();
        assertEquals("com.bot.flow.Subject.Outcomes", result.type());
        assertTrue(result.writable(), result.toString());
        Outcome set = turn.setSlot(result.id(), "NEXT");
        assertInstanceOf(Outcome.Accepted.class, set, set.toString());
        assertTrue(turn.source().contains("return Subject.Outcomes.NEXT;"), turn.source());
        assertInstanceOf(Outcome.Refused.class, turn.setSlot(result.id(), "LATER"), "not one of its constants");
        assertInstanceOf(Outcome.Refused.class, turn.setSlot(result.id(), "Other.NEXT"), "another type's name");
        Outcome qualified = turn.setSlot(result.id(), "Outcomes.DONE");
        assertInstanceOf(Outcome.Accepted.class, qualified, qualified.toString());
        assertTrue(turn.source().contains("return Subject.Outcomes.DONE;"), turn.source());
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
