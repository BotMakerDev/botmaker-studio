package com.botmaker.studio.sharing;

import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.MavenService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The plugins a user can add to a project, and the version each installs at: the registry's entries with what is
 * built locally taking precedence. One list, read by <i>Plugins &amp; Libraries ▸ Browse</i> and by New Project, so
 * the two never offer different plugins or different versions (2026-10-01).
 */
public final class PluginCatalog {

    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    public PluginCatalog(PluginRegistry registry, JitPackSearch jitpack) {
        this.registry = registry;
        this.jitpack = jitpack;
    }

    /** The rows to offer, and which coordinates a {@code ~/.m2} dev build answers for. */
    public record Rows(List<PluginRegistry.Plugin> plugins, Set<String> localCoordinates) {
        public Rows {
            plugins = List.copyOf(plugins);
            localCoordinates = Set.copyOf(localCoordinates);
        }
    }

    /**
     * The registry joined with the local builds. The {@code ~/.m2} scan opens jars, so it runs off the caller's
     * thread, and it never waits on the network: a plugin author testing an unpublished build may have no
     * registry at all. Both halves are joined before anything is answered, so rows never re-order under the mouse.
     */
    public CompletableFuture<Rows> rows() {
        CompletableFuture<List<MavenService.LocalPluginBuild>> local =
                CompletableFuture.supplyAsync(MavenService::localPluginBuilds);
        return registry.browse().thenCombine(local, PluginCatalog::merge);
    }

    /** The verified version, or — only when the entry carries none — JitPack's newest. */
    public CompletableFuture<String> version(PluginRegistry.Plugin plugin) {
        if (!plugin.verifiedVersion().isBlank()) {
            return CompletableFuture.completedFuture(plugin.verifiedVersion());
        }
        return jitpack.fetchLatestVersion(plugin.groupId(), plugin.artifactId());
    }

    /**
     * The registry's entries, with what is built locally taking precedence over what is published.
     *
     * <p>A local build of a coordinate the registry also lists <b>replaces that entry's version</b> rather
     * than adding a second row: two rows for one artifact would offer to install two versions of it, and a
     * developer who has just built one wants the one they built. A local build nobody has published yet
     * becomes a row of its own, at the top, because it is the row they came here for.
     *
     * <p>Only ever populated in a dev build — {@link MavenService#localPluginBuilds()} answers empty
     * otherwise — so a released Studio shows exactly the registry and nothing else. Pure.
     */
    static Rows merge(List<PluginRegistry.Plugin> published, List<MavenService.LocalPluginBuild> builds) {
        Set<String> localCoordinates = new LinkedHashSet<>();
        List<PluginRegistry.Plugin> rows = new ArrayList<>(published);
        for (MavenService.LocalPluginBuild build : builds) {
            localCoordinates.add(build.coordinate());
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).coordinate().equals(build.coordinate())) at = i;
            }
            if (at >= 0) {
                PluginRegistry.Plugin entry = rows.get(at);
                // The version is the local build's; everything else is still the registry's, the editor
                // dependencies included — a developer's own build of a plugin needs exactly what the
                // published one does.
                rows.set(at, new PluginRegistry.Plugin(entry.id(), entry.name(), entry.coordinate(),
                        entry.repo(), entry.description(), entry.tags(), entry.minContractVersion(),
                        entry.editorDependencies(), build.version(),
                        entry.verifiedAt()));
            } else {
                // A local build the registry has never seen has no entry to read a list from, so installing
                // it declares the plugin alone. That is the honest answer — nothing here can know what a
                // jar's optional dependencies are — and the way out is `botmaker plugin publish`.
                rows.add(0, new PluginRegistry.Plugin(build.coordinate(), build.artifactId(),
                        build.coordinate(), "", "Built locally into ~/.m2 — not published.", List.of(), "",
                        List.of(), build.version(), ""));
            }
        }
        return new Rows(rows, localCoordinates);
    }
}
