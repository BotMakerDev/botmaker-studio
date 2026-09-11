package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.project.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Which plugins this project holds data for, and which of them are not installed.
 *
 * <p>A plugin owns its own files under {@code src/main/resources/plugins/<author>/<plugin>/}, a folder per
 * author and a folder per plugin, derived from the plugin id alone. So a project carries the list of
 * everybody who has ever written something into it, and this class reads that list back: the folder names
 * are the id, joined by a dot.
 *
 * <h2>Why the host reads the tree at all</h2>
 *
 * <p>Every other question about a plugin's data is asked <em>of the plugin</em> — that is the whole of the
 * parameter surface, and {@link HostParameters} does nothing else. This one cannot be: the plugin whose data
 * is sitting in the project is the plugin that is <b>not there</b>, and an absent plugin answers nothing. The
 * only witness left is the project directory, and the host is the one thing that always has it.
 *
 * <p>It is also the narrowest possible reading. Nothing here opens a file, so the host learns no plugin's
 * format, no key and no value — only that somebody called {@code com.example.discord} has written something
 * into this project and is not here to read it back. The layout it does know is the one
 * {@code botmaker-plugin-basics} defines; Studio cannot depend on that module (it is a plugin), so the
 * convention is written down twice, which is stated here rather than hidden: the alternative is a project
 * that silently drops a plugin's data with no way to say so.
 *
 * <h2>What absent means</h2>
 *
 * <p>No <em>loaded</em> plugin claims the id. That covers the case the banner is for — the plugin was never
 * installed, or was removed from the pom while its data stayed — and it also covers a plugin that is
 * declared but would not load, which is a different problem with the same symptom. {@link PluginHost#failures}
 * is what separates those two, and Manage Plugins is where both are acted on, which is why the banner sends
 * the user there rather than trying to say which case this is.
 */
public final class PluginOwners {

    private PluginOwners() {}

    /** The directory every plugin's data lives under, inside a project's resources. */
    private static final String ROOT = "plugins";

    /** The ids of the plugins holding data in {@code config}'s project, in folder order. */
    public static List<String> owners(ProjectConfig config) {
        return config == null ? List.of() : owners(config.resourcesRoot());
    }

    /**
     * The ids holding data under {@code resourcesDir}, in folder order.
     *
     * <p>A folder that directly contains a {@code .json} is a plugin's folder, and its id is the path from
     * {@code plugins/} down to it with dots for separators — so {@code plugins/com.botmaker/sdk} is
     * {@code com.botmaker.sdk} and {@code plugins/discord}, an id with no author in it, is {@code discord}.
     * A folder holding nothing but folders is an author's, and a folder holding nothing at all names no
     * owner: an empty directory is what a delete leaves behind and is not data anybody is missing.
     */
    static List<String> owners(Path resourcesDir) {
        Path root = resourcesDir.resolve(ROOT);
        if (!Files.isDirectory(root)) return List.of();
        Set<String> found = new LinkedHashSet<>();
        collect(root, root, found, 0);
        return List.copyOf(found);
    }

    /** Walks at most two levels, which is every shape an id can take. */
    private static void collect(Path root, Path dir, Set<String> found, int depth) {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> children = entries.sorted().toList();
            boolean holdsData = children.stream()
                    .anyMatch(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"));
            if (holdsData) {
                found.add(root.relativize(dir).toString().replace(java.io.File.separatorChar, '.'));
                return;
            }
            // Two levels below plugins/, which is every shape an id can take: author then plugin, or a
            // single folder for an id with no dot in it.
            if (depth >= 2) return;
            for (Path child : children) {
                if (Files.isDirectory(child)) collect(root, child, found, depth + 1);
            }
        } catch (IOException | RuntimeException unreadable) {
            // A project whose resources cannot be walked reports no owners, which is the direction that
            // shows no banner: a warning about data nobody can prove is there would be its own bug report.
        }
    }

    /** The owners of {@code config}'s data that no loaded plugin answers for. */
    public static List<String> absent(ProjectConfig config) {
        return absent(owners(config), PluginOwners::isLoaded);
    }

    /** The shape question, so a test needs neither a project nor a loaded plugin. */
    static List<String> absent(List<String> owners, Predicate<String> loaded) {
        List<String> missing = new ArrayList<>();
        for (String id : owners) {
            if (!loaded.test(id)) missing.add(id);
        }
        return List.copyOf(missing);
    }

    private static boolean isLoaded(String id) {
        for (StudioPlugin plugin : PluginHost.plugins()) {
            if (id.equals(plugin.id())) return true;
        }
        return false;
    }
}
