package com.botmaker.studio.services;

import com.botmaker.studio.config.HostContract;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blank project's first {@code @Param} used to be a compile error — nothing brought the contract jar. These
 * hold the write that fixes it, and the two cases where writing would be the bug.
 */
class ContractDependencyTest {

    @TempDir
    Path root;

    @Test
    void aBlankProjectWithNoContractOnItsClasspathIsGivenOne() throws Exception {
        Path projectDir = blankProject();

        assertTrue(ContractDependency.ensure(projectDir, List.of()));

        List<Dependency> contract = contractEntries(projectDir);
        assertEquals(1, contract.size());
        assertEquals(HostContract.version(), contract.getFirst().getVersion());
        // compile, not provided: the bot's own classes carry the annotation.
        assertTrue(contract.getFirst().getScope() == null || "compile".equals(contract.getFirst().getScope()));
    }

    @Test
    void aContractAlreadyOnTheClasspathIsNeverDeclaredBesideIt() throws Exception {
        Path projectDir = blankProject();
        Path jar = jarWith(root.resolve("botmaker-sdk-2.0.0.jar"), ContractDependency.PARAM_CLASS);

        // Brought transitively (the SDK declares it at compile): a direct entry would win by nearest-wins.
        assertFalse(ContractDependency.ensure(projectDir, List.of(jar.toString())));
        assertTrue(contractEntries(projectDir).isEmpty());
    }

    @Test
    void aPomThatAlreadyDeclaresItIsLeftAloneEvenBeforeItResolves() throws Exception {
        Path projectDir = blankProject();
        assertTrue(ContractDependency.ensure(projectDir, List.of()));

        assertFalse(ContractDependency.ensure(projectDir, List.of()));
        assertEquals(1, contractEntries(projectDir).size());
    }

    @Test
    void presenceIsTheClassNotTheJarName() throws Exception {
        Path named = jarWith(root.resolve("botmaker-studio-api-0.1.0.jar"), "com/botmaker/plugin/api/Other.class");
        Path classes = root.resolve("classes");
        Files.createDirectories(classes.resolve(ContractDependency.PARAM_CLASS).getParent());
        Files.createFile(classes.resolve(ContractDependency.PARAM_CLASS));

        assertFalse(ContractDependency.onClasspath(List.of(named.toString())));
        assertTrue(ContractDependency.onClasspath(List.of(named.toString(), classes.toString())));
    }

    @Test
    void aBotWhosePluginLeftKeepsTheContractItsOwnSourceImports() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("Blank", root);
        MavenService.writeBlankPom(cfg.projectPath(), cfg);
        ProjectState state = new ProjectState();
        // The plugin brought the contract until it was removed; the classpath after the removal has none.
        assertFalse(ContractDependency.ensureFor(cfg, state, List.of()), "nothing imports it yet");
        assertTrue(contractEntries(cfg.projectPath()).isEmpty());

