package com.botmaker.studio.ui.app.runner;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.runtime.TreeCompile;

import java.io.IOException;
import java.util.List;

/**
 * The Run window's <i>Update</i> (2026-10-25, spec § 1a): the editor's <i>Get vX.Y</i> without the editor. A
 * version of what the runner has is saved, the author's tag fetched (the catalog's commit, when it pinned one),
 * merged, and compiled; it is committed only when git merged every file and the result compiles. Anything else
 * is undone and left to Studio, where the conflict sheet and the errors can be shown: a runner has no editor.
 *
 * <p>The runner's own values come back afterwards from {@code RunnerValues}, so an update that brought the
 * author's defaults back loses nothing.
 */
public final class RunnerUpdate {

    private RunnerUpdate() {}

    /** How an update left the bot. */
    public enum Outcome {
        UPDATED,
        UP_TO_DATE,
        /** A file both sides changed, or the result does not compile: nothing changed, and Studio finishes it. */
        NEEDS_STUDIO
    }

    /**
     * Updates the bot in {@code config} to {@code tag}. Blocking: a fetch and a compile.
     *
     * @param pinnedCommit the commit the catalog approved for {@code tag}, blank when the bot is not listed
     * @throws IOException when the tag cannot be fetched, was moved, or the bot holds an unfinished merge
     */
    public static Outcome update(ProjectConfig config, String tag, String pinnedCommit)
            throws IOException, InterruptedException {
        ProjectVcs vcs = new ProjectVcs(config.projectPath());
        vcs.checkpoint(VersionOrigin.SAFETY, "Before updating to " + tag);
        vcs.fetchTag(Remote.ORIGINAL, tag, null);
        if (pinnedCommit != null && !pinnedCommit.isBlank()
                && !vcs.tagCommit(tag).orElse("").equals(pinnedCommit)) {
            throw ProjectVcs.changedSinceApproved(tag);
        }
        ProjectVcs.Merge merge = vcs.mergeTag(tag);
        if (merge.upToDate()) return Outcome.UP_TO_DATE;
        if (merge.conflicted()) {
            vcs.abortMerge();
            return Outcome.NEEDS_STUDIO;
        }
        // From here the tree is mid-merge: anything but a finished commit puts it back, or every later Update and
        // Start would meet a half-merged bot.
        boolean finished = false;
        try {
            List<String> errors = TreeCompile.errors(config);
            if (!errors.isEmpty()) return Outcome.NEEDS_STUDIO;
            vcs.finishMerge("Updated to " + tag);
            finished = true;
            return Outcome.UPDATED;
        } finally {
            if (!finished) vcs.abortMerge();
        }
    }
}
