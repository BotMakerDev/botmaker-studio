package com.botmaker.studio.services.upgrade;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.ProgressReporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The version an upgrade moves a plugin to, loaded beside the bound one for as long as the upgrade reads it
 * (2026-10-11) — what the repair of the bot's managed values reads, since only the target jar declares them.
 *
 * <p><b>The bot's own classpath at the target version</b>, resolved from a copy of its pom with that one pin
 * moved, so the declarations link against the dependencies they will run with, and the bot is checked against
 * the jars it will compile with. The pom itself is not touched: it moves last, after every write.
 *
 * <p>Closed after use: an open loader keeps every jar it read open, which on Windows makes them unreplaceable.
 */
final class StagedPlugin implements AutoCloseable {

    private final PluginLoader loader;
    private final List<StudioPlugin> plugins;
    private final List<String> classpath;
    private final ValueGrammar grammar;

    private StagedPlugin(PluginLoader loader, List<StudioPlugin> plugins, List<String> classpath,
                         ValueGrammar grammar) {
        this.loader = loader;
        this.plugins = plugins;
        this.classpath = classpath;
        this.grammar = grammar;
    }

    /**
     * {@code coordinate} at {@code version}, its plugins those loaded from {@code jar}; empty when the classpath
     * cannot be resolved or no plugin loads from that jar.
     */
    static Optional<StagedPlugin> open(Path projectDir, String coordinate, String version, Path jar) {
        List<String> classpath;
        Path staging = null;
        try {
            staging = Files.createTempDirectory("botmaker-upgrade-");
            Files.copy(projectDir.resolve("pom.xml"), staging.resolve("pom.xml"));
            MavenService.setDependencyVersions(staging, Map.of(coordinate, version));
            classpath = MavenService.resolve(staging, ProgressReporter.NONE).jars();
        } catch (IOException | RuntimeException e) {
            System.err.println("Warning: could not resolve " + coordinate + " " + version + ": " + e.getMessage());
            return Optional.empty();
        } finally {
            deleteQuietly(staging);
        }
        PluginLoader loader = PluginLoader.openReporting(classpath).loader();
        if (loader == null) return Optional.empty();
        List<StudioPlugin> fromJar = new ArrayList<>();
        for (StudioPlugin plugin : loader.plugins()) {
            if (PluginUpgradeService.loadedFrom(plugin, jar)) fromJar.add(plugin);
        }
        if (fromJar.isEmpty()) {
            loader.close();
            return Optional.empty();
        }
        return Optional.of(new StagedPlugin(loader, List.copyOf(fromJar), List.copyOf(classpath),
                PluginHost.grammarOf(loader.plugins())));
    }

    /** The id of the first plugin the jar holds — what its holders' folder is named after. */
    String pluginId() {
        return plugins.getFirst().id();
    }

    /** Every value the jar's plugins declare; a plugin that throws declares nothing. */
    List<ManagedValue<?>> declared() {
        List<ManagedValue<?>> out = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            try {
                List<ManagedValue<?>> values = plugin.managedValues();
                if (values != null) values.stream().filter(v -> v != null).forEach(out::add);
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Warning: " + plugin.id() + " could not list managed values: " + e);
            }
        }
        return List.copyOf(out);
    }

    /** The target classpath, for parsing the bot against what it will compile with. */
    List<String> classpath() {
        return classpath;
    }

    /** The grammar every plugin on the target classpath composes. */
    ValueGrammar grammar() {
        return grammar;
    }

    @Override
    public void close() {
        loader.close();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A temp folder left behind is the OS's to clean.
        }
    }
}
