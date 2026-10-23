package com.botmaker.studio.sharing;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugins a published bot brings, read off the pom of the release New Project clones.
 *
 * <p>Since 2026-10-12 the gallery stores a bot's repository and nothing about its pom: a {@code requires}
 * list written at publish time said a bot needed SDK 1.2.3 while the release New Project copied pinned 1.4.4.
 * The pom at the tag is the one source, so it is the one read.
 *
 * <p>Keyed by the plugin registry, as publishing was: what makes a dependency a plugin is having an entry there.
 */
public final class PublishedBotPlugins {

    private PublishedBotPlugins() {
    }

    /** A plugin a bot's pom declares: its registry id and the version the pom pins, which may be blank. */
    public record Pin(String id, String version) {
        public Pin {
            id = id == null ? "" : id.trim();
            version = version == null ? "" : version.trim();
        }
    }

    /**
     * The registry's plugins {@code pom} declares, versions through the pom's own properties
     * ({@code ${botmaker.sdk.version}} is meaningless outside it). Empty for a pom that cannot be read.
     */
    public static List<Pin> of(String pom, List<PluginRegistry.Plugin> registry) {
        if (pom == null || pom.isBlank()) return List.of();
        Model model;
        try {
            model = new MavenXpp3Reader().read(new StringReader(pom));
        } catch (Exception e) {
            return List.of();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        model.getProperties().forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
        List<Pin> out = new ArrayList<>();
        for (Dependency dependency : model.getDependencies()) {
            String coordinate = dependency.getGroupId() + ":" + dependency.getArtifactId();
            registry.stream()
                    .filter(plugin -> !plugin.id().isEmpty() && plugin.coordinate().equals(coordinate))
                    .findFirst()
                    .ifPresent(plugin -> out.add(new Pin(plugin.id(),
                            PublishRequest.interpolate(dependency.getVersion(), properties))));
        }
        return List.copyOf(out);
    }
}
