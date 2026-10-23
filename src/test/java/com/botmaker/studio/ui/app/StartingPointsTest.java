package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.ui.app.StartingPoints.Fetch;
import com.botmaker.studio.ui.app.StartingPoints.Kind;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** New Project starts from Blank or any published bot (2026-10-23): Blank first, the bots below it. */
class StartingPointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GalleryEntry bot(String name) {
        return new GalleryEntry(name, "LiQiyeDev", "botmaker-" + name, "A " + name + " bot.",
                List.of("game"));
    }

    private static List<Kind> kinds(List<StartChoice> rows) {
        return rows.stream().map(StartChoice::kind).toList();
    }

    /** Shown at once, and never created from: the gallery may since have delisted one or changed its release. */
    @Test
    void rememberedBotsFollowBlankButWaitForTheGallery() {
        List<StartChoice> rows = StartingPoints.rows(List.of(bot("farm"), bot("mine")), Fetch.PENDING);

        assertEquals(List.of(Kind.BLANK, Kind.REMEMBERED, Kind.REMEMBERED), kinds(rows));
        assertTrue(rows.getFirst().isCreatable(), "Blank needs no gallery");
        assertEquals("Farm — A farm bot.  (LiQiyeDev)", rows.get(1).label());
        assertFalse(rows.get(1).isCreatable());
    }

    @Test
    void nothingRememberedShowsLoadingBelowBlank() {
        assertEquals(List.of(StartChoice.BLANK, StartChoice.LOADING), StartingPoints.rows(List.of(), Fetch.PENDING));
        assertFalse(StartChoice.LOADING.isCreatable());
    }

    @Test
    void freshBotsAreCreatableBelowBlank() {
        List<StartChoice> rows = StartingPoints.rows(List.of(bot("farm")), Fetch.ANSWERED);

        assertEquals(List.of(Kind.BLANK, Kind.BOT), kinds(rows));
        assertTrue(rows.get(1).isCreatable());
        assertEquals(List.of(StartChoice.BLANK), StartingPoints.rows(List.of(), Fetch.ANSWERED));
    }

    /** Nothing confirms a remembered entry offline, and it clones at creation anyway. */
    @Test
    void anUnreachableGalleryLeavesBlankAlone() {
        assertEquals(List.of(StartChoice.BLANK), StartingPoints.rows(List.of(bot("farm")), Fetch.FAILED));
        assertTrue(StartChoice.BLANK.isCreatable());
    }

    /** A remembered row picked while loading is still picked once the gallery lists it, changed or not. */
    @Test
    void thePickedBotSurvivesARefreshThatChangedIt() {
        GalleryEntry picked = bot("mine");
        GalleryEntry refreshed = new GalleryEntry(picked.name(), picked.owner(), picked.repo(), "Newer words.",
                List.of("game", "farming"));
        StartChoice remembered = StartingPoints.rows(List.of(picked), Fetch.PENDING).get(1);
        List<StartChoice> rows = StartingPoints.rows(List.of(bot("farm"), refreshed), Fetch.ANSWERED);

        assertEquals(Optional.of(StartChoice.of(refreshed)), StartingPoints.same(rows, remembered));
        assertEquals(Optional.of(StartChoice.BLANK), StartingPoints.same(rows, StartChoice.BLANK));
        assertEquals(Optional.empty(), StartingPoints.same(rows, StartChoice.LOADING));
    }

    /** Every listed bot is remembered, an old {@code template} tag or not. */
    @Test
    void theRememberedListReadsBackAsTheCatalogDoes() {
        GalleryEntry tagged = new GalleryEntry("old", "someone", "old", "", List.of("template"));
        List<GalleryEntry> entries = List.of(bot("farm"), tagged, bot("mine"));

        String json = StartingPoints.toJson(MAPPER, entries).orElseThrow();

        assertEquals(entries, StartingPoints.fromJson(MAPPER, json));
    }

    @Test
    void anEmptyListIsNotRemembered() {
        assertTrue(StartingPoints.toJson(MAPPER, List.of()).isEmpty());
        assertEquals(List.of(), StartingPoints.fromJson(MAPPER, null));
        assertEquals(List.of(), StartingPoints.fromJson(MAPPER, "not json"));
    }

    @Test
    void aBotIsLabelledByItsCapitalisedName() {
        assertEquals("Farm — A farm bot.  (LiQiyeDev)", StartChoice.of(bot("farm")).label());
        assertEquals("Loading published bots…", StartChoice.LOADING.label());
    }
}
