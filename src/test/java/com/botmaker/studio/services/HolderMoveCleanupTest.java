package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The folders a holder move leaves behind (2026-10-30: nine files out of one folder stopped at the second). */
class HolderMoveCleanupTest {

    @Test
    void severalFilesLeavingOneFolderRemoveItOnceAndKeepWhatIsNotEmpty(@TempDir Path root) throws IOException {
        Path old = Files.createDirectories(root.resolve("plugins/sdk"));
        Path kept = Files.createDirectories(root.resolve("plugins/com_github_botmakerdev/botmaker_sdk"));
        Files.writeString(kept.resolve("Sdk.java"), "");

        ClassFiles.removeEmptied(root, new LinkedHashSet<>(List.of(old.resolve("Sdk.java"), old.resolve("Tags.java"))));

        assertFalse(Files.exists(old));
        assertTrue(Files.isDirectory(root.resolve("plugins")));
        assertTrue(Files.isRegularFile(kept.resolve("Sdk.java")));
    }
}
