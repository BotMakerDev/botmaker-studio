package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's side of a {@code @Param} field: one list, and one edit per thing a person does to a row.
 *
 * <p>No JavaFX and no plugin. The grammar is {@link TestValues}' for the reason written there — Studio
 * depends on no plugin — and there is no {@code ProjectState}, because these are files nobody has open.
 * What is asserted is the <b>file</b> in every case: this class exists to write a person's own source, so a
 * row that came back right while the file was wrong would be the failure that matters.
 */
class JavaParametersTest {

    // ---- what a section is ------------------------------------------------------------------------------

    @Test
    void everyFieldIsListedUnderItsOwnClass(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(category = "Limits")
                    public static int maxAttempts = 10;
                """));
        write(config, "Tuning.java", """
                package com.refbot;

                import com.botmaker.plugin.basics.params.Param;

                public final class Tuning {
                    @Param
                    public static String mode = "fast";
                }
                """);

        List<JavaParameter> rows = JavaParameters.scan(config, null, TestValues.GRAMMAR);

        // A set: the walk visits files in whatever order the filesystem lists them, and which of two classes
        // comes first is not something this class decides or a user could notice.
        assertEquals(Set.of("Parameters", "Tuning"),
                rows.stream().map(JavaParameter::className).collect(Collectors.toSet()));
        assertEquals(List.of("Limits"), JavaParameters.categories(config, null, TestValues.GRAMMAR));
    }

    // ---- adding -----------------------------------------------------------------------------------------

    @Test
    void aProjectsFirstParameterCreatesTheClassItGoesIn(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);

        Optional<ParameterRow> stored = JavaParameters.add(config, null, "Parameters", "maxAttempts",
                TestValues.WHOLE_NUMBER, java("10"), "Limits", "How many times to try", TestValues.GRAMMAR);

        assertTrue(stored.isPresent());
        assertEquals("10", stored.get().value());
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertTrue(source.contains("import com.botmaker.plugin.api.params.Param;"), source);
        assertTrue(source.contains("public static int maxAttempts = 10;"), source);
        assertTrue(source.contains("category = \"Limits\""), source);
    }

    @Test
    void aNameTheClassAlreadyDeclaresIsNotWrittenTwice(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));

        Optional<ParameterRow> refused = JavaParameters.add(config, null, "Parameters", "maxAttempts",
                TestValues.TEXT, java("\"x\""), "", "", TestValues.GRAMMAR);

        assertTrue(refused.isEmpty());
        assertEquals(1, JavaParameters.scan(config, null, TestValues.GRAMMAR).size());
    }

    // ---- declaring --------------------------------------------------------------------------------------

    @Test
    void aRenameMovesTheDeclarationAndEveryReferenceToIt(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        write(config, "Bot.java", """
                package com.refbot;

                import static com.refbot.Parameters.maxAttempts;

                public class Bot {
                    void run() { for (int i = 0; i < Parameters.maxAttempts; i++) {} }
                    int bare() { return maxAttempts; }
                    int local() { int maxAttempts = 3; return maxAttempts; }
                    String text() { return "Parameters.maxAttempts"; }
                }
                """);

        JavaParameter entry = only(config);
        Optional<ParameterRow> stored = JavaParameters.declare(config, null, entry,
                renamed(entry.row(), "tries"), entry.form(), TestValues.GRAMMAR).stored();

        assertTrue(stored.isPresent());
        assertEquals("tries", stored.get().name());
        String bot = Files.readString(config.mainPackageDir().resolve("Bot.java"));
        assertTrue(bot.contains("i < Parameters.tries"), bot);
        assertTrue(bot.contains("import static com.refbot.Parameters.tries;"), bot);
        assertTrue(bot.contains("int bare() { return tries; }"), bot);
        // Found by binding, not by spelling: a local of the same name and a string are somebody else's.
        assertTrue(bot.contains("int local() { int maxAttempts = 3; return maxAttempts; }"), bot);
        assertTrue(bot.contains("return \"Parameters.maxAttempts\";"), bot);
    }

    @Test
    void aSameNamedFieldOfAnotherClassIsNotRenamed(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        write(config, "Limits.java", """
                package com.refbot;

                public class Limits {
                    public static int maxAttempts = 4;
                    int read() { return Limits.maxAttempts + Parameters.maxAttempts; }
                }
                """);

        JavaParameter entry = only(config);
        JavaParameters.declare(config, null, entry, renamed(entry.row(), "tries"), entry.form(), TestValues.GRAMMAR);

        String limits = Files.readString(config.mainPackageDir().resolve("Limits.java"));
        assertTrue(limits.contains("public static int maxAttempts = 4;"), limits);
        assertTrue(limits.contains("return Limits.maxAttempts + Parameters.tries;"), limits);
    }

    @Test
    void aRenameToANameJavaRefusesChangesNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        String before = Files.readString(config.mainPackageDir().resolve("Parameters.java"));

        JavaParameter entry = only(config);
        JavaParameters.Outcome outcome = JavaParameters.declare(config, null, entry, renamed(entry.row(), "class"),
                entry.form(), TestValues.GRAMMAR);

        assertInstanceOf(JavaParameters.Outcome.Refused.class, outcome);
        assertEquals(before, Files.readString(config.mainPackageDir().resolve("Parameters.java")));
    }

    @Test
    void aRetypeThatBreaksAUseElsewhereIsPutBack(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        write(config, "Bot.java", """
                package com.refbot;

                public class Bot {
                    int tries() { return Parameters.maxAttempts; }
                }
                """);
        String before = Files.readString(config.mainPackageDir().resolve("Parameters.java"));

        JavaParameter entry = only(config);
        JavaParameters.Outcome outcome = JavaParameters.declare(config, null, entry, entry.row(), TestValues.TEXT,
                TestValues.GRAMMAR, new BotParser(List.of(), config.sourceRoot()));

        JavaParameters.Outcome.Refused refused = assertInstanceOf(JavaParameters.Outcome.Refused.class, outcome);
        assertTrue(refused.reason().contains("Bot.java"), refused.reason());
        assertEquals(before, Files.readString(config.mainPackageDir().resolve("Parameters.java")),
                "a String where Bot.java returns an int would not compile, so the retype is undone");
    }

    @Test
    void theAnnotationMembersAreWrittenAndRemovedByTheSameCall(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(category = "Limits", description = "How many times to try")
                    public static int maxAttempts = 10;
                """));

        JavaParameter entry = only(config);
        ParameterRow wanted = entry.row().toBuilder()
                .category("")                       // removed: the default is already the empty answer
                .description("Attempts before it gives up")
                .visibility(Visibility.PUBLIC)
                .bounds(1, 50.5)
                .build();
        Optional<ParameterRow> stored =
                JavaParameters.declare(config, null, entry, wanted, entry.form(), TestValues.GRAMMAR).stored();

        assertTrue(stored.isPresent());
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertFalse(source.contains("category ="), source);
        assertTrue(source.contains("description = \"Attempts before it gives up\""), source);
        assertTrue(source.contains("visibility = Param.PUBLIC"), source);
        // A number, since @Param's bounds became double: a string here would not compile.
        assertTrue(source.contains("min = 1,") || source.contains("min = 1)"), source);
        assertTrue(source.contains("max = 50.5"), source);
        assertEquals(Visibility.PUBLIC, stored.get().visibility());
        assertEquals(50.5, stored.get().max());
    }

    @Test
    void anInfiniteBoundRemovesTheMember(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(min = 1, max = 50)
                    public static int maxAttempts = 10;
                """));

        JavaParameter entry = only(config);
        ParameterRow wanted = entry.row().toBuilder().bounds(1, Double.POSITIVE_INFINITY).build();
        JavaParameters.declare(config, null, entry, wanted, entry.form(), TestValues.GRAMMAR);

        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertFalse(source.contains("max ="), source);
        assertTrue(source.contains("min = 1"), source);
    }

    @Test
    void aRetypeRewritesTheTypeAndResetsTheValue(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));

        JavaParameter entry = only(config);
        ParameterRow stored = JavaParameters.declare(config, null, entry, entry.row(), TestValues.TEXT,
                TestValues.GRAMMAR).stored().orElseThrow();

        assertEquals("String", stored.typeName());
        // The old type's text is not carried across: a value written for one type is not a value of another.
        assertEquals("\"\"", stored.value());
        assertTrue(Files.readString(config.mainPackageDir().resolve("Parameters.java"))
                .contains("public static String maxAttempts"));
    }

    @Test
    void aRetypeDropsTheChoicesAndBoundsOfTheOldType(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(options = {"1", "5"}, min = 1, max = 5)
                    public static int maxAttempts = 1;
                """));

        JavaParameter entry = only(config);
        // The window retypes with the row as it stands, choices and all.
        ParameterRow stored = JavaParameters.declare(config, null, entry, entry.row(), TestValues.TEXT,
                TestValues.GRAMMAR).stored().orElseThrow();

        // A choice written for an int is no value of a String: kept, it drew as a disabled row holding the
        // old type's Java, and the window read as if the retype had not happened.
        assertEquals(List.of(), stored.options());
        assertEquals(Double.NEGATIVE_INFINITY, stored.min());
        assertEquals(Double.POSITIVE_INFINITY, stored.max());
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertFalse(source.contains("options"), source);
        assertFalse(source.contains("min ="), source);
    }

    @Test
    void aRetypeCarriesTheNewTypesOwnChoices(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(options = {"1", "5"})
                    public static int maxAttempts = 1;
                """));

        JavaParameter entry = only(config);
        // "One of" retyped to text: the window hands the new type's first choice over with the retype, so
        // the field is still one of a set rather than falling back to any value.
        ParameterRow wanted = entry.row().toBuilder().options(List.of("\"fast\"")).build();
        ParameterRow stored = JavaParameters.declare(config, null, entry, wanted, TestValues.TEXT,
                TestValues.GRAMMAR).stored().orElseThrow();

        assertEquals(List.of("\"fast\""), stored.options());
    }

    // ---- values -----------------------------------------------------------------------------------------

    @Test
    void aValueIsWrittenAsTheTypesOwnJava(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static java.time.Duration rest = java.time.Duration.ofMillis(3000L);
                """));

        ParameterRow stored = JavaParameters.setValue(config, null, only(config),
                java("java.time.Duration.ofMillis(60000L)"), TestValues.GRAMMAR).orElseThrow();

        assertEquals("java.time.Duration.ofMillis(60000L)", stored.value());
        assertTrue(Files.readString(config.mainPackageDir().resolve("Parameters.java"))
                .contains("java.time.Duration.ofMillis(60000L)"));
    }

    @Test
    void aFieldTheEditorCannotReadIsListedAndLeftAlone(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static java.time.Duration rest = java.time.Duration.ofSeconds(3);
                """));
        String before = Files.readString(config.mainPackageDir().resolve("Parameters.java"));

        JavaParameter entry = only(config);

        assertFalse(entry.editable());
        assertFalse(entry.note().isBlank(), "a read-only row says why");
        assertTrue(JavaParameters.setValue(config, null, entry,
                java("java.time.Duration.ofMillis(60000L)"), TestValues.GRAMMAR).isEmpty());
        assertEquals(before, Files.readString(config.mainPackageDir().resolve("Parameters.java")),
                "the author's own expression is not rewritten");
    }

    // ---- removing ---------------------------------------------------------------------------------------

    @Test
    void aParameterTheBotStillUsesIsNotRemoved(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        write(config, "Bot.java", """
                package com.refbot;

                public class Bot {
                    int tries() { return Parameters.maxAttempts; }
                }
                """);

        JavaParameters.Outcome outcome = JavaParameters.remove(config, null, only(config));

        // The bug this replaced: removed anyway, and Bot.java stopped compiling.
        JavaParameters.Outcome.Refused refused = assertInstanceOf(JavaParameters.Outcome.Refused.class, outcome);
        assertTrue(refused.reason().contains("Bot.java:4"), refused.reason());
        assertEquals(1, JavaParameters.scan(config, null, TestValues.GRAMMAR).size());
    }

    @Test
    void anUnusedParameterIsRemovedWhateverSharesItsName(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        write(config, "Bot.java", """
                package com.refbot;

                public class Bot {
                    int tries() { int maxAttempts = 2; return maxAttempts; }
                }
                """);

        assertInstanceOf(JavaParameters.Outcome.Removed.class, JavaParameters.remove(config, null, only(config)));

        assertEquals(List.of(), JavaParameters.scan(config, null, TestValues.GRAMMAR));
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static JavaValue java(String source) {
        return JavaValue.parse(source).orElseThrow();
    }

    private static JavaParameter only(ProjectConfig config) {
        return JavaParameters.scan(config, null, TestValues.GRAMMAR).getFirst();
    }

    private static ParameterRow renamed(ParameterRow row, String name) {
        return ParameterRow.named(name, row.typeName()).value(row.value()).description(row.description())
                .category(row.category()).visibility(row.visibility()).options(row.options())
                .bounds(row.min(), row.max()).build();
    }

    private static String parameters(String fields) {
        return """
                package com.refbot;

                import com.botmaker.plugin.basics.params.Param;

                public final class Parameters {
                %s
                }
                """.formatted(fields);
    }

    private static void write(ProjectConfig config, String name, String source) throws IOException {
        Path file = config.mainPackageDir().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.mainPackageDir());
        return config;
    }
}
