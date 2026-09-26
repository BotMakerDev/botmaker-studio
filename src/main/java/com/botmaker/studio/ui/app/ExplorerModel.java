package com.botmaker.studio.ui.app;

import com.botmaker.studio.nav.SourceNavigation;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectState.SourceFile;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VcsFileStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * What the Project Files explorer shows, with no JavaFX in it (2026-09-26): the folder tree with its package
 * chains folded, what kind of file each row is, which rows a filter keeps, and how each file differs from the
 * last saved version. {@link FileExplorerManager} draws it.
 */
final class ExplorerModel {

    private ExplorerModel() {}

    /**
     * What a row is, for its icon. A closed set drawn as small vector shapes ({@code svg}), so an icon scales
     * and takes the theme's colour rather than being an emoji the platform font draws its own way.
     */
    enum Kind {
        FOLDER("folder", "Folder", "M1 3h4l1 1.2h5V10H1z"),
        ENTRY_POINT("entry-point", "Entry point — where the bot starts", "M3 1.5l7.5 4.5L3 10.5z"),
        PARAMETERS("parameters", "Parameters (@Param fields)", "M1 2.5h10v1H1zM1 8h10v1H1zM3 1h2v4H3zM7 6.5h2v4H7z"),
        PLUGIN_FILE("plugin-file", "A plugin's file — yours to edit", "M3 1h1.2v3H3zM7.8 1H9v3H7.8zM2 4h8v2.2A4 4 0 0 1 6.6 9.6V11H5.4V9.6A4 4 0 0 1 2 6.2z"),
        JAVA("java", "Java source", "M2 1h5l3 3v7H2zM7 1v3h3"),
        LIBRARY("library", "Bundled library code — read only", "M1 3l5-2 5 2v6l-5 2-5-2zM6 5v6M1 3l5 2 5-2"),
        PICTURE("picture", "Picture", "M1 2h10v8H1zM2 9l3-3.2 2 2 1.2-1.2L10 9zM8 4.5a1 1 0 1 0 0.01 0z"),
        DATA("data", "Data file", "M3 1h6v10H3zM4.2 3h3.6v1H4.2zM4.2 5h3.6v1H4.2zM4.2 7h2.6v1H4.2z"),
        OTHER("other", "File", "M2 1h5l3 3v7H2zM7 1v3h3");

        private final String id;
        private final String displayName;
        private final String svg;

        Kind(String id, String displayName, String svg) {
            this.id = id;
            this.displayName = displayName;
            this.svg = svg;
        }

        /** The style class suffix, {@code tree-kind-<id>}. */
        String id() {
            return id;
        }

        /** The row's tooltip. */
        String displayName() {
            return displayName;
        }

        /** The icon, an SVG path on a 12×12 grid. */
        String svg() {
            return svg;
        }
    }

    /**
     * A folder: its label (a folded package chain reads {@code com.mybot}), its path, and what is under it.
     * Folders first, by name; then files, by name.
     */
    record Folder(String label, Path path, List<Folder> folders, List<Path> files) {

        boolean isEmpty() {
            return folders.isEmpty() && files.isEmpty();
        }
    }

    /**
     * The tree under {@code root}, keeping the files {@code include} accepts and dropping a folder left with
     * nothing in it. A folder holding exactly one folder and no file is folded into it — {@code com}, then
     * {@code mybot}, is one row, {@code com.mybot}: the chain is Java's ceremony, not a place anybody opens.
     * Empty when {@code root} is not a directory.
     */
    static Folder tree(Path root, Predicate<Path> include) {
        Folder built = walk(root, root.getFileName() == null ? "" : root.getFileName().toString(), include);
        return built == null ? new Folder("", root, List.of(), List.of()) : built;
    }

    private static Folder walk(Path dir, String label, Predicate<Path> include) {
        if (!Files.isDirectory(dir)) return null;
        List<Path> children;
        try (Stream<Path> list = Files.list(dir)) {
            children = list.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException unreadable) {
            return null;
        }
        List<Folder> folders = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                Folder sub = walk(child, child.getFileName().toString(), include);
                if (sub != null && !sub.isEmpty()) folders.add(fold(sub));
            } else if (include.test(child)) {
                files.add(child);
            }
        }
        return new Folder(label, dir, List.copyOf(folders), List.copyOf(files));
    }

    /** {@code folder} with any chain of lone sub-folders folded into its label. */
    private static Folder fold(Folder folder) {
        Folder f = folder;
        while (f.files().isEmpty() && f.folders().size() == 1) {
            Folder only = f.folders().getFirst();
            f = new Folder(f.label() + "." + only.label(), only.path(), only.folders(), only.files());
        }
        return f;
    }

    /** The filter's test: a file stays when its name matches {@code query}; everything stays for a blank one. */
    static Predicate<Path> matching(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return p -> true;
        return p -> SourceNavigation.match(q, p.getFileName().toString()) > 0;
    }

    /**
     * What {@code file} is. {@code source} is its text for a {@code .java} file (null otherwise), and
     * {@code entryPoint} the file declaring the bot's {@code main}, or null.
     */
    static Kind kindOf(Path file, String source, Path entryPoint) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            if (FileRole.of(file) == FileRole.LIBRARY) return Kind.LIBRARY;
            if (file.equals(entryPoint)) return Kind.ENTRY_POINT;
            if (isPluginFile(file)) return Kind.PLUGIN_FILE;
            if (source != null && source.contains("@Param")) return Kind.PARAMETERS;
            return Kind.JAVA;
        }
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp" -> Kind.PICTURE;
            case "json", "properties", "txt", "yml", "yaml", "xml", "csv" -> Kind.DATA;
            default -> Kind.OTHER;
        };
    }

    /**
     * A file a plugin handed the bot: {@code …/plugins/<segment>/<Name>.java}, exactly one package below
     * {@code plugins} — where {@code HostPluginValues} writes one.
     */
    static boolean isPluginFile(Path file) {
        Path segment = file.getParent();
        Path plugins = segment == null ? null : segment.getParent();
        return plugins != null && plugins.getFileName() != null && plugins.getFileName().toString().equals("plugins");
    }

    /**
     * How each file differs from the last saved version: git's answer for what is on disk, plus every open
     * buffer whose text is not what is on disk (the editor writes only on a run, so an edit made since reads as
     * a change here before git can see it). By absolute path; a file absent from the map is unchanged.
     */
    static Map<Path, VcsFileStatus> status(Path projectDir, ProjectVcs.FileStatus git, List<SourceFile> buffers) {
        Map<Path, VcsFileStatus> out = new HashMap<>();
        Path root = projectDir.toAbsolutePath().normalize();
        git.labelled().forEach((relative, status) -> out.put(root.resolve(relative).normalize(), status));
        for (SourceFile buffer : buffers) {
            if (buffer.path() == null) continue;
            Path path = buffer.path().toAbsolutePath().normalize();
            if (out.containsKey(path) || !Files.isRegularFile(path)) continue;
            try {
                if (!Objects.equals(Files.readString(path), buffer.content())) out.put(path, VcsFileStatus.MODIFIED);
            } catch (IOException unreadable) {
                // Nothing to compare against; the row stays untinted rather than guessing.
            }
        }
        return out;
    }
}
