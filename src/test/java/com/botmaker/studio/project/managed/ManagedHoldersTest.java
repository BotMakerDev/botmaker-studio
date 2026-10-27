package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.JavaParameter;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.params.TestValues;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host writes a plugin's missing holder once: every method-shaped value sharing the holder, each marked with
 * the plugin's annotation and returning its type's fresh value, in {@code plugins/<last segment of the plugin's
 * id>/}, and never over a file that is there.
 */
class ManagedHoldersTest {

    /** The SDK, whose holders are in {@code plugins/com_github_botmakerdev/botmaker_sdk/} — the folder its artifactId's last word names. */
    private static final PluginCoordinate SDK = new PluginCoordinate("com.github.BotMakerDev", "botmaker-sdk");
    private static final PluginCoordinate TEST = new PluginCoordinate("com.github.BotMakerDev", "botmaker-test");

    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.method(TestValue.Id.GREETING).in("Sdk").holds(String.class, null).because("Mine."),
            ManagedValue.method(TestValue.Id.REST_BETWEEN).in("Sdk").holds(java.time.Duration.class, null)
                    .because("Mine."),
            ManagedValue.openSet(TestValue.Id.PICTURES).of(String.class).in("Pictures").because("Mine."),
            ManagedValue.method(TestValue.Id.OPENED_ONLY).openedOnly().holds(String.class).because("Mine."));

    /** The test JVM's own classpath: it carries {@link TestValue}, as a bot's carries its plugin's marker. */
    private static final BotParser BOUND = new BotParser(
            Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), null);

    @TempDir
    Path dir;

    private ManagedHolders.Plan plan(TestValue.Id id) {
        ManagedValue<?> value = DECLARED.stream().filter(v -> v.id().equals(ManagedValue.idOf(id))).findFirst()
                .orElseThrow();
        return ManagedHolders.plan(ProjectConfig.forDirectory(dir.resolve("bot")), SDK, value, DECLARED,
                TestValues.GRAMMAR);
    }

    @Test
    void aMethodHolderHoldsEverySiblingEachReadableAsItsFreshValue() {
        ManagedHolders.Plan.Write write = assertInstanceOf(ManagedHolders.Plan.Write.class,
                plan(TestValue.Id.GREETING));
        assertEquals("plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java", write.relative());
        assertTrue(write.file().endsWith(Path.of("plugins", "com_github_botmakerdev", "botmaker_sdk", "Sdk.java")), write.file().toString());
        String source = write.source();
        assertTrue(source.contains("package " + ProjectConfig.forDirectory(dir.resolve("bot")).mainPackage() + ".plugins.com_github_botmakerdev.botmaker_sdk;"),
                source);
        assertTrue(source.contains("import " + TestValue.class.getCanonicalName() + ";"), source);
        assertTrue(source.contains("import java.time.Duration;"), source);
        assertTrue(source.contains(" * it is never rewritten. BotMaker changes the expression a {@code @TestValue}"
                + " method returns and nothing else.\n"), source);
        assertTrue(source.lines().noneMatch(line -> !line.equals(line.stripTrailing())), source);

        CompilationUnit unit = parse(source);
        assertEquals(0, unit.getProblems().length, source);
        List<ManagedMethod> read = JavaManagedSource.read(null, source, TestValues.GRAMMAR, BotRecords.none(),
                BOUND);
        assertEquals(List.of(ManagedValue.idOf(TestValue.Id.GREETING), ManagedValue.idOf(TestValue.Id.REST_BETWEEN)),
                read.stream().map(ManagedMethod::id).toList());
        for (ManagedMethod method : read) assertTrue(method.editable(), method.note());
        assertEquals("restBetween", read.get(1).methodName());
    }

    @Test
    void aTypeLevelValueIsAnEmptyAnnotatedClass() {
        ManagedHolders.Plan.Write write = assertInstanceOf(ManagedHolders.Plan.Write.class,
                plan(TestValue.Id.PICTURES));
        assertTrue(write.source().contains("@TestValue(TestValue.Id.PICTURES)\npublic final class Pictures {"),
                write.source());
        assertEquals(0, parse(write.source()).getProblems().length, write.source());
    }

    /** An enum set's holder is an empty enum carrying the mark and implementing the element type. */
    @Test
    void anEnumSetIsAnEmptyAnnotatedEnumOfItsElement() {
        ManagedValue<TestOutcome> outcomes = ManagedValue.openSet(TestValue.Id.OUTCOMES)
                .ofEnum(TestOutcome.class, TestOutcome::named).in("Outcomes").because("Mine.");
        String source = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(
                ProjectConfig.forDirectory(dir.resolve("bot")), SDK, outcomes, List.of(outcomes),
                TestValues.GRAMMAR)).source();

        assertTrue(source.contains("@TestValue(TestValue.Id.OUTCOMES)\npublic enum Outcomes implements TestOutcome {"),
                source);
        assertTrue(source.contains("import " + TestOutcome.class.getCanonicalName() + ";"), source);
        assertEquals(0, parse(source).getProblems().length, source);
    }

    /** A typed id is written in the plugin's own annotation, its method named after the constant. */
    @Test
    void aTypedValueIsMarkedWithThePluginsAnnotation() {
        List<ManagedValue<?>> declared = List.of(
                ManagedValue.method(TestValue.Id.GREETING).in("Values").holds(String.class, null).because("Mine."),
                ManagedValue.method(TestValue.Id.REST_BETWEEN).in("Values").holds(String.class, null)
                        .because("Mine."),
                ManagedValue.openSet(TestValue.Id.NAMES).of(String.class).in("Names").because("Mine."));
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));

        String values = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(config,
                TEST, declared.getFirst(), declared, TestValues.GRAMMAR)).source();
        assertTrue(values.contains("import " + TestValue.class.getCanonicalName() + ";"), values);
        assertTrue(values.contains("@TestValue(TestValue.Id.GREETING)\n    public static String greeting() {"),
                values);
        assertTrue(values.contains("@TestValue(TestValue.Id.REST_BETWEEN)\n    public static String restBetween() {"),
                values);
        assertTrue(values.contains("{@code @TestValue} method returns"), values);
        assertEquals(0, parse(values).getProblems().length, values);

        String names = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(config,
                TEST, declared.getLast(), declared, TestValues.GRAMMAR)).source();
        assertTrue(names.contains("@TestValue(TestValue.Id.NAMES)\npublic final class Names {"), names);
    }

    @Test
    void aValueWithNoHolderIsRefused() {
        assertInstanceOf(ManagedHolders.Plan.Refused.class, plan(TestValue.Id.OPENED_ONLY));
    }

    @Test
    void theFileIsWrittenOnceAndNeverOverwritten() throws Exception {
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan(TestValue.Id.GREETING);
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        assertTrue(ProjectWrites.create(config, write.file(), write.source(), "Create"));
        Files.writeString(write.file(), "// the user's now");
        assertTrue(ProjectWrites.create(config, write.file(), write.source(), "Create"));
        assertEquals("// the user's now", Files.readString(write.file()));
    }

    /** On bind (2026-09-26): every holder the project lacks, one per holder, and none it already has. */
    @Test
    void onBindEveryMissingHolderIsPlannedOnce() {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        List<ManagedHolders.Plan> plans = ManagedHolders.missing(config, java.util.Map.of(SDK, DECLARED),
                java.util.Set.of(), java.util.Set.of(), TestValues.GRAMMAR);
        assertEquals(List.of("plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java", "plugins/com_github_botmakerdev/botmaker_sdk/Pictures.java"), plans.stream()
                .map(p -> ((ManagedHolders.Plan.Write) p).relative()).toList());
    }

    @Test
    void onBindAHolderTheBotAlreadyHasIsLeftAlone() throws Exception {
        ProjectConfig config = ProjectConfig.forDirectory(dir.resolve("bot"));
        java.util.Map<PluginCoordinate, List<ManagedValue<?>>> sdk = java.util.Map.of(SDK, DECLARED);
        // A value it holds is declared somewhere (the user moved Sdk.java and renamed it).
        assertEquals(List.of("plugins/com_github_botmakerdev/botmaker_sdk/Pictures.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(ManagedValue.idOf(TestValue.Id.REST_BETWEEN)), java.util.Set.of(), TestValues.GRAMMAR)));
        // The bot has it ({@link HolderFiles#find}): written, or moved and still marked.
        assertEquals(List.of("plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(), java.util.Set.of(new HolderFiles.Key("com_github_botmakerdev.botmaker_sdk","Pictures")), TestValues.GRAMMAR)));
        // Another plugin's holder of that name is not this one's.
        assertEquals(List.of("plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java", "plugins/com_github_botmakerdev/botmaker_sdk/Pictures.java"), relatives(ManagedHolders.missing(
                config, sdk, java.util.Set.of(), java.util.Set.of(new HolderFiles.Key("other", "Pictures")),
                TestValues.GRAMMAR)));
        // Its file is on disk, whatever it holds.
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan(TestValue.Id.GREETING);
        Files.createDirectories(write.file().getParent());
        Files.writeString(write.file(), "// the user's");
        assertEquals(List.of("plugins/com_github_botmakerdev/botmaker_sdk/Pictures.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(), java.util.Set.of(), TestValues.GRAMMAR)));
    }

    private static List<String> relatives(List<ManagedHolders.Plan> plans) {
        return plans.stream().map(p -> ((ManagedHolders.Plan.Write) p).relative()).toList();
    }

    public static boolean setUpFirst() {
        return true;
    }

    public static java.time.Duration noPause() {
        return null;
    }

    private static final ManagedValue<Boolean> DO_SETUP = ManagedValue.param(TestValue.Id.DO_SETUP).in("Sdk")
            .startsAs(ManagedHoldersTest::setUpFirst).shown("Run", "Set up first", Param.Visibility.PUBLIC).because("Mine.");

    private static final ManagedValue<java.time.Duration> PAUSE = ManagedValue.param(TestValue.Id.PAUSE).in("Sdk")
            .startsAs(ManagedHoldersTest::noPause).shown("", "", Param.Visibility.EDITOR).because("Mine.");

    /** A plugin's parameters are marked {@code @Param} fields, written before the holder's methods (2026-10-11). */
    @Test
    void aParameterIsAMarkedParamFieldBeforeTheMethods() {
        List<ManagedValue<?>> declared = List.of(DECLARED.getFirst(), DO_SETUP, PAUSE);
        String source = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(
                ProjectConfig.forDirectory(dir.resolve("bot")), SDK, DECLARED.getFirst(), declared,
                TestValues.GRAMMAR)).source();

        assertTrue(source.contains("    @TestValue(TestValue.Id.DO_SETUP)\n    @Param(category = \"Run\","
                + " visibility = Param.PUBLIC, description = \"Set up first\")\n    public static boolean doSetup = true;"),
                source);
        assertTrue(source.contains("    @TestValue(TestValue.Id.PAUSE)\n    @Param\n    public static Duration pause"
                + " = null;"), source);
        assertTrue(source.indexOf("doSetup") < source.indexOf("greeting()"), source);
        assertTrue(source.contains("import " + Param.class.getCanonicalName() + ";"), source);
        assertEquals(0, parse(source).getProblems().length, source);

        List<JavaParameter> read = JavaParameterSource.read(null, source, TestValues.GRAMMAR, BotRecords.none(), BOUND);
        assertEquals(List.of("doSetup", "pause"), read.stream().map(JavaParameter::name).toList());
        assertTrue(read.getFirst().declaredByPlugin(), read.getFirst().toString());
        assertTrue(read.getFirst().editable(), read.getFirst().note());
    }

    /** A holder the bot has gets back a parameter it lacks; one it has, or a holder it lacks, is left alone. */
    @Test
    void onBindAMissingParameterIsAddedToTheHolderTheBotHas() {
        Path sdk = dir.resolve("src/main/java/com/bot/plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java");
        String had = """
                package com.bot.plugins.com_github_botmakerdev.botmaker_sdk;

                import %s;

                public final class Sdk {

                    @TestValue(TestValue.Id.GREETING)
                    public static String greeting() {
                        return "hi";
                    }
                }
                """.formatted(TestValue.class.getCanonicalName());
        com.botmaker.studio.project.source.BotIndex index = com.botmaker.studio.project.source.BotIndex.over(
                java.util.Map.of(sdk, had), Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)),
                dir);
        java.util.Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin = java.util.Map.of(SDK,
                List.of(DECLARED.getFirst(), DO_SETUP));

        java.util.Map<Path, List<ManagedValue<?>>> kept = ManagedHolders.valuesToKeep(index, byPlugin);
        assertEquals(List.of(DO_SETUP), kept.get(sdk));
        String after = ManagedHolders.keptValues(had, kept.get(sdk), TestValues.GRAMMAR);
        assertTrue(after.contains("public static boolean doSetup = true;"), after);
        assertTrue(after.indexOf("doSetup") < after.indexOf("greeting()"), after);
        assertTrue(after.contains("import " + Param.class.getCanonicalName() + ";"), after);
        assertEquals(0, parse(after).getProblems().length, after);

        // Kept as declared already: nothing to write.
        assertEquals(after, ManagedHolders.keptValues(after, kept.get(sdk), TestValues.GRAMMAR));
        assertTrue(ManagedHolders.valuesToKeep(index, java.util.Map.of(new PluginCoordinate("com.github.BotMakerDev", "botmaker-other"), List.of(DO_SETUP)))
                .isEmpty());
    }

    /**
     * A holder is found by plugin and name (2026-10-21): a moved one by the plugin's mark on it, never a class
     * that only shares its name — the bot's own, or another plugin's.
     */
    @Test
    void aHolderIsFoundByPluginAndNameNeverBySimpleNameAlone() {
        Path moved = dir.resolve("src/main/java/com/bot/Sdk.java");
        Path own = dir.resolve("src/main/java/com/bot/Pictures.java");
        String marked = """
                package com.bot;

                import %s;

                public final class Sdk {

                    @TestValue(TestValue.Id.GREETING)
                    public static String greeting() {
                        return "hi";
                    }
                }
                """.formatted(TestValue.class.getCanonicalName());
        com.botmaker.studio.project.source.BotIndex index = com.botmaker.studio.project.source.BotIndex.over(
                java.util.Map.of(moved, marked, own, "package com.bot;\npublic final class Pictures {}\n"),
                Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), dir);

        java.util.Map<HolderFiles.Key, Path> found = index.read(units -> HolderFiles.find(units,
                java.util.Map.of(SDK, DECLARED, new PluginCoordinate("com.acme", "other"), List.of(DO_SETUP))));
        assertEquals(java.util.Map.of(new HolderFiles.Key("com_github_botmakerdev.botmaker_sdk","Sdk"), moved), found);
    }

    /** A field of the parameter's name from before the plugin declared it is marked, never declared twice. */
    @Test
    void anUnmarkedFieldOfTheNameIsMarkedNotDuplicated() {
        String had = """
                package com.bot.plugins.com_github_botmakerdev.botmaker_sdk;

                import %s;
                import %s;

                public final class Sdk {

                    @Param(category = "Run", visibility = Param.PUBLIC, description = "Set up first")
                    public static boolean doSetup = false;
                }
                """.formatted(TestValue.class.getCanonicalName(), Param.class.getCanonicalName());

        String after = ManagedHolders.keptValues(had, List.of(DO_SETUP), TestValues.GRAMMAR);

        assertTrue(after.contains("@TestValue(TestValue.Id.DO_SETUP)"), after);
        assertEquals(1, after.split("boolean doSetup", -1).length - 1, after);
        assertTrue(after.contains("doSetup = false;"), "the user's value stays: " + after);
        assertEquals(0, parse(after).getProblems().length, after);
    }

    /** A marked field whose category, note or audience the plugin since changed gets the plugin's back. */
    @Test
    void aMarkedFieldsDeclarationFollowsThePlugin() {
        String had = """
                package com.bot.plugins.com_github_botmakerdev.botmaker_sdk;

                import %s;
                import %s;

                public final class Sdk {

                    @TestValue(TestValue.Id.DO_SETUP)
                    @Param(category = "Old", description = "Old words")
                    public static boolean doSetup = false;
                }
                """.formatted(TestValue.class.getCanonicalName(), Param.class.getCanonicalName());

        String after = ManagedHolders.keptValues(had, List.of(DO_SETUP), TestValues.GRAMMAR);

        assertTrue(after.contains("category = \"Run\""), after);
        assertTrue(after.contains("description = \"Set up first\""), after);
        assertTrue(after.contains("visibility = Param.PUBLIC") || after.contains("visibility = \"public\""), after);
        assertTrue(after.contains("doSetup = false;"), after);
        assertEquals(0, parse(after).getProblems().length, after);
    }

    public static java.awt.Point somewhere() {
        return new java.awt.Point(1, 2);
    }

    /** A parameter no plugin's grammar can spell is left out; the holder's values are still written. */
    @Test
    void anUnwritableParameterCostsOnlyItself() {
        ManagedValue<java.awt.Point> spot = ManagedValue.param(TestValue.Id.MINE).in("Sdk")
                .startsAs(ManagedHoldersTest::somewhere).shown("", "", Param.Visibility.EDITOR).because("Mine.");
        List<ManagedValue<?>> declared = List.of(DECLARED.getFirst(), spot);
        String source = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(
                ProjectConfig.forDirectory(dir.resolve("bot")), SDK, DECLARED.getFirst(), declared,
                TestValues.GRAMMAR)).source();
        assertTrue(source.contains("greeting()"), source);
        assertTrue(!source.contains("mine"), source);
    }

    public static java.util.Set<Class<? extends TestOutcome>> skipNothing() {
        return java.util.Set.of();
    }

    /** The SDK's {@code skip}: an empty set of class literals is written {@code Set.of()} (2026-10-12). */
    @Test
    void anEmptySetOfClassesParameterIsWritten() {
        ManagedValue<java.util.Set<Class<? extends TestOutcome>>> skip = ManagedValue.param(TestValue.Id.NAMES)
                .in("Sdk").startsAs(ManagedHoldersTest::skipNothing).shown("Run", "Skipped", Param.Visibility.PUBLIC)
                .because("Mine.");
        String had = """
                package com.bot.plugins.com_github_botmakerdev.botmaker_sdk;

                public final class Sdk {
                }
                """;
        String after = ManagedHolders.keptValues(had, List.of(skip), TestValues.GRAMMAR);
        assertTrue(after.contains("public static Set<Class<? extends TestOutcome>> names = Set.of();"), after);
        assertEquals(0, parse(after).getProblems().length, after);
    }

    /** A holder the bot has gets back a method value it lacks, its fresh value inside (2026-10-12). */
    @Test
    void onBindAMissingMethodIsAddedToTheHolderTheBotHas() {
        Path sdk = dir.resolve("src/main/java/com/bot/plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java");
        String had = """
                package com.bot.plugins.com_github_botmakerdev.botmaker_sdk;

                import %s;

                public final class Sdk {

                    @TestValue(TestValue.Id.DO_SETUP)
                    public static boolean doSetup = false;

                    @TestValue(TestValue.Id.GREETING)
                    public static String greeting() {
                        return "hi";
                    }

                    private Sdk() {
                    }
                }
                """.formatted(TestValue.class.getCanonicalName());
        com.botmaker.studio.project.source.BotIndex index = com.botmaker.studio.project.source.BotIndex.over(
                java.util.Map.of(sdk, had), Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)),
                dir);
        ManagedValue<?> rest = DECLARED.get(1);
        java.util.Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin = java.util.Map.of(SDK,
                List.of(DECLARED.getFirst(), rest, DO_SETUP));

        java.util.Map<Path, List<ManagedValue<?>>> kept = ManagedHolders.valuesToKeep(index, byPlugin);
        assertEquals(List.of(rest, DO_SETUP), kept.get(sdk));
        String after = ManagedHolders.keptValues(had, kept.get(sdk), TestValues.GRAMMAR);
        assertTrue(after.contains("@TestValue(TestValue.Id.REST_BETWEEN)"), after);
        assertTrue(after.contains("public static Duration restBetween()"), after);
        assertTrue(after.contains("import java.time.Duration;"), after);
        assertTrue(after.indexOf("greeting()") < after.indexOf("restBetween()"), after);
        assertTrue(after.indexOf("restBetween()") < after.indexOf("private Sdk()"), after);
        assertEquals(1, after.split("String greeting\\(", -1).length - 1, "a method present is kept: " + after);
        assertTrue(after.contains("return \"hi\";"), after);
        assertEquals(0, parse(after).getProblems().length, after);
        assertEquals(after, ManagedHolders.keptValues(after, kept.get(sdk), TestValues.GRAMMAR));
    }

    /** An unmarked method of the value's name is the user's: no second one is written beside it. */
    @Test
    void anUnmarkedMethodOfTheNameIsLeftAlone() {
        String had = """
                package com.bot.plugins.com_github_botmakerdev.botmaker_sdk;

                public final class Sdk {

                    public static java.time.Duration restBetween() {
                        return java.time.Duration.ZERO;
                    }
                }
                """;
        assertEquals(had, ManagedHolders.keptValues(had, List.of(DECLARED.get(1)), TestValues.GRAMMAR));
    }

    @Test
    void anIdBecomesAMethodName() {
        assertEquals("flow", ManagedHolders.methodName("flow"));
        assertEquals("restBetween", ManagedHolders.methodName("rest-between"));
        assertEquals("classValue", ManagedHolders.methodName("class"));
    }

    /** The folder is the whole coordinate, so two plugins never share one; BotMaker's two groupIds spell one. */
    @Test
    void aPluginsFolderIsItsGroupIdAndArtifactId() {
        assertEquals("com_github_botmakerdev.botmaker_sdk", ManagedHolders.segment(SDK));
        assertEquals("com_github_botmakerdev.botmaker_plugin_basics", ManagedHolders.segment(
                new PluginCoordinate("com.github.LiQiyeDev", "botmaker-plugin-basics")));
        assertNotEquals(ManagedHolders.segment(new PluginCoordinate("com.alice", "discord-plugin")),
                ManagedHolders.segment(new PluginCoordinate("com.alice", "slack-plugin")));
        assertEquals("com_acme._2d", ManagedHolders.segment(new PluginCoordinate("com.acme", "2d")));
        assertNull(ManagedHolders.segment(new PluginCoordinate("com.acme", "class")), "a keyword names no package");
    }

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        java.util.Map<String, String> options = org.eclipse.jdt.core.JavaCore.getOptions();
        org.eclipse.jdt.core.JavaCore.setComplianceOptions(org.eclipse.jdt.core.JavaCore.latestSupportedJavaVersion(),
                options);
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return (CompilationUnit) parser.createAST(null);
    }
}
