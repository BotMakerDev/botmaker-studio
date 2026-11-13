package com.botmaker.studio.ui.app;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.studio.services.EditPort;
import com.botmaker.studio.services.ReviewService;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status bar's badge: its problems follow the diagnostics, its count to review follows the marks in the bot's
 * sources, and <i>Done</i> on a mark takes its row away.
 */
class ProjectStatusBarTest extends FxHeadlessTest {

    @TempDir
    static Path root;

    private ProjectConfig config;
    private DiagnosticsPanel problems;
    private ReviewPanel review;
    private ProjectStatusBar bar;

    @Override
    public void start(Stage stage) {
        try {
            config = ProjectConfig.forProject("refbot", root);
            Files.createDirectories(config.sourceRoot());
            Files.writeString(config.sourceRoot().resolve("Miner.java"), """
                    package com.refbot;

                    import com.botmaker.plugin.api.meta.Refactor;

                    class Miner {
                        @Refactor("the first thing")
                        void mine() {}

                        @Refactor("the second thing")
                        void haul() {}
                    }
                    """);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        problems = new DiagnosticsPanel(new DiagnosticsManager(), block -> { }, count -> bar.problems(count));
        review = new ReviewPanel(config, new ProjectState(), new Actions());
        bar = new ProjectStatusBar("Refbot", new Label("Ready"), problems.node(), review.node(), review::refresh);
        stage.setScene(new Scene(bar.node(), 700, 40));
        stage.show();
    }

    @Test
    void theProblemsFollowTheDiagnostics() {
        assertEquals("⚠ 0 problems · ⚑ 0 to review ▴", bar.badgeText());
        interact(() -> problems.update(List.of(diagnostic(DiagnosticSeverity.Error),
                diagnostic(DiagnosticSeverity.Warning), diagnostic(DiagnosticSeverity.Hint))));
        assertTrue(bar.badgeText().startsWith("⚠ 2 problems"), "a hint is no problem: " + bar.badgeText());
        interact(() -> problems.update(List.of()));
        assertTrue(bar.badgeText().startsWith("⚠ 0 problems"), bar.badgeText());
    }

    @Test
    void theCountToReviewFollowsTheMarksAndDoneTakesOneAway() throws Exception {
        interact(review::refresh);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> bar.badgeText().contains("⚑ 2 to review"));

        ReviewService.Item first = review.marks().getFirst();
        interact(() -> review.done(first));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> bar.badgeText().contains("⚑ 1 to review"));
        assertEquals(List.of("haul"), review.marks().stream().map(ReviewService.Item::function).toList());
        assertTrue(Files.readString(config.sourceRoot().resolve("Miner.java")).contains("done = true"),
                "Done keeps the record in the code");
    }

    @Test
    void theBadgeOpensTheList() {
        assertFalse(bar.isOpen());
        clickOn("#" + ProjectStatusBar.BADGE_ID);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(bar.isOpen(), "a click on the badge opens Problems and To review");
        interact(bar::close);
    }

    @Test
    void oneProblemIsSaidInTheSingular() {
        assertEquals("⚠ 1 problem · ⚑ 3 to review ▴", ProjectStatusBar.badge(1, 3));
    }

    private static Diagnostic diagnostic(DiagnosticSeverity severity) {
        Diagnostic d = new Diagnostic();
        d.setSeverity(severity);
        d.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        d.setMessage("boom");
        return d;
    }

    /** The canvas's gestures are not under test: only what the panel says it found. */
    private final class Actions implements ReviewPanel.Actions {
        @Override public void reveal(ReviewService.Item item) { }
        @Override public void rename(ReviewService.Item item, String newName) { }
        @Override public void delete(ReviewService.Item item) { }
        @Override public void redraw(Path file) { }
        @Override public void migrate(Class<?> from, Class<?> to) { }
        @Override public void removeMigrationMethods() { }
        @Override public void openMigrate() { }
        @Override public EditPort edits() { return null; }

        @Override
        public void found(List<ReviewService.Item> marks, int rows) {
            bar.toReview(rows);
        }
    }
}
