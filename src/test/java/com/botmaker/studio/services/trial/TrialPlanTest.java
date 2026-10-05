package com.botmaker.studio.services.trial;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ▶ Try's plan: the earlier locals a statement reads, each defaulted to the last run, else computed when its
 * initializer only reads, else asked; the statement qualified for a caller in another class; and what such a caller
 * cannot run refused with a sentence.
 */
class TrialPlanTest {

    @TempDir
    Path root;

    private ProjectConfig config;
    private String pkg;

    /** {@code Integer.parseInt} stands for a call a plugin probes read-only; nothing else is. */
    private static final java.util.function.Predicate<org.eclipse.jdt.core.dom.IMethodBinding> READ_ONLY =
            b -> b != null && b.getName().equals("parseInt");

    @BeforeEach
    void writeBot() throws Exception {
        config = ProjectConfig.forProject("MyFarmer", root);
        pkg = config.mainPackage();
        Path dir = config.mainSourceFile().getParent();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Calls.java"), "package " + pkg + ";\n\n" + """
                public final class Calls {
                    static final int LIMIT = 3;
                    int count;

                    static int helper(int n) { return n + 1; }
                    private static int secret() { return 0; }
                    void instance() {}

                    static int run(int given) {
                        int parsed = Integer.parseInt("42");
                        int total = parsed + LIMIT;
                        int counter = 0;
                        counter++;
                        int clicked = helper(given);
                        helper(total + counter + clicked);
                        int hidden = secret();
                        for (int i = 0; i < 3; i++) {
                            break;
                        }
                        Runnable later = () -> helper(1);
                        return total;
                    }
                }
                """);
    }

    /** {@code run}'s statement at {@code index}, planned through {@code plan}. */
    private <T> T at(int index, Function<Statement, T> plan) {
        return BotIndex.of(config, null).read(units -> {
            CompilationUnit unit = units.entrySet().stream()
                    .filter(e -> e.getKey().getFileName().toString().equals("Calls.java"))
                    .findFirst().orElseThrow().getValue();
            TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
            MethodDeclaration run = java.util.Arrays.stream(type.getMethods())
                    .filter(m -> m.getName().getIdentifier().equals("run")).findFirst().orElseThrow();
            return plan.apply((Statement) run.getBody().statements().get(index));
        });
    }

    private String source() throws Exception {
        return Files.readString(config.mainSourceFile().getParent().resolve("Calls.java"));
    }

    private TrialPlan.Result plan(int index, TrialPlan.LastRun lastRun) throws Exception {
        String source = source();
        return at(index, s -> TrialPlan.plan(s, source, READ_ONLY, text -> false, lastRun));
    }

    private TrialPlan.Plan planned(int index, TrialPlan.LastRun lastRun) throws Exception {
        return assertInstanceOf(TrialPlan.Planned.class, plan(index, lastRun)).plan();
    }

    private static TrialPlan.Local local(TrialPlan.Plan plan, String name) {
        return plan.locals().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theSliceFollowsComputedLocalsAndStopsAtTheRunsOwn() throws Exception {
        TrialPlan.Plan plan = planned(5, TrialPlan.LastRun.NONE);   // helper(total + counter + clicked);

        // given is read by clicked's initializer only, and clicked is asked for, not computed.
        assertEquals(List.of("parsed", "total", "counter", "clicked"),
                plan.locals().stream().map(TrialPlan.Local::name).toList(), "declaration order");
        assertEquals(TrialPlan.Source.COMPUTE, local(plan, "parsed").defaultSource(), "a read-only probed call");
        assertEquals("Integer.parseInt(\"42\")", local(plan, "parsed").initializer());
        assertEquals("parsed + " + pkg + ".Calls.LIMIT", local(plan, "total").initializer(),
                "a constant of the class is qualified");
        assertEquals(List.of("parsed"), local(plan, "total").reads());
        assertEquals(TrialPlan.Source.ASK, local(plan, "counter").defaultSource(), "assigned again after it");
        assertEquals(TrialPlan.Source.ASK, local(plan, "clicked").defaultSource(), "helper() is not read-only");
        assertEquals("report(\"helper(…)\", " + pkg + ".Calls.helper(total + counter + clicked));", plan.body(),
                "the class's own call is qualified, and what it answers reported");
        assertTrue(plan.valued(), "run() returns a value");
    }

    @Test
    void askingForAComputedLocalDropsWhatItsInitializerRead() throws Exception {
        TrialPlan.Plan plan = planned(5, TrialPlan.LastRun.NONE);

        assertEquals(List.of("parsed", "total", "counter", "clicked"),
                plan.needed(Map.of()).stream().map(TrialPlan.Local::name).toList());
        assertEquals(List.of("total", "counter", "clicked"),
                plan.needed(Map.of("total", TrialPlan.Source.ASK)).stream().map(TrialPlan.Local::name).toList());
    }

    @Test
    void theLastRunComesFirstWhenItHasTheValue() throws Exception {
        JavaValue seven = JavaValue.parse("7").orElseThrow();
        TrialPlan.Plan plan = planned(4, (className, method, local, typeName) ->
                className.equals(pkg + ".Calls") && method.equals("run") && local.equals("given")
                        && typeName.equals("int") ? Optional.of(seven) : Optional.empty());

        TrialPlan.Local given = local(plan, "given");
        assertEquals(TrialPlan.Source.LAST_RUN, given.defaultSource());
        assertEquals(List.of(TrialPlan.Source.LAST_RUN, TrialPlan.Source.ASK), given.sources());
        assertEquals("int", given.type());
        assertNull(given.initializer(), "a parameter has no initializer to compute");
    }

    @Test
    void aDeclarationReportsWhatItHolds() throws Exception {
        TrialPlan.Plan plan = planned(0, TrialPlan.LastRun.NONE);   // int parsed = Integer.parseInt("42");

        assertEquals("int parsed = Integer.parseInt(\"42\");\nreport(\"parsed\", parsed);", plan.body());
        assertEquals(List.of(), plan.locals());
    }

    @Test
    void whatACallerInAnotherClassCannotRunIsRefused() throws Exception {
        assertTrue(assertInstanceOf(TrialPlan.Refused.class, plan(6, TrialPlan.LastRun.NONE)).reason()
                .contains("private to Calls"), "int hidden = secret();");

        TrialPlan.Plan loop = planned(7, TrialPlan.LastRun.NONE);
        assertTrue(loop.body().startsWith("for"), "a break inside its own loop is fine");
    }

    @Test
    void aBreakOutOfAnEnclosingLoopIsRefused() throws Exception {
        String source = source();
        TrialPlan.Result result = at(7, loop -> {
            Statement brk = (Statement) ((org.eclipse.jdt.core.dom.Block)
                    ((org.eclipse.jdt.core.dom.ForStatement) loop).getBody()).statements().getFirst();
            return TrialPlan.plan(brk, source, READ_ONLY, text -> false, TrialPlan.LastRun.NONE);
        });
        assertTrue(assertInstanceOf(TrialPlan.Refused.class, result).reason().contains("loop around it"));
    }

    @Test
    void anActivityIsACallOfItsMethod() {
        TrialPlan.Plan plan = TrialPlan.call("com.bot", "com.bot.Collect", "body", true);

        assertEquals("com.bot", plan.packageName());
        assertEquals("return com.bot.Collect.body();", plan.body());
        assertEquals("Collect.body()", plan.label());

        TrialPlan.Plan nested = TrialPlan.call("com.bot", "com.bot.Outer.Inner", "go", false);
        assertEquals("com.bot", nested.packageName(), "a nested class's package is not its outer class");
        assertEquals("com.bot.Outer.Inner.go();", nested.body());
    }
}
