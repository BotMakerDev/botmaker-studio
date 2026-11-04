package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.ParameterWrite;
import com.botmaker.plugin.api.source.ParameterWrite.Mark;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.api.source.RunParameter;
import com.botmaker.plugin.host.grammar.JavaValue;
import com.botmaker.studio.project.managed.TestScreen;
import com.botmaker.studio.project.managed.TestValue;
import com.botmaker.plugin.host.grammar.ParamsFitter;
import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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

    // ---- a plugin's parameter ---------------------------------------------------------------------------

    /** A plugin's parameter keeps its declaration: only the value is written, and it is never removed. */
    @Test
    void aPluginsParameterChangesItsValueOnly(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(category = "Run")
                    public static int tries = 10;
                """));
        JavaParameter read = JavaParameters.scan(config, null, TestValues.GRAMMAR).getFirst();
        JavaParameter plugins = new JavaParameter(read.file(), read.className(), read.row(), read.form(),
                read.initializer(), read.editable(), read.note(), "The plugin reads it.", "");

        JavaParameters.Outcome renamed = JavaParameters.declare(config, null, plugins,
                ParameterRow.named("attempts", read.row().typeName()).value("10").category("Run").build(),
                read.form(), TestValues.GRAMMAR);
        assertInstanceOf(JavaParameters.Outcome.Refused.class, renamed);
        JavaParameters.Outcome retyped = JavaParameters.declare(config, null, plugins, read.row(), TestValues.TEXT,
                TestValues.GRAMMAR);
        assertInstanceOf(JavaParameters.Outcome.Refused.class, retyped);
        JavaParameters.Outcome refiled = JavaParameters.declare(config, null, plugins,
                read.row().toBuilder().category("Limits").build(), read.form(), TestValues.GRAMMAR);
        assertInstanceOf(JavaParameters.Outcome.Refused.class, refiled);
        assertInstanceOf(JavaParameters.Outcome.Refused.class, JavaParameters.remove(config, null, plugins));

        JavaParameters.Outcome valued = JavaParameters.declare(config, null, plugins,
                read.row().toBuilder().value("3").build(), read.form(), TestValues.GRAMMAR);
        assertInstanceOf(JavaParameters.Outcome.Stored.class, valued);
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertTrue(source.contains("public static int tries = 3;"), source);
        assertTrue(source.contains("@Param(category = \"Run\")"), source);
    }

    // ---- a schema (2026-11-04) ----------------------------------------------------------------------------

    /** A plugin's group in params/ takes no parameter of the author's, and changes its values only. */
    @Test
    void aNewParameterGoesIntoTheBotsOwnClassOnly(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int tries = 10;
                """));
        writeGroup(config, "sdk", "Sdk", "    public int rounds() { return 2; }\n");

        assertEquals(List.of("Parameters"), JavaParameters.ownClasses(config, null));
        assertTrue(JavaParameters.add(config, null, "Sdk", "speed", TestValues.WHOLE_NUMBER, java("1"), "", "",
                TestValues.GRAMMAR).isEmpty(), "a plugin's group takes none");
        JavaParameter rounds = JavaParameters.find(config, null, "Sdk", "rounds", TestValues.GRAMMAR).orElseThrow();
        assertTrue(rounds.declaredByPlugin() && rounds.editable(), rounds.note());
    }

    /** A fresh copy of the bot has no params/: the fit fills it from starting-params/, values and all. */
    @Test
    void aFreshCopyGetsItsStartingParams(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path starting = startingOf(config, "bot", "RunChoices");
        Files.createDirectories(starting.getParent());
        Files.writeString(starting, group("bot", "RunChoices", """
                    @Param.Shown(category = "Limits", description = "How many tries")
                    public int tries() { return 10; }
                """));

        ParamsFitter.onDisk(config.parametersPackageDir(), config.projectPath().resolve(ParamsFitter.STARTING),
                config.projectPath().resolve(".botmaker/values-removed"));

        JavaParameter tries = only(config);
        assertTrue(tries.group() && tries.editable(), tries.note());
        assertEquals("10", tries.row().value());
        assertEquals("Limits", tries.row().category());
        assertEquals("How many tries", tries.row().description());
    }

    /**
     * A value edit is this computer's and stays in params/; what the parameter is shown as is written to both, and
     * only Make this the starting value copies a value into the starting copy.
     */
    @Test
    void anEditNeverReachesTheStartingCopy(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        writeGroup(config, "bot", "RunChoices", "    public int tries() { return 10; }\n");
        Path starting = startingOf(config, "bot", "RunChoices");
        JavaParameter tries = only(config);

        assertEquals("3", JavaParameters.setValue(config, null, tries, java("3"), TestValues.GRAMMAR)
                .orElseThrow().value());
        assertTrue(Files.readString(groupOf(config, "bot", "RunChoices")).contains("return 3;"));
        assertTrue(Files.readString(starting).contains("return 10;"), "the starting copy keeps its value");

        JavaParameter now = only(config);
        assertInstanceOf(JavaParameters.Outcome.Stored.class, JavaParameters.declare(config, null, now,
                now.row().toBuilder().category("Run").build(), now.form(), TestValues.GRAMMAR));
        assertTrue(Files.readString(starting).contains("category = \"Run\""), Files.readString(starting));
        assertTrue(Files.readString(starting).contains("return 10;"));
        assertEquals("3", only(config).row().value());

        assertTrue(JavaParameters.makeStarting(config, null, only(config)));
        assertTrue(Files.readString(starting).contains("return 3;"), Files.readString(starting));
    }

    /** One the bot writes during a run is read-only here; Forget sets it back to its starting value. */
    @Test
    void aDuringARunParameterIsReadOnly(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        writeGroup(config, "bot", "Progress", """
                    @Param.Shown(changes = Param.Changes.DURING_RUN)
                    public int level() { return 1; }
                """);
        Path mine = groupOf(config, "bot", "Progress");
        Files.writeString(mine, Files.readString(mine).replace("return 1;", "return 7;"));
        JavaParameter level = only(config);

        assertTrue(level.row().duringRun());
        assertFalse(level.editable(), "the bot writes it");
        assertTrue(JavaParameters.setValue(config, null, level, java("2"), TestValues.GRAMMAR).isEmpty());
        assertEquals("7", only(config).row().value());

        assertEquals("1", JavaParameters.forget(config, null, level, TestValues.GRAMMAR).orElseThrow().value());
    }

    /**
     * A new parameter of a group the bot has none of yet is a method in params/ and in its starting copy. Only the
     * declaration passes: the committed copy holds the type's blank value, and the fit keeps this computer's.
     */
    @Test
    void aNewParametersValueStaysOutOfTheStartingCopy(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);

        assertEquals("5", JavaParameters.add(config, null, "RunChoices", "tries", TestValues.WHOLE_NUMBER,
                java("5"), "Limits", "", TestValues.GRAMMAR).orElseThrow().value());

        Path mine = groupOf(config, "bot", "RunChoices");
        Path starting = startingOf(config, "bot", "RunChoices");
        for (Path file : List.of(mine, starting)) {
            String source = Files.readString(file);
            assertTrue(source.startsWith("package com.refbot.params.bot;"), source);
            assertTrue(source.contains("@Param\npublic class RunChoices"), source);
            assertTrue(source.contains("public int tries()"), source);
            assertTrue(source.contains("category = \"Limits\""), source);
        }
        assertTrue(Files.readString(mine).contains("return 5;"), Files.readString(mine));
        assertTrue(Files.readString(starting).contains("return 0;"), Files.readString(starting));
        assertFalse(Files.readString(starting).contains("return 5;"), Files.readString(starting));

        ParamsFitter.onDisk(config.parametersPackageDir(), config.projectPath().resolve(ParamsFitter.STARTING),
                config.projectPath().resolve(".botmaker/values-removed"));
        assertEquals("5", only(config).row().value(), "a blank starting value takes nothing from this computer");
    }

    /** A name the starting copy already holds would be reset by the fit, so the add is refused and params/ untouched. */
    @Test
    void aNameTheStartingCopyHoldsIsRefused(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        writeGroup(config, "bot", "RunChoices", "    public int rounds() { return 1; }\n");
        Path starting = startingOf(config, "bot", "RunChoices");
        Files.writeString(starting, group("bot", "RunChoices", """
                    public int rounds() { return 1; }
                    public String tries() { return ""; }
                """));

        assertTrue(JavaParameters.add(config, null, "RunChoices", "tries", TestValues.WHOLE_NUMBER, java("5"), "", "",
                TestValues.GRAMMAR).isEmpty());
        assertFalse(Files.readString(groupOf(config, "bot", "RunChoices")).contains("tries"));
    }

    /** A type with no blank value has nothing the committed copy may hold, so nothing is written, not even a group. */
    @Test
    void aTypeWithNoBlankValueIsNotAdded(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);

        assertTrue(JavaParameters.add(config, null, "RunChoices", "worker", Thread.class, java("null"), "", "",
                TestValues.GRAMMAR).isEmpty());

        assertFalse(Files.exists(groupOf(config, "bot", "RunChoices")));
        assertFalse(Files.exists(startingOf(config, "bot", "RunChoices")));
    }

    /** A plugin adds what a run keeps, with its own annotations on it, in both copies. */
    @Test
    void aPluginsRunParameterIsWrittenWithItsMarks(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        ParameterWrite gold = ParameterWrite.of("Progress", "gold", 0)
                .groupMarked(Mark.of(Deprecated.class))
                .marked(Mark.of(SuppressWarnings.class).with("value", "gold"));

        assertTrue(JavaParameters.addDuringRun(config, null, gold, nothing -> Optional.empty(), TestValues.GRAMMAR)
                .isEmpty());

        for (Path file : List.of(groupOf(config, "bot", "Progress"), startingOf(config, "bot", "Progress"))) {
            String source = Files.readString(file);
            assertTrue(source.contains("@Deprecated") && source.contains("@SuppressWarnings(value = \"gold\")"), source);
            assertTrue(source.contains("changes = Param.Changes.DURING_RUN") && source.contains("public int gold()")
                    && source.contains("return 0;"), source);
        }
        assertTrue(JavaParameters.addDuringRun(config, null, gold, nothing -> Optional.empty(), TestValues.GRAMMAR)
                .orElseThrow().contains("already has"), "a name is taken once");
        assertEquals(1, Files.readString(groupOf(config, "bot", "Progress")).split("@Deprecated", -1).length - 1,
                "a second parameter brings no second mark");
        assertTrue(JavaParameters.addDuringRun(config, null, ParameterWrite.of("Progress", "class", 0),
                nothing -> Optional.empty(), TestValues.GRAMMAR).isPresent(), "not a Java name");
    }

    @Test
    void aClassAmongAMarksValuesIsImported() {
        String source = group("bot", "Progress", "    public int gold() { return 0; }\n");
        Function<ParameterWrite.ClassOf, Optional<JavaParameterEdits.ClassName>> screens = classOf -> Optional.of(
                new JavaParameterEdits.ClassName("com.refbot.plugins.Screens", "Screens.Home"));
        ManagedClasses<TestScreen> set = ManagedValue.openSet(TestValue.Id.SCREENS)
                .ofClasses(TestScreen.class)
                .fields(TestScreen.Field.class, TestScreen::field)
                .setup(TestScreen::setup)
                .referredToAs(TestScreen::named)
                .in("Screens").because("Mine.");
        Mark mark = Mark.of(ReadOff.class).withClassOf("screen", set, PluginValues.Member.of("HOME")).with("field", "gold");

        String out = JavaParameterEdits.withMarks(source, "Progress", "gold", List.of(mark), List.of(), screens)
                .orElseThrow();

        assertTrue(out.contains("import com.refbot.plugins.Screens;"), out);
        assertTrue(out.contains("import " + JavaParametersTest.class.getName() + ";"), out);
        assertTrue(out.contains("@JavaParametersTest.ReadOff(field = \"gold\", screen = Screens.Home.class)"), out);
        assertEquals(Optional.empty(), JavaParameterEdits.withMarks(source, "Progress", "gold", List.of(mark), List.of(),
                classOf -> Optional.empty()), "a class that cannot be named writes nothing");
    }

    /** A parameter reaches both files with its marks, or neither. */
    @Test
    void aParameterThatCannotBeWholeIsNotWritten(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        writeGroup(config, "bot", "Progress", "    public int tries() { return 1; }\n");
        Path file = groupOf(config, "bot", "Progress");
        Path starting = JavaParameters.startingFile(config, file).orElseThrow();
        String params = Files.readString(file);
        ManagedClasses<TestScreen> set = ManagedValue.openSet(TestValue.Id.SCREENS)
                .ofClasses(TestScreen.class)
                .fields(TestScreen.Field.class, TestScreen::field)
                .setup(TestScreen::setup)
                .referredToAs(TestScreen::named)
                .in("Screens").because("Mine.");
        ParameterWrite unnamed = ParameterWrite.of("Progress", "gold", 0)
                .marked(Mark.of(ReadOff.class).withClassOf("screen", set, PluginValues.Member.of("GONE")));

        assertTrue(JavaParameters.addDuringRun(config, null, unnamed, nothing -> Optional.empty(), TestValues.GRAMMAR)
                .isPresent(), "a class that cannot be named");
        assertEquals(params, Files.readString(file));

        Files.writeString(starting, Files.readString(starting).replace("public int tries() { return 1; }",
                "public int tries() { return 1; } public int gold() { return 0; }"));
        String startingBefore = Files.readString(starting);
        assertTrue(JavaParameters.addDuringRun(config, null, ParameterWrite.of("Progress", "gold", 0),
                nothing -> Optional.empty(), TestValues.GRAMMAR).isPresent(), "a starting copy that cannot take it");
        assertEquals(params, Files.readString(file));
        assertEquals(startingBefore, Files.readString(starting));
    }

    /** A value with nothing to go back to is not said to have gone back. */
    @Test
    void aValueTheStartingCopyLacksIsNotForgotten(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        writeGroup(config, "bot", "Progress", """
                    @Param.Shown(changes = Param.Changes.DURING_RUN)
                    public int gold() { return 7; }

                    @Param.Shown(changes = Param.Changes.DURING_RUN)
                    public String title() { return "x"; }
                """);
        Path file = groupOf(config, "bot", "Progress");
        Path starting = JavaParameters.startingFile(config, file).orElseThrow();
        Files.writeString(starting, Files.readString(starting).replace("public int gold() { return 7; }", ""));

        assertTrue(JavaParameters.forgetDuringRun(config, null, "Progress", "gold", TestValues.GRAMMAR).isPresent());
        assertEquals(List.of("int", "String"), JavaParameters.duringRun(config, null, TestValues.GRAMMAR).stream()
                .map(RunParameter::type).toList(), "the names a condition's test is checked against");
    }

    @interface ReadOff {
        Class<?> screen();

        String field();
    }

    /** What a plugin reads is a run's values and their age, and nothing the person set. */
    @Test
    void onlyDuringARunParametersReachAPlugin(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        writeGroup(config, "bot", "Progress", """
                    @Param.Shown(changes = Param.Changes.DURING_RUN)
                    public int gold() { return 7; }

                    public String password() { return "hunter2"; }
                """);
        Path file = groupOf(config, "bot", "Progress");
        Files.writeString(file, Files.readString(file).replace("@Param.Shown(",
                "@Param.Kept(at = \"2026-11-04T10:00:00Z\") @Param.Shown("));

        RunParameter gold = JavaParameters.duringRun(config, null, TestValues.GRAMMAR).getFirst();

        assertEquals(1, JavaParameters.duringRun(config, null, TestValues.GRAMMAR).size());
        assertEquals("gold", gold.name());
        assertEquals("7", gold.value());
        assertEquals(Optional.of(Instant.parse("2026-11-04T10:00:00Z")), gold.keptAt());

        assertTrue(JavaParameters.forgetDuringRun(config, null, "Progress", "password", TestValues.GRAMMAR).isPresent(),
                "the person's value is not a plugin's to reset");
        assertTrue(JavaParameters.forgetDuringRun(config, null, "Progress", "gold", TestValues.GRAMMAR).isEmpty());
        assertFalse(Files.readString(file).contains("Kept"));
    }

    // ---- adding -----------------------------------------------------------------------------------------

    @Test
    void aProjectsFirstParameterCreatesTheClassItGoesIn(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);

        Optional<ParameterRow> stored = JavaParameters.add(config, null, "Parameters", "maxAttempts",
                TestValues.WHOLE_NUMBER, java("10"), "Limits", "How many times to try", TestValues.GRAMMAR);

        assertTrue(stored.isPresent());
        assertEquals("10", stored.get().value());
        // A group since 2026-11-04: params/bot/, private, beside its starting copy.
        String source = Files.readString(groupOf(config, "bot", "Parameters"));
        assertTrue(source.startsWith("package com.refbot.params.bot;"), "its own package, which Studio keeps");
        assertTrue(source.contains("import com.botmaker.plugin.api.params.Param;"), source);
        assertTrue(source.contains("public int maxAttempts()") && source.contains("return 10;"), source);
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
                TestValues.GRAMMAR, true);

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
                .build();
        Optional<ParameterRow> stored =
                JavaParameters.declare(config, null, entry, wanted, entry.form(), TestValues.GRAMMAR).stored();

        assertTrue(stored.isPresent());
        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertFalse(source.contains("category ="), source);
        assertTrue(source.contains("description = \"Attempts before it gives up\""), source);
        assertTrue(source.contains("visibility = Param.PUBLIC"), source);
        assertEquals(Visibility.PUBLIC, stored.get().visibility());
    }

    /** The upgrade to contract 0.14 takes the removed members off, keeps the rest, and names the fields. */
    @Test
    void theRemovedMembersAreStrippedByTheUpgrade(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param(category = "Limits", min = 1, max = 50)
                    public static int maxAttempts = 10;

                    @Param(options = {"fast", "slow"})
                    public static String speed = "fast";

                    @Param(description = "untouched")
                    public static int other = 1;
                """));

        List<String> said = JavaParameters.stripRemovedMembers(config, null);

        String source = Files.readString(config.mainPackageDir().resolve("Parameters.java"));
        assertFalse(source.contains("min ="), source);
        assertFalse(source.contains("max ="), source);
        assertFalse(source.contains("options"), source);
        assertTrue(source.contains("category = \"Limits\""), source);
        assertTrue(source.contains("description = \"untouched\""), source);
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.getFirst().contains("maxAttempts, speed"), said.getFirst());
        assertEquals(List.of(), JavaParameters.stripRemovedMembers(config, null), "a second pass finds nothing");
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

    /**
     * The one fix a refused remove can vouch for: a constant is the same wherever it is written, so each read
     * becomes the value and the field goes — as one plan, compiled first.
     */
    @Test
    void aConstantParameterStillUsedOffersToBeReplacedByItsValue(@TempDir Path root) throws IOException {
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

        JavaParameters.Outcome.Refused refused = assertInstanceOf(JavaParameters.Outcome.Refused.class,
                JavaParameters.remove(config, null, only(config)));

        assertEquals(1, refused.fixes().size(), "10 compiles wherever maxAttempts was read");
        assertEquals("Replace the use with 10 and remove it", refused.fixes().getFirst().label());
        JavaParameters.write(config, null, refused.fixes().getFirst().plan());
        assertTrue(Files.readString(config.mainPackageDir().resolve("Bot.java")).contains("return 10;"));
        assertTrue(JavaParameters.scan(config, null, TestValues.GRAMMAR).isEmpty(), "and the field is gone");
    }

    @Test
    void aParameterTheBotWritesOffersNoReplacement(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Parameters.java", parameters("""
                    @Param
                    public static int maxAttempts = 10;
                """));
        write(config, "Bot.java", """
                package com.refbot;

                public class Bot {
                    void reset() { Parameters.maxAttempts = 3; }
                }
                """);

        JavaParameters.Outcome.Refused refused = assertInstanceOf(JavaParameters.Outcome.Refused.class,
                JavaParameters.remove(config, null, only(config)));

        assertTrue(refused.fixes().isEmpty(), "10 = 3 is not Java, so nothing is offered");
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
                .category(row.category()).visibility(row.visibility()).build();
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

    /** A {@code @Param} group {@code name} of the folder {@code folder} of params/, holding {@code methods}. */
    private static String group(String folder, String name, String methods) {
        return """
                package com.refbot.params.%s;

                import com.botmaker.plugin.api.params.Param;

                @Param
                public class %s {
                %s}
                """.formatted(folder, name, methods);
    }

    /** Writes the group in params/ and the same as its starting copy. */
    private static void writeGroup(ProjectConfig config, String folder, String name, String methods)
            throws IOException {
        for (Path file : List.of(groupOf(config, folder, name), startingOf(config, folder, name))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, group(folder, name, methods));
        }
    }

    private static Path groupOf(ProjectConfig config, String folder, String name) {
        return config.parametersPackageDir().resolve(folder).resolve(name + ".java");
    }

    private static Path startingOf(ProjectConfig config, String folder, String name) {
        return config.projectPath().resolve(ParamsFitter.STARTING).resolve(folder).resolve(name + ".java");
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
