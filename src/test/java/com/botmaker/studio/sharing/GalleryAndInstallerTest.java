package com.botmaker.studio.sharing;

import com.botmaker.studio.project.ProjectMode;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryAndInstallerTest {

    @Test
    void indexJsonDeserializesToEntriesIgnoringUnknownFields() throws Exception {
        String json = """
                [
                  {"name":"ClickerBot","owner":"alice","repo":"clicker-bot",
                   "description":"auto clicker","tags":["clicker","afk"],"extraneous":true},
                  {"name":"Farmer","owner":"bob","repo":"farmer"}
                ]
                """;
        List<GalleryEntry> entries = List.of(new ObjectMapper().readValue(json, GalleryEntry[].class));

        assertEquals(2, entries.size());
        assertEquals("alice/clicker-bot", entries.get(0).slug());
        assertEquals("https://github.com/alice/clicker-bot", entries.get(0).htmlUrl());
        assertEquals(List.of("clicker", "afk"), entries.get(0).tags());
        assertTrue(entries.get(1).tags().isEmpty());
    }

    @Test
    void entryMatchesQueryAcrossFields() {
        GalleryEntry e = new GalleryEntry("ClickerBot", "alice", "clicker-bot", "auto clicker", List.of("afk"));
        assertTrue(e.matches(""));
        assertTrue(e.matches("clicker"));
        assertTrue(e.matches("ALICE"));
        assertTrue(e.matches("afk"));
        assertTrue(e.matches("auto"));
        assertTrue(!e.matches("zzz"));
    }

    @Test
    void sanitizeNameProducesValidProjectName() {
        assertEquals("ClickerBot", BotInstaller.sanitizeName("ClickerBot"));
        assertEquals("ClickerBot", BotInstaller.sanitizeName("clicker-bot"));
        assertEquals("Bot123", BotInstaller.sanitizeName("123"));
        assertEquals("ImportedBot", BotInstaller.sanitizeName("---"));
    }

    /** Since 2026-10-23 nothing is written for provenance: the remote and the nearest tag are all of it. */
    @Test
    void provenanceIsTheOriginalRemoteAndTheNearestTag(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        vcs.checkpoint(VersionOrigin.INSTALL, "Installed v1.0.0");
        vcs.tagHead("v1.0.0", "Release v1.0.0");
        vcs.setRemote(Remote.ORIGINAL, "https://github.com/alice/clicker-bot.git");

        assertEquals(Optional.of(new BotSource("alice", "clicker-bot", "v1.0.0")), BotSource.read(dir));

        Files.writeString(dir.resolve("Bot.java"), "class Bot { int edited; }");
        vcs.checkpoint(VersionOrigin.SAVE, "an edit after the release");
        assertEquals("v1.0.0", BotSource.read(dir).orElseThrow().tag(), "an edit keeps the release it started from");
        assertFalse(Files.exists(dir.resolve(BotSource.FILE_NAME)));
    }

    /**
     * A fork published as its own bot (2026-10-23): its release is the user's, and the project still says it is at
     * the author's release, so the author's next one is still offered. The same number from the author refuses.
     */
    @Test
    void aForksOwnReleaseIsNotTheReleaseItFollows(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        vcs.checkpoint(VersionOrigin.INSTALL, "Installed v1.0.0");
        vcs.tagHead("v1.0.0", "Release v1.0.0");
        vcs.setRemote(Remote.ORIGINAL, "https://github.com/alice/clicker-bot.git");
        vcs.setRemote(Remote.MINE, "https://github.com/bob/clicker-bot.git");
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int bobs; }");
        vcs.checkpoint(VersionOrigin.PUBLISH, "v1.0.1 published");
        vcs.tagHead("v1.0.1", "Published v1.0.1");
        assertEquals("v1.0.1", BotSource.read(dir).orElseThrow().tag(), "unmarked, any tag is the nearest");

        vcs.markOwnRelease("v1.0.1");
        assertTrue(vcs.isOwnRelease("v1.0.1"));
        assertFalse(vcs.isOwnRelease("v1.0.0"));
        assertEquals("v1.0.0", BotSource.read(dir).orElseThrow().tag());
        IOException clash = assertThrows(IOException.class, () -> vcs.fetchTag(Remote.ORIGINAL, "v1.0.1", null));
        assertTrue(clash.getMessage().startsWith("Your own copy was published as v1.0.1"), clash.getMessage());

        assertTrue(BotPublisher.isFork(vcs, "bob"), "publishing into bob's repository publishes the fork");
        assertFalse(BotPublisher.isFork(vcs, "Alice"), "the author publishing their own");
    }

    /** A fork the user owns is theirs to edit; someone else's bot opens for reading. */
    @Test
    void aForkOfYoursOpensForEditing(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        vcs.checkpoint(VersionOrigin.INSTALL, "Installed");
        vcs.setRemote(Remote.ORIGINAL, "https://github.com/alice/clicker-bot.git");
        assertTrue(ProjectMode.isReader(dir, "bob"), "no fork yet: alice's bot");
        vcs.setRemote(Remote.MINE, "https://github.com/Bob/clicker-bot.git");
        assertFalse(ProjectMode.isReader(dir, "bob"));
        assertTrue(ProjectMode.isReader(dir, "carol"));
        assertFalse(ProjectMode.isReader(dir, "alice"), "the author");
    }

    /** Stop following: the original goes, the user's copy stays, and nothing links the project back. */
    @Test
    void stopFollowingRemovesOnlyTheOriginal(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        vcs.checkpoint(VersionOrigin.INSTALL, "Installed");
        vcs.setRemote(Remote.ORIGINAL, "https://github.com/alice/clicker-bot.git");
        vcs.setRemote(Remote.MINE, "https://github.com/bob/clicker-bot.git");

        vcs.removeRemote(Remote.ORIGINAL);
        assertNull(vcs.remoteUrl(Remote.ORIGINAL));
        assertEquals("https://github.com/bob/clicker-bot.git", vcs.remoteUrl(Remote.MINE));
        assertTrue(BotSource.read(dir).isEmpty());
        assertFalse(ProjectMode.isReader(dir, "carol"), "no provenance: the project's own");
        vcs.removeRemote(Remote.ORIGINAL);
        assertFalse(vcs.hasRemoteBranch(Remote.MINE, "studio/main"), "never pushed");
    }

    @Test
    void aProjectOfYourOwnHasNoProvenance(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        new ProjectVcs(dir).checkpoint(VersionOrigin.SAVE, "mine");
        assertTrue(BotSource.read(dir).isEmpty());
    }

    /** A project made before 2026-10-23 may carry the file; it is read when there is no original. */
    @Test
    void anOlderProjectsFileIsReadWhenThereIsNoOriginal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(BotSource.FILE_NAME),
                "{\"owner\":\"alice\",\"repo\":\"clicker-bot\",\"tag\":\"1.2.0\"}");
        assertEquals(Optional.of(new BotSource("alice", "clicker-bot", "1.2.0")), BotSource.read(dir));

        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.checkpoint(VersionOrigin.SAVE, "files");
        vcs.setRemote(Remote.ORIGINAL, "https://github.com/bob/farmer.git");
        assertEquals("bob/farmer", BotSource.read(dir).orElseThrow().slug(), "the remote wins over the file");
    }
}
