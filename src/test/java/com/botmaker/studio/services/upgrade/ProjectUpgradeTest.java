package com.botmaker.studio.services.upgrade;

import com.botmaker.shared.config.CacheDirs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rule the project upgrade window adds to the engine: <b>nothing is written unless every row can be</b>.
 *
 * <p>The pass is ordered check-all → snapshot → repair each row → write the pom once, and the order is what
 * these tests are about. A row that refuses must refuse while the project is still untouched: after the
 * snapshot there is something to undo, and after the pom write the project pins a version its source was
 * never repaired for.
 *
 * <p>The rows here point at a fixture project with no pom, so every {@code compare} comes back as <i>could
 * not resolve</i> — which is exactly one of the two refusals, and the cheapest way to prove the pre-flight
 * runs before the snapshot: the snapshot would throw on a directory that is not a git repository, and the
 * error these tests see is the report's, not the VCS's.
 */
class ProjectUpgradeTest {

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(CacheDirs.cacheRoot().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /** A writer that records rather than writes — the real one re-resolves and re-binds every plugin. */
    private static final class Recorder implements ProjectUpgrade.PomWriter {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Map<String, String>> wrote = new AtomicReference<>();

        @Override
        public CompletableFuture<Void> write(Map<String, String> versionsByCoordinate) {
            calls.incrementAndGet();
            wrote.set(versionsByCoordinate);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final String BOT = """
            package com.mybot;

            public class Subject {
                public static void run() {
                    Mouse.click(1, 2);
                }
            }
            """;

    @Test
    void aRowThatCannotBeCheckedRefusesThePassWithNothingWritten(@TempDir Path tmp) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, BOT);
        Recorder writer = new Recorder();

        CompletableFuture<ProjectUpgrade.Result> pass =
                ProjectUpgrade.run(List.of(new ProjectUpgrade.Row(service, "2.0.0")), writer);

        ExecutionException failed = assertThrows(ExecutionException.class, pass::get);
        assertTrue(failed.getCause() instanceof IllegalStateException, failed::toString);
        assertTrue(failed.getCause().getMessage().contains("nothing has been changed"),
                failed.getCause()::getMessage);
        assertEquals(0, writer.calls.get(), "the pom must not move when a row refuses");
    }

    /**
     * And the refusal is the <em>pass</em>'s, not the row's: one plugin that cannot be checked stops the
     * plugin beside it too, because the versions are written together.
     */
    @Test
    void oneBadRowStopsTheRowsBesideIt(@TempDir Path tmp) throws IOException {
        Recorder writer = new Recorder();
        List<ProjectUpgrade.Row> rows = List.of(
                new ProjectUpgrade.Row(UpgradeFixtures.serviceOver(tmp.resolve("a"), BOT), "2.0.0"),
                new ProjectUpgrade.Row(UpgradeFixtures.serviceOver(tmp.resolve("b"), BOT), "3.0.0"));

        assertThrows(ExecutionException.class, () -> ProjectUpgrade.run(rows, writer).get());
        assertEquals(0, writer.calls.get());
    }

    /**
     * A row sitting on the version it already has is not an operation. It is dropped before anything is
     * checked, so a window where the user moved one row of four does not snapshot the project four times or
     * re-pin three coordinates to the values they already hold.
     */
    @Test
    void aRowOnItsInstalledVersionIsNotAnOperationAtAll(@TempDir Path tmp) throws IOException {
        PluginUpgradeService service = UpgradeFixtures.serviceOver(tmp, BOT);
        Recorder writer = new Recorder();

        // The fixture project has no pom, so the installed version reads as blank — which is what this row
        // asks to move to. Blank and unchanged are both nothing to do.
        ProjectUpgrade.run(List.of(new ProjectUpgrade.Row(service, "")), writer).join();

        assertEquals(0, writer.calls.get(), "an unchanged row must not cause a pom write");
        assertNull(writer.wrote.get());
    }

    @Test
    void anEmptyWindowWritesNothing() {
        Recorder writer = new Recorder();
        ProjectUpgrade.Result result = ProjectUpgrade.run(List.of(), writer).join();
        assertEquals(0, writer.calls.get());
        assertEquals(List.of(), result.moved());
    }

    // -------------------------------------------------------------------------
    // What the window says afterwards
    // -------------------------------------------------------------------------

    /**
     * The sentence the window shows instead of closing. It is asserted here rather than in the dialog,
     * because the counts are the pass's own and the wording is the only part of the result the user reads.
     */
    @Test
    void theSummaryNamesTheVersionsAndWhatTheyCostThisBot() {
        String summary = new ProjectUpgrade.Result(
                List.of(new ProjectUpgrade.Moved("BotMaker SDK", "1.1.6", "1.1.12")), 2, 3).summary();

        assertTrue(summary.contains("BotMaker SDK 1.1.6 → 1.1.12"), summary);
        assertTrue(summary.contains("3 calls repaired in 2 files"), summary);
        assertTrue(summary.contains("marked for review"), summary);
        assertTrue(summary.contains("Project History"), "it says where the way back is: " + summary);
    }

    /** An upgrade that rewrote nothing says so, and offers no review to walk. */
    @Test
    void anUpgradeThatTouchedNoCodeSaysThatInsteadOfCountingZeroes() {
        ProjectUpgrade.Result result = new ProjectUpgrade.Result(
                List.of(new ProjectUpgrade.Moved("Basics", "0.0.4", "0.0.5")), 0, 0);

        assertTrue(result.summary().contains("Nothing in this bot's own code had to change"),
                result.summary());
        assertFalse(result.touchedSources(), "so the window offers no Review tab button");
    }

    @Test
    void severalPluginsAreCountedAndThenListed() {
        String summary = new ProjectUpgrade.Result(List.of(
                new ProjectUpgrade.Moved("SDK", "1", "2"),
                new ProjectUpgrade.Moved("Basics", "3", "4")), 1, 1).summary();

        assertTrue(summary.startsWith("Moved 2 plugins: SDK 1 → 2, Basics 3 → 4."), summary);
        assertTrue(summary.contains("1 call repaired in 1 file"), "singular where it is one: " + summary);
    }
}
