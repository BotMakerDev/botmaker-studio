package com.botmaker.studio.ui.app.overlay;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which windows the overlay offers to dock beside: the open ones only.
 *
 * <p>Headless: {@code candidates} is pure and {@code ask} is the only part that needs a toolkit, which is the
 * same split {@code BlockTree} and {@code ToolbarVisibility} were given.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class OverlayWindowPickerTest {

    @Test
    void nothingOpenIsAnEmptyListNotANull() {
        assertTrue(OverlayWindowPicker.candidates(List.of()).isEmpty());
        assertTrue(OverlayWindowPicker.candidates(null).isEmpty());
    }

    @Test
    void aBlankTitleIsNotACandidate() {
        // The native window list carries untitled windows, and an empty row in a choice dialog is a row a
        // user can select and learn nothing from.
        assertEquals(List.of("Diablo IV"), OverlayWindowPicker.candidates(List.of("", "  ", "Diablo IV")));
    }

    @Test
    void aTitleIsTrimmedSoTheSameWindowIsNotOfferedTwice() {
        assertEquals(List.of("Diablo IV"), OverlayWindowPicker.candidates(List.of(" Diablo IV ", "Diablo IV")));
    }

    @Test
    void studiosOwnOverlaysAreNotOffered() {
        assertEquals(List.of("Diablo IV"),
                OverlayWindowPicker.candidates(List.of("__bm_overlay_1f3a", "Diablo IV")));
    }

    @Test
    void thePlatformsOrderIsKept() {
        // Whatever the window manager lists first is what the user most likely means; re-sorting it would be
        // the picker having an opinion it has no basis for.
        assertEquals(List.of("C", "A", "B"), OverlayWindowPicker.candidates(List.of("C", "A", "B")));
    }
}
