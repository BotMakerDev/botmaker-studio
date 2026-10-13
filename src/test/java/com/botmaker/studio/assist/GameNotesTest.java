package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the assistant learns about a game stays in the bot, one section at a time. */
class GameNotesTest {

    @Test
    void aSectionIsAddedRewrittenAndRemovedAndTheRestKept(@TempDir Path bot) throws Exception {
        assertTrue(GameNotes.read(bot, "").startsWith("The bot has no notes"));
        assertEquals("", GameNotes.atConnect(bot));

        assertEquals("Added Hotkeys to GAME.md.", GameNotes.write(bot, "Hotkeys", "Q opens Quests.\n"));
        assertEquals("Added Screens to GAME.md.", GameNotes.write(bot, " Screens ", "Town: the home picture."));
        assertEquals("## Hotkeys\n\nQ opens Quests.", GameNotes.read(bot, "hotkeys"));

        assertEquals("Rewrote Hotkeys in GAME.md.", GameNotes.write(bot, "Hotkeys", "Q opens Quests.\nM opens the Map."));
        String notes = Files.readString(bot.resolve(GameNotes.FILE));
        assertTrue(notes.startsWith("# The game\n"), notes);
        assertTrue(notes.contains("## Hotkeys\n\nQ opens Quests.\nM opens the Map.\n\n## Screens\n\nTown: the home"
                + " picture.\n"), notes);
        assertTrue(notes.endsWith("picture.\n"), "one line break at the end: " + notes);

        assertEquals("Removed Hotkeys in GAME.md.", GameNotes.write(bot, "Hotkeys", ""));
        assertFalse(GameNotes.read(bot, "").contains("Hotkeys"));
        assertTrue(GameNotes.atConnect(bot).contains("## Screens"));
    }

    @Test
    void aMissingSectionOrABadHeadingIsRefused(@TempDir Path bot) {
        GameNotes.write(bot, "Screens", "Town.");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GameNotes.read(bot, "Hotkeys"));
        assertEquals("No section Hotkeys in GAME.md. It has: Screens.", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "Hotkeys", ""));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "## Hotkeys", "Q."));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "Two\nlines", "Q."));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, " ", "Q."));
    }

    @Test
    void aHeadingInAFenceOrABodyStartsNoSection(@TempDir Path bot) throws Exception {
        GameNotes.write(bot, "Code", "Look:\n```\n## not a heading\n```\n## demoted");
        GameNotes.write(bot, "Screens", "Town.");
        assertEquals("## Code\n\nLook:\n```\n## not a heading\n```\n### demoted", GameNotes.read(bot, "Code"));
        GameNotes.write(bot, "code", "Shorter.");
        assertEquals("## Code\n\nShorter.", GameNotes.read(bot, "Code"), "the same section, any case");
        assertEquals("## Screens\n\nTown.", GameNotes.read(bot, "Screens"));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "a\rb", "Q."));

        Files.write(bot.resolve(GameNotes.FILE), new byte[] {'#', ' ', (byte) 0xE9, '\n'});
        assertTrue(GameNotes.atConnect(bot).startsWith("# "), "a byte not UTF-8 still reads");
    }

    @Test
    void longNotesAreCutAtConnect(@TempDir Path bot) {
        GameNotes.write(bot, "Long", "x".repeat(GameNotes.AT_CONNECT * 2));
        String said = GameNotes.atConnect(bot);
        assertTrue(said.endsWith("read_notes reads the rest."), said.substring(said.length() - 60));
        assertTrue(said.length() < GameNotes.AT_CONNECT + 100);
    }
}
