package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.params.BotRecords;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host writes a plugin's missing holder once: every method-shaped value sharing the holder, each marked with
 * the plugin's annotation and returning its type's fresh value, in {@code plugins/<last segment of the plugin's
 * id>/}, and never over a file that is there.
 */
class ManagedHoldersTest {

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
        return ManagedHolders.plan(ProjectConfig.forDirectory(dir), "com.botmaker.sdk", value, DECLARED,
                TestValues.GRAMMAR);
    }

    @Test
    void aMethodHolderHoldsEverySiblingEachReadableAsItsFreshValue() {
        ManagedHolders.Plan.Write write = assertInstanceOf(ManagedHolders.Plan.Write.class,
                plan(TestValue.Id.GREETING));
        assertEquals("plugins/sdk/Sdk.java", write.relative());
        assertTrue(write.file().endsWith(Path.of("plugins", "sdk", "Sdk.java")), write.file().toString());
        String source = write.source();
        assertTrue(source.contains("package " + ProjectConfig.forDirectory(dir).mainPackage() + ".plugins.sdk;"),
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
                ProjectConfig.forDirectory(dir), "com.botmaker.sdk", outcomes, List.of(outcomes),
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
        ProjectConfig config = ProjectConfig.forDirectory(dir);

        String values = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(config,
                "com.botmaker.test", declared.getFirst(), declared, TestValues.GRAMMAR)).source();
        assertTrue(values.contains("import " + TestValue.class.getCanonicalName() + ";"), values);
        assertTrue(values.contains("@TestValue(TestValue.Id.GREETING)\n    public static String greeting() {"),
                values);
        assertTrue(values.contains("@TestValue(TestValue.Id.REST_BETWEEN)\n    public static String restBetween() {"),
                values);
        assertTrue(values.contains("{@code @TestValue} method returns"), values);
        assertEquals(0, parse(values).getProblems().length, values);

        String names = assertInstanceOf(ManagedHolders.Plan.Write.class, ManagedHolders.plan(config,
                "com.botmaker.test", declared.getLast(), declared, TestValues.GRAMMAR)).source();
        assertTrue(names.contains("@TestValue(TestValue.Id.NAMES)\npublic final class Names {"), names);
    }

    @Test
    void aValueWithNoHolderIsRefused() {
        assertInstanceOf(ManagedHolders.Plan.Refused.class, plan(TestValue.Id.OPENED_ONLY));
    }

    @Test
    void theFileIsWrittenOnceAndNeverOverwritten() throws Exception {
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan(TestValue.Id.GREETING);
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        assertTrue(ProjectWrites.create(config, write.file(), write.source(), "Create"));
        Files.writeString(write.file(), "// the user's now");
        assertTrue(ProjectWrites.create(config, write.file(), write.source(), "Create"));
        assertEquals("// the user's now", Files.readString(write.file()));
    }

    /** On bind (2026-09-26): every holder the project lacks, one per holder, and none it already has. */
    @Test
    void onBindEveryMissingHolderIsPlannedOnce() {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        List<ManagedHolders.Plan> plans = ManagedHolders.missing(config, java.util.Map.of("com.botmaker.sdk", DECLARED),
                java.util.Set.of(), java.util.Set.of("Main.java"), TestValues.GRAMMAR);
        assertEquals(List.of("plugins/sdk/Sdk.java", "plugins/sdk/Pictures.java"), plans.stream()
                .map(p -> ((ManagedHolders.Plan.Write) p).relative()).toList());
    }

    @Test
    void onBindAHolderTheBotAlreadyHasIsLeftAlone() throws Exception {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        java.util.Map<String, List<ManagedValue<?>>> sdk = java.util.Map.of("com.botmaker.sdk", DECLARED);
        // A value it holds is declared somewhere (the user moved Sdk.java and renamed it).
        assertEquals(List.of("plugins/sdk/Pictures.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(ManagedValue.idOf(TestValue.Id.REST_BETWEEN)), java.util.Set.of(), TestValues.GRAMMAR)));
        // A source of that name exists elsewhere in the bot.
        assertEquals(List.of("plugins/sdk/Sdk.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(), java.util.Set.of("Pictures.java"), TestValues.GRAMMAR)));
        // Its file is on disk, whatever it holds.
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan(TestValue.Id.GREETING);
        Files.createDirectories(write.file().getParent());
        Files.writeString(write.file(), "// the user's");
        assertEquals(List.of("plugins/sdk/Pictures.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(), java.util.Set.of(), TestValues.GRAMMAR)));
    }

    private static List<String> relatives(List<ManagedHolders.Plan> plans) {
        return plans.stream().map(p -> ((ManagedHolders.Plan.Write) p).relative()).toList();
    }

    @Test
    void anIdBecomesAMethodName() {
        assertEquals("flow", ManagedHolders.methodName("flow"));
        assertEquals("restBetween", ManagedHolders.methodName("rest-between"));
        assertEquals("classValue", ManagedHolders.methodName("class"));
        assertEquals("sdk", ManagedHolders.segment("com.botmaker.sdk"));
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
