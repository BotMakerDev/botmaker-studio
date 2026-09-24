package com.botmaker.studio.services;

import com.botmaker.studio.config.HostContract;
import com.botmaker.studio.project.ProjectConfig;
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
