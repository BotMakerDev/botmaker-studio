package com.botmaker.studio.ui;

import com.botmaker.studio.util.NativeFileDialog;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Every file chooser Studio shows, its own and the ones a plugin asks for through {@code Dialogs} (2026-10-28), so
 * that {@code ui choose} can answer the next one: a queued answer is returned without showing anything. With none
 * queued the chooser shows as it always did, and {@link #waiting()} names it while it is open.
 *
 * <p>An answer that cannot fit the chooser (a missing file to open, two paths for one, a folder to save as) is not
 * used: the chooser shows, and {@link #news()} says why. A queued answer naming a file to save that exists replaces
 * it without asking, and {@link #news()} says so.
 */
public final class Choosers {

    /** What a chooser picks, for the check a queued answer gets. */
    private enum Kind {
        OPEN, OPEN_MANY, SAVE, FOLDER, PROGRAM
    }

    /** A chooser's file filter: what the user reads, and the extensions without a dot ({@code "png"}). */
    public record Filter(String name, List<String> extensions) {

        /** A null or blank extension is left out. */
        public static Filter of(String name, String... extensions) {
            return new Filter(name, Arrays.stream(extensions).filter(e -> e != null && !e.isBlank())
                    .map(String::strip).toList());
        }

        private FileChooser.ExtensionFilter fx() {
            return new FileChooser.ExtensionFilter(name, extensions.stream().map(Filter::glob).toList());
        }

        private static String glob(String ext) {
            return ext.startsWith("*.") ? ext : ext.startsWith(".") ? "*" + ext : "*." + ext;
        }
    }

    /** The next chooser's answer, an empty list to cancel; null when none is queued. */
    private static final AtomicReference<List<Path>> NEXT = new AtomicReference<>();
    /** The titles of the choosers showing now, newest last: a program chooser shows off the FX thread beside others. */
    private static final ConcurrentLinkedDeque<String> WAITING = new ConcurrentLinkedDeque<>();
    /** What became of queued answers since {@link #news()} last said. */
    private static final ConcurrentLinkedQueue<String> SAID = new ConcurrentLinkedQueue<>();

    private Choosers() {
    }

    /** One file to open, or empty when cancelled. FX thread. */
    public static Optional<Path> open(Window owner, String title, Path initialDir, Filter filter) {
        return answered(Kind.OPEN, title, () -> {
            File picked = fileChooser(title, initialDir, filter).showOpenDialog(owner);
            return picked == null ? List.of() : List.of(picked.toPath());
        }).stream().findFirst();
    }

    /** Files to open, none when cancelled. FX thread. */
    public static List<Path> openMany(Window owner, String title, Path initialDir, Filter filter) {
        return answered(Kind.OPEN_MANY, title, () -> {
            List<File> picked = fileChooser(title, initialDir, filter).showOpenMultipleDialog(owner);
            return picked == null ? List.of() : picked.stream().map(File::toPath).toList();
        });
    }

    /** A file to write, {@code fileName} proposed; the native dialog asks before replacing one. FX thread. */
    public static Optional<Path> save(Window owner, String title, Path initialDir, String fileName, Filter filter) {
        return answered(Kind.SAVE, title, () -> {
            FileChooser chooser = fileChooser(title, initialDir, filter);
            if (fileName != null && !fileName.isBlank()) chooser.setInitialFileName(fileName);
            File picked = chooser.showSaveDialog(owner);
            return picked == null ? List.of() : List.of(picked.toPath());
        }).stream().findFirst();
    }

    /** A folder, or empty when cancelled. FX thread. */
    public static Optional<Path> folder(Window owner, String title, Path initialDir) {
        return answered(Kind.FOLDER, title, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(title);
            initialDirectory(initialDir, chooser::setInitialDirectory);
            File picked = chooser.showDialog(owner);
            return picked == null ? List.of() : List.of(picked.toPath());
        }).stream().findFirst();
    }

    /**
     * An executable through {@link NativeFileDialog}, off the FX thread; a queued answer counts as the native dialog
     * shown, so the caller offers no fallback.
     */
    public static NativeFileDialog.Choice program(Path initialDir) {
        String title = "Choose a program";
        return queued(Kind.PROGRAM, title)
                .map(picked -> new NativeFileDialog.Choice(true, picked.stream().findFirst().map(Path::toString)))
                .orElseGet(() -> showing(title,
                        () -> NativeFileDialog.chooseProgram(initialDir == null ? null : initialDir.toString())));
    }

    // ---- ui choose ----------------------------------------------------------------------------------------

    /** Queues {@code paths} for the next chooser, none to cancel it; replaces an answer still queued. */
    public static String answerNext(List<Path> paths) {
        List<Path> before = NEXT.getAndSet(List.copyOf(paths));
        String what = paths.isEmpty() ? "cancels" : "answers " + said(paths);
        return "The next file chooser " + what + (before == null ? "." : "; the answer queued before is dropped.");
    }

    /** The title of the newest chooser waiting for the user now. */
    public static Optional<String> waiting() {
        return Optional.ofNullable(WAITING.peekLast());
    }

    /** Drops a queued answer and what {@link #news()} would say: for a test that queued one and may not have used it. */
    public static void forget() {
        NEXT.set(null);
        SAID.clear();
    }

    /** What became of queued answers since the last call, oldest first. */
    public static List<String> news() {
        List<String> out = new ArrayList<>();
        for (String line; (line = SAID.poll()) != null; ) out.add(line);
        return out;
    }

    // ---- shared -------------------------------------------------------------------------------------------

    /** The queued answer when it fits a {@code kind} chooser, else what {@code show} returns while it shows. */
    private static List<Path> answered(Kind kind, String title, Supplier<List<Path>> show) {
        return queued(kind, title).orElseGet(() -> showing(title, show));
    }

    /** The queued answer, taken, when it fits a {@code kind} chooser; what became of it goes to {@link #news()}. */
    private static Optional<List<Path>> queued(Kind kind, String title) {
        List<Path> queued = NEXT.getAndSet(null);
        if (queued == null) return Optional.empty();
        String unfit = unfit(kind, queued);
        if (unfit != null) {
            SAID.add("The file chooser \"" + title + "\" is shown: the queued answer " + unfit + ".");
            return Optional.empty();
        }
        String replaces = kind == Kind.SAVE && !queued.isEmpty() && Files.exists(queued.getFirst()) ? ", replacing the file there" : "";
        SAID.add("The file chooser \"" + title + "\" " + (queued.isEmpty() ? "was cancelled" : "got " + said(queued))
                + replaces + ", not shown.");
        return Optional.of(queued);
    }

    private static <T> T showing(String title, Supplier<T> show) {
        WAITING.addLast(title);
        try {
            return show.get();
        } finally {
            WAITING.removeLastOccurrence(title);
        }
    }

    /** Why {@code paths} cannot answer a {@code kind} chooser; null when they can. */
    private static String unfit(Kind kind, List<Path> paths) {
        if (paths.isEmpty()) return null;
        if (kind != Kind.OPEN_MANY && paths.size() > 1) return "names " + paths.size() + " paths for one";
        for (Path path : paths) {
            boolean fits = switch (kind) {
                case OPEN, OPEN_MANY -> Files.isRegularFile(path);
                case PROGRAM -> Files.isRegularFile(path) && Files.isExecutable(path);
                case FOLDER -> Files.isDirectory(path);
                case SAVE -> !Files.isDirectory(path) && path.toAbsolutePath().getParent() != null
                        && Files.isDirectory(path.toAbsolutePath().getParent());
            };
            if (!fits) return switch (kind) {
                case OPEN, OPEN_MANY -> "names no file at " + path;
                case PROGRAM -> "names no program at " + path;
                case FOLDER -> "names no folder at " + path;
                case SAVE -> Files.isDirectory(path) ? "names a folder, not a file: " + path
                        : "names a file in no folder: " + path;
            };
        }
        return null;
    }

    private static String said(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(", "));
    }

    private static FileChooser fileChooser(String title, Path initialDir, Filter filter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        initialDirectory(initialDir, chooser::setInitialDirectory);
        if (filter != null && !filter.extensions().isEmpty()) chooser.getExtensionFilters().add(filter.fx());
        return chooser;
    }

    /** A directory that does not exist is no directory at all — JavaFX throws rather than ignoring it. */
    private static void initialDirectory(Path dir, Consumer<File> set) {
        if (dir == null) return;
        File file = dir.toFile();
        if (file.isDirectory()) set.accept(file);
    }
}
