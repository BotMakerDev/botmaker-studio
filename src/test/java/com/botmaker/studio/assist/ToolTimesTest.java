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
        times.record("sdk_click", 18_634_000_000L, false, 412, 1);
        times.record("read_tree", 700_000L, true, 30, 0);

        List<String> lines = Files.readAllLines(file);
        assertEquals(ToolTimes.HEADER.strip(), lines.getFirst());
        assertEquals(3, lines.size());
        String[] click = lines.get(1).split("\t");
        assertEquals(List.of("sdk_click", "18634", "ok", "412", "1"),
                List.of(click[1], click[2], click[3], click[4], click[5]));
        assertTrue(lines.get(2).endsWith("\tread_tree\t0\terror\t30\t0"), lines.get(2));
    }

    @Test
    void aFileWithAnOlderHeaderIsMovedAside(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tool-times.tsv");
        Files.writeString(file, "ended\ttool\tms\tresult\n2026-10-14T10:00:00Z\tread_tree\t3\tok\n");

        new ToolTimes(file).record("list_files", 1_000_000L, false, 12, 0);

        assertEquals(ToolTimes.HEADER.strip(), Files.readAllLines(file).getFirst());
        assertEquals(2, Files.readAllLines(file).size());
        assertTrue(Files.readString(dir.resolve("tool-times.old.tsv")).contains("\tread_tree\t3\tok"));
    }
}
