package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A template arrives as its author shipped it, except for its package.
 *
 * <p>That asymmetry is the whole design and is what these assert: the package prefix and its directories
 * move, and <b>nothing else is renamed</b> — the entry class keeps the author's name, so a copy's stack
 * traces and README still match the project somebody published.
 */
class TemplateProjectTest {

    /** A minimal published template: one class with main, one helper and a pom. */
    private static Path template(@TempDir Path root) throws IOException {
        Path dir = root.resolve("unpacked");
        Path sources = dir.resolve("src/main/java/com/botmaker/gamebot");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("GameBot.java"), """
                package com.botmaker.gamebot;

                public class GameBot {
                    public static void main(String[] args) {
                        Helper.go();
                    }
                }
                """);
        Files.writeString(sources.resolve("Helper.java"), """
                package com.botmaker.gamebot;

                final class Helper {
                    static void go() {}
                }
                """);
        Files.writeString(dir.resolve("pom.xml"),
                "<project><artifactId>game-bot</artifactId></project>\n");
        return dir;
    }

    @Test
    void thePackageMovesAndEveryMentionOfItFollows(@TempDir Path root) throws IOException {
        Path dir = template(root);

        TemplateProject.read(dir).renameInto(dir, "com.myfarmer");

        Path moved = dir.resolve("src/main/java/com/myfarmer");
        assertTrue(Files.isDirectory(moved), "the sources move to the new package's directories");
        assertFalse(Files.exists(dir.resolve("src/main/java/com/botmaker")),
                "and the empty shells they left behind are pruned");
        assertTrue(Files.readString(moved.resolve("GameBot.java")).contains("package com.myfarmer;"));
        assertTrue(Files.readString(moved.resolve("Helper.java")).contains("package com.myfarmer;"));
    }

    /** The point of the whole thing: what the author shipped is what the user gets. */
    @Test
    void nothingButThePackageIsRenamed(@TempDir Path root) throws IOException {
        Path dir = template(root);

        TemplateProject.read(dir).renameInto(dir, "com.myfarmer");

        Path moved = dir.resolve("src/main/java/com/myfarmer");
        assertTrue(Files.isRegularFile(moved.resolve("GameBot.java")),
                "the entry class keeps the author's name, whatever the project is called");
        assertTrue(Files.isRegularFile(moved.resolve("Helper.java")));
        assertTrue(Files.readString(moved.resolve("GameBot.java")).contains("public class GameBot"));
        assertEquals("<project><artifactId>game-bot</artifactId></project>\n",
                Files.readString(dir.resolve("pom.xml")),
                "the pom is the author's, versions and all");
    }

    /**
     * A main whose package line disagrees with its directory names a package with no sources in it, which
     * would leave the project sitting in the author's package and still compiling — the one failure worth
     * refusing outright rather than reporting later.
     */
    @Test
    void aPackageWithNoSourcesInItIsRefused(@TempDir Path root) throws IOException {
        Path dir = template(root);
        Path entry = dir.resolve("src/main/java/com/botmaker/gamebot/GameBot.java");
        Files.writeString(entry, Files.readString(entry).replace("package com.botmaker.gamebot;",
                "package com.somebody.else;"));

        TemplateProject subject = TemplateProject.read(dir);
        assertFalse(subject.matches(dir), "the publish-time check sees it first");
        assertThrows(IOException.class, () -> subject.renameInto(dir, "com.myfarmer"),
                "and the unpack refuses rather than renaming nothing");
    }

    /** An older template release still ships the declaration file; nothing reads it, and the copy drops it. */
    @Test
    void anOldDeclarationFileIsIgnoredAndNotPartOfTheUsersProject(@TempDir Path root) throws IOException {
        Path dir = template(root);
        Files.writeString(dir.resolve("botmaker-template.properties"), "package=com.somebody.else\n");

        assertEquals("com.botmaker.gamebot", TemplateProject.read(dir).packageName());
        TemplateProject.read(dir).renameInto(dir, "com.myfarmer");

        assertFalse(Files.exists(dir.resolve("botmaker-template.properties")),
                "it said how to unpack a template, which is over");
    }

    /** The package holding main is the template's, and a main further down does not change it. */
    @Test
    void thePackageOfMainIsTheTemplates(@TempDir Path root) throws IOException {
        Path dir = template(root);
        Path sub = dir.resolve("src/main/java/com/botmaker/gamebot/tools");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("Probe.java"), """
                package com.botmaker.gamebot.tools;
                public class Probe { public static void main(String[] a) {} }
                """);

        assertEquals("com.botmaker.gamebot", TemplateProject.read(dir).packageName());
        TemplateProject.read(dir).renameInto(dir, "com.myfarmer");
        assertTrue(Files.exists(dir.resolve("src/main/java/com/myfarmer/GameBot.java")));
    }

    @Test
    void withNoMainItIsRefusedWithASentenceForItsAuthor(@TempDir Path root) throws IOException {
        Path dir = template(root);
        Files.delete(dir.resolve("src/main/java/com/botmaker/gamebot/GameBot.java"));

        IOException thrown = assertThrows(IOException.class, () -> TemplateProject.read(dir));
        assertTrue(thrown.getMessage().contains("main method"), thrown.getMessage());
    }

    @Test
    void mainInUnrelatedPackagesIsRefused(@TempDir Path root) throws IOException {
        Path dir = template(root);
        Path other = dir.resolve("src/main/java/org/other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("Other.java"), """
                package org.other;
                public class Other { public static void main(String[] a) {} }
                """);
        assertThrows(IOException.class, () -> TemplateProject.read(dir));
    }
}
