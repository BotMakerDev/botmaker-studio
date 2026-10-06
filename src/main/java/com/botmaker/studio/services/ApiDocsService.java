package com.botmaker.studio.services;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.ApiDocsParser;
import com.botmaker.studio.palette.ApiDocs;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Per-project owner of the plugins' API documentation ({@link ApiDocs}). Descriptions and parameter docs come
 * from each bound plugin's {@code sources} jar — the version <em>the bot</em> resolves, and the only place
 * Javadoc exists at all (bytecode has none) — parsed by {@link ApiDocsParser} off the FX thread, restricted to
 * the packages the plugins catalogue, and served to blocks through {@link #current()}.
 *
 * <p>It read the SDK's sources jar alone, by the SDK's coordinate, until 2026-09-28 ({@code SdkDocsService}).
 * A plugin is found by the jar its class was loaded from, so any plugin that publishes sources is described,
 * and a project with none bound has {@link ApiDocs#EMPTY}.
 *
 * <p>Non-blocking for callers: {@link #current()} returns {@link ApiDocs#EMPTY} until the background parse
 * finishes. Re-parses when the set of plugin jars changes ({@code LibrariesChangedEvent}, which the host
 * publishes after it rebinds the plugins).
 */
public final class ApiDocsService {

    private final ProjectConfig config;
    private final ExecutorService loader =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "api-docs-loader");
                t.setDaemon(true);
                return t;
            });

    private volatile ApiDocs docs = ApiDocs.EMPTY;
    private volatile Set<Path> loadedJars = Set.of();

    public ApiDocsService(ProjectConfig config, EventBus eventBus) {
        this.config = config;
        eventBus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class, e -> refresh(), false);
        refresh();
    }

    /** The current docs, or {@link ApiDocs#EMPTY} while loading or when no sources can be resolved. */
    public ApiDocs current() {
        return docs;
    }

    /** Kicks off a background (re)load when the bound plugins' jars differ from what is loaded. */
    public void refresh() {
        Set<Path> jars = pluginJars(PluginHost.plugins());
        if (jars.equals(loadedJars) && docs != ApiDocs.EMPTY) return;
        Set<String> packages = PluginHost.cataloguedPackages();
        loader.submit(() -> load(jars, packages));
    }

    private void load(Set<Path> jars, Set<String> packages) {
        ApiDocs merged = ApiDocs.EMPTY;
        for (Path jar : jars) {
            // A plugin cataloguing nothing (basics: types and editors only) has no docs to show, so its
            // sources are never asked for — nor reported missing, when it publishes none.
            if (!holdsAny(jar, packages)) continue;
            try {
                Optional<Path> sources = sourcesJarOf(jar);
                if (sources.isPresent()) merged = merged.mergedWith(ApiDocsParser.fromSourcesJar(sources.get(), packages));
            } catch (RuntimeException e) {
                System.err.println("ApiDocsService: no docs from " + jar.getFileName() + ": " + e.getMessage());
            }
        }
        this.docs = merged;
        this.loadedJars = jars;
    }

    /** The jar each plugin's class was loaded from. A plugin loaded from a directory (a test) has none. */
    static Set<Path> pluginJars(List<StudioPlugin> plugins) {
        Set<Path> jars = new LinkedHashSet<>();
        for (StudioPlugin plugin : plugins) {
            CodeSource source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) continue;
            try {
                Path path = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) jars.add(path);
            } catch (URISyntaxException | IllegalArgumentException e) {
                // Not a file on disk; nothing to find sources beside.
            }
        }
        return Set.copyOf(jars);
    }

    /**
     * The {@code sources} jar of the artifact {@code jar} is: beside it in the local repository when already
     * downloaded, else resolved through the project's repositories by the coordinate the jar's own
     * {@code pom.properties} names and the version its repository directory says. May block on the network.
     */
    private Optional<Path> sourcesJarOf(Path jar) {
        String file = jar.getFileName().toString();
        Path beside = jar.resolveSibling(file.substring(0, file.length() - ".jar".length()) + "-sources.jar");
        if (Files.isRegularFile(beside)) return Optional.of(beside);
        Optional<Properties> pom = pomProperties(jar);
        Path versionDir = jar.getParent();
        if (pom.isEmpty() || versionDir == null) return Optional.empty();
        String groupId = pom.get().getProperty("groupId");
        String artifactId = pom.get().getProperty("artifactId");
        // The directory, not pom.properties: a JitPack build of a tag cut before 2026-10-06 keeps the pom's
        // cosmetic 0.0.0-SNAPSHOT there.
        String version = versionDir.getFileName().toString();
        if (groupId == null || artifactId == null) return Optional.empty();
        return MavenService.resolveArtifact(config.projectPath(), groupId, artifactId, "sources", version);
    }

    /** Whether {@code jar} has a class in one of {@code packages} or below — where its docs would come from. */
    static boolean holdsAny(Path jar, Set<String> packages) {
        if (packages.isEmpty()) return false;
        List<String> prefixes = packages.stream().map(p -> p.replace('.', '/') + "/").toList();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                for (String prefix : prefixes) if (name.startsWith(prefix)) return true;
            }
        } catch (IOException e) {
            // An unreadable jar has nothing to document.
        }
        return false;
    }

    private static Optional<Properties> pomProperties(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("META-INF/maven/") || !entry.getName().endsWith("/pom.properties")) {
                    continue;
                }
                Properties properties = new Properties();
                try (InputStream in = zip.getInputStream(entry)) {
                    properties.load(in);
                }
                return Optional.of(properties);
            }
        } catch (IOException e) {
            // An unreadable jar has no coordinate to offer.
        }
        return Optional.empty();
    }
}
