package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The HUD's activity list is the methods a bot's {@code @Managed} values reference, each resolved to the file
 * that declares its class — whatever plugin's value holds them, and whatever file the author chose.
 */
class MethodReferencesTest {

    private static final String FLOW = """
            import com.botmaker.plugin.api.managed.Managed;

            public final class Sdk {
                @Managed("flow")
                public static Flow flow() {
                    return Flow.of(List.of(
                            Flow.activity(Collect::body, "Collect"),
                            Flow.activity(ctx -> Outcome.NEXT, "Inline"),
                            Flow.activity(Jobs::battle, "Battle"),
                            Flow.activity(Collect::body, "Again"),
                            Flow.activity(Missing::body, "Missing")));
                }

                public static Flow notManaged() {
                    return Flow.of(List.of(Flow.activity(Ignored::body, "Ignored")));
                }
            }
            """;

    @Test
    void readsEachReferenceInAManagedValueOnceInOrderAndSkipsLambdas() {
        List<String> labels = MethodReferences.read(FLOW).stream().map(MethodReferences.Target::label).toList();

        assertEquals(List.of("Collect::body", "Jobs::battle", "Missing::body"), labels);
    }

    @Test
    void aQualifiedReferenceIsMatchedOnItsSimpleName() {
        MethodReferences.Target target = MethodReferences.read("""
                class Sdk {
                    @Managed("flow") static Object flow() { return use(com.acme.bot.Collect::body); }
                }
                """).getFirst();

        assertEquals("com.acme.bot.Collect", target.className());
        assertEquals("Collect", target.simpleClassName());
        assertEquals("body", target.method());
    }

    @Test
    void eachTargetResolvesToTheFileDeclaringItsClass(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyFarmer", root);
        Path pkg = config.mainSourceFile().getParent();
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Sdk.java"), "package " + config.mainPackage() + ";\n\n" + FLOW);
        Files.writeString(pkg.resolve("Collect.java"), "package " + config.mainPackage() + ";\n\n"
                + "public final class Collect { public static Outcome body(ActivityContext ctx) { return null; } }\n");
        Files.writeString(pkg.resolve("Fights.java"), "package " + config.mainPackage() + ";\n\n"
                + "final class Fights { static final class Jobs { static Outcome battle(ActivityContext c) "
                + "{ return null; } } }\n");

        List<MethodReferences.Target> targets = MethodReferences.scan(config, null);

        assertEquals(3, targets.size());
        assertEquals(pkg.resolve("Collect.java").toAbsolutePath().normalize(), targets.get(0).file());
        assertEquals(pkg.resolve("Fights.java").toAbsolutePath().normalize(), targets.get(1).file(),
                "a nested class is found in the file that nests it");
        assertNull(targets.get(2).file(), "a class the bot does not declare is listed with nowhere to open");
        assertNotNull(MethodReferences.find(config, null, "Jobs::battle"));
        assertNull(MethodReferences.find(config, null, "Ignored::body"), "only @Managed values are read");
    }
}
