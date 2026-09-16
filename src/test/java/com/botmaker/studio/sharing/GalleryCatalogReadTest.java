package com.botmaker.studio.sharing;

import com.botmaker.studio.project.launch.SupportedTargets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tiered catalog as Studio reads it: parsing, the legacy fallback, and the release each tier installs. */
class GalleryCatalogReadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape the gallery's CatalogBuilder writes. */
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
    void catalogCarriesTierVettedReleaseAndRequirements() {
        List<GalleryEntry> bots = GitHubGallery.parseCatalog(MAPPER, CATALOG).orElseThrow();

        assertEquals(2, bots.size());
        GalleryEntry gamebot = bots.get(0);
        assertEquals(GalleryTier.VETTED, gamebot.tier());
        assertEquals("v0.1.0", gamebot.vettedVersion());
        assertEquals(List.of(new GalleryEntry.Requirement("com.botmaker.sdk", "1.1.6")), gamebot.requires());
        assertEquals("com.botmaker.sdk 1.1.6", gamebot.requires().get(0).describe());
        assertTrue(gamebot.launchTargets().declared());
        assertTrue(gamebot.isTemplate());

        GalleryEntry update = bots.get(1);
        assertEquals(GalleryTier.COMMUNITY, update.tier());
        assertEquals("", update.vettedVersion());
        assertTrue(update.requires().isEmpty());
    }

    @Test
    void anUnknownTierReadsAsCommunityAndANewerSchemaStillReads() {
        String newer = """
                {"schemaVersion": 9, "bots": [
                  {"name": "x", "owner": "a", "repo": "x", "tier": "platinum", "somethingNew": {"k": 1}}
                ]}
                """;
        List<GalleryEntry> bots = GitHubGallery.parseCatalog(MAPPER, newer).orElseThrow();
        assertEquals(GalleryTier.COMMUNITY, bots.get(0).tier());
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
    void theLegacyIndexIsAllVetted() {
        String index = """
                [ {"name": "base", "owner": "LiQiyeDev", "repo": "botmaker-base", "tags": ["template"]} ]
                """;
        List<GalleryEntry> bots = GitHubGallery.parseLegacyIndex(MAPPER, index);
        assertEquals(1, bots.size());
        assertEquals(GalleryTier.VETTED, bots.get(0).tier());
        // It names no release, so it installs the newest, which is what every Studio before tiers did.
        assertEquals("v2.0.0", bots.get(0).installTag("v2.0.0"));
        assertTrue(GitHubGallery.parseLegacyIndex(MAPPER, "{").isEmpty());
    }

    @Test
    void anEntryWithNoTierIsCommunity() {
        GalleryEntry legacy = new GalleryEntry("x", "a", "x", "", List.of(), SupportedTargets.any());
        assertEquals(GalleryTier.COMMUNITY, legacy.tier());
        assertFalse(legacy.isVetted());
    }

    @Test
    void aVettedBotInstallsItsVettedReleaseAndACommunityBotTheNewest() {
        GalleryEntry vetted = entry(GalleryTier.VETTED, "v0.1.0");
        GalleryEntry community = entry(GalleryTier.COMMUNITY, "");

        assertEquals("v0.1.0", vetted.installTag("v0.3.0"));
        assertEquals("v0.1.0", vetted.installTag(""));
        assertEquals("v0.3.0", community.installTag("v0.3.0"));
        assertEquals("", community.installTag(null));
    }

    @Test
    void anUpdateOffersTheVettedReleaseAndNeverAnOlderOne() {
        GalleryEntry vetted = entry(GalleryTier.VETTED, "v0.2.0");

        assertEquals(Optional.of("v0.2.0"), GalleryEntry.updateTarget(vetted, "v0.1.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget(vetted, "v0.2.0", "v0.5.0"));
        // Installed a newer release while it was Community, or before a maintainer pinned an older one.
        assertEquals(Optional.empty(), GalleryEntry.updateTarget(vetted, "v0.4.0", "v0.5.0"));
    }

    @Test
    void anUpdateOffersTheNewestForCommunityAndForABotNoLongerListed() {
        GalleryEntry community = entry(GalleryTier.COMMUNITY, "");

        assertEquals(Optional.of("v0.5.0"), GalleryEntry.updateTarget(community, "v0.1.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget(community, "v0.5.0", "v0.5.0"));
        assertEquals(Optional.of("v0.5.0"), GalleryEntry.updateTarget(null, "v0.1.0", "v0.5.0"));
        assertEquals(Optional.empty(), GalleryEntry.updateTarget(null, "v0.1.0", ""));
    }

    @Test
    void templatesListVettedFirstAndCommunityOnlyWhenAsked() {
        GalleryEntry vettedZ = template("zeta", GalleryTier.VETTED);
        GalleryEntry vettedA = template("Alpha", GalleryTier.VETTED);
        GalleryEntry community = template("aardvark", GalleryTier.COMMUNITY);
        GalleryEntry bot = new GalleryEntry("bot", "o", "bot", "", List.of("game"), null, GalleryTier.VETTED,
                null, null);
        List<GalleryEntry> all = List.of(vettedZ, community, bot, vettedA);

        assertEquals(List.of(vettedA, vettedZ), GalleryEntry.templates(all, false));
        assertEquals(List.of(vettedA, vettedZ, community), GalleryEntry.templates(all, true));
    }

    @Test
    void findIgnoresCase() {
        GalleryEntry e = entry(GalleryTier.VETTED, "v1.0.0");
        assertEquals(Optional.of(e), GitHubGallery.find(List.of(e), "OWNER", "BOT"));
        assertTrue(GitHubGallery.find(List.of(e), "owner", "other").isEmpty());
    }

    private static GalleryEntry entry(GalleryTier tier, String vettedVersion) {
        return new GalleryEntry("bot", "owner", "bot", "", List.of(), null, tier, vettedVersion, null);
    }

    private static GalleryEntry template(String name, GalleryTier tier) {
        return new GalleryEntry(name, "o", name, "", List.of("template"), null, tier, null, null);
    }
}
