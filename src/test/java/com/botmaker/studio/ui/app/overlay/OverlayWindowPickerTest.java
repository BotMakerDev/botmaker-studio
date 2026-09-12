package com.botmaker.studio.ui.app.overlay;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which windows the overlay offers to draw over.
 *
 * <p>The ordering rule is the one with no visible symptom when it is wrong: a window that is open right now
 * has to lead, because a list led by a remembered title sends a user to a window that is not there and reads
 * as the picker being broken rather than as a stale entry.
 *
 * <p>Headless: {@code candidates} is pure and {@code ask} is the only part that needs a toolkit, which is the
 * same split {@code BlockTree} and {@code ToolbarVisibility} were given.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class OverlayWindowPickerTest {

    @Test
    void liveWindowsLeadAndRememberedOnesFollow() {
        List<String> candidates =
                OverlayWindowPicker.candidates(List.of("Diablo IV", "Studio"), List.of("Firestone", "Studio"));

        assertEquals(List.of("Diablo IV", "Studio", "Firestone"), candidates);
    }

    @Test
    void aWindowOpenAndRememberedIsOfferedOnce() {
        assertEquals(List.of("Diablo IV"),
                OverlayWindowPicker.candidates(List.of("Diablo IV"), List.of("Diablo IV")));
    }

    @Test
    void nothingOpenAndNothingRememberedIsAnEmptyListNotANull() {
        assertTrue(OverlayWindowPicker.candidates(List.of(), List.of()).isEmpty());
        assertTrue(OverlayWindowPicker.candidates(null, null).isEmpty());
    }

    @Test
    void aBlankTitleIsNotACandidate() {
        // The native window list carries untitled windows, and an empty row in a choice dialog is a row a
        // user can select and learn nothing from.
        assertEquals(List.of("Diablo IV"),
                OverlayWindowPicker.candidates(List.of("", "  ", "Diablo IV"), List.of()));
    }

    @Test
    void aTitleIsTrimmedSoTheSameWindowIsNotOfferedTwice() {
        assertEquals(List.of("Diablo IV"),
                OverlayWindowPicker.candidates(List.of(" Diablo IV "), List.of("Diablo IV")));
    }

    @Test
    void thePlatformsOrderIsKeptWithinTheLiveWindows() {
        // Whatever the window manager lists first is what the user most likely means; re-sorting it would be
        // the picker having an opinion it has no basis for.
        assertEquals(List.of("C", "A", "B"),
                OverlayWindowPicker.candidates(List.of("C", "A", "B"), List.of()));
    }
}
