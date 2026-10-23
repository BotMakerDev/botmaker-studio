package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish flow's rules with no network: the plan's retry, the tags it sets as topics, the catalog pull request
 * it opens for a bot, its removal and its {@code detached} mark, and how the catalog's answer is read.
 */
class PublishFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- PublishPlan -----------------------------------------------------------------------------------------

    @Test
    void aPlanRunsItsStepsInOrderAndFinishes() {
        PublishPlan plan = PublishPlan.start(true);
        for (PublishPlan.Step step : PublishPlan.Step.values()) {
            assertEquals(Optional.of(step), plan.next());
            plan = plan.running(step).done(step, "ok");
        }
        assertTrue(plan.finished());
        assertTrue(plan.next().isEmpty());
    }

    @Test
    void anUnlistedPublishSkipsTheListing() {
        PublishPlan plan = PublishPlan.start(false);
        assertEquals(PublishPlan.Status.SKIPPED, plan.state(PublishPlan.Step.LISTING).status());
        for (PublishPlan.Step step : List.of(PublishPlan.Step.REPO, PublishPlan.Step.PUSH, PublishPlan.Step.RELEASE,
                PublishPlan.Step.ARCHIVE)) {
            plan = plan.done(step, "");
        }
        assertTrue(plan.finished());
    }

    @Test
    void aRetryResumesAtTheFailedStepAndKeepsWhatWasDone() {
        PublishPlan plan = PublishPlan.start(true)
                .done(PublishPlan.Step.REPO, "")
                .done(PublishPlan.Step.PUSH, "")
                .done(PublishPlan.Step.RELEASE, "")
                .failed(PublishPlan.Step.ARCHIVE, "404");

        assertFalse(plan.finished());
        assertEquals("404", plan.failure().orElseThrow().detail());
        assertEquals(Optional.of(PublishPlan.Step.ARCHIVE), plan.next());

        plan = plan.running(PublishPlan.Step.ARCHIVE);
        assertTrue(plan.failure().isEmpty());
        assertEquals(PublishPlan.Status.DONE, plan.state(PublishPlan.Step.RELEASE).status());
        assertTrue(plan.started());
    }

    // --- PublishRequest --------------------------------------------------------------------------------------

    @Test
    void aPropertyNobodyDefinesIsLeftAsWritten() {
        assertEquals("${missing}", PublishRequest.interpolate("${missing}", Map.of()));
    }

    /** A property naming itself stops instead of growing until memory runs out. */
    @Test
    void interpolationStopsOnASelfReference() {
        assertEquals("v1.4.4", PublishRequest.interpolate("v${sdk}", Map.of("sdk", "1.4.4")));
        String cycle = PublishRequest.interpolate("${a}", Map.of("a", "x${a}"));
        assertEquals(PublishRequest.MAX_SUBSTITUTIONS + "${a}".length(), cycle.length(), cycle);
    }

    /** Named by its repository, as the catalog names a bot, with its tags as GitHub would take them. */
    @Test
    void thePreviewIsTheListingBrowseBotsWouldShow() {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala", "d", "1.0.0",
                List.of("Idle Game", "x", "botmaker-bot"), true);
        assertEquals(new GalleryEntry("koala", "alice", "koala", "d", List.of("idle-game", "x")),
                request.preview("alice"));
    }

    /** The catalog lists a bot only when its pom declares a catalog plugin (its rule 6), so Publish waits. */
    @Test
    void aBotWithNoCatalogPluginIsNotPublishable() {
        PluginRegistry.Plugin sdk = new PluginRegistry.Plugin("com.botmaker.sdk", "BotMaker SDK",
                "com.github.BotMakerDev:botmaker-sdk", "BotMakerDev/botmaker-sdk", "", List.of(), "", List.of(),
                "v1.5.0");
        List<UserLibrary> declared = List.of(new UserLibrary("com.github.botmakerdev", "botmaker-sdk", "v1.5.0"),
                new UserLibrary("org.example", "lib", "1.0"));
        assertEquals(List.of("com.github.botmakerdev:botmaker-sdk"),
                PublishRequest.catalogPlugins(declared, List.of(sdk)), "a coordinate ignores case, as JitPack does");
        assertTrue(PublishRequest.catalogPlugins(declared.subList(1, 2), List.of(sdk)).isEmpty());
    }

    // --- Tags as topics --------------------------------------------------------------------------------------

    @Test
    void publishingAddsTheTagsAndKeepsTheAuthorsTopics() {
        assertEquals(List.of("game", "farming", "idle-game"),
                BotPublisher.topics(List.of("game"), List.of("Farming", "idle_game", "game")));
        assertEquals(List.of("game"), BotPublisher.topics(List.of("game"), List.of("botmaker-detached")),
                "no tag spelt like Studio's own");
    }

    @Test
    void aTagIsMadeATopicGitHubAccepts() {
        assertEquals("idle-game", BotPublisher.asTopic("  Idle _ Game!! "));
        assertEquals("c-bot", BotPublisher.asTopic("C# bot"));
        assertEquals("", BotPublisher.asTopic("!!!"));
        assertEquals(50, BotPublisher.asTopic("a".repeat(80)).length());
    }

    @Test
    void neverMoreTopicsThanGitHubTakes() {
        List<String> many = java.util.stream.IntStream.range(0, 30).mapToObj(i -> "t" + i).toList();
        assertEquals(BotPublisher.MAX_TOPICS, BotPublisher.topics(List.of(), many).size());
        assertEquals(many.subList(0, 20), BotPublisher.topics(many.subList(0, 20), List.of("new")),
                "twenty already: the author's stay");
    }

    // --- The catalog's entry (2026-10-23) --------------------------------------------------------------------

    @Test
    void aBotsEntryIsTheFileTheCliWrites() throws Exception {
        CatalogListing.BotEntry entry = new CatalogListing.BotEntry("Alice/koala-bot", "1.2.0", false);
        assertEquals("bots/Alice__koala-bot.json", entry.path());
        assertEquals("entry/bots/Alice__koala-bot.json", CatalogListing.branch(entry.path()));
        assertEquals("{\n  \"repo\" : \"Alice/koala-bot\",\n  \"tag\" : \"1.2.0\"\n}\n", entry.json(MAPPER)
                .replace(System.lineSeparator(), "\n"), "detached only when true");
        assertEquals("{\"repo\":\"Alice/koala-bot\",\"tag\":\"1.2.0\",\"detached\":true}",
                MAPPER.readTree(new CatalogListing.BotEntry("Alice/koala-bot", "1.2.0", true).json(MAPPER))
                        .toString());
    }

    @Test
    void aContentsFileReadsBackAsItsEntry() throws Exception {
        CatalogListing.BotEntry entry = new CatalogListing.BotEntry("a/b", "v1", true);
        assertEquals(Optional.of(entry), CatalogListing.BotEntry.of(MAPPER, contents(entry.json(MAPPER))));
        assertEquals(Optional.empty(), CatalogListing.BotEntry.of(MAPPER, null), "not listed");
        assertEquals(Optional.empty(), CatalogListing.BotEntry.of(MAPPER, contents("{\"repo\":\"a/b\"}")),
                "no tag");
        assertEquals(Optional.empty(), CatalogListing.BotEntry.of(MAPPER, MAPPER.readTree("{\"content\":\"%%\"}")));
    }

    /** A first publish adds, a re-publish updates, and the same entry opens nothing. */
    @Test
    void publishingABotOpensOnePullRequestForItsEntry() throws Exception {
        CatalogListing.BotEntry v1 = new CatalogListing.BotEntry("alice/koala", "1.0.0", false);
        CatalogListing.Change add = CatalogListing.Change.put(MAPPER, v1, Optional.empty()).orElseThrow();
        assertEquals("bots/alice__koala.json", add.path());
        assertEquals("entry/bots/alice__koala.json", add.branch());
        assertEquals("bot: add alice/koala 1.0.0", add.title());
        assertEquals(v1.json(MAPPER), add.content().orElseThrow());

        CatalogListing.BotEntry v2 = new CatalogListing.BotEntry("alice/koala", "1.1.0", false);
        assertEquals("bot: update alice/koala 1.1.0",
                CatalogListing.Change.put(MAPPER, v2, Optional.of(v1)).orElseThrow().title());
        assertTrue(CatalogListing.Change.put(MAPPER, v1, Optional.of(v1)).isEmpty());
    }

    @Test
    void unpublishingRemovesTheEntryAndNothingWhenUnlisted() {
        CatalogListing.Change removal = CatalogListing.Change.remove("alice/koala",
                Optional.of(new CatalogListing.BotEntry("alice/koala", "1.0.0", false))).orElseThrow();
        assertEquals("bots/alice__koala.json", removal.path());
        assertTrue(removal.content().isEmpty());
        assertEquals("bot: remove alice/koala", removal.title());
        assertTrue(CatalogListing.Change.remove("alice/koala", Optional.empty()).isEmpty());
    }

    /** Stop following marks the listed entry at its own tag; following again takes the mark off. */
    @Test
    void stopFollowingMarksTheEntryDetached() throws Exception {
        CatalogListing.BotEntry listed = new CatalogListing.BotEntry("bob/fork", "2.0.0", false);
        CatalogListing.BotEntry detached = new CatalogListing.BotEntry(listed.repo(), listed.tag(), true);
        CatalogListing.Change mark = CatalogListing.Change.put(MAPPER, detached, Optional.of(listed)).orElseThrow();
        assertEquals("bot: update bob/fork 2.0.0", mark.title());
        assertTrue(MAPPER.readTree(mark.content().orElseThrow()).path("detached").asBoolean());
        assertFalse(MAPPER.readTree(CatalogListing.Change.put(MAPPER, listed, Optional.of(detached)).orElseThrow()
                .content().orElseThrow()).has("detached"));
    }

    /** Rules 3 and 5, before anything is opened. */
    @Test
    void aReleaseTheCatalogWouldRefuseIsRefusedFirst() throws Exception {
        assertEquals(Optional.empty(), CatalogListing.refusal(release("alice", false, false), "Alice", "alice"));
        assertTrue(CatalogListing.refusal(release("alice", true, false), "alice", "alice").isPresent(), "a draft");
        assertTrue(CatalogListing.refusal(release("alice", false, true), "alice", "alice").isPresent(),
                "a prerelease");
        assertTrue(CatalogListing.refusal(release("carol", false, false), "alice", "org").isPresent(),
                "another member's release");
        assertEquals(Optional.empty(), CatalogListing.refusal(release("github-actions[bot]", false, false),
                "alice", "alice"), "an Action's release, published by the owner");
        assertTrue(CatalogListing.refusal(release("github-actions[bot]", false, false), "alice", "SomeOrg")
                .orElseThrow().contains("an Action's"));
        assertTrue(CatalogListing.refusal(null, "alice", "alice").isPresent(), "no release");
    }

    // --- What the catalog says ------------------------------------------------------------------------------

    @Test
    void aPullRequestReadsAsTheCatalogsAnswer() throws Exception {
        JsonNode open = MAPPER.readTree("{\"state\":\"open\",\"html_url\":\"u\",\"labels\":[]}");
        assertEquals(ListingStatus.State.OPEN, ListingStatus.ofPullRequest(open, "", "", "").state());
        assertEquals(ListingStatus.State.OPEN, ListingStatus.ofPullRequest(open, "success", "", "").state(),
                "passed, not merged yet");
        ListingStatus refused = ListingStatus.ofPullRequest(open, "failure", "", "rule 6: no plugin");
        assertEquals(ListingStatus.State.REFUSED, refused.state());
        assertTrue(refused.describe().endsWith("rule 6: no plugin"));
        assertTrue(ListingStatus.ofPullRequest(open, "failure", "", "").describe().endsWith("says why: u"));
        assertEquals(ListingStatus.State.OPEN, ListingStatus.ofPullRequest(open, "cancelled", "", "").state(),
                "a cancelled run is no answer");

        JsonNode waiting = MAPPER.readTree("{\"state\":\"open\",\"labels\":[{\"name\":\"waiting\"}]}");
        ListingStatus held = ListingStatus.ofPullRequest(waiting, "success",
                ListingStatus.MARKER + "\nThis pull request passes the catalog's check. Merges after 18:00.", "");
        assertEquals(ListingStatus.State.WAITING, held.state());
        assertEquals("This pull request passes the catalog's check. Merges after 18:00.", held.reason());

        assertEquals(ListingStatus.State.LISTED, ListingStatus.ofPullRequest(MAPPER.readTree(
                "{\"state\":\"closed\",\"merged_at\":\"2026-10-23T18:00:00Z\"}"), "", "", "").state());
        assertEquals(ListingStatus.State.CLOSED, ListingStatus.ofPullRequest(MAPPER.readTree(
                "{\"state\":\"closed\",\"merged_at\":null}"), "", "", "").state());
        assertEquals("", ListingStatus.reasonOf("someone else's comment"));
    }

    @Test
    void aRefusalsWordsComeFromItsAnnotations() throws Exception {
        assertEquals("bots/a__b.json: no plugin.", CatalogListing.refusalOf(MAPPER.readTree(
                "[{\"message\":\"bots/a__b.json: no plugin.\"},{\"message\":\"Process completed with exit code 1.\"}]")));
        assertEquals("", CatalogListing.refusalOf(MAPPER.readTree(
                "[{\"message\":\"Process completed with exit code 1.\"}]")));
        assertEquals("", CatalogListing.refusalOf(null));
    }

    @Test
    void aRepositoryReadsAsItsListing() throws Exception {
        ListingStatus listed = ListingStatus.of(ListingStatus.State.LISTED);
        BotPublisher.Listing listing = BotPublisher.Listing.of(MAPPER.readTree(
                "{\"description\":\"Mines\",\"topics\":[\"botmaker-bot\",\"mining\",\"botmaker-detached\"]}"), listed);
        assertEquals(new BotPublisher.Listing(listed, "Mines", List.of("mining")), listing);
        assertTrue(listing.listed());
        assertEquals(BotPublisher.Listing.NONE, BotPublisher.Listing.of(null, listed), "no repository yet");
        assertFalse(BotPublisher.Listing.NONE.listed());
    }

    // --- Pins and owners -------------------------------------------------------------------------------------

    /** A bot pinned to a build only this computer has would compile here and nowhere else, so Publish waits. */
    @Test
    void aSnapshotOrAnUndefinedPropertyIsAnUnreleasedPin() {
        List<UserLibrary> declared = List.of(
                new UserLibrary("com.github.BotMakerDev", "botmaker-sdk", "${botmaker.sdk.version}"),
                new UserLibrary("com.github.BotMakerDev", "botmaker-studio-api", "0.0.0-SNAPSHOT"),
                new UserLibrary("org.example", "mine", "${nobody.defines.this}"),
                new UserLibrary("org.junit.jupiter", "junit-jupiter", "5.9.3"),
                new UserLibrary("org.example", "managed", ""));

        assertEquals(List.of("com.github.BotMakerDev:botmaker-studio-api:0.0.0-SNAPSHOT",
                        "org.example:mine:${nobody.defines.this}"),
                PublishRequest.unreleasedPins(declared, Map.of("botmaker.sdk.version", "1.2.3")));
        assertEquals(List.of("com.github.BotMakerDev:botmaker-sdk:1.2-snapshot"),
                PublishRequest.unreleasedPins(declared.subList(0, 1), Map.of("botmaker.sdk.version", "1.2-snapshot")));
    }

    /** An organization's template publishes into the organization's repository, when the account can push there. */
    @Test
    void aPublishGoesToTheCopysOwnerOnlyWhenTheAccountCanPushThere() {
        var org = com.botmaker.studio.project.vcs.SyncModel.slug("https://github.com/BotMakerDev/botmaker-gamebot");
        assertEquals("BotMakerDev", BotPublisher.ownerFor("LiQiyeDev", org, true));
        assertEquals("LiQiyeDev", BotPublisher.ownerFor("LiQiyeDev", org, false));
        assertEquals("alice", BotPublisher.ownerFor("alice", Optional.empty(), true), "no copy yet: your own");
        assertEquals("alice", BotPublisher.ownerFor("alice",
                com.botmaker.studio.project.vcs.SyncModel.slug("https://github.com/Alice/koala"), false),
                "your own copy, whatever its case");
    }

    private static JsonNode contents(String text) {
        String encoded = Base64.getMimeEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return MAPPER.createObjectNode().put("content", encoded);
    }

    private static JsonNode release(String author, boolean draft, boolean prerelease) throws Exception {
        return MAPPER.readTree("{\"tag_name\":\"1.0.0\",\"draft\":" + draft + ",\"prerelease\":" + prerelease
                + ",\"author\":{\"login\":\"" + author + "\"}}");
    }
}
