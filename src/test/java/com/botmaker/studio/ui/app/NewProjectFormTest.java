package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.ui.app.StartingPoints.Fetch;
import com.botmaker.studio.ui.app.StartingPoints.Kind;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** New Project's name rule, pre-fill and lines (2026-10-24). */
class NewProjectFormTest {

    private static final String PROBLEM = "A project name may hold letters and digits only.";

    private static StartChoice bot(String name) {
        return StartChoice.of(new GalleryEntry(name, "LiQiyeDev", name, "", List.of()));
    }

    @Test
    void nameErrorOnlyWhenBroken() {
        assertEquals(Optional.empty(), NewProjectForm.nameError("", n -> PROBLEM));
        assertEquals(Optional.empty(), NewProjectForm.nameError("MyBot", n -> null));
        assertEquals(Optional.of(PROBLEM), NewProjectForm.nameError("my bot", n -> PROBLEM));
    }

    /** Review Focus 3: a typed name is never replaced; Blank fills nothing. */
    @Test
    void prefillOnlyWhenUntouched() {
        assertEquals(Optional.of("Firestone"), NewProjectForm.prefill(false, bot("Firestone")));
        assertEquals(Optional.empty(), NewProjectForm.prefill(true, bot("Firestone")));
        assertEquals(Optional.empty(), NewProjectForm.prefill(false, StartChoice.BLANK));
        assertEquals(Optional.empty(), NewProjectForm.prefill(false, null));
    }

    @Test
    void prefillMakesARepositoryNameAProjectName() {
        assertEquals(Optional.of("FirestoneBot"), NewProjectForm.prefill(false, bot("firestone-bot")));
        assertEquals(Optional.of("Farm2"), NewProjectForm.prefill(false, bot("2-farm.2")));
        assertEquals(Optional.empty(), NewProjectForm.prefill(false, bot("42")));
        assertEquals(50, NewProjectForm.prefill(false, bot("a".repeat(80))).orElseThrow().length());
    }

    @Test
    void catalogLines() {
        Optional<Instant> kept = Optional.of(Instant.parse("2026-10-20T12:00:00Z"));
        assertEquals("Checking for new bots…",
                NewProjectForm.catalogLine(Fetch.PENDING, kept, true, ZoneOffset.UTC));
        assertEquals("", NewProjectForm.catalogLine(Fetch.ANSWERED, kept, true, ZoneOffset.UTC));
        assertEquals("Offline — showing the bots from 20 Oct",
                NewProjectForm.catalogLine(Fetch.FAILED, kept, true, ZoneOffset.UTC));
        assertEquals("The bots could not be loaded — Blank only.",
                NewProjectForm.catalogLine(Fetch.FAILED, Optional.empty(), false, ZoneOffset.UTC));
        assertEquals("The bots could not be loaded — Blank only.",
                NewProjectForm.catalogLine(Fetch.FAILED, kept, false, ZoneOffset.UTC));
    }

    @Test
    void emptyLines() {
        assertEquals("No bots listed yet.", NewProjectForm.emptyLine(true));
        assertEquals("No official bots yet — show community bots to see the rest.", NewProjectForm.emptyLine(false));
    }

    @Test
    void startNotes() {
        StartChoice fresh = bot("farm");
        StartChoice kept = new StartChoice(Kind.REMEMBERED, fresh.entry());
        assertEquals("", NewProjectForm.startNote(StartChoice.BLANK, Fetch.ANSWERED));
        assertEquals("", NewProjectForm.startNote(null, Fetch.ANSWERED));
        assertEquals(fresh.entry().trustWarning(), NewProjectForm.startNote(fresh, Fetch.ANSWERED));
        assertEquals("Checking that Farm is still listed…", NewProjectForm.startNote(kept, Fetch.PENDING));
        assertEquals("Farm can be started from once the catalog can be reached.",
                NewProjectForm.startNote(kept, Fetch.FAILED));
    }
}
