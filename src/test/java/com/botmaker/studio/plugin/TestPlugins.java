package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.PluginDeclaration;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;

import java.nio.file.Path;

/**
 * Plugins a test builds by hand: a contract plugin is a {@link DeclaredPlugin} (the interface is sealed), and the
 * host keeps each as a {@link LoadedPlugin} — the coordinate and name its pom would have given it.
 */
public final class TestPlugins {

    /** The groupId every hand-built plugin is under. */
    public static final String GROUP = "com.example";

    private TestPlugins() {
    }

    /** A plugin stated as {@code declaration}. */
    public static class Fake extends DeclaredPlugin {
        public Fake(PluginDeclaration declaration) {
            super(declaration);
        }
    }

    /** {@code declaration} as a plugin. */
    public static StudioPlugin plugin(PluginDeclaration declaration) {
        return new Fake(declaration);
    }

    /** {@code com.example:<artifactId>}. */
    public static PluginCoordinate coordinate(String artifactId) {
        return new PluginCoordinate(GROUP, artifactId);
    }

    /** {@code plugin} loaded as {@code com.example:<artifactId>}, named {@code artifactId}. */
    public static LoadedPlugin loaded(String artifactId, StudioPlugin plugin) {
        return loaded(artifactId, artifactId, plugin);
    }

    /** {@code plugin} loaded as {@code com.example:<artifactId>}, named {@code name}. */
    public static LoadedPlugin loaded(String artifactId, String name, StudioPlugin plugin) {
        return new LoadedPlugin(coordinate(artifactId), name, Path.of(artifactId).toAbsolutePath().normalize(),
                plugin);
    }

    /** {@code declaration} loaded as {@code com.example:<artifactId>}, named {@code artifactId}. */
    public static LoadedPlugin loaded(String artifactId, PluginDeclaration declaration) {
        return loaded(artifactId, plugin(declaration));
    }
}
