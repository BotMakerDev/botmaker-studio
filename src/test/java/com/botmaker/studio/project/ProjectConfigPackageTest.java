package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bot cloned from the gallery keeps its author's package in a folder its user named (2026-10-23): the package is
 * read off the class holding {@code main}, never derived from the folder.
 */
class ProjectConfigPackageTest {

    /** A cloned bot in {@code MyFarm}: its main in {@code com.farm}, a helper beside it. */
    private static Path clone(Path root) throws IOException {
        Path dir = root.resolve("MyFarm");
        Path sources = dir.resolve("src/main/java/com/farm");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("FarmBot.java"), """
                package com.farm;

                public class FarmBot {
                    public static void main(String[] args) {}
                }
                """);
        Files.writeString(sources.resolve("Helper.java"), "package com.farm;\nfinal class Helper {}\n");
        return dir;
    }

    @Test
    void aClonedBotKeepsItsAuthorsPackageAndEntryClass(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forDirectory(clone(root));

        assertEquals("MyFarm", config.projectName());
        assertEquals("farm", config.packageName());
        assertEquals("com.farm.FarmBot", config.entryClassName());
    }

    @Test
    void aMainFurtherDownDoesNotMoveThePackage(@TempDir Path root) throws IOException {
        Path dir = clone(root);
        Path tools = dir.resolve("src/main/java/com/farm/tools");
        Files.createDirectories(tools);
        Files.writeString(tools.resolve("Probe.java"), """
                package com.farm.tools;
                public class Probe { public static void main(String[] a) {} }
                """);

        assertEquals(Optional.of("com.farm"), ProjectConfig.mainPackage(dir));
    }

    @Test
    void noMainOrMainsInUnrelatedPackagesFallBackToTheFolder(@TempDir Path root) throws IOException {
        Path dir = clone(root);
        Path other = dir.resolve("src/main/java/org/other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("Other.java"), """
                package org.other;
                public class Other { public static void main(String[] a) {} }
                """);
        assertEquals(Optional.empty(), ProjectConfig.mainPackage(dir));
        assertEquals("myfarm", ProjectConfig.forDirectory(dir).packageName());

        Files.delete(other.resolve("Other.java"));
        Files.delete(dir.resolve("src/main/java/com/farm/FarmBot.java"));
        assertEquals(Optional.empty(), ProjectConfig.mainPackage(dir));
    }
}
