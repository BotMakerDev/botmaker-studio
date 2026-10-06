package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.LibrariesChangedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.SettingsChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.plugin.HostPluginValues;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.UserLibrary;

import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates changes to the project's user libraries. The {@code pom.xml} is the source of truth;
 * a change rewrites it, re-resolves the classpath, refreshes the type index, updates project state and
 * announces a {@link LibrariesChangedEvent}.
 *
 * <p>All I/O lives here at the service edge. {@link #updateLibraries} runs the slow
 * resolve/re-index off the calling thread and returns a future the UI can attach to.
 */
public final class LibraryService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final TypeSummaryManager typeIndex;
    private final EventBus eventBus;

    /** Every resolve-and-bind runs here, one at a time and in the order asked; see {@link #bind}. */
    private final ExecutorService rebinds = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "library-rebind");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<String> unresolved = List.of();

    /**
     * The pom text the editor is bound to, so {@link #pomChanged()} can tell an outside edit from a write this
     * service already bound. Written only on {@link #rebinds}.
     */
    private String boundPom;
    private PomWatcher pomWatcher;

    public LibraryService(ProjectConfig config,
                          ProjectState state,
                          TypeSummaryManager typeIndex,
                          EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.typeIndex = typeIndex;
        this.eventBus = eventBus;
    }

    /** The user libraries currently declared in the project pom. */
    public List<UserLibrary> currentLibraries() {
        return MavenService.readUserLibraries(config.projectPath());
    }

    /**
     * <b>Every</b> dependency the project pom declares, the built-in ones included.
     *
     * <p>What <b>Manage Plugins</b> asks, and it is a different question from {@link #currentLibraries}:
     * <i>is this coordinate installed</i> rather than <i>what did the user add</i>. The SDK is a built-in as
     * far as {@code readUserLibraries} is concerned, so asking the narrow question about it always answered
     * no. See {@link MavenService#readDeclaredLibraries}.
     */
    public List<UserLibrary> declaredLibraries() {
        return MavenService.readDeclaredLibraries(config.projectPath());
    }

    /**
     * Declares {@code plugin} in the pom, with the {@code provided} entries its registry entry says it needs
     * in the editor, then re-resolves, re-binds and re-indexes.
     *
     * <p>Not expressible through {@link #updateLibraries}: that writer keeps the pom's default dependencies
     * and appends whatever list it is given, so installing a plugin that is also a default writes it twice.
     * {@link MavenService#installPlugin} edits the pom in place instead and is idempotent by coordinate.
     *
     * @param editorDependencies the plugin's own {@code editorDependencies}; empty for the plugins that need
     *                           nothing, which is every plugin whose dependencies are ordinary and therefore
     *                           transitive
     * @return the declared plugins the install made redundant and removed ({@link MavenService#dropShadowedPlugins})
     */
    public CompletableFuture<List<MavenService.Shadowed>> installPlugin(UserLibrary plugin,
                                                                       List<UserLibrary> editorDependencies) {
        List<String> named = new ArrayList<>(List.of(plugin.groupId() + ":" + plugin.artifactId()));
        editorDependencies.forEach(d -> named.add(d.groupId() + ":" + d.artifactId()));
        java.util.concurrent.atomic.AtomicReference<List<MavenService.Shadowed>> dropped =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        return edit(() -> {
            MavenService.installPlugin(config.projectPath(), plugin, editorDependencies);
            dropped.set(MavenService.dropShadowedPlugins(config.projectPath()));
        }, named).thenApply(done -> dropped.get());
    }

    /** Removes {@code groupId:artifactId} and its editor dependencies — the mirror of {@link #installPlugin}. */
    public CompletableFuture<Void> removePlugin(String groupId, String artifactId,
                                                List<UserLibrary> editorDependencies) {
        return edit(() -> MavenService.removePlugin(config.projectPath(), groupId, artifactId, editorDependencies),
                List.of());
    }

    /** A pom write: what {@link #edit} runs before it re-resolves. */
    @FunctionalInterface
    private interface PomWrite {
        void run() throws Exception;
    }

    /**
     * Writes the pom, then re-resolves and re-binds — or, when a coordinate the write named cannot be
     * resolved, puts the pom back as it was and fails, naming why.
     *
     * <p>The put-back is the install bug of 2026-09-29: a plugin whose tag JitPack had not built was written,
     * resolved to nothing, and the dialog said <i>installed</i> while the plugin never appeared. A pom naming
     * a jar nobody can download is a broken project, so the edit does not stand. A dependency that fails and
     * that this write did not name is not this write's to refuse; it is reported with the rest.
     *
     * @param named {@code groupId:artifactId} of every coordinate the write declares or re-versions
     */
    private CompletableFuture<Void> edit(PomWrite write, List<String> named) {
        return CompletableFuture.runAsync(() -> {
            Path pom = pom();
            String before = readQuietly(pom);
            try {
                write.run();
            } catch (Exception e) {
                throw new RuntimeException("Failed to update pom.xml: " + e.getMessage(), e);
            }
            String after = readQuietly(pom);
            MavenService.Resolution resolution = MavenService.resolve(config.projectPath(), ProgressReporter.NONE);
            List<String> refused = resolution.problems().stream()
                    .filter(line -> named.stream().anyMatch(coordinate -> line.startsWith(coordinate + ":")))
                    .toList();
            if (!refused.isEmpty()) {
                if (before != null) {
                    try {
                        Files.writeString(pom, before);
                    } catch (Exception e) {
                        throw new RuntimeException("Could not download " + String.join("; ", refused)
                                + ", and pom.xml could not be put back: " + e.getMessage(), e);
                    }
                }
                throw new RuntimeException("Could not download " + String.join("; ", refused)
                        + ". pom.xml is unchanged.");
            }
            boundPom = bind(resolution, after);
        }, rebinds);
    }

    private Path pom() {
        return config.projectPath().resolve("pom.xml");
    }

    /**
     * Rebinds whenever {@code pom.xml} changes on disk and the change is not one this service already bound —
     * a plugin added from a terminal, an IDE or a pull appears without Reload. Stopped by {@link #close()}.
     */
    public void watchPom() {
        CompletableFuture.runAsync(() -> boundPom = readQuietly(pom()), rebinds).join();
        pomWatcher = PomWatcher.start(config.projectPath(), this::pomChanged);
    }

    /**
     * What the watcher calls: re-resolves when the pom on disk is not the one bound. Queued behind any
     * resolve already running, so a write of Studio's own is compared only once it is bound.
     */
    CompletableFuture<Boolean> pomChanged() {
        return CompletableFuture.supplyAsync(() -> {
            String now = readQuietly(pom());
            if (now == null || now.equals(boundPom)) return false;
            boundPom = bind(MavenService.resolve(config.projectPath(), ProgressReporter.NONE), now);
            return true;
        }, rebinds);
    }

    /** Stops watching the pom. The project is closing. */
    public void close() {
        if (pomWatcher != null) pomWatcher.close();
        pomWatcher = null;
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Makes sure the project can compile {@code @Param}: declares the plugin contract when the resolved
     * classpath lacks it, then re-resolves in the background. Answers whether the pom was written.
     *
     * <p>The pom write is synchronous on purpose — a Run pressed the moment after must already see it — and
     * only the slow re-resolve is not. See {@link ContractDependency} for why present is judged on the
     * classpath rather than the pom.
     */
    public boolean ensureContract() {
        try {
            if (!ContractDependency.ensure(config.projectPath(), state.getResolvedClasspath())) return false;
        } catch (Exception e) {
            System.err.println("Could not declare the plugin contract: " + e.getMessage());
            return false;
        }
        CompletableFuture.runAsync(this::rebind, rebinds);
        return true;
    }

    /** Re-resolve without a pom write, then {@link #bind}. */
    private void rebind() {
        String pom = readQuietly(pom());
        boundPom = bind(MavenService.resolve(config.projectPath(), ProgressReporter.NONE), pom);
    }

    /**
     * Re-bind the plugins, re-index, announce — everything that follows a resolve.
     *
     * <p>One copy, because three callers had grown it: a wrong answer here is a project whose editor is
     * bound to the classpath it had before the edit, which looks exactly like the edit not having happened.
     *
     * <p><b>Always on {@link #rebinds}, one at a time</b> (2026-09-29). An install and the background rebind
     * {@link #ensureContract} starts could overlap, and the one that finished last — not the one that
     * started last — decided which classpath the editor was bound to. The classpath itself is handed to
     * {@link ProjectState} on the FX thread, which is the only thread that object is used on.
     *
     * @param pom the pom text {@code resolution} was resolved from
     * @return the pom text now bound — {@code pom}, or the pom as re-read when the contract entry was changed
     *         here, so the watcher does not take Studio's own write for an outside one
     */
    private String bind(MavenService.Resolution resolution, String pom) {
        // The contract entry follows the bot's own imports (ContractDependency.reconcile): declared when a pom
        // edit took away what brought it (a plugin removed), dropped when a plugin brings it again at that version
        // or newer. Only after a complete resolve: a jar that failed to download is not a plugin gone, and a direct
        // entry written then would outlive it and pin the contract once it came back.
        if (resolution.problems().isEmpty()) {
            boolean[] used = {false};
            onFx(() -> used[0] = ContractDependency.usedBy(config, state));
            try {
                if (ContractDependency.reconcile(config.projectPath(), used[0], resolution.jars())) {
                    resolution = MavenService.resolve(config.projectPath(), ProgressReporter.NONE);
                    pom = readQuietly(pom());
                }
            } catch (Exception e) {
                System.err.println("Could not put the plugin contract right in pom.xml: " + e.getMessage());
            }
        }
        List<String> classpath = resolution.jars();
        onFx(() -> state.setResolvedClasspath(classpath));
        PluginHost.bind(classpath, HostServices.forProject(config),
                StudioProjectSettings.devModeIn(config.projectPath()));
        // A plugin just added brings its @Managed holders with it (2026-09-26): written from what the plugin
        // declares, never over a file, never into main. The explorer redraws on the event below.
        HostPluginValues.createMissing();
        typeIndex.refresh(classpath);
        unresolved = resolution.problems();
        eventBus.publish(new LibrariesChangedEvent(currentLibraries()));
        return pom;
    }

    /** What the last re-resolve could not download, one {@code group:artifact:version — reason} per line. */
    public List<String> unresolved() {
        return unresolved;
    }

    /** Runs {@code work} on the FX thread, waiting for it; directly where there is no FX toolkit (a test). */
    private static void onFx(Runnable work) {
        if (Platform.isFxApplicationThread()) {
            work.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    work.run();
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
        } catch (IllegalStateException toolkitNotRunning) {
            work.run();
            return;
        }
        done.join();
    }

    /**
     * The BotMaker SDK version currently declared in the project pom, or {@code ""} when it declares none.
     *
     * <p>Blank rather than {@code Optional} because of where it goes: straight back into
     * {@link #updateLibraries}, whose {@code sdkVersion} parameter has always treated blank as <i>pin
     * nothing</i>. A blank project therefore edits its libraries without acquiring an SDK, which is the
     * behaviour that matters here and which the pom writer enforces rather than trusting — it only ever
     * re-versions a dependency the pom already declares.
     */
    public String currentSdkVersion() {
        return MavenService.readSdkVersion(config.projectPath()).orElse("");
    }

    /**
     * Persists {@code userLibs} plus the BotMaker SDK version to the pom, then (asynchronously) re-resolves
     * the classpath, refreshes the type index and publishes {@link LibrariesChangedEvent}. The returned
     * future completes once the index is refreshed; it completes exceptionally if writing the pom fails.
     */
    public CompletableFuture<Void> updateLibraries(List<UserLibrary> userLibs, String sdkVersion) {
        // The SDK pin may have just moved, so the plugins answering for this project have to move with it — a
        // palette built from the previous jar would offer members the new one may not have.
        return edit(() -> MavenService.writeUserLibraries(config.projectPath(), userLibs, sdkVersion),
                userLibs.stream().map(l -> l.groupId() + ":" + l.artifactId()).toList());
    }

    /**
     * Re-pins several coordinates at once — {@code "groupId:artifactId"} to its new version — then
     * re-resolves, re-binds and re-indexes.
     *
     * <p>What the <b>project upgrade window</b> writes with, and the reason it is a map rather than a call
     * per row: the window moves several plugins under one snapshot, so the pom must move once. It is also
     * the generalisation of {@link #updateLibraries}'s {@code sdkVersion} parameter — that one can pin
     * exactly one artifact, the SDK, which is the last place in this service that knew a plugin by name.
     *
     * <p>Only coordinates the pom already declares are touched; see
     * {@link MavenService#setDependencyVersions}.
     */
    public CompletableFuture<Void> updateVersions(Map<String, String> versionsByCoordinate) {
        return edit(() -> MavenService.setDependencyVersions(config.projectPath(), versionsByCoordinate),
                List.copyOf(versionsByCoordinate.keySet()));
    }

    /**
     * Re-resolves the classpath and re-binds the plugins, without touching the pom.
     *
     * <p><b>What this is for:</b> a plugin author rebuilds their plugin into {@code ~/.m2} and wants Studio to
     * pick it up. The project's dependencies have not changed — the same coordinate resolves to the same jar
     * <em>path</em> — so there is nothing to write, and calling {@link #updateLibraries} with the libraries
     * that are already declared would rewrite the pom to say what it already says.
     *
     * <p>It works because the jar's <b>bytes</b> are what changed: {@link PluginHost#bind} closes the previous
     * loader and opens a fresh one over the same paths, rebuilding every memoised catalog behind it. No
     * restart, and no tag pushed — the same property the SDK has had all along through {@code ~/.m2}.
     *
     * <p>Publishes {@link LibrariesChangedEvent} for the same reason a real library change does: every
     * listener that reacts to the palette moving has to react to this too.
     */
    public CompletableFuture<Void> reloadPlugins() {
        return CompletableFuture.runAsync(this::rebind, rebinds);
    }

    /** The classpath the editor is bound to: every jar the pom resolved to, transitive ones included. */
    public List<String> resolvedClasspath() {
        return List.copyOf(state.getResolvedClasspath());
    }

    /** Whether this project is in dev mode: its plugin jars at a {@code -SNAPSHOT} version are bound. */
    public boolean devMode() {
        return StudioProjectSettings.devModeIn(config.projectPath());
    }

    /**
     * Turns dev mode on or off for this project (2026-10-06), writes it to the project's settings and
     * {@link #reloadPlugins reloads}, so the dev builds the pom pins load or are refused at once.
     *
     * <p>Built from the settings in state, as {@code ProjectSettingsService} builds every other change, so a
     * save from either keeps the other's fields; written on {@link #rebinds}, so the reload that follows reads
     * what was just written.
     */
    public CompletableFuture<Void> setDevMode(boolean on) {
        return CompletableFuture.runAsync(() -> {
            StudioProjectSettings[] next = new StudioProjectSettings[1];
            onFx(() -> {
                StudioProjectSettings current = state.getSettings();
                next[0] = (current != null ? current : StudioProjectSettings.empty()).withDevMode(on);
                try {
                    next[0].write(config.studioRoot());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException("Could not save dev mode: " + e.getMessage(), e);
                }
                state.setSettings(next[0]);
            });
            eventBus.publish(new SettingsChangedEvent(next[0]));
            rebind();
        }, rebinds);
    }
}
