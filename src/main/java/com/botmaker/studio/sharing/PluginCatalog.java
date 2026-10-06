package com.botmaker.studio.sharing;

import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LocalBuilds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The plugins a user can add to a project, and the version each installs at: the registry's entries. One list,
 * read by <i>Plugins &amp; Libraries ▸ Browse</i> and by New Project, so the two never offer different plugins or
 * different versions (2026-10-01).
 *
 * <p>It also merged {@code ~/.m2} dev builds over the registry until later the same day, when they were removed:
 * a project made from a local build pinned a version nobody else could resolve, and a bot published from it
 * carried that pin into the gallery. A released plugin is what Studio offers, except to a project in dev mode,
 * whose Browse tab reads {@link #withLocalBuilds} (2026-10-06).
 */
public final class PluginCatalog {

    private final PluginRegistry registry;
    private final JitPackSearch jitpack;

    public PluginCatalog(PluginRegistry registry, JitPackSearch jitpack) {
        this.registry = registry;
        this.jitpack = jitpack;
    }

    /** The rows to offer: the registry's entries, empty when it cannot be read. */
    public CompletableFuture<List<PluginRegistry.Plugin>> rows() {
        return registry.browse();
    }

    /**
     * The registry's entries with the {@code ~/.m2} plugin builds over them, for a project in dev mode
     * (2026-10-06). A build of a listed coordinate replaces that entry's version, keeping its name and editor
     * dependencies — a dev build needs what the release needs, and two rows for one artifact would offer to
     * install it twice. A build nobody has listed becomes a row of its own, at the top, in {@code builds}' order
     * (newest first). Pure.
     *
     * <p>This merge was here until 2026-10-01 for every project, and went because a bot made from a local build
     * was published with a pin nobody else could resolve; dev mode is per project, and Publish refuses the pin.
     */
    public static List<PluginRegistry.Plugin> withLocalBuilds(List<PluginRegistry.Plugin> published,
                                                              List<LocalBuilds.Build> builds) {
        List<PluginRegistry.Plugin> rows = new ArrayList<>(published);
        int unlisted = 0;                       // where the next unlisted build goes: the builds' own order
        for (LocalBuilds.Build build : builds) {
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).coordinate().equals(build.coordinate())) at = i;
            }
            if (at >= 0) {
                PluginRegistry.Plugin entry = rows.get(at);
                rows.set(at, new PluginRegistry.Plugin(entry.id(), entry.name(), entry.coordinate(), entry.repo(),
                        entry.description(), entry.tags(), entry.minContractVersion(), entry.editorDependencies(),
                        build.version(), entry.verifiedAt()));
            } else {
                // Nothing lists its optional dependencies, so installing it declares the plugin alone.
                rows.add(unlisted++, new PluginRegistry.Plugin(build.coordinate(), build.artifactId(), build.coordinate(),
                        "", LOCAL_BUILD_DESCRIPTION, List.of(), "", List.of(), build.version(), ""));
            }
        }
        return List.copyOf(rows);
    }

    /** What a local build's own row says about it. */
    public static final String LOCAL_BUILD_DESCRIPTION = "Built on this computer into ~/.m2 — not published.";

    /** The verified version, or — only when the entry carries none — JitPack's newest. */
    public CompletableFuture<String> version(PluginRegistry.Plugin plugin) {
        if (!plugin.verifiedVersion().isBlank()) {
            return CompletableFuture.completedFuture(plugin.verifiedVersion());
        }
        return jitpack.fetchLatestVersion(plugin.groupId(), plugin.artifactId());
    }
}
