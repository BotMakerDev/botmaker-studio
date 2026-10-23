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

/** The publish flow's rules with no network: the plan's retry, the entry it writes, and how a listing is read. */
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
    void theEntryIsSchemaTwoInTheCliOrder() throws Exception {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala-bot", " Mines ", "1.0.0",
                List.of("mining"), true);

        String json = new String(BotPublisher.entryJson(MAPPER, request.entry("alice")), StandardCharsets.UTF_8);
        assertEquals(List.of("schemaVersion", "name", "owner", "repo", "description", "tags"),
                fieldNames(MAPPER.readTree(json)), "no requires, no launch targets");
        JsonNode node = MAPPER.readTree(json);
        assertEquals(2, node.get("schemaVersion").asInt());
        assertEquals("Mines", node.get("description").asText());
    }

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

    @Test
    void thePreviewIsTheListingTheGalleryWouldShow() {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala", "d", "1.0.0", List.of("x"),
                true);
        assertEquals(new GalleryEntry("Koala", "alice", "koala", "d", List.of("x")), request.preview("alice"));
    }

    // --- ListingStatus ---------------------------------------------------------------------------------------

    @Test
    void aListingPullRequestReadsAsItsState() throws Exception {
        assertEquals(ListingStatus.State.LISTED,
                status("{\"state\":\"closed\",\"merged_at\":\"2026-09-16T10:00:00Z\"}", "", "").state());
        assertEquals(ListingStatus.State.CLOSED,
                status("{\"state\":\"closed\",\"merged_at\":null}", "", "").state());
        assertEquals(ListingStatus.State.REFUSED, status("{\"state\":\"open\"}", "failure", "").state());
        assertEquals(ListingStatus.State.CHECKING, status("{\"state\":\"open\"}", "", "").state());
        assertEquals(ListingStatus.State.CHECKING, status("{\"state\":\"open\"}", "success", "").state());

        ListingStatus waiting = status("{\"state\":\"open\",\"html_url\":\"https://x/1\","
                + "\"labels\":[{\"name\":\"waiting\"}]}", "success",
                ListingStatus.MARKER + "\nThis pull request passes the gallery's checks. It merges after 12:00.");
        assertEquals(ListingStatus.State.WAITING, waiting.state());
        assertEquals("https://x/1", waiting.url());
        assertTrue(waiting.describe().endsWith("It merges after 12:00."), waiting.describe());

        assertEquals(ListingStatus.State.NEEDS_MAINTAINER, status("{\"state\":\"open\","
                + "\"labels\":[{\"name\":\"needs-maintainer\"}]}", "success", "").state());
    }

    @Test
    void aCommentThatIsNotTheGallerysIsNoReason() {
        assertEquals("", ListingStatus.reasonOf("LGTM"));
        assertEquals("", ListingStatus.reasonOf(null));
    }

    @Test
    void anEntryIdenticalToTheGallerysIsNotResubmitted() throws Exception {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala", "", "1.0.0", List.of("a"),
                true);
        Map<String, Object> entry = request.entry("alice");
        // The Contents API wraps base64 at 60 columns; the comparison must not care.
        String encoded = Base64.getMimeEncoder().encodeToString(BotPublisher.entryJson(MAPPER, entry));
        JsonNode contents = MAPPER.createObjectNode().put("content", encoded);

        assertTrue(BotPublisher.sameEntry(MAPPER, contents, entry));
        Map<String, Object> changed = new PublishRequest(Path.of("."), "Koala", "koala", "new", "1.0.0",
                List.of("a"), true).entry("alice");
        assertFalse(BotPublisher.sameEntry(MAPPER, contents, changed));
    }

    @Test
    void eachBotHasItsOwnListingBranch() {
        assertEquals("listing/koala", BotPublisher.listingBranch("koala"));
    }

    private static ListingStatus status(String pr, String conclusion, String comment) throws Exception {
        return ListingStatus.ofPullRequest(MAPPER.readTree(pr), conclusion, comment);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
