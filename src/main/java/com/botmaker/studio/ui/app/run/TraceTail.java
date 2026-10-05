package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.TraceLine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The last few lines of a run's trace, oldest first, as the run bar shows them. Not thread-safe: FX thread. */
final class TraceTail {

    private final int capacity;
    private final Deque<TraceLine> lines = new ArrayDeque<>();

    TraceTail(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity " + capacity);
        this.capacity = capacity;
    }

    /** Adds {@code line}, dropping the oldest past the capacity; a null line is ignored. */
    void add(TraceLine line) {
        if (line == null) return;
        lines.addLast(line);
        while (lines.size() > capacity) lines.removeFirst();
    }

    List<TraceLine> lines() {
        return List.copyOf(lines);
    }

    void clear() {
        lines.clear();
    }
}
