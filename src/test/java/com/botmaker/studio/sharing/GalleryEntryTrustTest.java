package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What starting from a bot says first, by rank, and a bot trusted no further than its plugins (2026-10-23). */
class GalleryEntryTrustTest {

    private static final String CATALOG = """
            {"plugins": [{"id": "sdk", "repo": "BotMakerDev/botmaker-sdk", "rank": "admin"},
                         {"id": "farm", "repo": "bob/farm", "rank": "community"}],
             "bots": [{"repo": "BotMakerDev/official", "rank": "admin", "plugins": [{"id": "sdk"}]},
                      {"repo": "carol/vetted", "rank": "trusted", "plugins": [{"id": "sdk"}]},
                      {"repo": "carol/farmer", "rank": "trusted", "plugins": [{"id": "sdk"}, {"id": "farm"}]},
                      {"repo": "carol/unlisted", "rank": "trusted", "plugins": [{"id": "gone"}]},
                      {"repo": "carol/bare", "rank": "trusted"},
                      {"repo": "eve/anyone", "rank": "community", "plugins": [{"id": "sdk"}]}]}""";

    private static GalleryEntry bot(String slug) {
        return CatalogIndex.parse(CATALOG).bots().stream().filter(b -> b.slug().equals(slug)).findFirst()
                .orElseThrow();
    }

    @Test
    void anOfficialBotSaysNothingAndIsNotConfirmed() {
        GalleryEntry official = bot("BotMakerDev/official");
        assertEquals(PublisherRank.ADMIN, official.trust());
        assertEquals("", official.trustWarning());
        assertFalse(official.asksFirst());
    }

    @Test
    void aTrustedBotNamesItsPublisher() {
        GalleryEntry vetted = bot("carol/vetted");
        assertEquals("By carol, a vetted developer.", vetted.trustWarning());
        assertFalse(vetted.asksFirst());
        assertEquals(PublisherRank.TRUSTED, bot("carol/bare").trust(), "a bot with no plugin is its publisher's");
    }

    @Test
    void aCommunityBotIsWarnedAboutAndConfirmed() {
        GalleryEntry anyone = bot("eve/anyone");
        assertTrue(anyone.trustWarning().contains("nobody reviewed its code"), anyone.trustWarning());
        assertTrue(anyone.trustWarning().contains("by eve"), anyone.trustWarning());
        assertTrue(anyone.asksFirst());
    }

    /** A bot runs its plugins' code too: one community plugin, or one no row lists, makes the bot community. */
    @Test
    void aBotUsingACommunityPluginIsCommunity() {
        GalleryEntry farmer = bot("carol/farmer");
        assertEquals(PublisherRank.COMMUNITY, farmer.trust());
        assertTrue(farmer.trustWarning().contains("uses a community plugin"), farmer.trustWarning());
        assertTrue(farmer.asksFirst());
        assertEquals(PublisherRank.COMMUNITY, bot("carol/unlisted").trust());
    }

    @Test
    void botsAreRankedThenStarredThenNamed() {
        GalleryEntry a = new GalleryEntry("a", "x", "a", "", List.of(), "", "v1", PublisherRank.COMMUNITY, 9,
                List.of(), PublisherRank.ADMIN);
        GalleryEntry b = new GalleryEntry("b", "x", "b", "", List.of(), "", "v1", PublisherRank.TRUSTED, 1,
                List.of(), PublisherRank.ADMIN);
        GalleryEntry c = new GalleryEntry("c", "x", "c", "", List.of(), "", "v1", PublisherRank.TRUSTED, 5,
                List.of(), PublisherRank.ADMIN);
        GalleryEntry d = new GalleryEntry("d", "x", "d", "", List.of(), "", "v1", PublisherRank.ADMIN, 0,
                List.of(), PublisherRank.ADMIN);
        assertEquals(List.of(d, c, b, a), GalleryEntry.startingPoints(List.of(a, b, c, d)));
    }
}
