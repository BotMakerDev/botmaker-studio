package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What Find and Find in Project say beside their results — sentences, so asserted with no scene. */
class FindTextTest {

    @Test
    void theFindBarCountsFromOne() {
        assertEquals("3 of 12", FindBar.countText(2, 12, "ore"));
    }

    @Test
    void theFindBarSaysWhenNothingMatchesAndNothingForNoQuery() {
        assertEquals("No results", FindBar.countText(-1, 0, "ore"));
        assertEquals("", FindBar.countText(-1, 0, ""));
    }

    @Test
    void findInProjectSaysWhenItStoppedShort() {
        assertEquals("2 matches", NavigationPopups.findNote("ore", 2, 500));
        assertEquals("1 match", NavigationPopups.findNote("ore", 1, 500));
        assertEquals("No match in this bot.", NavigationPopups.findNote("ore", 0, 500));
        assertEquals("The first 500 matches — type more to narrow them.",
                NavigationPopups.findNote("e", 500, 500));
    }
}
