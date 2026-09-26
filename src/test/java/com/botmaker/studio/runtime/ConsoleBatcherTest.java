package com.botmaker.studio.runtime;

import com.botmaker.studio.palette.InputKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The console's one path from a pipe to the Run tab. What matters is what a flush carries — never more than
 * {@link ConsoleBatcher#MAX_PER_FLUSH}, the <em>latest</em> text when a flood overruns it, and a sentence saying
 * so — and that a prompt marker survives any flood, since it is taken out before anything is held.
 */
class ConsoleBatcherTest {

    private static ConsoleBatcher idle() {
        ConsoleBatcher batcher = new ConsoleBatcher(text -> { }, kind -> { });
        batcher.close(); // no timer: the test drains by hand
        return batcher;
    }

    @Test
    void aQuietWindowHandsOverExactlyWhatWasPrinted() {
        ConsoleBatcher batcher = idle();
        batcher.append("hello\n");
        batcher.append("world\n");
        assertEquals("hello\nworld\n", batcher.drain());
        assertNull(batcher.drain(), "a drained batcher has nothing until something is printed");
    }

    @Test
    void aFloodKeepsTheLatestLinesAndSaysHowMuchWentMissing() {
        ConsoleBatcher batcher = idle();
        for (int i = 0; i < 10_000; i++) batcher.append("line " + i + "\n");

        String flush = batcher.drain();
        assertTrue(flush.startsWith("[… "), flush.substring(0, 40));
        assertTrue(flush.endsWith("line 9999\n"), "the newest line is the one kept");
        String kept = flush.substring(flush.indexOf('\n') + 1);
        assertTrue(kept.length() <= ConsoleBatcher.MAX_PER_FLUSH);
        assertTrue(kept.startsWith("line "), "the cut lands on a line boundary: " + kept.substring(0, 20));
    }

    @Test
    void aPromptMarkerIsTakenOutAndReported() {
        List<InputKind> asked = new ArrayList<>();
        StringBuilder carry = new StringBuilder();
        String marker = InputKind.MARKER_DELIMITER + InputKind.MARKER_PREFIX + "int" + InputKind.MARKER_DELIMITER;

        String shown = ConsoleBatcher.extractInputMarkers("Age?\n" + marker + "\nrest", carry, asked::add);

        assertEquals("Age?\nrest", shown);
        assertEquals(List.of(InputKind.INT), asked);
    }

    @Test
    void aMarkerSplitAcrossTwoReadsIsCompletedByTheSecond() {
        List<InputKind> asked = new ArrayList<>();
        StringBuilder carry = new StringBuilder();
        String marker = InputKind.MARKER_DELIMITER + InputKind.MARKER_PREFIX + "line" + InputKind.MARKER_DELIMITER;
        int half = marker.length() / 2;

        String first = ConsoleBatcher.extractInputMarkers("a" + marker.substring(0, half), carry, asked::add);
        String second = ConsoleBatcher.extractInputMarkers(marker.substring(half) + "b", carry, asked::add);

        assertEquals("a", first);
        assertEquals("b", second);
        assertEquals(List.of(InputKind.LINE), asked);
    }
}
