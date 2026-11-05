package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.params.Param;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.java.index.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Studio's upgrade of a bot's own {@code @Param} fields into a group of {@code params/} (2026-11-04). */
class FieldParametersTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();

    private static String holder(ProjectConfig config, String members) {
        return """
                package %s;

                import com.botmaker.plugin.api.params.Param;

                public final class Parameters {
                %s
                }
                """.formatted(config.mainPackage(), members);
    }

    private static String reader(ProjectConfig config, String body) {
        return """
                package %s;

                public final class Reader {
                    int read() {
                        %s
                    }
                }
                """.formatted(config.mainPackage(), body);
    }

    private static Path source(ProjectConfig config, String name) {
        return config.mainPackageDir().resolve(name + ".java").toAbsolutePath().normalize();
    }

    private static FieldParameters.Plan plan(ProjectConfig config, Map<String, String> sources) {
        Map<Path, String> files = new LinkedHashMap<>();
        sources.forEach((name, text) -> files.put(source(config, name), text));
        return FieldParameters.plan(config, BotIndex.over(files, List.of(locationOf(Param.class)), config.sourceRoot()),
                GRAMMAR);
    }

    @Test
    void aFieldParameterBecomesAParamFile(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        FieldParameters.Plan plan = plan(config, Map.of(
                "Parameters", holder(config, """
                            @Param(visibility = Param.PUBLIC, category = "Limits", description = "Tries")
                            public static int tries = 3;
                            @Param(visibility = Param.PUBLIC)
                            public static int rounds = 2;
                        """),
                "Reader", reader(config, "return Parameters.tries + Parameters.rounds;")));

        Path group = JavaParameters.ownGroupFile(config, "Parameters").toAbsolutePath().normalize();
        String written = plan.rewrites().get(group);
        assertTrue(written.contains("public int tries()"), plan.toString());
        assertTrue(written.contains("return 3;"), written);
        assertTrue(written.contains("category = \"Limits\""), written);
        assertEquals(written, plan.starting().get(JavaParameters.startingFile(config, group).orElseThrow()));
        String read = plan.rewrites().get(source(config, "Reader"));
        assertTrue(read.contains("return new Parameters().tries() + new Parameters().rounds();"), read);
        String imported = "import " + config.parametersPackage() + ".bot.Parameters;";
        assertEquals(read.indexOf(imported), read.lastIndexOf(imported), "imported once: " + read);
        assertTrue(read.contains(imported), read);
        assertEquals(Set.of(source(config, "Parameters")), plan.deleted());
    }

    @Test
    void aHolderWithOtherMembersStaysAndTheGroupIsWrittenInFull(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        FieldParameters.Plan plan = plan(config, Map.of(
                "Parameters", holder(config, """
                            @Param(visibility = Param.PUBLIC) public static int tries = 3;
                            public static int helper() { return tries; }
                        """),
                "Reader", reader(config, "return Parameters.tries + Parameters.helper();")));

        assertTrue(plan.deleted().isEmpty(), plan.toString());
        String kept = plan.rewrites().get(source(config, "Parameters"));
        String group = "new " + config.parametersPackage() + ".bot.Parameters().tries()";
        assertTrue(kept.contains("return " + group + ";"), kept);
        assertFalse(kept.contains("import com.botmaker.plugin.api.params.Param;"), kept);
        assertTrue(plan.rewrites().get(source(config, "Reader")).contains(group + " + Parameters.helper()"));
    }

    @Test
    void anAuthorOnlyFieldBecomesAPlainConstantWhereItWas(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        // @Param's default visibility is the editor's: naming none is the same field.
        for (String mark : List.of("@Param(visibility = Param.EDITOR)", "@Param")) {
            FieldParameters.Plan plan = plan(config, Map.of(
                    "Parameters", holder(config, mark + "\n    public static int delay = 5;"),
                    "Reader", reader(config, "return Parameters.delay;")));

            String kept = plan.rewrites().get(source(config, "Parameters"));
            assertTrue(kept.contains("public static final int delay = 5;"), kept);
            assertFalse(kept.replace("Parameters", "").contains("Param"), kept);
            assertFalse(plan.rewrites().containsKey(source(config, "Reader")), plan.toString());
            assertTrue(plan.starting().isEmpty(), plan.toString());
        }
    }

    @Test
    void aFieldTheBotAssignsOrAValueThatNeedsItsHolderStays(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        FieldParameters.Plan plan = plan(config, Map.of(
                "Parameters", holder(config, """
                            @Param(visibility = Param.PUBLIC) public static int tries = 3;
                            @Param(visibility = Param.PUBLIC) public static int twice = tries * 2;
                        """),
                "Reader", reader(config, "Parameters.tries = 4; return 0;")));

        assertTrue(plan.isEmpty(), plan.toString());
        assertEquals(2, plan.left().size(), plan.toString());
        assertTrue(plan.left().getFirst().contains("assigns"), plan.toString());
    }

    @Test
    void anElementWriteOfAnArrayKeepsTheField(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        FieldParameters.Plan plan = plan(config, Map.of(
                "Parameters", holder(config,
                        "    @Param(visibility = Param.PUBLIC) public static int[] slots = {1, 2};"),
                "Reader", reader(config, "Parameters.slots[0] = 4; return 0;")));

        assertTrue(plan.isEmpty(), plan.toString());
    }

    @Test
    void anArrayInitializerStaysAField(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        FieldParameters.Plan plan = plan(config, Map.of(
                "Parameters", holder(config,
                        "    @Param(visibility = Param.PUBLIC) public static int[] slots = {1, 2};"),
                "Reader", reader(config, "return Parameters.slots.length;")));

        assertTrue(plan.starting().isEmpty(), plan.toString());
    }

    @Test
    void aNewGroupIsAMarkedClassOfItsPackage(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        String group = JavaParameters.emptyGroup(config, "Progress");

        assertTrue(group.contains("package " + config.parametersPackage() + ".bot;"), group);
        assertTrue(group.contains("import " + Param.class.getCanonicalName() + ";"), group);
        assertTrue(group.contains("@Param"), group);
        assertTrue(group.contains("public class Progress"), group);
    }

    @Test
    void anEnumThatHeldAFieldKeepsItsConstants(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        FieldParameters.Plan plan = plan(config, Map.of(
                "Mode", """
                        package %s;

                        import com.botmaker.plugin.api.params.Param;

                        public enum Mode {
                            A, B;
                            @Param(visibility = Param.PUBLIC) public static int level = 1;
                        }
                        """.formatted(config.mainPackage())));

        assertTrue(plan.deleted().isEmpty(), plan.toString());
    }

    @Test
    void aMoveThatWouldStopTheBotCompilingMovesNothing(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        // Nothing here reads the field, so nothing imports the group: the holder's own name would dangle.
        FieldParameters.Plan plan = plan(config, Map.of(
                "Parameters", holder(config, "    @Param(visibility = Param.PUBLIC) public static int tries = 3;"),
                "Reader", reader(config, "return Parameters.class.getModifiers();")));

        assertTrue(plan.isEmpty(), plan.toString());
        assertTrue(plan.starting().isEmpty(), plan.toString());
        assertEquals(1, plan.left().size(), plan.toString());
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
