package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.plugin.DuplicatePlugins;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.PluginRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * One plugin this project has installed, with the version it pins and the version it could move to — one
 * row of the project upgrade window.
 *
 * <p><b>Installed means the pom declares it.</b> That is the same rule {@code PluginRegistry.isInstalledIn}
 * uses and it is the only defensible one: a plugin that arrives transitively is something another plugin
 * brought, not something the user chose, and offering to change its version would write a pin over a
 * decision Maven's own mediation is making. So the list is built from {@code readDeclaredLibraries} and
 * never from the resolved classpath.
 *
 * <p><b>Nothing here reaches the network.</b> {@link #of} is given what the registry said, what
 * {@code ~/.m2} holds and a predicate that answers whether a jar is a plugin; each of those is fetched or
 * scanned by the caller, and each degrades to a sentence rather than to an empty table — an unreachable
 * registry leaves {@link #available()} blank on the rows it would have answered, and the window says
 * <i>could not check</i> rather than showing the user nothing.
 */
public record InstalledPlugin(UserLibrary artifact, String displayName, String installed,
                              String available, Source source, List<UserLibrary> editorDependencies,
                              List<String> approved) {

    /**
     * How this row came to be known, which is also how far its {@link #available()} can be trusted.
     *
     * <p>The three cases are the ones <b>Manage Plugins</b> already distinguishes; naming them here is what
     * stops the upgrade window inventing a fourth.
     */
    public enum Source {
        /**
         * The catalog lists it. {@code available} is the newest release it approved, and {@code approved} every
         * one, newest first (2026-10-24): what the version menu offers.
         */
        REGISTRY,
        /** The pom declares a plugin nothing else accounts for — published by hand, or not published. */
        UNLISTED
    }

    public InstalledPlugin {
        displayName = displayName == null || displayName.isBlank() ? artifact.artifactId() : displayName.trim();
        installed = installed == null ? "" : installed.trim();
        available = available == null ? "" : available.trim();
        editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
        approved = approved == null ? List.of() : List.copyOf(approved);
    }

    /** {@code groupId:artifactId} — the identity, since the version is what the row is about. */
    public String coordinate() {
        return artifact.groupId() + ":" + artifact.artifactId();
    }

    /**
     * Whether moving is even on offer: something to move to, and somewhere different to move from.
     *
     * <p>It says nothing about the <em>direction</em>. A row whose available version is older than its
     * installed one is a downgrade, which is a supported operation and the same control.
     */
    public boolean canChange() {
        return !available.isBlank() && !available.equals(installed);
    }

    /** The same row with {@code available} filled in — how an async version lookup lands on a built row. */
    public InstalledPlugin withAvailable(String version) {
        return new InstalledPlugin(artifact, displayName, installed, version, source, editorDependencies, approved);
    }

    /**
     * Every plugin {@code declared} contains, in pom order.
     *
     * <p>The two sources are tried in order of how much they know — a registry entry carries a name, a
     * description and the editor dependencies; an unlisted plugin carries only its coordinate. (A third, a
     * {@code ~/.m2} dev build, went on 2026-10-01 with the scan behind it.)
     *
     * @param declared what the pom declares — {@code LibraryService.declaredLibraries()}
     * @param registry the registry's entries, empty when it could not be read
     * @param isPlugin whether a coordinate the registry does not account for is a plugin at all. Live,
     *                 that is {@link #jarDeclaresPlugin}; a test hands in a set.
     */
    public static List<InstalledPlugin> of(List<UserLibrary> declared,
                                           List<PluginRegistry.Plugin> registry,
                                           Predicate<UserLibrary> isPlugin) {
        List<InstalledPlugin> rows = new ArrayList<>();
        for (UserLibrary library : declared) {
            // Either of BotMaker's groupIds (2026-10-07): an entry still naming com.github.LiQiyeDev left the
            // com.github.BotMakerDev SDK UNLISTED, with no editor dependencies to install or keep.
            PluginRegistry.Plugin entry = PluginRegistry.entryFor(registry, library).orElse(null);

            if (entry != null) {
                // The entry's version is a tag under the entry's groupId; offered under the other one, it
                // would pin a coordinate that tag was never released at.
                boolean sameGroup = entry.groupId().equals(library.groupId());
                rows.add(new InstalledPlugin(library, entry.name(), library.version(),
                        sameGroup ? entry.version() : "", Source.REGISTRY, entry.editorLibraries(),
                        sameGroup ? entry.approvedTags() : List.of()));
            } else if (isPlugin.test(library)) {
                // No entry to read a name, a description or an editor-dependency list from. The coordinate
                // is the whole of what is known, and an empty `available` is the truthful answer until
                // somebody asks JitPack — which this does not, because nothing here reaches the network.
                rows.add(new InstalledPlugin(library, library.artifactId(), library.version(),
                        "", Source.UNLISTED, List.of(), List.of()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The live {@code isPlugin} predicate: resolve the coordinate and ask the jar.
     *
     * <p>{@code MavenService.declaresPlugin} is the project's one definition of the question — the
     * {@code botmaker.plugin} property of the jar's pom, which the loader itself reads. <b>Resolution is local-or-nothing
     * in practice and must stay best-effort</b>: a coordinate that has never been downloaded answers false
     * and the row is simply absent, which is the right failure. The alternative — listing a dependency as a
     * plugin because its name looks like one — puts a row in the window whose Check can only fail.
     */
    public static Predicate<UserLibrary> jarDeclaresPlugin(Path projectDir) {
        return library -> {
            Optional<Path> jar = MavenService.resolveArtifact(
                    projectDir, library.groupId(), library.artifactId(), "", library.version());
            return jar.isPresent() && MavenService.declaresPlugin(jar.get());
        };
    }

    /**
     * {@code rows} grouped by artifact, in pom order: a group of two is one plugin the pom declares under both
     * of BotMaker's groupIds (2026-10-06, {@code DuplicatePlugins}). Every other plugin is a group of one.
     */
    public static List<List<InstalledPlugin>> sameArtifactGroups(List<InstalledPlugin> rows) {
        List<List<InstalledPlugin>> groups = new ArrayList<>();
        for (InstalledPlugin row : rows) {
            List<InstalledPlugin> group = groups.stream()
                    .filter(g -> DuplicatePlugins.sameArtifact(g.getFirst().artifact().groupId(),
                            g.getFirst().artifact().artifactId(), row.artifact().groupId(), row.artifact().artifactId()))
                    .findFirst().orElse(null);
            if (group == null) groups.add(group = new ArrayList<>());
            group.add(row);
        }
        return groups.stream().map(List::copyOf).toList();
    }

    /**
     * The row of a group that stays: the one Studio loaded, else the one under the current groupId, else the
     * first the pom declares.
     */
    public static InstalledPlugin keeper(List<InstalledPlugin> group, Predicate<InstalledPlugin> loaded) {
        return group.stream().filter(loaded).findFirst()
                .or(() -> group.stream()
                        .filter(row -> DuplicatePlugins.CURRENT_GROUP.equals(row.artifact().groupId())).findFirst())
                .orElse(group.getFirst());
    }
}
