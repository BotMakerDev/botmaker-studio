package com.botmaker.studio.project.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Both sides of a file, as a version or the unsaved edits changed it. */
class VersionReaderTest {

    @Test
    void aVersionGivesTheFileBeforeAndAfter(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
        vcs.init();
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int b; }");
        vcs.checkpoint(VersionOrigin.SAVE, "b");
        String sha = vcs.history().getFirst().sha();

        VersionReader.Sides sides = new VersionReader(dir).version(sha, "Bot.java");
        assertEquals("class Bot { int a; }", sides.beforeText());
        assertEquals("class Bot { int b; }", sides.afterText());
        assertFalse(sides.renamed());
    }

    @Test
    void theFirstVersionHasNoBefore(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        vcs.init();
        VersionReader.Sides sides = new VersionReader(dir).version(vcs.history().getFirst().sha(), "Bot.java");
        assertNull(sides.before());
        assertEquals("class Bot {}", sides.afterText());
    }

    @Test
    void aRenameIsFollowedBack(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        String body = "class Bot {\n" + "    void mine() { wait(500); }\n".repeat(12) + "}\n";
        Files.writeString(dir.resolve("Bot.java"), body);
        vcs.init();
        Files.move(dir.resolve("Bot.java"), dir.resolve("Miner.java"));
        Files.writeString(dir.resolve("Miner.java"), body.replace("class Bot", "class Miner"));
        vcs.checkpoint(VersionOrigin.SAVE, "rename");

        VersionReader.Sides sides = new VersionReader(dir).version(vcs.history().getFirst().sha(), "Miner.java");
        assertTrue(sides.renamed());
        assertEquals("Bot.java", sides.oldPath());
        assertEquals(body, sides.beforeText());
    }

    @Test
    void unsavedIsTheLastVersionAgainstTheDisk(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "v1");
        vcs.init();
        Files.writeString(dir.resolve("Bot.java"), "v2");
        Files.writeString(dir.resolve("New.java"), "new");

        VersionReader reader = new VersionReader(dir);
        assertEquals("v1", reader.unsaved("Bot.java").beforeText());
        assertEquals("v2", reader.unsaved("Bot.java").afterText());
        assertNull(reader.unsaved("New.java").before());
    }
}
