package com.botmaker.studio.assist;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.CoreApplicationEvents.OutputAppendedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TraceLineEvent;
import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code read_trace} reads: a traced line is also printed, and the assistant reads it once. */
class RunLogTest {

    private final EventBus bus = new EventBus(false);
    private final RunLog log = new RunLog(bus, Clock.systemUTC(), () -> false);

    @AfterEach
    void close() {
        log.close();
    }

    private void print(String line) {
        bus.publish(new OutputAppendedEvent(line + "\n"));
    }

    private void trace(TraceLine.Level level, String source, String text, int count) {
        bus.publish(new TraceLineEvent(new TraceLine(null, level, source, text, count, null, null,
                "com.bot.Collect", OptionalInt.of(31), Optional.empty())));
    }

    @Test
    void aTraceLineAfterItsPrintedCopyReplacesIt() {
        print("[Source] set -> Window[game]");
        trace(TraceLine.Level.DEBUG, "Source", "set -> Window[game]", 1);
        assertEquals("[Source] set -> Window[game] @Collect:31", log.tail(10));
    }

    @Test
    void aPrintedCopyAfterItsTraceLineIsDropped() {
        trace(TraceLine.Level.DEBUG, "Source", "set -> Window[game]", 1);
        print("[Source] set -> Window[game]");
        assertEquals("[Source] set -> Window[game] @Collect:31", log.tail(10));
    }

    @Test
    void repeatsPairOneForOne() {
        trace(TraceLine.Level.DEBUG, "Window", "w -> Firestone", 1);
        trace(TraceLine.Level.DEBUG, "Window", "w -> Firestone", 1);
        print("[Window] w -> Firestone");
        print("[Window] w -> Firestone");
        print("[Window] w -> Firestone");
        assertEquals("""
                [Window] w -> Firestone @Collect:31
                [Window] w -> Firestone @Collect:31
                [Window] w -> Firestone""", log.tail(10));
    }

    @Test
    void aWarningKeepsItsLevelAndAPlainLineStays() {
        print("hello");
        trace(TraceLine.Level.WARN, "Vision", "no match", 1);
        print("[Vision] no match");
        assertEquals("hello\n[Warning Vision] no match @Collect:31", log.tail(10));
    }

    @Test
    void aCollapsedLinePairsWithItsCountedCopy() {
        print("[Wait] tick  (×3)");
        trace(TraceLine.Level.DEBUG, "Wait", "tick", 3);
        assertEquals("[Wait] tick (×3) @Collect:31", log.tail(10));
    }

    @Test
    void anErrorPairsOnItsMessageAndItsPrintedStackStays() {
        print("[Bot] boom");
        print("java.lang.IllegalStateException: boom");
        trace(TraceLine.Level.ERROR, "Bot", "boom\njava.lang.IllegalStateException: boom", 1);
        assertEquals("""
                java.lang.IllegalStateException: boom
                [Error Bot] boom
                java.lang.IllegalStateException: boom @Collect:31""", log.tail(10));
    }
}
