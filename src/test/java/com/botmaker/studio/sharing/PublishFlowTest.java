package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.ApprovedRelease;
import com.botmaker.cli.catalog.BotEntry;
import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.Entry;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Checks;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.PublishHost;
import com.botmaker.cli.publish.PublishTarget;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        for (PublishPlan.Step step : List.of(PublishPlan.Step.REPO, PublishPlan.Step.CHECK, PublishPlan.Step.PUSH,
                PublishPlan.Step.RELEASE, PublishPlan.Step.ARCHIVE)) {
            plan = plan.done(step, "");
        }
        assertTrue(plan.finished());
    }

    @Test
    void aRetryResumesAtTheFailedStepAndKeepsWhatWasDone() {
        PublishPlan plan = PublishPlan.start(true)
                .done(PublishPlan.Step.REPO, "")
                .done(PublishPlan.Step.CHECK, "")
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
        PluginRegistry.Plugin sdk = new PluginRegistry.Plugin("BotMaker SDK",
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

    /**
     * The pull request is the cli's change (2026-10-24), on the branch Studio reads the catalog's answer from: an
     * entry with the release appended, and Stop following marking the same releases detached.
     */
    @Test
    void aBotsPullRequestIsTheClisChangeOnTheBranchStudioReads() throws Exception {
        BotEntry v1 = new BotEntry("Alice/koala-bot", List.of(ApprovedRelease.ofBot("1.0.0", "a".repeat(40))), false);
        CatalogPr.Change add = CatalogPr.Change.put(v1, BotPublisher.COMMAND);
        assertEquals("bots/Alice__koala-bot.json", add.path().path());
        assertEquals(CatalogListing.path("Alice/koala-bot"), add.path().path());
        assertEquals(CatalogListing.branch(CatalogListing.path("Alice/koala-bot")), add.branch());
        assertEquals("bot: Alice/koala-bot 1.0.0", add.title());

        BotEntry v2 = (BotEntry) v1.appended(ApprovedRelease.ofBot("1.1.0", "b".repeat(40)));
        JsonNode written = MAPPER.readTree(CatalogPr.Change.put(v2, BotPublisher.COMMAND).content().orElseThrow());
        assertEquals(2, written.path("releases").size(), "every approved release kept");
        assertEquals("1.1.0", written.path("releases").get(1).path("tag").asText(), "appended at the end");
        assertFalse(written.has("detached"));

        BotEntry detached = new BotEntry(v2.repo(), v2.releases(), true);
        assertTrue(MAPPER.readTree(CatalogPr.Change.put(detached, BotPublisher.COMMAND).content().orElseThrow())
                .path("detached").asBoolean());
    }

    @Test
    void aContentsFileReadsBackAsItsText() {
        assertEquals(Optional.of("{\"repo\":\"a/b\"}\n"), CatalogListing.text(Optional.of(contents("{\"repo\":\"a/b\"}\n"))));
        assertEquals(Optional.empty(), CatalogListing.text(Optional.empty()), "not listed");
    }

    /**
     * A fork's host leaves its author's releases out of {@code HEAD}'s tags, so the library never takes the author's
     * tag for the fork's release; its own, marked, count.
     */
    @Test
    void aForksHostLeavesTheAuthorsReleasesOutOfHead(@TempDir Path dir) throws Exception {
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        vcs.checkpoint(VersionOrigin.INSTALL, "Installed v1.0.0");
        vcs.tagHead("v1.0.0", "Release v1.0.0");
        PublishRequest request = new PublishRequest(dir, "Bot", "bot", "", "v1.0.1", List.of(), true);
        StudioPublishHost fork = new StudioPublishHost(null, "", new PublishHost.Account("bob", 2), vcs, request,
                "bob/bot", true, "main", step -> { });
        StudioPublishHost own = new StudioPublishHost(null, "", new PublishHost.Account("alice", 1), vcs, request,
                "alice/bot", false, "main", step -> { });

        assertEquals(List.of(), fork.tagsAtHead(), "the author's release is no release of the fork's");
        assertEquals(List.of("v1.0.0"), own.tagsAtHead());
        assertEquals(List.of("v1.0.0"), fork.tagsMerged(), "but a bump goes past it");
        assertEquals(vcs.headCommit(), fork.tagCommit("v1.0.0"));
        assertEquals(Optional.of("v1.0.0"), fork.nearestTag());
        assertTrue(fork.clean());

        vcs.markOwnRelease("v1.0.0");
        assertEquals(List.of("v1.0.0"), fork.tagsAtHead());
    }

    /** Rule 6 refuses before anything is tagged, and the sheet's row says it was the check. */
    @Test
    void theSavedVersionMeetsTheCatalogsRulesBeforeAnyTag(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId>"
                + "<artifactId>b</artifactId><version>1</version></project>");
        List<PublishPlan.Step> rows = new ArrayList<>();
        CatalogPr.Reader empty = new CatalogPr.Reader() {
            @Override
            public Optional<Entry> listed(EntryPath path) {
                return Optional.empty();
            }

            @Override
            public Optional<Catalog> catalog() {
                return Optional.empty();
            }
        };
        Checks.Result result = new StudioChecks(empty, rows::add).folder(
                new PublishTarget(dir, EntryPath.Kind.BOT, "alice/b", false, false), QUIET);
        assertTrue(result.refusal().orElseThrow().contains("declares no plugin the catalog lists"),
                result.refusal().orElseThrow());
        assertEquals(List.of(PublishPlan.Step.CHECK), rows);
        assertTrue(new StudioChecks(empty, rows::add).published("a:b:v1", QUIET).refusal().isPresent(),
                "Studio publishes bots only");
    }

    private static final Listener QUIET = new Listener() {
        @Override
        public void step(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void checks(List<CheckResult> results) {}
    };

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

}
