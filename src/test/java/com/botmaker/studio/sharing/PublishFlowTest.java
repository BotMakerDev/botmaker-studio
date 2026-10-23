package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The publish flow's rules with no network: the plan's retry, the topics it sets, and how a listing is read. */
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

    /** Named by its repository, as the topic search finds it, with its tags as GitHub would take them. */
    @Test
    void thePreviewIsTheListingBrowseBotsWouldShow() {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala", "d", "1.0.0",
                List.of("Idle Game", "x"), true);
        assertEquals(new GalleryEntry("koala", "alice", "koala", "d", List.of("idle-game", "x")),
                request.preview("alice"));
    }

    // --- The listing: topics (2026-10-23) --------------------------------------------------------------------

    @Test
    void publishingAddsTheTopicAndTheTagsAndKeepsTheAuthorsTopics() {
        assertEquals(List.of("botmaker-bot", "game", "farming", "idle-game"),
                BotPublisher.topics(List.of("game"), List.of("Farming", "idle_game", "game"), true));
        assertEquals(List.of("botmaker-bot"), BotPublisher.topics(List.of("botmaker-bot"), List.of(), true),
                "a re-publish adds nothing twice");
    }

    @Test
    void unpublishingTakesOnlyTheTopicOff() {
        assertEquals(List.of("game", "botmaker-detached"),
                BotPublisher.topics(List.of("game", "botmaker-bot", "botmaker-detached"), List.of("x"), false));
    }

    @Test
    void aTagIsMadeATopicGitHubAccepts() {
        assertEquals("idle-game", BotPublisher.asTopic("  Idle _ Game!! "));
        assertEquals("c-bot", BotPublisher.asTopic("C# bot"));
        assertEquals("", BotPublisher.asTopic("!!!"));
        assertEquals(50, BotPublisher.asTopic("a".repeat(80)).length());
        assertEquals(List.of("botmaker-bot"), BotPublisher.topics(List.of(), List.of("botmaker-detached"), true),
                "an author cannot detach by tag");
    }

    @Test
    void neverMoreTopicsThanGitHubTakes() {
        List<String> many = java.util.stream.IntStream.range(0, 30).mapToObj(i -> "t" + i).toList();
        List<String> topics = BotPublisher.topics(List.of(), many, true);
        assertEquals(BotPublisher.MAX_TOPICS, topics.size());
        assertEquals("botmaker-bot", topics.getFirst());

        List<String> full = BotPublisher.topics(many.subList(0, 20), List.of(), true);
        assertEquals(BotPublisher.MAX_TOPICS, full.size(), "twenty already: the last makes room");
        assertEquals(List.of("botmaker-bot", "t0"), full.subList(0, 2));
    }

    @Test
    void aRepositoryReadsAsItsListing() throws Exception {
        BotPublisher.Listing listing = BotPublisher.Listing.of(MAPPER.readTree(
                "{\"description\":\"Mines\",\"topics\":[\"botmaker-bot\",\"mining\",\"botmaker-detached\"]}"));
        assertEquals(new BotPublisher.Listing(true, "Mines", List.of("mining")), listing);
        assertFalse(BotPublisher.Listing.of(MAPPER.readTree("{\"topics\":[\"mining\"]}")).listed());
        assertEquals(BotPublisher.Listing.NONE, BotPublisher.Listing.of(null), "no repository yet");
    }

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
}
