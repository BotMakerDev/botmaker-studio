package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Published bots as Studio finds them since 2026-10-23: a topic search on GitHub, a result listed only when its
 * release is a bot, this computer's hidden list, and the release an installed bot moves to. No gallery catalog.
 */
class BotSearchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape of GitHub's {@code /search/repositories} answer, trimmed to what is read. */
    private static final String SEARCH = """
            {"total_count": 3, "items": [
              {"name": "farm-bot", "owner": {"login": "alice"}, "description": "Farms.",
               "topics": ["botmaker-bot", "idle", "botmaker-detached"], "stargazers_count": 12,
               "pushed_at": "2026-10-20T08:00:00Z", "fork": false},
              {"name": "farm-bot", "owner": {"login": "bob"}, "description": null, "topics": ["botmaker-bot"],
               "fork": true},
              {"name": "", "owner": {"login": "nobody"}}
            ]}
            """;

    private static final List<PluginRegistry.Plugin> REGISTRY = List.of(new PluginRegistry.Plugin("com.botmaker.sdk",
            "SDK", "com.github.BotMakerDev:botmaker-sdk", "", "", List.of(), "", List.of(), ""));

    @Test
    void aSearchResultIsAListingNamedByItsRepository() throws Exception {
        List<GitHubGallery.Found> found = GitHubGallery.parseSearch(MAPPER.readTree(SEARCH));

        assertEquals(List.of(
                new GalleryEntry("farm-bot", "alice", "farm-bot", "Farms.", List.of("idle")),
                new GalleryEntry("farm-bot", "bob", "farm-bot", "", List.of())),
                found.stream().map(GitHubGallery.Found::entry).toList(), "Studio's own topics are not tags");
        assertEquals(new GitHubGallery.RepoMeta(12, 1792483200L), found.getFirst().meta());
    }

    @Test
    void noAnswerIsNoListing() throws Exception {
        assertTrue(GitHubGallery.parseSearch(null).isEmpty());
        assertTrue(GitHubGallery.parseSearch(MAPPER.readTree("{\"message\": \"API rate limit exceeded\"}")).isEmpty());
    }

    /** Anyone can put the topic on any repository: only a pom that declares a registry plugin is a bot. */
    @Test
    void onlyAReleaseThatDeclaresARegistryPluginIsListed() {
        String bot = pom("<dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-sdk</artifactId>"
                + "<version>${botmaker.sdk.version}</version></dependency>");
        String other = pom("<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>"
                + "<version>5.9.3</version></dependency>");

        assertTrue(GitHubGallery.isBotPom(bot, REGISTRY));
        assertFalse(GitHubGallery.isBotPom(other, REGISTRY), "a Maven project that is not a bot");
        assertFalse(GitHubGallery.isBotPom("", REGISTRY), "no release, so no pom");
        assertFalse(GitHubGallery.isBotPom("not xml", REGISTRY));
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

    private static String pom(String dependency) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version><dependencies>" + dependency + "</dependencies></project>";
    }
}
