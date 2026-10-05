package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.launch.SupportedTargets;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GalleryTier;
import com.botmaker.studio.ui.app.StartingPoints.Fetch;
import com.botmaker.studio.ui.app.StartingPoints.Kind;
import com.botmaker.studio.ui.app.StartingPoints.TemplateChoice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** New Project opens on the templates, never on Blank while they load. */
class StartingPointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GalleryEntry template(String name) {
        return new GalleryEntry(name, "LiQiyeDev", "botmaker-" + name, "A " + name + " bot.",
                List.of(GalleryEntry.TEMPLATE_TAG), SupportedTargets.any(), GalleryTier.VETTED, "v1.0.0",
                List.of(new GalleryEntry.Requirement("com.botmaker.sdk", "1.2.3")));
    }

    private static List<Kind> kinds(List<TemplateChoice> rows) {
        return rows.stream().map(TemplateChoice::kind).toList();
    }

    /** Shown at once, and never created from: the gallery may since have delisted one or changed its release. */
    @Test
    void rememberedTemplatesAreTheFirstRowsButWaitForTheGallery() {
        List<TemplateChoice> rows = StartingPoints.rows(List.of(template("base"), template("gamebot")), Fetch.PENDING);

        assertEquals(List.of(Kind.REMEMBERED, Kind.REMEMBERED), kinds(rows));
        assertEquals("Base — A base bot.  (LiQiyeDev)", rows.getFirst().label());
        assertFalse(rows.getFirst().isCreatable());
    }

    @Test
    void nothingRememberedShowsLoadingNotBlank() {
        List<TemplateChoice> rows = StartingPoints.rows(List.of(), Fetch.PENDING);

        assertEquals(List.of(TemplateChoice.LOADING), rows);
        assertFalse(rows.getFirst().isCreatable());
    }

    @Test
    void freshTemplatesAreCreatableAndNeverListBlank() {
        List<TemplateChoice> rows = StartingPoints.rows(List.of(template("base")), Fetch.ANSWERED);

        assertEquals(List.of(Kind.TEMPLATE), kinds(rows));
        assertTrue(rows.getFirst().isCreatable());
        assertEquals(List.of(TemplateChoice.BLANK), StartingPoints.rows(List.of(), Fetch.ANSWERED));
    }

    /** Nothing confirms a remembered entry offline, and it downloads at creation anyway. */
    @Test
    void anUnreachableGalleryLeavesBlankAlone() {
        assertEquals(List.of(TemplateChoice.BLANK), StartingPoints.rows(List.of(template("base")), Fetch.FAILED));
        assertTrue(TemplateChoice.BLANK.isCreatable());
    }

    /** A remembered row picked while loading is still picked once the gallery lists it, changed or not. */
    @Test
    void thePickedTemplateSurvivesARefreshThatChangedIt() {
        GalleryEntry picked = template("gamebot");
        GalleryEntry refreshed = new GalleryEntry(picked.name(), picked.owner(), picked.repo(), "Newer words.",
                picked.tags(), picked.launchTargets(), picked.tier(), "v2.0.0", picked.requires());
        TemplateChoice remembered = StartingPoints.rows(List.of(picked), Fetch.PENDING).getFirst();
        List<TemplateChoice> rows = StartingPoints.rows(List.of(template("base"), refreshed), Fetch.ANSWERED);

        assertEquals(Optional.of(TemplateChoice.of(refreshed)), StartingPoints.same(rows, remembered));
        assertEquals(Optional.empty(), StartingPoints.same(rows, TemplateChoice.LOADING));
    }

    @Test
    void theRememberedListReadsBackAsTheCatalogDoes() {
        GalleryEntry bot = new GalleryEntry("farm", "someone", "farm", "", List.of(), SupportedTargets.any());
        List<GalleryEntry> entries = List.of(template("base"), bot, template("gamebot"));

        String json = StartingPoints.toJson(MAPPER, entries).orElseThrow();

        assertEquals(List.of(template("base"), template("gamebot")), StartingPoints.fromJson(MAPPER, json));
    }

    @Test
    void aListWithNoTemplateIsNotRemembered() {
        GalleryEntry bot = new GalleryEntry("farm", "someone", "farm", "", List.of(), SupportedTargets.any());

        assertTrue(StartingPoints.toJson(MAPPER, List.of(bot)).isEmpty());
        assertEquals(List.of(), StartingPoints.fromJson(MAPPER, null));
        assertEquals(List.of(), StartingPoints.fromJson(MAPPER, "not json"));
    }

    @Test
    void aTemplateIsLabelledByItsCapitalisedName() {
        assertEquals("Base — A base bot.  (LiQiyeDev)", TemplateChoice.of(template("base")).label());
        assertEquals("Loading templates…", TemplateChoice.LOADING.label());
    }
}
