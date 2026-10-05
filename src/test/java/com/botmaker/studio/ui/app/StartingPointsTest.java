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

    @Test
    void rememberedTemplatesAreTheFirstRows() {
        List<TemplateChoice> rows = StartingPoints.rows(List.of(template("base"), template("gamebot")), Fetch.PENDING);

        assertEquals(List.of(Kind.TEMPLATE, Kind.TEMPLATE), kinds(rows));
        assertTrue(rows.getFirst().isCreatable());
    }

    @Test
    void nothingRememberedShowsLoadingNotBlank() {
        List<TemplateChoice> rows = StartingPoints.rows(List.of(), Fetch.PENDING);

        assertEquals(List.of(TemplateChoice.LOADING), rows);
        assertFalse(rows.getFirst().isCreatable());
    }

    @Test
    void freshTemplatesNeverListBlank() {
        assertEquals(List.of(Kind.TEMPLATE), kinds(StartingPoints.rows(List.of(template("base")), Fetch.ANSWERED)));
        assertEquals(List.of(TemplateChoice.BLANK), StartingPoints.rows(List.of(), Fetch.ANSWERED));
    }

    /** A remembered template still downloads at creation, so offline Blank is the row that can be created. */
    @Test
    void anUnreachableGalleryAddsBlankAfterTheRememberedTemplates() {
        assertEquals(List.of(Kind.TEMPLATE, Kind.BLANK), kinds(StartingPoints.rows(List.of(template("base")),
                Fetch.FAILED)));
        assertEquals(List.of(TemplateChoice.BLANK), StartingPoints.rows(List.of(), Fetch.FAILED));
    }

    /** A refreshed entry (a new vetted release) is still the row the user picked. */
    @Test
    void thePickedTemplateSurvivesARefreshThatChangedIt() {
        GalleryEntry picked = template("gamebot");
        GalleryEntry refreshed = new GalleryEntry(picked.name(), picked.owner(), picked.repo(), "Newer words.",
                picked.tags(), picked.launchTargets(), picked.tier(), "v2.0.0", picked.requires());
        List<TemplateChoice> rows = StartingPoints.rows(List.of(template("base"), refreshed), Fetch.ANSWERED);

        assertEquals(Optional.of(TemplateChoice.of(refreshed)), StartingPoints.same(rows, TemplateChoice.of(picked)));
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
