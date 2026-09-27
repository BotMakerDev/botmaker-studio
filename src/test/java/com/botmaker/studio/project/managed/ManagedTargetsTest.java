package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.BeforeEach;
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
 * The HUD's activity list is the methods a bot's {@code @Managed} values reference, each resolved by binding to
 * the file that declares it — whatever plugin's value holds them, and whatever file the author chose.
 */
class ManagedTargetsTest {

    private static final String FLOW = """
            import com.botmaker.plugin.api.managed.Managed;
            import java.util.List;
            import java.util.function.Function;

            public final class Sdk {
                @Managed("flow")
                public static List<Function<Object, Object>> flow() {
                    return List.of(
                            Collect::body,
                            ctx -> ctx,
                            Fights.Jobs::battle,
                            Collect::body,
                            Missing::body,
                            other.Collect::body);
                }

                public static List<Function<Object, Object>> notManaged() {
                    return List.of(Ignored::body);
                }
            }
            """;

    @TempDir
    Path root;

    private ProjectConfig config;
    private Path pkg;

    @BeforeEach
    void writeBot() throws IOException {
        config = ProjectConfig.forProject("MyFarmer", root);
        pkg = config.mainSourceFile().getParent();
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Sdk.java"), "package " + config.mainPackage() + ";\n\n" + FLOW);
        Files.writeString(pkg.resolve("Collect.java"), "package " + config.mainPackage() + ";\n\n"
                + "public final class Collect { public static Object body(Object ctx) { return null; } }\n");
        Files.writeString(pkg.resolve("Fights.java"), "package " + config.mainPackage() + ";\n\n"
                + "final class Fights { static final class Jobs { static Object battle(Object c) "
                + "{ return null; } } }\n");
        // A second Collect in another package: a name match would open the first file it met.
        Path other = config.sourceRoot().resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("Collect.java"), "package other;\n\n"
                + "public final class Collect { public static Object body(Object ctx) { return null; } }\n");
    }

    @Test
    void eachReferenceInAManagedValueIsListedOnceInOrderAndLambdasAreSkipped() {
        List<String> labels = ManagedTargets.scan(config, null).stream().map(ManagedTargets.Target::label).toList();

        assertEquals(List.of("Collect::body", "Fights.Jobs::battle", "Missing::body", "other.Collect::body"),
                labels);
        assertNull(ManagedTargets.find(config, null, "Ignored::body"), "only @Managed values are read");
    }

    @Test
    void eachTargetOpensTheFileJavacResolvesItTo() {
        List<ManagedTargets.Target> targets = ManagedTargets.scan(config, null);

        assertEquals(pkg.resolve("Collect.java").toAbsolutePath().normalize(), targets.get(0).file());
        assertEquals(config.mainPackage() + ".Collect", targets.get(0).className());
        assertEquals(pkg.resolve("Fights.java").toAbsolutePath().normalize(), targets.get(1).file(),
                "a nested class is found in the file that nests it");
        assertEquals(config.sourceRoot().resolve("other/Collect.java").toAbsolutePath().normalize(),
                targets.get(3).file(), "a same-named class elsewhere is the one the reference names");
    }

    @Test
    void aReferenceThatResolvesNowhereIsListedWithNowhereToOpen() {
        ManagedTargets.Target missing = ManagedTargets.find(config, null, "Missing::body");

        assertNotNull(missing);
        assertNull(missing.file());
        assertEquals("Missing", missing.simpleClassName());
        assertEquals("body", missing.method());
    }
}
