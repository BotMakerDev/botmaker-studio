package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The line over the fix list: how many are done and how many are left. */
class FixSheetTest {

    @Test
    void progressCountsWhatIsLeft() {
        assertEquals("1 of 3 fixed · 2 left", FixSheet.progressText(3, 2));
        assertEquals("All 3 fixes are chosen.", FixSheet.progressText(3, 0));
        assertEquals("The one fix is chosen.", FixSheet.progressText(1, 0));
    }
}
