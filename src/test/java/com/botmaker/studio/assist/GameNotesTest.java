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

    private static String read(Path bot, String section) {
        String said = GameNotes.read(bot, section);
        assertTrue(said.startsWith(GameNotes.FROM), "the notes are said to be the bot's, not orders: " + said);
        return said.substring(GameNotes.FROM.length());
    }

    @Test
    void aSectionIsAddedRewrittenAndRemovedAndTheRestKept(@TempDir Path bot) throws Exception {
        assertTrue(GameNotes.read(bot, "").startsWith("The bot has no notes"));

        assertEquals("Added Hotkeys to GAME.md.", GameNotes.write(bot, "Hotkeys", "Q opens Quests.\n"));
        assertEquals("Added Screens to GAME.md.", GameNotes.write(bot, " Screens ", "Town: the home picture."));
        assertEquals("## Hotkeys\n\nQ opens Quests.", read(bot, "hotkeys"));

        assertEquals("Rewrote Hotkeys in GAME.md.", GameNotes.write(bot, "hotkeys", "Q opens Quests.\nM opens the Map."));
        String notes = Files.readString(bot.resolve(GameNotes.FILE));
        assertTrue(notes.startsWith("# The game\n"), notes);
        assertTrue(notes.contains("## Hotkeys\n\nQ opens Quests.\nM opens the Map.\n\n## Screens\n\nTown: the home"
                + " picture.\n"), "the heading kept as written, any case finds it: " + notes);
        assertTrue(notes.endsWith("picture.\n"), "one line break at the end: " + notes);

        assertEquals("Removed Hotkeys in GAME.md.", GameNotes.write(bot, "Hotkeys", ""));
        assertFalse(read(bot, "").contains("Hotkeys"));
        assertTrue(read(bot, "").contains("## Screens"));
    }

    @Test
    void codeIsNotAHeadingAndABodysHeadingStartsNoSection(@TempDir Path bot) throws Exception {
        GameNotes.write(bot, "Code", "Look:\n```\n## fenced\n```\n~~~\n## tilde\n~~~\n## demoted\nUnder\n---");
        GameNotes.write(bot, "Screens", "Town.");
        assertEquals("## Code\n\nLook:\n```\n## fenced\n```\n~~~\n## tilde\n~~~\n### demoted\n### Under",
                read(bot, "Code"));
        GameNotes.write(bot, "Code", "Shorter.");
        assertEquals("## Code\n\nShorter.", read(bot, "Code"));
        assertEquals("## Screens\n\nTown.", read(bot, "Screens"));

        // An author's own spelling: an underlined heading, and one inside a quote that is not a section.
        Files.writeString(bot.resolve(GameNotes.FILE), "# Mine\n\nKeys\n----\n\nQ.\n\n> ## quoted\n\n## Map\n\nM.\n");
        assertEquals("Keys\n----\n\nQ.\n\n> ## quoted", read(bot, "Keys"));
        GameNotes.write(bot, "Keys", "Q opens Quests.");
        assertEquals("# Mine\n\n## Keys\n\nQ opens Quests.\n\n## Map\n\nM.\n",
                Files.readString(bot.resolve(GameNotes.FILE)), "every other line as the author wrote it");
    }

    @Test
    void aMissingSectionOrABadHeadingIsRefused(@TempDir Path bot) throws Exception {
        GameNotes.write(bot, "Screens", "Town.");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GameNotes.read(bot, "Hotkeys"));
        assertEquals("No section Hotkeys in GAME.md. It has: Screens.", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "Hotkeys", ""));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "## Hotkeys", "Q."));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "Two\nlines", "Q."));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, "a\rb", "Q."));
        assertThrows(IllegalArgumentException.class, () -> GameNotes.write(bot, " ", "Q."));

        Files.write(bot.resolve(GameNotes.FILE), new byte[] {'#', ' ', (byte) 0xE9, '\n'});
        assertTrue(read(bot, "").startsWith("# "), "a byte not UTF-8 still reads");
    }
}
