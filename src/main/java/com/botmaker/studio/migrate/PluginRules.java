package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.plugin.host.PluginPoms;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.ProgressReporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The {@code @Migrate} rules a plugin ships beside an element it marks {@code @Deprecated(forRemoval = true)}
 * (2026-10-28, {@link Migrate}'s javadoc): read from each plugin jar's {@code -sources.jar}, since the annotation
 * has source retention, and never copied into the bot.
 *
 * <p>Only the jars of one classpath are read: the one the bot moves <em>from</em>, where the rule still compiles
 * against its element. Of each sources jar, only the {@code .java} entries that write {@code @Migrate} are kept;
 * no contract step or file convention says where a plugin writes its rules. Each kept entry goes into the old
 * parse under {@link Rules#PROBE_PACKAGE}'s folder, where nothing searches for uses or rewrites, and its rules are
 * read there as the bot's are read in its own parse. Since its element still compiles there, a plugin's rule names
 * its owner by class literal; one by {@link Migrate#name()} is refused, as the probe names only the bot's owners.
 *
 * <p>What a jar holds is kept for the session, by its path, size and time; a plugin whose sources jar could not be
 * had is asked again after ten minutes.
 */
public final class PluginRules {

    /** Who wrote a rule the bot does not hold: the plugin's coordinate, and its name for a person. */
    public record Plugin(PluginCoordinate coordinate, String name) {}

    /** The rule files of the plugins on one classpath: each one's text, and its plugin, by its path in the parse. */
    record Shipped(Map<Path, String> sources, Map<Path, Plugin> plugins) {

        static final Shipped NONE = new Shipped(Map.of(), Map.of());

        Shipped {
            sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            plugins = Collections.unmodifiableMap(new LinkedHashMap<>(plugins));
        }
    }

    /**
     * What one jar holds: its plugin, or {@code null} for a library, and its rule files' text by entry name;
     * {@code missedAt} the time its sources jar could not be had, else {@code 0}.
     */
    private record Held(Plugin plugin, Map<String, String> files, long missedAt) {

        static final Held NOTHING = new Held(null, Map.of(), 0);

        /** Whether a sources jar that could not be had is worth asking for again: offline or rate-limited then. */
        boolean stale(long now) {
            return missedAt != 0 && now - missedAt > RETRY_MILLIS;
        }
    }

    /** How long a plugin whose sources jar could not be had is taken to ship nothing. */
    private static final long RETRY_MILLIS = 10 * 60 * 1000;

    /** What a rule file holds that another source does not: the annotation itself. */
    private static final String MARK = "@" + Migrate.class.getSimpleName();

    /** One jar as it is on disk now: a changed jar is a new key. */
    private record Key(Path jar, long size, long modified) {}

    private static final PluginRules SHARED = new PluginRules(PluginRules::sourcesJar);

    private final BiFunction<Path, PluginCoordinate, Optional<Path>> sourcesOf;
    private final Map<Key, Held> held = new ConcurrentHashMap<>();

    /** @param sourcesOf a plugin jar's sources jar, given the jar and its coordinate; empty when it has none */
    PluginRules(BiFunction<Path, PluginCoordinate, Optional<Path>> sourcesOf) {
        this.sourcesOf = sourcesOf;
    }

    /** The one Studio reads with: a sources jar beside the jar in {@code ~/.m2}, else downloaded. */
    public static PluginRules shared() {
        return SHARED;
    }

    /**
     * Whether a plugin on {@code classpath} ships a rule, or one of its jars has not been read yet — what decides,
     * without reading anything, that a classpath nothing changed on is worth a migration check.
     */
    public boolean mayShip(List<String> classpath) {
        for (String entry : classpath) {
            Key key = keyOf(Path.of(entry));
            if (key == null) continue;
            Held one = held.get(key);
            if (one == null || !one.files().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether a plugin on {@code classpath} ships a rule; reads every jar not read yet, which may download, so
     * {@link #mayShip} knows the whole classpath after it.
     */
    public boolean ships(List<String> classpath) {
        boolean any = false;
        for (String entry : classpath) {
            Key key = keyOf(Path.of(entry));
            if (key != null && !held(key).files().isEmpty()) any = true;
        }
        return any;
    }

    /**
     * Every rule file the plugins on {@code classpath} ship, at a path under {@code probe}'s folder:
     * {@code <group>/<artifact>/<entry>}. May download a sources jar: off the FX thread.
     */
    Shipped of(List<String> classpath, Path probe) {
        Map<Path, String> sources = new LinkedHashMap<>();
        Map<Path, Plugin> plugins = new LinkedHashMap<>();
        Path base = probe.getParent();
        for (String entry : classpath) {
            Key key = keyOf(Path.of(entry));
            if (key == null) continue;
            Held one = held(key);
            if (one.files().isEmpty()) continue;
            Path own = base.resolve(one.plugin().coordinate().groupId())
                    .resolve(one.plugin().coordinate().artifactId()).normalize();
            one.files().forEach((name, text) -> {
                Path at = own.resolve(name).normalize();
                // An entry that climbs out of the plugin's own folder would stand in for a file of the bot.
                if (!at.startsWith(own) || at.equals(own)) return;
                sources.put(at, text);
                plugins.put(at, one.plugin());
            });
        }
        return sources.isEmpty() ? Shipped.NONE : new Shipped(sources, plugins);
    }

    private Held held(Key key) {
        long now = System.currentTimeMillis();
        return held.compute(key, (k, was) -> was == null || was.stale(now) ? read(k, now) : was);
    }

    private Held read(Key key, long now) {
        List<PluginPoms.PluginPom> poms = PluginPoms.read(key.jar());
        if (poms.size() != 1) return Held.NOTHING;
        Plugin plugin = new Plugin(poms.getFirst().coordinate(), poms.getFirst().name());
        Optional<Path> sources = sourcesOf.apply(key.jar(), plugin.coordinate());
        if (sources.isEmpty()) return new Held(plugin, Map.of(), now);
        Map<String, String> files = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(sources.get().toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".java") || name.startsWith("/") || name.contains("..")) {
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (text.contains(MARK)) files.put(name, text);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read the rules in " + sources.get() + ": " + e.getMessage());
            return new Held(plugin, Map.of(), now);
        }
        return new Held(plugin, files, 0);
    }

    /** {@code jar} as it is on disk, or {@code null} for a class folder or a jar that is not there. */
    private static Key keyOf(Path jar) {
        if (!jar.getFileName().toString().endsWith(".jar")) return null;
        try {
            return new Key(jar.toAbsolutePath().normalize(), Files.size(jar), Files.getLastModifiedTime(jar).toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@code jar}'s sources jar: beside it, as {@code mvn install} and a download leave it, else fetched for the
     * version its {@code ~/.m2} folder names. Empty when the plugin publishes none or Studio is offline.
     */
    static Optional<Path> sourcesJar(Path jar, PluginCoordinate coordinate) {
        String name = jar.getFileName().toString();
        Path beside = jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + "-sources.jar");
        if (Files.isRegularFile(beside)) return Optional.of(beside);
        Path folder = jar.getParent();
        if (folder == null || folder.getFileName() == null) return Optional.empty();
        String version = folder.getFileName().toString();
        if (!name.equals(coordinate.artifactId() + "-" + version + ".jar")) return Optional.empty();
        String wanted = coordinate + ":" + version + ":sources";
        return Optional.ofNullable(MavenService.fetch(List.of(wanted), ProgressReporter.NONE).files().get(wanted));
    }
}
