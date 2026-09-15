package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.PluginRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                              String available, Source source, List<UserLibrary> editorDependencies) {

    /**
     * How this row came to be known, which is also how far its {@link #available()} can be trusted.
     *
     * <p>The three cases are the ones <b>Manage Plugins</b> already distinguishes; naming them here is what
     * stops the upgrade window inventing a fourth.
     */
    public enum Source {
        /** The plugin registry lists it. {@code available} is the entry's {@code verifiedVersion}. */
        REGISTRY,
        /** A {@code *SNAPSHOT} build in {@code ~/.m2}, which is a plugin author's own working copy. */
        LOCAL_BUILD,
        /** The pom declares a plugin nothing else accounts for — published by hand, or not published. */
        UNLISTED
    }

    public InstalledPlugin {
        displayName = displayName == null || displayName.isBlank() ? artifact.artifactId() : displayName.trim();
        installed = installed == null ? "" : installed.trim();
        available = available == null ? "" : available.trim();
        editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
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
        return new InstalledPlugin(artifact, displayName, installed, version, source, editorDependencies);
    }

    /**
     * Every plugin {@code declared} contains, in pom order.
     *
     * <p>The three sources are tried in order of how much they know — a registry entry carries a name, a
     * description and the editor dependencies; a local build carries a version and nothing else; an
     * unlisted plugin carries only its coordinate. A coordinate matching <b>both</b> the registry and a
     * local build is a {@code LOCAL_BUILD} row keeping the registry's editor dependencies, which is exactly
     * what {@code ManagePluginsDialog.merge} already does and for the same reason: a developer's own build
     * of a plugin needs what the published one needs.
     *
     * @param declared    what the pom declares — {@code LibraryService.declaredLibraries()}
     * @param registry    the registry's entries, empty when it could not be read
     * @param localBuilds {@code MavenService.localPluginBuilds()}, empty in a packaged Studio
     * @param isPlugin    whether a coordinate the first two do not account for is a plugin at all. Live,
     *                    that is {@link #jarDeclaresPlugin}; a test hands in a set.
     */
    public static List<InstalledPlugin> of(List<UserLibrary> declared,
                                           List<PluginRegistry.Plugin> registry,
                                           List<MavenService.LocalPluginBuild> localBuilds,
                                           Predicate<UserLibrary> isPlugin) {
        Map<String, PluginRegistry.Plugin> listed = new LinkedHashMap<>();
        for (PluginRegistry.Plugin entry : registry) {
            if (entry.isInstallable()) listed.put(entry.coordinate(), entry);
        }
        Map<String, String> built = new LinkedHashMap<>();
        for (MavenService.LocalPluginBuild build : localBuilds) {
            built.putIfAbsent(build.coordinate(), build.version());
        }

        List<InstalledPlugin> rows = new ArrayList<>();
        for (UserLibrary library : declared) {
            String coordinate = library.groupId() + ":" + library.artifactId();
            PluginRegistry.Plugin entry = listed.get(coordinate);
            String localVersion = built.get(coordinate);

            if (localVersion != null) {
                rows.add(new InstalledPlugin(library,
                        entry == null ? library.artifactId() : entry.name(),
                        library.version(), localVersion, Source.LOCAL_BUILD,
                        entry == null ? List.of() : entry.editorLibraries()));
            } else if (entry != null) {
                rows.add(new InstalledPlugin(library, entry.name(), library.version(),
                        entry.verifiedVersion(), Source.REGISTRY, entry.editorLibraries()));
            } else if (isPlugin.test(library)) {
                // No entry to read a name, a description or an editor-dependency list from. The coordinate
                // is the whole of what is known, and an empty `available` is the truthful answer until
                // somebody asks JitPack — which this does not, because nothing here reaches the network.
                rows.add(new InstalledPlugin(library, library.artifactId(), library.version(),
                        "", Source.UNLISTED, List.of()));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The live {@code isPlugin} predicate: resolve the coordinate and ask the jar.
     *
     * <p>{@code MavenService.declaresPlugin} is the project's one definition of the question — the
     * {@code META-INF/services} entry {@code ServiceLoader} itself reads. <b>Resolution is local-or-nothing
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
     * The simple type names <b>more than one</b> of these jars declares — every name no report may attribute.
     *
     * <p>Attribution is by the simple name the bot's source writes ({@code ApiReferences}), because there
     * are no bindings; two plugins declaring {@code Point} therefore make {@code Point.of(…)} unanswerable.
     * This is the set {@code PluginUpgradeService} is handed so it can refuse rather than guess — naming the
     * wrong plugin would report a break in a class the bot never touched <em>and rewrite it there</em>,
     * which is the one outcome worse than a compile error.
     *
     * <p>It is computed from the jars rather than from the coordinates because a clash is a property of
     * what two plugins actually declare, and nothing but the bytecode knows that.
     *
     * @param byCoordinate each plugin's coordinate mapped to the type names its jar declares —
     *                     {@code ApiModel.snapshot(jar).keySet()}
     */
    /**
     * The same set, computed from these rows' own installed jars — what the window hands every service it
     * builds.
     *
     * <p>Each jar is resolved at the version the row says is <em>installed</em>, because that is the one the
     * bot's source was written against and so the one attribution is being done over. A row whose jar cannot
     * be resolved contributes nothing rather than refusing: a coordinate nobody can fetch declares no names
     * that could clash.
     *
     * <p><b>Blocking</b> — it scans one jar per row. Call it off the FX thread.
     */
    public static Set<String> ambiguousAmong(Path projectDir, List<InstalledPlugin> rows) {
        Map<String, Set<String>> byCoordinate = new LinkedHashMap<>();
        for (InstalledPlugin row : rows) {
            MavenService.resolveArtifact(projectDir, row.artifact().groupId(), row.artifact().artifactId(),
                            "", row.installed())
                    .ifPresent(jar -> byCoordinate.put(row.coordinate(), ApiModel.snapshot(jar).keySet()));
        }
        return ambiguousTypeNames(byCoordinate);
    }

    public static Set<String> ambiguousTypeNames(Map<String, Set<String>> byCoordinate) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> twice = new LinkedHashSet<>();
        for (Set<String> names : byCoordinate.values()) {
            for (String name : names) {
                if (!seen.add(name)) twice.add(name);
            }
        }
        return Set.copyOf(twice);
    }
}