        Files.createDirectories(cfg.mainPackageDir());
        Files.writeString(cfg.mainPackageDir().resolve("Blank.java"), """
                package %s;

                import com.botmaker.plugin.api.meta.Refactor;

                public class Blank {
                    @Refactor("Ask.choice went with the plugin.")
                    public static void main(String[] args) {}
                }
                """.formatted(cfg.mainPackage()));
        assertTrue(ContractDependency.ensureFor(cfg, state, List.of()));
        assertEquals(1, contractEntries(cfg.projectPath()).size());
    }

    @Test
    void anEntryNothingImportsAnyMoreStaysWhileNoPluginBringsIt() throws Exception {
        Path projectDir = blankProject();
        assertTrue(ContractDependency.ensure(projectDir, List.of()));

        // An import gone for a moment (a cut before a paste) must not cost the bot its contract; alone, the
        // entry is harmless.
        assertFalse(ContractDependency.reconcile(projectDir, false, List.of()));
        assertEquals(1, contractEntries(projectDir).size());
    }

    @Test
    void anOlderBroughtContractDoesNotReplaceTheEntry() {
        assertTrue(MavenService.notOlder("v0.5.0", "v0.4.0"));
        assertTrue(MavenService.notOlder("0.4.0", "v0.4.0"));
        assertFalse(MavenService.notOlder("v0.3.1", "v0.4.0"), "it may lack what the bot uses, @Refactor say");
        assertFalse(MavenService.notOlder(null, "v0.4.0"));
    }

    @Test
    void anEntryTheBotStillNeedsAndNothingElseBringsStays() throws Exception {
        Path projectDir = blankProject();
        assertTrue(ContractDependency.ensure(projectDir, List.of()));
        Path jar = jarWith(root.resolve("botmaker-studio-api.jar"), ContractDependency.PARAM_CLASS);

        assertFalse(ContractDependency.reconcile(projectDir, true, List.of(jar.toString())));
        assertEquals(1, contractEntries(projectDir).size());
    }

    @Test
    void anEntryAPluginBringsAgainIsTakenOut() throws Exception {
        // The SDK brings the contract at compile; the locally installed snapshot is the one tree to read offline.
        Path sdk = Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "github", "LiQiyeDev",
                "botmaker-sdk", "0.0.0-SNAPSHOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(sdk), "needs the SDK chain installed");
        Path projectDir = blankProject();
        // Pinned at what the local SDK brings (its contract is the snapshot too), so the plugin's is not older.
        MavenService.declareIfAbsent(projectDir, new com.botmaker.studio.project.UserLibrary(
                HostContract.GROUP_ID, HostContract.ARTIFACT_ID, "0.0.0-SNAPSHOT"));
        MavenService.declareIfAbsent(projectDir, new com.botmaker.studio.project.UserLibrary(
                "com.github.LiQiyeDev", "botmaker-sdk", "0.0.0-SNAPSHOT"));
        Path jar = jarWith(root.resolve("botmaker-studio-api.jar"), ContractDependency.PARAM_CLASS);

        assertTrue(ContractDependency.reconcile(projectDir, true, List.of(jar.toString())));
        assertTrue(contractEntries(projectDir).isEmpty());
    }

    @Test
    void anEntryNewerThanThePluginsContractStays() throws Exception {
        Path sdk = Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "github", "LiQiyeDev",
                "botmaker-sdk", "0.0.0-SNAPSHOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(sdk), "needs the SDK chain installed");
        Path projectDir = blankProject();
        MavenService.declareIfAbsent(projectDir, new com.botmaker.studio.project.UserLibrary(
                HostContract.GROUP_ID, HostContract.ARTIFACT_ID, "v99.0.0"));
        MavenService.declareIfAbsent(projectDir, new com.botmaker.studio.project.UserLibrary(
                "com.github.LiQiyeDev", "botmaker-sdk", "0.0.0-SNAPSHOT"));
        Path jar = jarWith(root.resolve("botmaker-studio-api.jar"), ContractDependency.PARAM_CLASS);

        // Dropping it would hand the bot the plugin's older contract, which may lack what the bot uses.
        assertFalse(ContractDependency.reconcile(projectDir, true, List.of(jar.toString())));
        assertEquals(1, contractEntries(projectDir).size());
    }

    private Path blankProject() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("Blank", root);
        MavenService.writeBlankPom(cfg.projectPath(), cfg);
        return cfg.projectPath();
    }

    private static Path jarWith(Path jar, String entry) throws Exception {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.closeEntry();
        }
        return jar;
    }

    private static List<Dependency> contractEntries(Path projectDir) throws Exception {
        try (InputStream in = Files.newInputStream(projectDir.resolve("pom.xml"))) {
            Model model = new MavenXpp3Reader().read(in);
            return model.getDependencies().stream()
                    .filter(d -> HostContract.ARTIFACT_ID.equals(d.getArtifactId()))
                    .toList();
        }
    }
}
