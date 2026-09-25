package com.botmaker.studio.project.vcs;

import com.botmaker.studio.project.ProjectState;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The versions Studio takes on the user's behalf ({@code docs/refactor/39-versions.md} §3): before a run,
 * around an AI session, before a rewrite of files the user is not looking at.
 *
 * <p><b>Best-effort, never fatal.</b> A project that is not a repository yet becomes one; a commit that fails
 * is logged and forgotten. The alternative — refusing to run, or to rewrite, because the safety net could not
 * be hung — protects nothing and blocks the work. Blocking; call off the FX thread.
 */
public final class Checkpoints {

    /** One writer at a time: two commits racing for one repository's index lock would drop one of them. */
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "checkpoints");
        t.setDaemon(true);
        return t;
    });

    private Checkpoints() {
    }

    /** {@link #take(Path, ProjectState.Snapshot, VersionOrigin, String)} on Studio's checkpoint thread. */
    public static void takeLater(Path projectDir, ProjectState.Snapshot snapshot, VersionOrigin origin,
                                 String label) {
        QUEUE.execute(() -> take(projectDir, snapshot, origin, label));
    }

    /** A version of the project as it is on disk. */
    public static synchronized void take(Path projectDir, VersionOrigin origin, String label) {
        if (projectDir == null) return;
        try {
            new ProjectVcs(projectDir).checkpoint(origin, label);
        } catch (Exception e) {
            System.err.println("Couldn't take a version (" + label + "): " + e.getMessage());
        }
    }

    /**
     * A version of the project as the editor holds it: {@code snapshot}'s sources are written first, since the
     * editor keeps edits in memory until a run. Take the snapshot on the FX thread.
     */
    public static synchronized void take(Path projectDir, ProjectState.Snapshot snapshot, VersionOrigin origin,
                                         String label) {
        if (projectDir == null) return;
        try {
            save(projectDir, snapshot, origin, label);
        } catch (Exception e) {
            System.err.println("Couldn't take a version (" + label + "): " + e.getMessage());
        }
    }

    /**
     * The same, for a version somebody asked for and is waiting on: it answers the new short SHA (null when
     * nothing changed) and throws what went wrong instead of logging it.
     */
    public static synchronized String save(Path projectDir, ProjectState.Snapshot snapshot, VersionOrigin origin,
                                           String label) throws java.io.IOException {
        flush(snapshot);
        return new ProjectVcs(projectDir).checkpoint(origin, label);
    }

    /**
     * Puts the editor's sources on disk and nothing else, so that what git reports as unsaved includes the
     * edits not yet run. Under the same lock as a version, which reads what this writes.
     */
    public static synchronized void flush(ProjectState.Snapshot snapshot) throws java.io.IOException {
        if (snapshot != null) snapshot.writeSources();
    }
}
