package com.botmaker.studio.services;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.plugin.DuplicatePlugins;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.sharing.PluginRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A declared plugin's {@code editorDependencies} put back on open, when the pom has lost them (2026-10-07).
 *
 * <p>They are declared {@code provided} beside the plugin by {@link MavenService#installPlugin}, and nothing
 * brings them otherwise: the SDK marks javalin and zxing {@code optional}, which is not transitive. A pom that
 * lost them — removing one copy of an SDK declared under both groupIds took them with it until 2026-10-07 —
 * left the Pilot failing on {@code NoClassDefFoundError: io/javalin/websocket/WsContext}, with nothing in
 * Studio to say why or to repair it. Only the registry knows the list, so a pom naming no BotMaker artifact
 * asks nothing, and an unreachable registry repairs nothing.
 */
public final class EditorCompanions {

    /** How long an open waits on the registry before giving up on the repair. */
    private static final long REGISTRY_TIMEOUT_S = 5;

    private EditorCompanions() {}

    /**
     * The companions of each plugin {@code declared} names — its entry found by
     * {@link PluginRegistry#entryFor} — that {@code declared} lacks, each once, in pom order.
     */
    static List<UserLibrary> missing(List<UserLibrary> declared, List<PluginRegistry.Plugin> registry) {
        Map<String, UserLibrary> wanted = new LinkedHashMap<>();
        for (UserLibrary library : declared) {
            PluginRegistry.Plugin entry = PluginRegistry.entryFor(registry, library).orElse(null);
            if (entry == null) continue;
            for (UserLibrary companion : entry.editorLibraries()) {
                boolean present = declared.stream().anyMatch(d -> d.groupId().equals(companion.groupId())
                        && d.artifactId().equals(companion.artifactId()));
                if (!present) wanted.putIfAbsent(companion.groupId() + ":" + companion.artifactId(), companion);
            }
        }
        return new ArrayList<>(wanted.values());
    }

    /**
     * Declares what {@link #missing} finds in {@code projectDir}'s pom and answers whether it wrote — the
     * caller re-resolves when it did. Blocking: up to {@value #REGISTRY_TIMEOUT_S}s on the registry, said on
     * {@code progress}.
     */
    public static boolean restore(Path projectDir, ProgressReporter progress) throws IOException {
        List<UserLibrary> declared = MavenService.readDeclaredLibraries(projectDir);
        boolean namesBotMaker = declared.stream().anyMatch(d -> DuplicatePlugins.isBotMakerGroup(d.groupId()));
        if (!namesBotMaker) return false;
        progress.message("Checking the libraries your plugins need…");
        List<PluginRegistry.Plugin> registry = new PluginRegistry(new GitHubClient()).browse()
                .completeOnTimeout(List.of(), REGISTRY_TIMEOUT_S, TimeUnit.SECONDS)
                .exceptionally(failure -> List.of())
                .join();
        List<UserLibrary> lacking = missing(declared, registry);
        if (lacking.isEmpty()) return false;
        List<UserLibrary> added = MavenService.declareCompanions(projectDir, lacking);
        if (added.isEmpty()) return false;
        System.out.println("[Plugins] Restored editor libraries a declared plugin needs: "
                + added.stream().map(l -> l.groupId() + ":" + l.artifactId() + ":" + l.version()).toList());
        return true;
    }
}
