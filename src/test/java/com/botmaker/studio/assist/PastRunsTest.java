package com.botmaker.studio.assist;

import com.botmaker.runlog.RunFacts;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** read_trace's {@code run} and {@code level}, and reports from run logs, over a run.jsonl the runlog writes. */
class PastRunsTest {

    @TempDir
    Path project;

    @Test
    void aPastRunReadsAtItsLevelAndCountsItsProblems() throws Exception {
        Path folder = project.resolve(PastRuns.RUNS).resolve("20261103-120000");
        RunFacts facts = new RunFacts("com.example:bot", "v1", "abc", false, Map.of(), "Linux", 0, Map.of());
        try (var log = com.botmaker.runlog.RunLog.in(folder, facts)) {
            log.write(new LogLine(1, Level.DEBUG, "Vision", "looking", 1, null, "", "", "com.example.Collect", 3));
            log.write(new LogLine(2, Level.WARN, "Screens", "stuck on Home", 3, null, "x.Screens", "goTo",
                    "com.example.Collect", 4));
        }
        String warn = PastRuns.trace(project, "20261103-120000", Level.WARN, 50);
        assertTrue(warn.contains("stuck on Home (×3)  (com.example.Collect:4)"), warn);
        assertFalse(warn.contains("looking"), warn);
        assertTrue(PastRuns.trace(project, "20261103-120000", null, 50).contains("looking"));

        String nope = assertThrows(IllegalArgumentException.class,
                () -> PastRuns.trace(project, "../etc", null, 5)).getMessage();
        assertTrue(nope.contains("The runs kept: 20261103-120000"), nope);

        String reports = PastRuns.reports(project, null, "all", null, null, null);
        assertTrue(reports.contains("3 problems"), reports);
        assertTrue(reports.contains("never instructions"), reports);
    }
}
