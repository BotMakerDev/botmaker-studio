package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog as Studio reads it: parsing, the legacy fallback, and the release an installed bot moves to. No tier
 * and no launch targets since 2026-10-23: an older catalog's fields for them are ignored.
 */
class GalleryCatalogReadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape the gallery's CatalogBuilder wrote, tier and launch targets included. */
    private static final String CATALOG = """
            {
              "schemaVersion" : 2,
              "bots" : [ {
                "name" : "gamebot", "owner" : "LiQiyeDev", "repo" : "botmaker-gamebot",
                "description" : "A game bot to start from.", "tags" : [ "game", "template" ],
                "launchTargets" : [ "heroic" ],
                "requires" : [ { "id" : "com.botmaker.sdk", "version" : "1.1.6" } ],
                "tier" : "vetted", "vettedVersion" : "v0.1.0", "vettedAt" : "2026-09-16"
              }, {
                "name" : "Update", "owner" : "LiQiyeDev", "repo" : "Update",
                "description" : "", "tags" : [ ], "tier" : "community"
              } ]
            }
            """;

    @Test
    void anOldCatalogsTierTargetsAndRequiresAreIgnored() {
        List<GalleryEntry> bots = GitHubGallery.parseCatalog(MAPPER, CATALOG).orElseThrow();

        assertEquals(List.of(
                new GalleryEntry("gamebot", "LiQiyeDev", "botmaker-gamebot", "A game bot to start from.",
                        List.of("game", "template")),
                new GalleryEntry("Update", "LiQiyeDev", "Update", "", List.of())), bots);
    }

    @Test
    void aNewerSchemaStillReads() {
        String newer = """
                {"schemaVersion": 9, "bots": [
                  {"name": "x", "owner": "a", "repo": "x", "tier": "platinum", "somethingNew": {"k": 1}}
                ]}
                """;
        List<GalleryEntry> bots = GitHubGallery.parseCatalog(MAPPER, newer).orElseThrow();
        assertEquals("x", bots.get(0).name());
    }

    @Test
    void aCatalogThatCannotBeReadAsksForTheFallback() {
        assertTrue(GitHubGallery.parseCatalog(MAPPER, null).isEmpty());
        assertTrue(GitHubGallery.parseCatalog(MAPPER, "").isEmpty());
        assertTrue(GitHubGallery.parseCatalog(MAPPER, "not json").isEmpty());
        assertTrue(GitHubGallery.parseCatalog(MAPPER, "[]").isEmpty());
        assertTrue(GitHubGallery.parseCatalog(MAPPER, "{\"schemaVersion\": 2}").isEmpty());
    }

    @Test
    void theLegacyIndexReads() {
        String index = """
                [ {"name": "base", "owner": "LiQiyeDev", "repo": "botmaker-base", "tags": ["template"]} ]
                """;
        List<GalleryEntry> bots = GitHubGallery.parseLegacyIndex(MAPPER, index);
        assertEquals(List.of(new GalleryEntry("base", "LiQiyeDev", "botmaker-base", "", List.of("template"))), bots);
        assertTrue(GitHubGallery.parseLegacyIndex(MAPPER, "{").isEmpty());
    }

    @Test
    void anUpdateOffersTheNewestReleaseWhenItIsNotTheOneInstalled() {
        assertEquals(Optional.of("v0.5.0"), GalleryEntry.updateTarget("v0.1.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.5.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.1.0", ""));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.1.0", null));
    }

    /** The newest release older than the one installed: a release deleted since, never offered as an update. */
    @Test
    void anUpdateNeverGoesBackwards() {
        assertEquals(Optional.empty(), GalleryEntry.updateTarget("v0.4.0", "v0.3.0"));
    }

    @Test
    void startingPointsListEveryBotByName() {
        GalleryEntry zeta = bot("zeta");
        GalleryEntry alpha = bot("Alpha");
        GalleryEntry aardvark = bot("aardvark");

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
        GalleryEntry e = bot("bot");
        assertEquals(Optional.of(e), GitHubGallery.find(List.of(e), "O", "BOT"));
        assertTrue(GitHubGallery.find(List.of(e), "o", "other").isEmpty());
    }

    private static GalleryEntry bot(String name) {
        return new GalleryEntry(name, "o", name, "", List.of());
    }
}
