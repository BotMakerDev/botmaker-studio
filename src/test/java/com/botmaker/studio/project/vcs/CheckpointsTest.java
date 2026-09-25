package com.botmaker.studio.project.vcs;

import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckpointsTest {

    /** The editor keeps edits in memory until a run; a version of the disk alone would miss them. */
    @Test
    void aVersionOfTheEditorWritesWhatItHoldsFirst(@TempDir Path dir) throws IOException {
        Path bot = dir.resolve("src/Bot.java");
        Files.createDirectories(bot.getParent());
        Files.writeString(bot, "class Bot {}");
        new ProjectVcs(dir).init();

        ProjectState.Snapshot editor = new ProjectState.Snapshot("", null, Map.of(), List.of(),
                List.of(new ProjectState.SourceFile(bot, "class Bot { int edited; }")), null);
        Checkpoints.take(dir, editor, VersionOrigin.AI, "AI: Claude Code");

        assertEquals("class Bot { int edited; }", Files.readString(bot));
        ProjectVcs.CommitInfo last = new ProjectVcs(dir).history().getFirst();
        assertEquals(VersionOrigin.AI, last.origin());
        assertEquals("AI: Claude Code", last.message());
        assertEquals(true, new ProjectVcs(dir).status().isClean());
    }

    @Test
    void aProjectWithNoHistoryYetGetsOne(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        Checkpoints.take(dir, VersionOrigin.SAFETY, "Before a rename");

        List<ProjectVcs.CommitInfo> history = new ProjectVcs(dir).history();
        assertEquals(1, history.size(), "init commits everything, so the checkpoint itself has nothing left");
        assertEquals(VersionOrigin.CREATE, history.getFirst().origin());
    }
}
