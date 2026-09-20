package com.botmaker.studio.project;

import com.botmaker.studio.parser.refactor.ReviewMarker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The few places the host puts a {@code .java} file into a user's project, with the snapshot they owe.
 *
 * <p><b>Why a snapshot.</b> The editor keeps every change in memory and writes on run
 * ({@code CodeExecutionService}), so a file the host creates or rewrites outside that loop appears on disk
 * without the user having typed anything. Project History is what makes that undoable, and
 * {@link ReviewMarker#snapshot} is the existing way to take one — {@code CallMigrator.commit} already does
 * this before a refactor touches files the user is not looking at. Doing it here rather than at each call
 * site is the point: there are exactly three places that create a source file, and the one that forgot is
 * the one that would lose somebody's work.
 *
 * <p><b>Whole files only.</b> Nothing here merges, patches or preserves a region. A file is created once and
 * then belongs to whoever edits it — a plugin's own file included, which is why
 * {@code PluginSourceFiles} goes through {@link #create} and not through {@link #replace}. Rewriting a
 * region of somebody's file is {@code project/managed/JavaManagedEdits}' job, over one AST node, and not
 * this class's.
 */
public final class ProjectWrites {

    private ProjectWrites() {
    }

    /**
     * Creates {@code file} with {@code source} unless it is already there, and answers whether it is now.
     *
     * <p>An existing file is never overwritten — it is somebody's work, and a class that exists is a class a
     * field can go into. No snapshot is taken when nothing is written, because a history entry for a
     * no-op is noise in the one list a user reads to find a real change.
     */
    public static boolean create(ProjectConfig config, Path file, String source, String label) {
        if (file == null || source == null) return false;
        if (Files.isRegularFile(file)) return true;
        write(config, file, source, label);
        return true;
    }

    /**
     * Rewrites {@code file} whole, and answers whether the file now holds {@code source}.
     *
     * <p>Identical content is not a write: a model saved twice with nothing changed leaves the project
     * clean, which is what makes {@code git status} after dragging nothing an honest answer.
     */
    public static boolean replace(ProjectConfig config, Path file, String source, String label) {
        if (file == null || source == null) return false;
        try {
            if (Files.isRegularFile(file) && source.equals(Files.readString(file))) return true;
        } catch (IOException unreadable) {
            // Unreadable is not "unchanged": fall through and write, which is the answer that leaves the
            // file holding what the caller asked for.
        }
        write(config, file, source, label);
        return true;
    }

    private static void write(ProjectConfig config, Path file, String source, String label) {
        ReviewMarker.snapshot(config, label);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (IOException notWritten) {
            throw new UncheckedIOException("could not write " + file, notWritten);
        }
    }
}
