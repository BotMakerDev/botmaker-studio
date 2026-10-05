package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.overlay.ProbeResult;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A row's probe is the first call of the row's own expression whose binding is a declared probe's call — the
 * overload by its parameter types, never a call in a lambda — and its arguments are read as written, a named
 * constant by its initializer.
 */
class ProbeCallsTest {

    @TempDir
    Path root;

    private ProjectConfig config;
    private String pkg;
    private List<ProbeCalls.Declared> declared;

    @BeforeEach
    void writeBot() throws Exception {
        config = ProjectConfig.forProject("MyFarmer", root);
        pkg = config.mainPackage();
        Path dir = config.mainSourceFile().getParent();
        Files.createDirectories(dir);
        write(dir, "Pictures", "public final class Pictures { public static final String ONE = \"1\"; }");
        write(dir, "Calls", """
                public final class Calls {
                    static void run() {
                        int n = Integer.parseInt("42");
                        Integer.parseInt("7", 8);
                        Runnable later = () -> Integer.parseInt("1");
                        if (Integer.parseInt(Pictures.ONE) > 0) {
                            Integer.parseInt("inside");
                        }
                        Math.abs(Integer.parseInt("9"));
                    }
                }""");
        declared = List.of(new ProbeCalls.Declared("test", new OverlayPart.ProbedCall(
                Integer.class.getMethod("parseInt", String.class), context -> ProbeResult.unknown("probed"))));
    }

    private void write(Path dir, String name, String body) throws IOException {
        Files.writeString(dir.resolve(name + ".java"), "package " + pkg + ";\n\n" + body + "\n");
    }

    /** {@code run()}'s statement at {@code index}'s probe, read from the bound tree. */
    private Optional<ProbeCalls.Job> jobAt(int index) {
        ManagedConstants.Lookup constants = new ManagedConstants.Lookup(
                List.of(new ManagedConstants.Constant(pkg + ".Pictures", "ONE", "\"1\"")), PluginHost.grammar());
        return BotIndex.of(config, null).read(units -> {
            CompilationUnit unit = units.entrySet().stream()
                    .filter(e -> e.getKey().getFileName().toString().equals("Calls.java"))
                    .findFirst().orElseThrow().getValue();
            TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
            MethodDeclaration run = type.getMethods()[0];
            Statement statement = (Statement) run.getBody().statements().get(index);
            return ProbeCalls.job(index, statement, declared, constants);
        });
    }

    @Test
    void aLocalsInitializerIsProbedWithItsArgumentAsWritten() {
        ProbeCalls.Job job = jobAt(0).orElseThrow();
        assertEquals(0, job.key());
        assertEquals(List.of(new ProbeCalls.Argument("\"42\"", null)), job.arguments());
        assertFalse(job.acting(), "a probe not declared acting reads only");
    }

    @Test
    void anotherOverloadIsNotTheProbedCall() {
        assertTrue(jobAt(1).isEmpty(), "parseInt(String, int) is not parseInt(String)");
    }

    @Test
    void aCallInsideALambdaIsNotTheRowsCall() {
        assertTrue(jobAt(2).isEmpty());
    }

    @Test
    void anIfIsProbedByItsConditionAndAConstantReadsAsItsInitializer() {
        ProbeCalls.Job job = jobAt(3).orElseThrow();
        assertEquals(List.of(new ProbeCalls.Argument("Pictures.ONE", "\"1\"")), job.arguments(),
                "not the call in its branch, which is a row of its own");
    }

    @Test
    void aProbedCallNestedInAnUnprobedOneIsFound() {
        assertEquals(List.of(new ProbeCalls.Argument("\"9\"", null)), jobAt(4).orElseThrow().arguments());
    }

    @Test
    void noDeclaredProbeNoJob() {
        declared = List.of();
        assertTrue(jobAt(0).isEmpty());
    }
}
