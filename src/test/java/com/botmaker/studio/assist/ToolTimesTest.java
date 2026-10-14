package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTimesTest {

    @Test
    void eachCallIsOneRowUnderTheHeader(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache").resolve("tool-times.tsv");
        ToolTimes times = new ToolTimes(file);
        times.record("sdk_click", 18_634_000_000L, false);
        times.record("read_tree", 700_000L, true);

        List<String> lines = Files.readAllLines(file);
        assertEquals(ToolTimes.HEADER.strip(), lines.getFirst());
        assertEquals(3, lines.size());
        String[] click = lines.get(1).split("\t");
        assertEquals(List.of("sdk_click", "18634", "ok"), List.of(click[1], click[2], click[3]));
        assertTrue(lines.get(2).endsWith("\tread_tree\t0\terror"), lines.get(2));
    }
}
