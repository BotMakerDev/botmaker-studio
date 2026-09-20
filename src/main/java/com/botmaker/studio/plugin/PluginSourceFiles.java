package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.PluginSource;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectWrites;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Studio's own side of {@code StudioPlugin.pluginSources()} — the file a plugin gives a bot, put into the
 * project once.
 *
 * <p>Each entry lands at {@code src/main/java/<bot package>/plugins/<last id segment>/<Name>.java}, with
 * {@link PluginSource#PACKAGE} replaced by the package that path spells. From that moment it is the user's
 * file: nothing here ever writes over one that exists, and nothing anywhere regenerates it. What Studio does
 * to it afterwards is rewrite the expression a {@code @Managed} method returns
 * ({@code project/managed/JavaManagedEdits}), one node at a time.
 *
 * <h2>Why this runs on every bind rather than on the install click</h2>
 *
 * <p>A plugin is installed by writing its coordinate into the pom, and at that moment its jar is not on any
 * classloader — there is nothing to ask for a {@code PluginSource}. The plugin becomes askable at the next
 * {@code PluginHost.bind}, which is exactly what follows a pom write ({@code LibraryService.rebind}) and
 * what opening a project does ({@code BotProject}). So "when the plugin is added" is answered by "the first
 * bind that sees it", and running on every bind costs nothing because a file that is already there is left
 * alone.
 *
 * <p>That also covers the two cases an install-time hook would miss: a project whose pom already named the
 * plugin before this feature existed, and a user who deleted the file and wants it back by reloading.
 *
 * <h2>The file is not locked, and that is deliberate</h2>
 *
 * <p>{@code FileRole.GENERATED} classed everything one package below {@code plugins/} as read-only, from
 * the withdrawn design where the host wrote that file whole. It is deleted with this change: the host
 * generates nothing any more, and locking the file a plugin hands the user would fight the whole point of
 * handing it over. Only a {@code @Managed} body is refused on the canvas, by {@code LockResolver}, and only
 * because another window owns that one expression.
 */
public final class PluginSourceFiles {

    /** The package under the bot's own that holds one package per plugin. */
    public static final String PLUGINS = "plugins";

    private PluginSourceFiles() {}

    /** Puts every bound plugin's file into {@code config}'s project, and answers the ones newly written. */
    public static List<Path> install(ProjectConfig config) {
        return install(config, PluginHost.plugins());
    }

    /**
     * The same for a given plugin set — the seam a test uses.
     *
     * <p>A plugin that throws costs only its own file, the rule every other contribution surface follows:
     * a project must open even when one plugin is broken.
     */
    public static List<Path> install(ProjectConfig config, List<StudioPlugin> plugins) {
        if (config == null || plugins == null) return List.of();
        List<Path> written = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            try {
                install(config, plugin, written);
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply ships no file.
                System.err.println("Warning: " + plugin.id() + " could not supply its source: " + e);
            }
        }
        return List.copyOf(written);
    }

    private static void install(ProjectConfig config, StudioPlugin plugin, List<Path> written) {
        List<PluginSource> sources = plugin.pluginSources();
        if (sources == null || sources.isEmpty()) return;
        String segment = segmentOf(plugin.id());
        if (segment.isEmpty()) return;
        Path dir = config.mainPackageDir().resolve(PLUGINS).resolve(segment);
        String packageName = packageOf(config.mainPackage(), segment);
        for (PluginSource source : sources) {
            if (source == null || !source.isPresent()) continue;
            Path file = dir.resolve(source.fileName());
            if (Files.isRegularFile(file)) continue;
            ProjectWrites.create(config, file, source.sourceIn(packageName),
                    "Add " + plugin.id() + "'s " + source.fileName());
            written.add(file);
        }
    }

    /**
     * The package a plugin's file lands in: the bot's own, then {@code plugins}, then the id's last segment.
     *
     * <p>A bot with no package of its own puts them at the top level rather than nowhere, which is the
     * answer that still compiles.
     */
    public static String packageOf(String mainPackage, String segment) {
        String head = mainPackage == null || mainPackage.isBlank() ? "" : mainPackage + ".";
        return head + PLUGINS + "." + segment;
    }

    /**
     * The last segment of a plugin id, as a Java package name — {@code com.botmaker.sdk} is {@code sdk}.
     *
     * <p>The same segment {@code PluginData} names a plugin's resource folder with, so the Java package and
     * the data folder read alike. It is then made a legal identifier, which a folder name does not have to
     * be: anything a package name may not hold becomes an underscore, and a segment starting with a digit
     * gains one — an id is a plugin's to choose, and not every legal id is a legal identifier.
     */
    public static String segmentOf(String id) {
        if (id == null || id.isBlank()) return "";
        String last = id.substring(id.lastIndexOf('.') + 1).strip().toLowerCase(java.util.Locale.ROOT);
        if (last.isEmpty()) return "";
        StringBuilder out = new StringBuilder(last.length());
        for (int i = 0; i < last.length(); i++) {
            char c = last.charAt(i);
            out.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (!Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, '_');
        return out.toString();
    }
}
