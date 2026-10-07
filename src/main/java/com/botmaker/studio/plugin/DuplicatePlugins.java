package com.botmaker.studio.plugin;

import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.services.MavenService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One plugin, one jar: the classpath entries a project gets twice because its pom names the same artifact
 * under both of BotMaker's groupIds (2026-10-06).
 *
 * <p>The coordinate moved from {@code com.github.LiQiyeDev} to {@code com.github.BotMakerDev} on 2026-10-05,
 * and existing bots were not migrated. A bot pinning the SDK under the old groupId and then installing it
 * under the new one declares <em>two</em> Maven artifacts, so Maven resolves both jars — and both of their
 * trees, so plugin-basics, shared and session twice too. {@code PluginLoader} opens one classloader over all
 * of them, and a class name has one answer there: the first jar listing it. The result was neither version
 * but a mix, with nothing on screen to say so.
 *
 * <p>So the loader is given one side only. The side kept is the new groupId's whenever one of its plugin jars
 * is on the classpath, else the old one's; every entry of the other side whose artifact the kept side also
 * has is left off. A plugin jar left off is reported, so the Plugins window can say what was dropped and offer
 * the pom fix — the pom itself is the user's to change, in Installed.
 */
public final class DuplicatePlugins {

    /** BotMaker's groupId since 2026-10-05. */
    public static final String CURRENT_GROUP = "com.github.BotMakerDev";

    /** BotMaker's groupId before 2026-10-05; tags built under it still resolve under it. */
    public static final String FORMER_GROUP = "com.github.LiQiyeDev";

    /** What a dropped plugin jar is reported with, after its coordinate. */
    static final String DROPPED = "declared twice, under com.github.LiQiyeDev and com.github.BotMakerDev; "
            + "Studio loads one of them. Keep one in Project ▸ Plugins & Libraries ▸ Installed";

    private DuplicatePlugins() {}

    /** What the loader may open, and the plugin jars left off it because the other groupId has them. */
    public record Split(List<String> loadable, List<PluginLoader.PluginFailure> dropped) {
        public Split {
            loadable = List.copyOf(loadable);
            dropped = List.copyOf(dropped);
        }
    }

    /** Whether {@code groupId} is one of BotMaker's two. */
    public static boolean isBotMakerGroup(String groupId) {
        return CURRENT_GROUP.equals(groupId) || FORMER_GROUP.equals(groupId);
    }

    /**
     * Whether two coordinates name the same artifact: the same groupId, or BotMaker's two groupIds, and the
     * same artifactId.
     */
    public static boolean sameArtifact(String groupA, String artifactA, String groupB, String artifactB) {
        if (!artifactA.equals(artifactB)) return false;
        return groupA.equals(groupB) || (isBotMakerGroup(groupA) && isBotMakerGroup(groupB));
    }

    /** {@link #split(List, Predicate)} over the jars' own service files. */
    public static Split split(List<String> classpath) {
        return split(classpath, MavenService::declaresPlugin);
    }

    /**
     * Keeps one groupId's copy of each artifact that {@code classpath} holds under both.
     *
     * @param declaresPlugin whether a jar carries the {@code StudioPlugin} service entry; asked only of
     *                       entries under BotMaker's two groupIds
     */
    public static Split split(List<String> classpath, Predicate<Path> declaresPlugin) {
        Set<String> current = new HashSet<>();
        Set<String> former = new HashSet<>();
        boolean currentPlugin = false;
        for (String entry : classpath) {
            Path jar = Path.of(entry);
            String group = groupOf(jar);
            if (group.isEmpty()) continue;
            String artifact = artifactOf(jar);
            if (group.equals(CURRENT_GROUP)) {
                current.add(artifact);
                if (!currentPlugin && declaresPlugin.test(jar)) currentPlugin = true;
            } else {
                former.add(artifact);
            }
        }
        Set<String> both = new HashSet<>(current);
        both.retainAll(former);
        if (both.isEmpty()) return new Split(classpath, List.of());

        String losing = currentPlugin ? FORMER_GROUP : CURRENT_GROUP;
        List<String> loadable = new ArrayList<>();
        List<PluginLoader.PluginFailure> dropped = new ArrayList<>();
        for (String entry : classpath) {
            Path jar = Path.of(entry);
            if (!groupOf(jar).equals(losing) || !both.contains(artifactOf(jar))) {
                loadable.add(entry);
            } else if (declaresPlugin.test(jar)) {
                dropped.add(new PluginLoader.PluginFailure(losing + ":" + ReleasedPlugins.describe(entry),
                        new IllegalStateException(DROPPED)));
            }
        }
        return new Split(loadable, dropped);
    }

    /**
     * Which of BotMaker's groupIds a resolved jar sits under, read off the repository layout
     * ({@code …/com/github/<org>/<artifact>/<version>/<jar>}), or {@code ""} for any other jar.
     */
    public static String groupOf(Path jar) {
        Path artifactDir = parent(parent(jar));
        Path org = parent(artifactDir);
        Path github = parent(org);
        Path com = parent(github);
        if (org == null || github == null || com == null) return "";
        if (!"com".equals(name(com)) || !"github".equals(name(github))) return "";
        String group = "com.github." + name(org);
        return isBotMakerGroup(group) ? group : "";
    }

    private static String artifactOf(Path jar) {
        return name(parent(parent(jar)));
    }

    private static Path parent(Path path) {
        return path == null ? null : path.getParent();
    }

    private static String name(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }
}
