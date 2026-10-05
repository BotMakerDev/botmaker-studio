package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.TraceLine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceTailTest {

    private static TraceLine line(String text) {
        return new TraceLine(Instant.EPOCH, TraceLine.Level.WARN, "", text, 1, "", "", "", OptionalInt.empty(),
                Optional.empty());
    }

    @Test
    void keepsTheNewestLinesOldestFirst() {
        TraceTail tail = new TraceTail(2);
        tail.add(line("a"));
        tail.add(null);
        tail.add(line("b"));
        tail.add(line("c"));
        assertEquals(java.util.List.of("b", "c"), tail.lines().stream().map(TraceLine::text).toList());
        tail.clear();
        assertTrue(tail.lines().isEmpty());
    }

    @Test
    void holdsAtLeastOneLine() {
        assertThrows(IllegalArgumentException.class, () -> new TraceTail(0));
    }
}
