package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Published bots as Studio lists them since 2026-10-23: the catalog's rows ({@link CatalogIndexTest} reads them),
 * this computer's hidden list, and the release an installed bot moves to — the listed one.
 */
class CatalogBotsTest {

    /** A fork reads "based on" its parent; the catalog leaves {@code forkOf} out for a detached one. */
    @Test
    void aForkReadsBasedOnItsParent() {
        GalleryEntry fork = new GalleryEntry("b", "bob", "b", "", List.of()).withBasedOn("alice/a");
        assertEquals("based on alice/a", fork.basedOnLine());
        assertEquals("", fork.withBasedOn("").basedOnLine());
        GalleryEntry detached = CatalogIndex.parse("{\"bots\":[{\"repo\":\"carol/c\",\"tag\":\"v1\"}]}").bots().getFirst();
        assertEquals("", detached.basedOnLine());
    }

    @Test
    void aHiddenBotIsLeftOutOnThisComputerOnly() {
        GalleryEntry farm = bot("alice", "farm-bot");
        GalleryEntry mine = bot("bob", "mine-bot");

        HiddenBots hidden = HiddenBots.of("").with(farm, true);
        assertEquals(List.of(mine), hidden.without(List.of(farm, mine)));
        assertTrue(hidden.hides(bot("ALICE", "Farm-Bot")), "GitHub compares without case");
        assertEquals(List.of(farm, mine), hidden.with(farm, false).without(List.of(farm, mine)), "unhidden");
    }

    @Test
    void theHiddenListReadsBackAsWritten() {
        HiddenBots hidden = HiddenBots.of("alice/farm-bot\n\nnot a slug\nBob/Mine-Bot\n");
        assertEquals(Set.of("alice/farm-bot", "bob/mine-bot"), hidden.slugs());
        assertTrue(HiddenBots.of(null).slugs().isEmpty());
    }

    @Test
    void anUpdateOffersTheListedReleaseWhenItIsNotTheOneInstalled() {
        assertEquals(Optional.of("v0.5.0"), GalleryEntry.updateTarget("v0.1.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.5.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.1.0", ""), "not listed: no update");
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.1.0", null));
    }

    /** A listed release older than the one installed is never offered as an update. */
    @Test
    void anUpdateNeverGoesBackwards() {
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.4.0", "v0.3.0"));
    }

    @Test
    void startingPointsListEveryBotByName() {
        GalleryEntry zeta = bot("o", "zeta");
        GalleryEntry alpha = bot("o", "Alpha");
        GalleryEntry aardvark = bot("o", "aardvark");

        assertEquals(List.of(aardvark, alpha, zeta), GalleryEntry.startingPoints(List.of(zeta, aardvark, alpha)));
    }

    @Test
    void theTrustWarningNamesTheBotAndItsAuthor() {
        String warning = new GalleryEntry("Farm", "alice", "farm", "", List.of()).trustWarning();
        assertTrue(warning.contains("Farm") && warning.contains("alice") && warning.contains("nobody reviewed"),
                warning);
    }

    @Test
    void findIgnoresCase() {
        GalleryEntry e = bot("o", "bot");
        assertEquals(Optional.of(e), GitHubGallery.find(List.of(e), "O", "BOT"));
        assertTrue(GitHubGallery.find(List.of(e), "o", "other").isEmpty());
    }

    private static GalleryEntry bot(String owner, String name) {
        return new GalleryEntry(name, owner, name, "", List.of());
    }
}
