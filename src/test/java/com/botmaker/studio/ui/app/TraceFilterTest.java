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
    private static final String MOUSE = "com.botmaker.sdk.api.input.Mouse";

    private static TraceLine line(TraceLine.Level level, String source, String text) {
        return written(level, source, text, "", "");
    }

    private static TraceLine written(TraceLine.Level level, String source, String text, String writerClass,
                                     String writerMethod) {
        return new TraceLine(Instant.EPOCH, level, source, text, 1, writerClass, writerMethod, "",
                OptionalInt.empty(), Optional.empty());
    }

    @Test
    void aLevelThatIsOffHidesItsLinesButAnUnknownLevelAlwaysShows() {
        Set<TraceLine.Level> noDebug = EnumSet.of(TraceLine.Level.INFO, TraceLine.Level.WARN, TraceLine.Level.ERROR);

        assertFalse(TracePanel.matches(line(TraceLine.Level.DEBUG, "Mouse", "click"), Set.of(), noDebug, null, ""));
        assertTrue(TracePanel.matches(line(TraceLine.Level.ERROR, "Game", "died"), Set.of(), noDebug, null, ""));
        assertTrue(TracePanel.matches(line(TraceLine.Level.UNKNOWN, "Game", "new"), Set.of(), Set.of(), null, ""));
    }

    @Test
    void theSourceFilterAndTheSearchNarrowIgnoringCase() {
        TraceLine vision = line(TraceLine.Level.DEBUG, "Vision", "found Ore at (1,2)");

        assertTrue(TracePanel.matches(vision, Set.of(), ALL, TracePanel.ALL_SOURCES, ""));
        assertTrue(TracePanel.matches(vision, Set.of(), ALL, "vision", ""));
        assertFalse(TracePanel.matches(vision, Set.of(), ALL, "Mouse", ""));
        assertTrue(TracePanel.matches(vision, Set.of(), ALL, null, "ore"));
        assertTrue(TracePanel.matches(vision, Set.of(), ALL, null, "VISION"), "the search reads the source too");
        assertFalse(TracePanel.matches(vision, Set.of(), ALL, null, "click"));
    }

    /** A class key hides every method of it; a method key hides that method only. */
    @Test
    void aHiddenClassOrMethodHidesExactlyItsOwnLines() {
        TraceLine click = written(TraceLine.Level.DEBUG, "Mouse", "click", MOUSE, "click");
        TraceLine move = written(TraceLine.Level.DEBUG, "Mouse", "move", MOUSE, "moveTo");
        TraceLine unknown = line(TraceLine.Level.DEBUG, "Mouse", "no writer");
        Set<String> oneMethod = Set.of(TraceWriters.key(MOUSE, "click"));

        assertFalse(TracePanel.matches(click, oneMethod, ALL, null, ""));
        assertTrue(TracePanel.matches(move, oneMethod, ALL, null, ""));
        assertFalse(TracePanel.matches(move, Set.of(MOUSE), ALL, null, ""));
        assertTrue(TracePanel.matches(unknown, Set.of(MOUSE), ALL, null, ""),
                "a line whose writer is unknown cannot be hidden by writer");
        assertEquals("Mouse", TraceWriters.simpleName(MOUSE));
        assertEquals("LaunchTarget$Steam", TraceWriters.simpleName("com.botmaker.sdk.internal.launch.LaunchTarget$Steam"));
    }

    @Test
    void aRowSaysWhatWroteItAndHowManyTimes() {
        TraceLine repeated = new TraceLine(Instant.EPOCH, TraceLine.Level.DEBUG, "Vision", "miss", 47, "", "", "",
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
