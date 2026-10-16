package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Migrate and its banner say, without a screen. */
class MigrateWindowTest {

    @Test
    void theBannerCountsWhatTheLastChangeBroke() {
        assertTrue(MigrateWindow.brokenText(1).startsWith("1 old element the bot uses was broken"),
                MigrateWindow.brokenText(1));
        assertTrue(MigrateWindow.brokenText(3).startsWith("3 old elements the bot uses were broken"),
                MigrateWindow.brokenText(3));
    }

    @Test
    void theHeadingSaysWhatTheBotIsComparedWith() {
        assertTrue(MigrateWindow.headingText(List.of(), false).startsWith("Compared with the jars"));
        assertTrue(MigrateWindow.headingText(List.of(), true).startsWith("No plugin changed"));
    }

    @Test
    void nothingLeftSaysNothing() {
        assertEquals("", MigrateWindow.leftText(List.of()));
    }
}
