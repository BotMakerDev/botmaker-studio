package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run copy moved to a newer tag keeps this computer's values classes (2026-11-04), whichever older tag held them. */
class UnpackedBotsValuesTest {

    @Test
    void valuesMoveFromEveryOlderTagTheNewOneLacks(@TempDir Path bot) throws IOException {
        Path v1 = Files.createDirectories(bot.resolve("v1.0.0"));
        Path v2 = Files.createDirectories(bot.resolve("v2.0.0"));
        Path v3 = Files.createDirectories(bot.resolve("v3.0.0"));
        Path values = Files.createDirectories(v1.resolve("src/main/java/com/refbot/values"));
        Files.writeString(values.resolve("RunChoicesValues.java"), "private");
        Files.createDirectories(v2.resolve("src/main/java/com/refbot"));

        assertTrue(UnpackedBots.moveValues(v2, v3), "nothing to move is a success");
        assertTrue(UnpackedBots.moveValues(v1, v3));

        Path moved = v3.resolve("src/main/java/com/refbot/values/RunChoicesValues.java");
        assertEquals("private", Files.readString(moved));
        assertFalse(Files.exists(values));
    }

    @Test
    void theNewTagsOwnValuesAreKept(@TempDir Path bot) throws IOException {
        Path v1 = Files.createDirectories(bot.resolve("v1.0.0"));
        Path v2 = Files.createDirectories(bot.resolve("v2.0.0"));
        Path older = Files.createDirectories(v1.resolve("src/main/java/com/refbot/values"));
        Files.writeString(older.resolve("RunChoicesValues.java"), "old");
        Path newer = Files.createDirectories(v2.resolve("src/main/java/com/refbot/values"));
        Files.writeString(newer.resolve("RunChoicesValues.java"), "new");

        assertTrue(UnpackedBots.moveValues(v1, v2));

        assertEquals("new", Files.readString(newer.resolve("RunChoicesValues.java")));
    }
}
