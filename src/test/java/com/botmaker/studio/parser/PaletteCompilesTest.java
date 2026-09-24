package com.botmaker.studio.parser;

import com.botmaker.studio.assist.AssistTurn;
import com.botmaker.studio.assist.AssistWorkspace;
import com.botmaker.studio.assist.BlockView;
import com.botmaker.studio.assist.Outcome;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectTemplate;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every statement the palette offers, inserted where a user inserts one, compiles. This is the net under
 * "every block is compile-safe": a seed that writes an instance call into {@code main}, a second type of the
 * same name, a variable that is out of scope — each fails here, naming the entry and the place, before it
 * reaches anyone's file.
 *
 * <p>Inserted through {@link AssistTurn}, which compiles each edit against this test's classpath with
 * bindings, exactly as the canvas's guard does; a refusal is the failure.
 */
class PaletteCompilesTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                int total = 1;
                public static void main(String[] args) {
                    int count = 3;
                }
                public void run() {
                    String name = "a";
                    for (int i = 0; i < 3; i++) {
                        System.out.println(i);
                    }
                }
                static int tick() {
                    return 1;
                }
            }
            """;

    /** Entries that are refused in some places by design, since no seed could make them compile there. */
    private static final Set<String> NEEDS_A_LOOP = Set.of("BREAK", "CONTINUE");

    private static AssistWorkspace workspace() {
        return new AssistWorkspace(
                ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")),
                Paths.get("Subject.java").toAbsolutePath(),
                List.of(System.getProperty("java.class.path").split(File.pathSeparator)),
                Paths.get("src", "main", "java").toAbsolutePath(),
                ProjectTemplate.EMPTY, null, null, null,
                RefusalJournal.in(Path.of(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")),
                ValueGrammar.empty());
    }

    private static BlockView.Body method(AssistTurn turn, String name) {
        return turn.tree().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow().body();
    }

    private static BlockView.Body loop(AssistTurn turn) {
        return method(turn, "run").statements().stream()
                .flatMap(s -> s.bodies().stream()).findFirst().orElseThrow();
    }

    @Test
    void everyStatementCompilesInStaticMainAnInstanceMethodAndALoop() {
        List<String> failures = new ArrayList<>();
        record Place(String name, boolean loop, Function<AssistTurn, BlockView.Body> body) {}
        List<Place> places = List.of(
                new Place("static main", false, t -> method(t, "main")),
                new Place("an instance method", false, t -> method(t, "run")),
                new Place("a loop body", true, PaletteCompilesTest::loop));

        for (BlockType type : BlockCatalog.all()) {
            if (!type.isStatement()) continue;
            for (Place place : places) {
                if (!place.loop() && NEEDS_A_LOOP.contains(type.id())) continue;
                AssistTurn turn = new AssistTurn(workspace(), SOURCE);
                BlockView.Body body = place.body().apply(turn);
                Outcome outcome = turn.insert(body.id(), body.statements().size(), "block:" + type.id());
                if (!(outcome instanceof Outcome.Accepted)) {
                    failures.add(type.id() + " in " + place.name() + ": " + outcome);
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * Every value form the expression menu offers for a slot, picked into that slot, in static and in instance
     * code. The references ({@code Variable}, {@code Function Call}, …) are left out: they open a submenu of
     * the names in scope rather than writing anything themselves.
     */
    @Test
    void everyValueFormTheMenuOffersForASlotCompilesInIt() {
        List<String> slotTypes = List.of("int", "double", "String", "boolean", "char", "Runnable",
                "Throwable", "Object", "int[]", "Class<?>");
        List<String> failures = new ArrayList<>();
        int tried = 0;
        for (String modifier : List.of("static ", "")) {
            for (String slotType : slotTypes) {
                String source = """
                        package com.mybot;
                        public class Subject {
                            int total = 1;
                            public %svoid run() {
                                %s v = %s;
                            }
                        }
                        """.formatted(modifier, slotType, seedFor(slotType));
                EditorFixture probe = new EditorFixture(source);
                ResolvedType type = ResolvedType.of(declaration(probe).getType().resolveBinding());
                for (ExpressionType form : ExpressionCatalog.getForType(type, probe.state)) {
                    if (form instanceof ExpressionType.Reference) continue;
                    tried++;
                    EditorFixture f = new EditorFixture(source);
                    f.editor.replaceExpression(initializer(f), form);
                    if (f.lastCode == null) {
                        failures.add(form.id() + " into " + modifier + slotType + ": " + f.statusMessages);
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(tried > 60, "the menu offered only " + tried + " picks — the slots did not resolve");
    }

    private static String seedFor(String slotType) {
        return switch (slotType) {
            case "int", "double" -> "0";
            case "boolean" -> "false";
            case "char" -> "'a'";
            default -> "null";
        };
    }

    private static VariableDeclarationStatement declaration(EditorFixture f) {
        return (VariableDeclarationStatement) f.body("run").getStatements().getFirst().getAstNode();
    }

    private static Expression initializer(EditorFixture f) {
        return ((VariableDeclarationFragment) declaration(f).fragments().getFirst()).getInitializer();
    }

    @Test
    void aJumpOutsideALoopIsRefusedRatherThanWritten() {
        AssistTurn turn = new AssistTurn(workspace(), SOURCE);
        BlockView.Body main = method(turn, "main");
        Outcome outcome = turn.insert(main.id(), main.statements().size(), "block:BREAK");
        assertTrue(outcome instanceof Outcome.Refused, outcome.toString());
        assertTrue(turn.source().equals(SOURCE));
    }
}
