package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.plugin.api.value.Range;
import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.project.ProjectConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window's side of a {@code @Param} field: one list, and one edit per thing a person does to a row.
 *
 * <p>No JavaFX and no plugin. The catalog is {@link TestValues}' for the reason written there — Studio
 * depends on no plugin — and there is no {@code ProjectState}, because these are files nobody has open.
 * What is asserted is the <b>file</b> in every case: this class exists to write a person's own source, so a
 * row that came back right while the file was wrong would be the failure that matters.
 */
class ParameterSurfaceTest {

    private static final String PIN = null;

    // ---- what a section is ------------------------------------------------------------------------------

    @Test
    void aSectionIdSaysWhetherItIsAClassOrAPlugin() {
        assertEquals("java:Parameters", ParameterSurface.groupOf("Parameters"));
        assertEquals("Parameters", ParameterSurface.classOf("java:Parameters"));
        assertTrue(ParameterSurface.isJavaGroup("java:Tuning"));
        assertFalse(ParameterSurface.isJavaGroup("com.botmaker.sdk/settings"));
        assertEquals("", ParameterSurface.classOf(""), "the default plugin's group is not a class");
    }

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

        List<ParameterSurface.Entry> rows = ParameterSurface.rows(config, null, PIN, TestValues.CATALOG);

        // A set: the walk visits files in whatever order the filesystem lists them, and which of two classes
        // comes first is not something this class decides or a user could notice.
        assertEquals(Set.of("java:Parameters", "java:Tuning"),
                rows.stream().map(ParameterSurface.Entry::group).collect(Collectors.toSet()));
        assertTrue(rows.stream().allMatch(ParameterSurface.Entry::isJava));
        assertEquals(List.of("Limits"), ParameterSurface.categories(config, null, PIN, TestValues.CATALOG));
    }

    // ---- adding -----------------------------------------------------------------------------------------

    @Test
    void aProjectsFirstParameterCreatesTheClassItGoesIn(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);

        Optional<ParameterRow> stored = ParameterSurface.add(config, null, "Parameters", "maxAttempts",
                ValueChoice.of(TestValues.WHOLE_NUMBER), List.of("10"), "Limits", "How many times to try",
                TestValues.CATALOG);

        assertTrue(stored.isPresent());
        assertEquals(List.of("10"), stored.get().value());
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertTrue(source.contains("import com.botmaker.plugin.basics.params.Param;"), source);
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

        Optional<ParameterRow> refused = ParameterSurface.add(config, null, "Parameters", "maxAttempts",
                ValueChoice.of(TestValues.TEXT), List.of("x"), "", "", TestValues.CATALOG);

        assertTrue(refused.isEmpty());
        assertEquals(1, ParameterSurface.rows(config, null, PIN, TestValues.CATALOG).size());
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

                public class Bot {
                    void run() { for (int i = 0; i < Parameters.maxAttempts; i++) {} }
                }
                """);

        ParameterSurface.Entry entry = only(config);
        Optional<ParameterRow> stored = ParameterSurface.declare(config, null, entry,
                renamed(entry.row(), "tries"), TestValues.CATALOG);

        assertTrue(stored.isPresent());
        assertEquals("tries", stored.get().name());
        assertTrue(Files.readString(config.mainPackageDir().resolve("Bot.java"))
                .contains("Parameters.tries"));
    }

    @Test
    void theAnnotationMembersAreWrittenAndRemovedByTheSameCall(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(category = "Limits", description = "How many times to try")
                    public static int maxAttempts = 10;
                """));

        ParameterSurface.Entry entry = only(config);
        ParameterRow wanted = entry.row().toBuilder()
                .category("")                       // removed: the default is already the empty answer
                .description("Attempts before it gives up")
                .visibility(Visibility.PUBLIC)
                .bounds(new Range("1", "50"))
                .build();
        Optional<ParameterRow> stored =
                ParameterSurface.declare(config, null, entry, wanted, TestValues.CATALOG);

        assertTrue(stored.isPresent());
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertFalse(source.contains("category ="), source);
        assertTrue(source.contains("description = \"Attempts before it gives up\""), source);
        assertTrue(source.contains("visibility = \"public\""), source);
        assertTrue(source.contains("min = \"1\""), source);
        assertEquals(Visibility.PUBLIC, stored.get().visibility());
        assertEquals("50", stored.get().bounds().max());
    }

    @Test
    void aRetypeRewritesTheTypeAndResetsTheValue(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));

        ParameterSurface.Entry entry = only(config);
        ParameterRow stored = ParameterSurface.declare(config, null, entry,
                retyped(entry.row(), ValueChoice.of(TestValues.TEXT)), TestValues.CATALOG).orElseThrow();

        assertEquals(TestValues.TEXT, stored.type().type());
        // The old type's text is not carried across: a value written for one type is not a value of another.
        assertEquals(List.of(""), stored.value());
        assertTrue(Files.readString(config.mainPackageDir().resolve("Parameters.java"))
                .contains("public static String maxAttempts"));
    }

    // ---- values -----------------------------------------------------------------------------------------

    @Test
    void aValueIsWrittenAsTheTypesOwnJava(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static java.time.Duration rest = java.time.Duration.ofMillis(3000L);
                """));

        ParameterRow stored = ParameterSurface
                .setValue(config, null, only(config), List.of("60000"), TestValues.CATALOG).orElseThrow();

        assertEquals(List.of("60000"), stored.value());
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

        ParameterSurface.Entry entry = only(config);

        assertFalse(entry.editable());
        assertFalse(entry.note().isBlank(), "a read-only row says why");
        assertTrue(ParameterSurface.setValue(config, null, entry, List.of("60000"), TestValues.CATALOG)
                .isEmpty());
        assertEquals(before, Files.readString(config.mainPackageDir().resolve("Parameters.java")),
                "the author's own expression is not rewritten");
    }

    // ---- removing ---------------------------------------------------------------------------------------

    @Test
    void removingTakesTheDeclarationAndLeavesTheUses(@TempDir Path root) throws IOException {
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

        assertTrue(ParameterSurface.remove(config, null, only(config)));

        assertEquals(List.of(), ParameterSurface.rows(config, null, PIN, TestValues.CATALOG));
        // Left alone on purpose: what a use should become is a judgement, and the compiler is what finds it.
        assertTrue(Files.readString(config.mainPackageDir().resolve("Bot.java"))
                .contains("Parameters.maxAttempts"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static ParameterSurface.Entry only(ProjectConfig config) {
        return ParameterSurface.rows(config, null, PIN, TestValues.CATALOG).getFirst();
    }

    private static ParameterRow renamed(ParameterRow row, String name) {
        return ParameterRow.named(name, row.type()).value(row.value()).description(row.description())
                .category(row.category()).visibility(row.visibility()).options(row.options())
                .bounds(row.bounds()).build();
    }

    private static ParameterRow retyped(ParameterRow row, ValueChoice type) {
        return ParameterRow.named(row.name(), type).value(row.value()).description(row.description())
                .category(row.category()).visibility(row.visibility()).options(row.options())
                .bounds(row.bounds()).build();
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
