package com.botmaker.studio.sharing;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.studio.project.vcs.ProjectVcs;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** <i>Export .bmk…</i> (2026-10-25, plan phase 6): a tagged, saved version only, its sources from the commit. */
class BmkExportTest {

    @TempDir
    Path tmp;

    private Path project() throws IOException {
        Path dir = tmp.resolve("Firestone");
        Files.createDirectories(dir.resolve("src/main/java/firestone"));
        Files.writeString(dir.resolve("pom.xml"), """
                <project><artifactId>firestone</artifactId><name>Firestone</name>
                <properties><maven.compiler.release>25</maven.compiler.release></properties></project>
                """);
        Files.writeString(dir.resolve("src/main/java/firestone/Main.java"),
                "package firestone; class Main { public static void main(String[] a) {} }");
        new ProjectVcs(dir).init();
        try (Git git = Git.open(dir.toFile())) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "mine", "url", "https://github.com/LiQiyeDev/Firestone.git");
            config.save();
        }
        return dir;
    }

    private static void tag(Path dir, String name) throws Exception {
        try (Git git = Git.open(dir.toFile())) {
            git.tag().setName(name).call();
        }
    }

    private BmkExport.Jars jars() {
        return dir -> List.of(new BotFile.Jar("com.github.BotMakerDev:botmaker-sdk:2.0.0",
                Files.write(tmp.resolve("sdk.jar"), new byte[]{1, 2})));
    }

    @Test
    void refusesAnUntaggedOrUnsavedVersion() throws Exception {
        Path dir = project();
        BotFile.Refused untagged = assertThrows(BotFile.Refused.class,
                () -> BmkExport.export(dir, tmp.resolve("out"), jars()));
        assertTrue(untagged.getMessage().startsWith("This version has no tag"), untagged.getMessage());
        tag(dir, "v1.0.0");
        Files.writeString(dir.resolve("src/main/java/firestone/Extra.java"), "class Extra {}");
        BotFile.Refused dirty = assertThrows(BotFile.Refused.class,
                () -> BmkExport.export(dir, tmp.resolve("out"), jars()));
        assertTrue(dirty.getMessage().startsWith("Commit first: a .bmk is made from a commit."), dirty.getMessage());
    }

    @Test
    void exportsTheTaggedCommit() throws Exception {
        Path dir = project();
        tag(dir, "v1.0.0");
        Path written = BmkExport.export(dir, tmp.resolve("out"), jars());
        assertEquals(tmp.resolve("out/Firestone-1.0.0.bmk"), written);
        BotJson bot = BotFile.read(written, tmp.resolve("open"));
        assertEquals("LiQiyeDev/Firestone v1.0.0 25 firestone.Main",
                bot.owner() + "/" + bot.repo() + " " + bot.tag() + " " + bot.java() + " " + bot.main());
        assertTrue(Files.isRegularFile(tmp.resolve("open/lib/botmaker-sdk-2.0.0.jar")));
        assertTrue(Files.isRegularFile(tmp.resolve("open/src/main/java/firestone/Main.java")));
        // Twice from one commit: the same bytes.
        byte[] first = Files.readAllBytes(written);
        assertArrayEquals(first, Files.readAllBytes(BmkExport.export(dir, tmp.resolve("out"), jars())));
    }
}
