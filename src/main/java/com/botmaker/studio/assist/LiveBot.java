package com.botmaker.studio.assist;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The bot's files as an outside caller reaches them — the MCP endpoint, which runs on a server thread and so may
 * not read {@code ProjectState} itself. The implementation hops to the FX thread for both halves.
 *
 * <p>Was {@code LiveFile}, the active file only, until 2026-10-06: an assistant automating a game edits the
 * activity it is working on, which is seldom the file the user left open.
 */
public interface LiveBot {

    /**
     * A turn over {@code file} as it is now — the editor's buffer when it holds the file, else the disk — or
     * empty when no project is open. {@code file} as {@link #resolve} reads it; blank for the file open in the
     * editor.
     *
     * @throws IllegalArgumentException when {@code file} names no file of the bot, or more than one
     */
    Optional<AssistTurn> begin(String file);

    /**
     * Publishes {@code turn}'s edits as one step, unless its file changed since {@link #begin}. A file other than
     * the open one is opened in the editor first, so the change is made where the user can see it and undo it.
     * Answers a sentence for the caller: applied, or why not.
     */
    String commit(AssistTurn turn, String label);

    /** The bot's source files, relative to its source root, {@code /}-separated, in the editor's order. */
    List<String> files();

    /**
     * Why {@code turn}'s file as it now stands would stop <em>another</em> file of the bot compiling — a call of a
     * method whose signature changed — or empty when it would not. The turn's own compile sees only its file.
     */
    default Optional<String> breaksElsewhere(AssistTurn turn) {
        return Optional.empty();
    }

    /**
     * The one of {@code files} that {@code given} names: its path below {@code root} ({@code com/bot/Collect.java}),
     * its file name ({@code Collect.java}) or its class's simple name ({@code Collect}).
     *
     * @throws IllegalArgumentException with a sentence naming what to do, when none or several match
     */
    static Path resolve(Collection<Path> files, Path root, String given) {
        String wanted = given == null ? "" : given.strip().replace('\\', '/');
        List<Path> found = new ArrayList<>();
        for (Path file : files) {
            String relative = relative(root, file);
            String name = file.getFileName().toString();
            if (relative.equals(wanted) || name.equals(wanted) || name.equals(wanted + ".java")) found.add(file);
        }
        if (found.size() == 1) return found.getFirst();
        if (found.isEmpty()) {
            throw new IllegalArgumentException("The bot has no file " + wanted + ". list_files names them.");
        }
        throw new IllegalArgumentException(wanted + " names " + found.size() + " files: "
                + found.stream().map(f -> relative(root, f)).toList() + ". Give its path.");
    }

    /** {@code file} below {@code root}, {@code /}-separated; its whole path when it is not below it. */
    static String relative(Path root, Path file) {
        Path shown = root != null && file.startsWith(root) ? root.relativize(file) : file;
        return shown.toString().replace('\\', '/');
    }

    /** Whether {@code file} is a Java source, by its name. */
    static boolean isJava(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }
}
