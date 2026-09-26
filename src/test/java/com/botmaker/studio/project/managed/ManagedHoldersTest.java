package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.params.TestValues;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host writes a plugin's missing {@code @Managed} holder once: every method-shaped value sharing the holder,
 * each returning its type's fresh value, in {@code plugins/<last id segment>/}, and never over a file that is
 * there.
 */
class ManagedHoldersTest {

    private static final List<ManagedValue> DECLARED = List.of(
            new ManagedValue("greeting", "Mine.", "Sdk", TestValues.TEXT),
            new ManagedValue("rest-between", "Mine.", "Sdk", TestValues.DURATION),
            new ManagedValue("pictures", "Mine.", "Pictures", null),
            new ManagedValue("opened-only", "Mine."));

    @TempDir
    Path dir;

    private ManagedHolders.Plan plan(String id) {
        ManagedValue value = DECLARED.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
        return ManagedHolders.plan(ProjectConfig.forDirectory(dir), "com.botmaker.sdk", value, DECLARED,
                TestValues.GRAMMAR);
    }

    @Test
    void aMethodHolderHoldsEverySiblingEachReadableAsItsFreshValue() {
        ManagedHolders.Plan.Write write = assertInstanceOf(ManagedHolders.Plan.Write.class, plan("greeting"));
        assertEquals("plugins/sdk/Sdk.java", write.relative());
        assertTrue(write.file().endsWith(Path.of("plugins", "sdk", "Sdk.java")), write.file().toString());
        String source = write.source();
        assertTrue(source.contains("package " + ProjectConfig.forDirectory(dir).mainPackage() + ".plugins.sdk;"),
                source);
        assertTrue(source.contains("import " + ManagedHolders.MANAGED + ";"), source);
        assertTrue(source.contains("import java.time.Duration;"), source);

        CompilationUnit unit = parse(source);
        assertEquals(0, unit.getProblems().length, source);
        List<ManagedMethod> read = JavaManagedSource.read(null, source, TestValues.GRAMMAR);
        assertEquals(List.of("greeting", "rest-between"), read.stream().map(ManagedMethod::id).toList());
        for (ManagedMethod method : read) assertTrue(method.editable(), method.note());
        assertEquals("restBetween", read.get(1).methodName());
    }

    @Test
    void aTypeLevelValueIsAnEmptyAnnotatedClass() {
        ManagedHolders.Plan.Write write = assertInstanceOf(ManagedHolders.Plan.Write.class, plan("pictures"));
        assertTrue(write.source().contains("@Managed(\"pictures\")\npublic final class Pictures {"), write.source());
        assertEquals(0, parse(write.source()).getProblems().length, write.source());
    }

    @Test
    void aValueWithNoHolderIsRefused() {
        assertInstanceOf(ManagedHolders.Plan.Refused.class, plan("opened-only"));
    }

    @Test
    void theFileIsWrittenOnceAndNeverOverwritten() throws Exception {
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan("greeting");
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
        java.util.Map<String, List<ManagedValue>> sdk = java.util.Map.of("com.botmaker.sdk", DECLARED);
        // A value it holds is declared somewhere (the user moved Sdk.java and renamed it).
        assertEquals(List.of("plugins/sdk/Pictures.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of("rest-between"), java.util.Set.of(), TestValues.GRAMMAR)));
        // A source of that name exists elsewhere in the bot.
        assertEquals(List.of("plugins/sdk/Sdk.java"), relatives(ManagedHolders.missing(config, sdk,
                java.util.Set.of(), java.util.Set.of("Pictures.java"), TestValues.GRAMMAR)));
        // Its file is on disk, whatever it holds.
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan("greeting");
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
