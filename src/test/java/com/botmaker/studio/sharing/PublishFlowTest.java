package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.launch.SupportedTargets;
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
                List.of("mining", "template"), SupportedTargets.fromIds(List.of("heroic")),
                List.of(new GalleryEntry.Requirement("com.botmaker.sdk", "1.1.6"),
                        new GalleryEntry.Requirement("com.example.x", "")),
                true);

        String json = new String(BotPublisher.entryJson(MAPPER, request.entry("alice")), StandardCharsets.UTF_8);
        assertEquals(List.of("schemaVersion", "name", "owner", "repo", "description", "tags", "launchTargets",
                "requires"), fieldNames(MAPPER.readTree(json)));
        JsonNode node = MAPPER.readTree(json);
        assertEquals(2, node.get("schemaVersion").asInt());
        assertEquals("Mines", node.get("description").asText());
        assertEquals("heroic", node.get("launchTargets").get(0).asText());
        assertEquals("1.1.6", node.get("requires").get(0).get("version").asText());
        assertFalse(node.get("requires").get(1).has("version"), "a blank version is left out, not written empty");
        assertTrue(request.isTemplate());
    }

    @Test
    void undeclaredTargetsAndNoRequirementsAreLeftOut() {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala", "", "1.0.0", List.of(),
                SupportedTargets.any(), List.of(), true);
        Map<String, Object> entry = request.entry("alice");
        assertFalse(entry.containsKey("launchTargets"));
        assertFalse(entry.containsKey("requires"));
        assertTrue(entry.containsKey("tags"));
    }

    @Test
    void requiresNamesRegistryPluginsWithTheirInterpolatedVersion() {
        List<UserLibrary> declared = List.of(
                new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "${botmaker.sdk.version}"),
                new UserLibrary("org.junit.jupiter", "junit-jupiter", "5.10.0"),
                new UserLibrary("com.example", "unregistered-plugin", "1.0"));
        List<PluginRegistry.Plugin> registry = List.of(new PluginRegistry.Plugin("com.botmaker.sdk", "SDK",
                "com.github.LiQiyeDev:botmaker-sdk", "", "", List.of(), "", List.of(), "v1.1.6", ""));

        assertEquals(List.of(new GalleryEntry.Requirement("com.botmaker.sdk", "1.1.6")),
                PublishRequest.requires(declared, Map.of("botmaker.sdk.version", "1.1.6"), registry));
        assertEquals("${missing}", PublishRequest.interpolate("${missing}", Map.of()));
    }

    @Test
    void thePreviewIsTheListingTheGalleryWouldShow() {
        PublishRequest request = new PublishRequest(Path.of("."), "Koala", "koala", "d", "1.0.0", List.of("x"),
                null, List.of(), true);
        GalleryEntry preview = request.preview("alice", GalleryTier.COMMUNITY, "");
        assertEquals("alice/koala", preview.slug());
        assertEquals(GalleryTier.COMMUNITY, preview.tier());
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
                null, List.of(), true);
        Map<String, Object> entry = request.entry("alice");
        // The Contents API wraps base64 at 60 columns; the comparison must not care.
        String encoded = Base64.getMimeEncoder().encodeToString(BotPublisher.entryJson(MAPPER, entry));
        JsonNode contents = MAPPER.createObjectNode().put("content", encoded);

        assertTrue(BotPublisher.sameEntry(MAPPER, contents, entry));
        Map<String, Object> changed = new PublishRequest(Path.of("."), "Koala", "koala", "new", "1.0.0",
                List.of("a"), null, List.of(), true).entry("alice");
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
}
