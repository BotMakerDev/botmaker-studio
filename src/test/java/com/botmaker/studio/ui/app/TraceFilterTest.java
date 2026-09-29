package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.TraceLine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Trace tab's filter and the toolbar's debug choice, headless: the logic, without the controls. */
class TraceFilterTest {

    private static final Set<TraceLine.Level> ALL = EnumSet.allOf(TraceLine.Level.class);

    private static TraceLine line(TraceLine.Level level, String source, String text) {
        return new TraceLine(Instant.EPOCH, level, source, text, 1, "", OptionalInt.empty(), Optional.empty());
    }

    @Test
    void aLevelThatIsOffHidesItsLinesButAnUnknownLevelAlwaysShows() {
        Set<TraceLine.Level> noDebug = EnumSet.of(TraceLine.Level.INFO, TraceLine.Level.WARN, TraceLine.Level.ERROR);

        assertFalse(TracePanel.matches(line(TraceLine.Level.DEBUG, "Mouse", "click"), noDebug, null, ""));
        assertTrue(TracePanel.matches(line(TraceLine.Level.ERROR, "Game", "died"), noDebug, null, ""));
        assertTrue(TracePanel.matches(line(TraceLine.Level.UNKNOWN, "Game", "new"), Set.of(), null, ""));
    }

    @Test
    void theSourceFilterAndTheSearchNarrowIgnoringCase() {
        TraceLine vision = line(TraceLine.Level.DEBUG, "Vision", "found Ore at (1,2)");

        assertTrue(TracePanel.matches(vision, ALL, TracePanel.ALL_SOURCES, ""));
        assertTrue(TracePanel.matches(vision, ALL, "vision", ""));
        assertFalse(TracePanel.matches(vision, ALL, "Mouse", ""));
        assertTrue(TracePanel.matches(vision, ALL, null, "ore"));
        assertTrue(TracePanel.matches(vision, ALL, null, "VISION"), "the search reads the source too");
        assertFalse(TracePanel.matches(vision, ALL, null, "click"));
    }

    @Test
    void aRowSaysWhatWroteItAndHowManyTimes() {
        TraceLine repeated = new TraceLine(Instant.EPOCH, TraceLine.Level.DEBUG, "Vision", "miss", 47, "",
                OptionalInt.empty(), Optional.empty());

        String row = TracePanel.render(repeated);

        assertTrue(row.endsWith("[Vision] miss  (×47)"), row);
    }

    @Test
    void theDebugButtonCyclesBotOnOffAndReadsThePropertyAsTheBotDoes() {
        assertEquals(DebugOutput.ON, DebugOutput.BOT.next());
        assertEquals(DebugOutput.OFF, DebugOutput.ON.next());
        assertEquals(DebugOutput.BOT, DebugOutput.OFF.next());

        assertEquals(DebugOutput.ON, DebugOutput.fromProperty(" TRUE "));
        assertEquals(DebugOutput.OFF, DebugOutput.fromProperty("false"));
        assertEquals(DebugOutput.BOT, DebugOutput.fromProperty("yes"));
        assertEquals(DebugOutput.BOT, DebugOutput.fromProperty(null));
        assertEquals(null, DebugOutput.BOT.propertyValue(), "the bot's own setting is the property unset");
    }
}
