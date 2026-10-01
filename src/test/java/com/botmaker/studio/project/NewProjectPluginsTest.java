package com.botmaker.studio.project;

import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugins ticked in New Project are declared before the first commit, for a blank project and a template
 * alike, and a failure on the way leaves no directory (2026-10-01). The check for a plugin another one brings
 * resolves jars, so it is a seam here; {@code ShadowedPluginsTest} holds its rule.
 */
class NewProjectPluginsTest {

    private static final ProjectCreator.PluginPick SDK = new ProjectCreator.PluginPick(
            new UserLibrary("g", "sdk", "2.0.0"), List.of(new UserLibrary("io.javalin", "javalin", "6.1")));

    private static UserLibrary declared(Path project, String artifactId) {
        return MavenService.readDeclaredLibraries(project).stream()
                .filter(d -> d.artifactId().equals(artifactId)).findFirst().orElse(null);
    }

    /** A published template whose pom pins a plugin the way botmaker-base pins basics. */
    private static void unpackBase(Path dest) throws IOException {
        Path sources = dest.resolve("src/main/java/com/botmaker/base");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Base.java"), """
                package com.botmaker.base;

                public class Base {
                    public static void main(String[] args) {
                    }
                }
                """);
        Files.writeString(dest.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.botmaker</groupId>
                  <artifactId>base</artifactId>
                  <version>1.0</version>
                  <properties><botmaker.basics.version>0.1.1</botmaker.basics.version></properties>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>basics</artifactId>
                      <version>${botmaker.basics.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    @Test
    void aBlankProjectDeclaresTheTickedPluginAndWhatItsEditorNeeds(@TempDir Path root) throws IOException {
        List<Path> checked = new ArrayList<>();
        new ProjectCreator(root, dir -> {
            checked.add(dir);
            return List.of();
        }).createProject("MyBot", ProjectTemplate.EMPTY, List.of(SDK));

        Path project = root.resolve("MyBot");
        assertEquals("2.0.0", declared(project, "sdk").version());
        assertEquals("6.1", declared(project, "javalin").version());
        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<scope>provided</scope>"));
        assertEquals(List.of(project), checked, "the redundant-entry check runs once, after the writes");
    }

    @Test
    void nothingTickedWritesNoPluginAndChecksNothing(@TempDir Path root) throws IOException {
        new ProjectCreator(root, dir -> fail("nothing was installed, so nothing can shadow anything"))
                .createProject("MyBot", ProjectTemplate.EMPTY, List.of());

        assertNull(declared(root.resolve("MyBot"), "sdk"));
    }

    @Test
    void aTemplatesOwnPluginKeepsItsPin(@TempDir Path root) throws IOException {
        ProjectCreator.PluginPick basics = new ProjectCreator.PluginPick(
                new UserLibrary("g", "basics", "9.9.9"), List.of());
        new ProjectCreator(root, dir -> List.of())
                .createFromTemplate("MyBot", NewProjectPluginsTest::unpackBase, List.of(basics, SDK));

        Path project = root.resolve("MyBot");
        assertEquals("${botmaker.basics.version}", declared(project, "basics").version(),
                "the template's entry is its author's, placeholder and all");
        assertEquals(1, MavenService.readDeclaredLibraries(project).stream()
                .filter(d -> d.artifactId().equals("basics")).count());
        assertEquals("2.0.0", declared(project, "sdk").version());
    }

    @Test
    void aFailedBlankCreationLeavesNoDirectory(@TempDir Path root) {
        ProjectCreator creator = new ProjectCreator(root, dir -> {
            throw new IOException("unreachable");
        });

        assertThrows(IOException.class, () -> creator.createProject("MyBot", ProjectTemplate.EMPTY, List.of(SDK)));
        assertFalse(Files.exists(root.resolve("MyBot")));
    }

    @Test
    void aFailedTemplateCreationLeavesNoDirectory(@TempDir Path root) {
        ProjectCreator creator = new ProjectCreator(root, dir -> {
            throw new IOException("unreachable");
        });

        assertThrows(IOException.class,
                () -> creator.createFromTemplate("MyBot", NewProjectPluginsTest::unpackBase, List.of(SDK)));
        assertFalse(Files.exists(root.resolve("MyBot")));
    }

    @Test
    void aDirectoryThatWasAlreadyThereIsNotDeleted(@TempDir Path root) throws IOException {
        Path mine = Files.createDirectories(root.resolve("MyBot")).resolve("notes.txt");
        Files.writeString(mine, "mine");
        ProjectCreator creator = new ProjectCreator(root, dir -> {
            throw new IOException("unreachable");
        });

        assertThrows(IOException.class, () -> creator.createProject("MyBot", ProjectTemplate.EMPTY, List.of(SDK)));
        assertTrue(Files.exists(mine));
    }
}
