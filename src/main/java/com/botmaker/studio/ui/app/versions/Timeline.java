package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.project.vcs.ProjectVcs.CommitInfo;
import com.botmaker.studio.project.vcs.VersionOrigin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What the Simple timeline lists, as values: the history with the versions nobody chose folded away
 * ({@code docs/refactor/39-versions.md} §4). Pure, so the folding rule is tested without a scene.
 *
 * <p>Studio takes a version before every run; a user who runs forty times between two saves should read two
 * milestones and "·· 40 automatic versions" between them, not scroll. A run of one is not folded — a fold
 * that hides a single row saves nothing and costs a click.
 */
public final class Timeline {

    /** One row of the list. */
    public sealed interface Row permits Unsaved, Version, Fold {
    }

    /** What the editor holds that no version has yet: pinned first, and only when there is some. */
    public record Unsaved(int files) implements Row {
    }

    /** One version; {@code quiet} when nobody chose it, so it is drawn dimmer than a milestone. */
    public record Version(CommitInfo commit, boolean quiet) implements Row {
    }

    /**
     * Two or more versions nobody chose, one after another; {@code key} (the newest one's SHA) is what the
     * expanded set remembers. Its versions follow it as rows when expanded.
     */
    public record Fold(String key, List<CommitInfo> commits, boolean expanded) implements Row {

        public String label() {
            boolean allAutomatic = commits.stream().allMatch(c -> c.origin().automatic());
            return "·· " + commits.size() + (allAutomatic ? " automatic versions" : " more versions");
        }
    }

    private Timeline() {
    }

    /**
     * The rows for {@code history} (newest first), with {@code unsavedFiles} changed files not yet in a
     * version, and the folds whose keys are in {@code expanded} opened.
     */
    public static List<Row> rows(List<CommitInfo> history, int unsavedFiles, Set<String> expanded) {
        List<Row> rows = new ArrayList<>();
        if (unsavedFiles > 0) rows.add(new Unsaved(unsavedFiles));
        List<CommitInfo> run = new ArrayList<>();
        for (CommitInfo c : history) {
            if (folds(c)) {
                run.add(c);
                continue;
            }
            flush(run, rows, expanded);
            rows.add(new Version(c, false));
        }
        flush(run, rows, expanded);
        return rows;
    }

    /** A version nobody chose: Studio's own, or one without a trailer, and not named since. */
    static boolean folds(CommitInfo c) {
        return !c.milestone() && (c.origin().automatic() || c.origin() == VersionOrigin.UNKNOWN);
    }

    private static void flush(List<CommitInfo> run, List<Row> rows, Set<String> expanded) {
        if (run.size() == 1) {
            rows.add(new Version(run.getFirst(), true));
        } else if (run.size() > 1) {
            String key = run.getFirst().sha();
            boolean open = expanded.contains(key);
            rows.add(new Fold(key, List.copyOf(run), open));
            if (open) run.forEach(c -> rows.add(new Version(c, true)));
        }
        run.clear();
    }
}
