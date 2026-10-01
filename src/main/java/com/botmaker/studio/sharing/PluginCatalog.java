package com.botmaker.studio.sharing;

import com.botmaker.studio.services.JitPackSearch;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The plugins a user can add to a project, and the version each installs at: the registry's entries. One list,
 * read by <i>Plugins &amp; Libraries ▸ Browse</i> and by New Project, so the two never offer different plugins or
 * different versions (2026-10-01).
 *
 * <p>It also merged {@code ~/.m2} dev builds over the registry until later the same day, when they were removed:
 * a project made from a local build pinned a version nobody else could resolve, and a bot published from it
 * carried that pin into the gallery. A released plugin is what Studio offers; a plugin author pins their own
 * build in the pom by hand.
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

    /** The verified version, or — only when the entry carries none — JitPack's newest. */
    public CompletableFuture<String> version(PluginRegistry.Plugin plugin) {
        if (!plugin.verifiedVersion().isBlank()) {
            return CompletableFuture.completedFuture(plugin.verifiedVersion());
        }
        return jitpack.fetchLatestVersion(plugin.groupId(), plugin.artifactId());
    }
}
