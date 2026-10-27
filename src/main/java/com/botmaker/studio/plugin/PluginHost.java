package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.run.Readiness;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.toolbar.ToolbarGroup;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.EditableType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.FilledTypes;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.Palettes;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.PluginFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The plugins this Studio has loaded, and the one place their contributions are composed.
 *
 * <p><b>Plugins are declared, never named here.</b> Nothing in this package writes down an implementation
 * class: each jar's own pom names its plugin ({@code botmaker.plugin}, 2026-10-27), {@link PluginLoader} hands
 * back each one as a {@link LoadedPlugin} — its coordinate, name, jar and instance — and every call from here on
 * is javac-checked against the contract. A plugin is its coordinate: every owner below is keyed by it. The SDK
 * reaches this list exactly as a third-party plugin would — it is Studio's plugin #1 and gets no back door.
 *
 * <p><b>The set is bound per project, and that is what makes the pin real.</b> {@link #bind} builds a
 * {@link PluginLoader} over the project's <em>resolved artifacts</em>, so the plugin answering for a bot
 * pinned to SDK 1.1.0 is the one inside that jar. That loader lived in this package until 2026-08-28 and is
 * now {@code botmaker-plugin-host}: Studio is no longer the only host, and the delegation split it makes is
 * the last code here that should exist in two copies. What stayed is everything below — the swap and the two
 * catalogs — because all of it is about <em>Studio's</em> open project. With no project open, or a classpath
 * that loads nothing, the set is empty: Studio bundles no plugin (the bundled set was always empty, and was
 * dropped on 2026-10-27).
 *
 * <p><b>Fail-open, in one direction only.</b> An empty classpath, a jar whose pom names no plugin, a plugin
 * whose constructor throws: each is reported ({@link #failures()}) and the rest bind. A type no plugin declares
 * is read as unknown and its value is preserved read-only. {@link #grammar()} must never throw, whatever the
 * state of the loader.
 *
 * <h2>Two catalogs, and the difference matters</h2>
 *
 * <p>{@link #catalogFor()} answers for <em>the project's own plugins</em>: a bot compiles against the
 * plugin jars named in its pom, which are routinely older than anything this Studio has seen, and the
 * palette must describe those jars. It took the pinned SDK version as an argument until 2026-09-22; no
 * plugin ever read it, because the bound set already <em>is</em> the pinned jar.
 *
 * <p>{@link #bundled()} answers for the plugin jars Studio itself was compiled against, and has exactly one
 * legitimate use: resolving a bare simple name to a fully-qualified one when there is no project in hand
 * (an import repair, a paste, a rewrite built from a bare {@link java.lang.String}). It replaced
 * {@code palette.SdkType}, a hand-mirrored enum of the SDK's class list which had the same scope and the
 * same job but no author but us — a class renamed in the SDK broke a menu at runtime instead of this build.
 *
 * <p>Each plugin's share is {@link Palettes#of}: its own {@code catalog()} when it builds one, otherwise the
 * {@code @Palette} classes found in its jar. Both are memoised, because discovery reads the plugin's jar and
 * the menus ask on every open.
 */
public final class PluginHost {

    private PluginHost() {}

    /**
     * The plugin set answering right now: the bound project's, empty with none. Volatile because
     * {@link #bind} runs off the FX thread (the classpath resolve it follows does) while the readers below
     * are called from wherever a variable is constructed.
     */
    private static volatile List<LoadedPlugin> plugins = List.of();

    /** The loader behind a bound set, held only so it can be closed. Null whenever the set is empty. */
    private static volatile PluginLoader loader;

    /** Whether the live set is serving an open project, so {@link StudioPlugin#projectClosing()} is owed. */
    private static boolean serving;

    /** The bound set's merged palette, built on first ask and dropped on every bind. */
    private static volatile PaletteCatalog catalog;

    /**
     * Memoised on the same reasoning as the palette — the merge walks every plugin and the value cells ask
     * on every keystroke — and rebuilt on every bind, which is why it is a field rather than a constant.
     */
    private static volatile ValueGrammar grammar = compose(List.of()).grammar();

    /** Memoised beside the value catalog, and rebuilt on the same bind, for the same reason. */
    private static volatile List<OwnedEditor> ownedSlotEditors = List.of();

    /** The same editors without their owner — derived, so the two can never disagree. See {@link #slotEditors()}. */
    private static volatile List<SlotEditor> slotEditors = strip(ownedSlotEditors);

    /**
     * The user's choice of editor per Java type name, or empty when nothing is contested.
     *
     * <p>Held here rather than read at each walk site for the same reason the editor list is: the canvas walk
     * has a whole project to ask ({@code PluginPickers} holds a {@code CodeEditorService}) and the Parameters
     * window's has only a {@code ProjectConfig}, so a verdict read at the call site would be reachable in one
     * of them. It is bound from the open project's settings exactly as the plugin list itself is — a plugin is
     * constructed once and serves whatever is bound to it, and so is this.
     */
    private static volatile Map<String, String> preferredEditors = Map.of();

    /** Memoised beside the slot editors, rebuilt on the same bind. See {@link #toolbarItems()}. */
    private static volatile List<OwnedItem> ownedToolbarItems = List.of();
    private static volatile List<ToolbarItem> toolbarItems = List.of();

    /** Memoised the same way. See {@link #managedValues()}. */
    private static volatile List<ManagedValue<?>> managedValues = List.of();

    /** The holder files the bound plugins declare, rebuilt on the same bind. See {@link #pluginFiles()}. */
    private static volatile List<PluginFiles.Holder> pluginFiles = List.of();

    /** What the last {@link #bind} could not load. See {@link #failures()}. */
    private static volatile List<PluginLoader.PluginFailure> failures = List.of();

    /** The dev builds the last {@link #bind} bound in dev mode. See {@link #devBuilds()}. */
    private static volatile List<String> devBuilds = List.of();

    /**
     * The project being bound, handed to every plugin that ends up serving it.
     *
     * <p>Held only across one {@link #bind} — {@link #swap} reads it and {@link #unbind} clears it — because
     * it is the argument of a call rather than state anybody here reads. It is a field only because
     * {@code swap} is reached from three places and is where the incoming set is finally known.
     */
    private static StudioServices opening;

    /** The bound plugins, each with its coordinate, name and jar; empty with no project. */
    public static List<LoadedPlugin> plugins() {
        return plugins;
    }

    /** The bound plugins' instances, in the same order. */
    public static List<StudioPlugin> instances() {
        return instancesOf(plugins);
    }

    /** {@code set}'s instances, in order. */
    public static List<StudioPlugin> instancesOf(List<LoadedPlugin> set) {
        return set.stream().map(LoadedPlugin::plugin).toList();
    }

    /**
     * The bound plugin whose jar holds {@code type}, or empty for a class no plugin ships — the bot's own, the
     * JDK's, another library's. Decided by where the class was loaded from, not by its name: a jar is the
     * one thing two plugins cannot share, and the loader refuses a jar that declares two.
     */
    public static Optional<LoadedPlugin> pluginOwning(Class<?> type) {
        Path where = jarOf(type);
        if (where == null) return Optional.empty();
        for (LoadedPlugin plugin : plugins) {
            if (where.equals(plugin.jar())) return Optional.of(plugin);
        }
        return Optional.empty();
    }

    /** The name a user reads for the plugin that offers facade {@code simpleName}, or empty for none. */
    public static Optional<String> pluginNameFor(String simpleName) {
        return ownerOf(simpleName)
                .flatMap(facade -> pluginOwning(facade.type()))
                .map(LoadedPlugin::name);
    }

    /**
     * The name a user reads for the bound plugin whose jar holds the class {@code binaryName}, or empty for a
     * class no plugin ships or none can load (2026-09-29: the Trace tab groups the lines' writers by it). The
     * class is looked up without being initialised, so asking runs none of its code.
     */
    public static Optional<String> pluginNameOwning(String binaryName) {
        if (binaryName == null || binaryName.isBlank()) return Optional.empty();
        try {
            return pluginOwning(Class.forName(binaryName, false, classLoader())).map(LoadedPlugin::name);
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** The bound plugin {@code coordinate} names, BotMaker's two groupIds counting as one; empty for none. */
    public static Optional<LoadedPlugin> plugin(PluginCoordinate coordinate) {
        if (coordinate == null) return Optional.empty();
        return plugins.stream().filter(loaded -> same(loaded.coordinate(), coordinate)).findFirst();
    }

    /** Whether two coordinates name one plugin: BotMaker's two groupIds count as one (DuplicatePlugins). */
    public static boolean same(PluginCoordinate a, PluginCoordinate b) {
        return a != null && b != null
                && DuplicatePlugins.sameArtifact(a.groupId(), a.artifactId(), b.groupId(), b.artifactId());
    }

    /** The classpath entry {@code type} was loaded from, absolute and normalised, or null for none. */
    private static Path jarOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null
                    : Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (SecurityException | java.net.URISyntaxException | IllegalArgumentException
                 | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    /**
     * Binds the plugins declared on {@code resolvedClasspath}, replacing whatever was bound before.
     *
     * <p>Called immediately after a project's classpath is resolved — on open, and again whenever the
     * libraries or the SDK pin change. A classpath that loads nothing binds the empty set: see the fail-open
     * note in the class javadoc.
     *
     * <p>{@code services} names the project being bound, and every plugin that ends up serving it is told
     * through {@link StudioPlugin#projectOpened(StudioServices)}. It is what a plugin reads its own files out of; no
     * contribution surface takes a services argument, deliberately, because they are asked every time a
     * window is drawn.
     */
    public static void bind(List<String> resolvedClasspath, StudioServices services) {
        bind(resolvedClasspath, services, false);
    }

    /**
     * {@link #bind(List, StudioServices)}, binding the classpath's dev builds too when {@code devMode} — the
     * project's own setting, {@code StudioProjectSettings.devMode}. See {@link #devBuilds()}.
     */
    public static void bind(List<String> resolvedClasspath, StudioServices services, boolean devMode) {
        // A dev build of a plugin is bound only in dev mode (ReleasedPlugins); otherwise it is reported beside
        // what failed to load.
        ReleasedPlugins.Split released = ReleasedPlugins.split(resolvedClasspath, devMode);
        // One groupId's copy of each artifact a pom names under both of BotMaker's (DuplicatePlugins): two
        // copies on one loader is a mix of the two versions, class by class.
        DuplicatePlugins.Split single = DuplicatePlugins.split(released.loadable());
        // A community plugin loads only once the user agreed to run it here (PluginGate, 2026-10-23). Asked before
        // the lock is taken: the question waits for the FX thread, which may itself be waiting on the lock to
        // unbind. A catalog plugin is its coordinate, which the catalog pins, so nothing is compared after it
        // loads (the id check went on 2026-10-27).
        PluginGate checking = gate;
        PluginGate.Admitted admitted = checking == null
                ? new PluginGate.Admitted(single.loadable(), List.of())
                : checking.admit(checking.catalog(), single.loadable(), released.dev());
        bindAdmitted(services, released, single, admitted);
    }

    private static synchronized void bindAdmitted(StudioServices services, ReleasedPlugins.Split released,
                                                  DuplicatePlugins.Split single, PluginGate.Admitted admitted) {
        opening = services;
        PluginLoader.Loaded loaded = PluginLoader.openReporting(admitted.loadable());
        failures = concat(concat(concat(released.refused(), single.dropped()), admitted.refused()),
                loaded.failures());
        PluginLoader opened = loaded.loader();
        // Not unbind() for a classpath that would not open: it is still a project opening, and saying "no project"
        // here would leave the next close with nobody to tell.
        swap(opened, opened == null ? List.of() : opened.plugins());
        devBuilds = bound(released.dev());
        loadedJars = loadedJars();
        serving = true;
    }

    /** What each {@link #bind} checks against the catalog; none until Studio installs one ({@link #guard}). */
    private static volatile PluginGate gate;

    /**
     * Makes every later {@link #bind} ask before a community plugin loads ({@link PluginGate}). Studio calls it
     * once at start; a host that never does binds as before.
     */
    public static void guard(PluginGate checking) {
        gate = checking;
    }

    /**
     * A bound plugin and the resolved jar it came from — what the Installed tab says is <i>loaded</i>, so a
     * user can tell a dev build from a release without guessing.
     *
     * @param coordinate the plugin's identity, its pom's {@code groupId:artifactId}
     * @param jar        the classpath entry, in the Maven repository layout
     */
    public record LoadedJar(PluginCoordinate coordinate, String name, String jar) {

        /** The jar's version directory: {@code 1.3.1-SNAPSHOT}, {@code v1.2.3}. */
        public String version() {
            return ReleasedPlugins.versionOf(Path.of(jar));
        }

        /** Whether the jar is a build only this machine has. */
        public boolean dev() {
            return ReleasedPlugins.isDevVersion(version());
        }

        /** Whether this is {@code groupId:artifactId}'s plugin, BotMaker's two groupIds counting as one. */
        public boolean isOf(String groupId, String artifactId) {
            return DuplicatePlugins.sameArtifact(coordinate.groupId(), coordinate.artifactId(), groupId, artifactId);
        }

        /** Which of BotMaker's two groupIds the jar is under, or {@code ""} for another publisher's. */
        public String groupId() {
            return DuplicatePlugins.groupOf(Path.of(jar));
        }

        /** Whether the jar is under the {@code ~/.m2}-style layout at all, rather than a bundled class folder. */
        boolean inRepository() {
            return !version().isEmpty() && Path.of(jar).getFileName().toString().endsWith(".jar");
        }
    }

    /** What the last {@link #bind} bound, each with its jar. See {@link #loaded()}. */
    private static volatile List<LoadedJar> loadedJars = List.of();

    /**
     * Every bound plugin that came from a jar in the Maven repository layout, with that jar — empty before a
     * project binds. Rebuilt on every {@link #bind}.
     */
    public static List<LoadedJar> loaded() {
        return loadedJars;
    }

    private static List<LoadedJar> loadedJars() {
        return plugins.stream()
                .map(plugin -> new LoadedJar(plugin.coordinate(), plugin.name(), plugin.jar().toString()))
                .filter(LoadedJar::inRepository)
                .toList();
    }

    /**
     * The dev-build jars among {@code devJars} that a bound plugin came from, described: a dev build that
     * failed to load, or that {@link #swap} rejected, is a failure and not a loaded build.
     */
    private static List<String> bound(List<String> devJars) {
        if (devJars.isEmpty()) return List.of();
        Set<Path> sources = pluginJars();
        return devJars.stream()
                .filter(jar -> sources.contains(Path.of(jar).toAbsolutePath().normalize()))
                .map(ReleasedPlugins::describe)
                .toList();
    }

    /**
     * Binds the empty set and releases the project's jars.
     *
     * <p>Closing is required rather than tidy: an open {@code URLClassLoader} keeps every jar it read open,
     * and on Windows that makes the file unreplaceable — so a project switch that skipped this would break
     * the <em>next</em> project's dependency resolve rather than this one's.
     */
    public static synchronized void unbind() {
        opening = null;
        swap(null, List.of());
        serving = false;
        failures = List.of();
        devBuilds = List.of();
        loadedJars = List.of();
    }

    /**
     * The dev builds the last {@link #bind} let through because the project is in dev mode, each as
     * {@code <artifact> <version>}: empty outside dev mode, and empty in it when the pom pins only releases.
     */
    public static List<String> devBuilds() {
        return devBuilds;
    }

    /**
     * The plugins on the open project's classpath that did not load, and why — empty when everything did.
     *
     * <p><b>This is the missing half of three incidents in a row</b>, each of which reads the same way in the
     * record: <i>an empty palette and one line on stderr</i>. A plugin that will not load is caught here on
     * purpose — a classpath with no plugin on it is an ordinary state and must not stop a project opening —
     * and until 2026-09-06 being caught was the end of it. Nothing above this layer could tell <i>the
     * project pins no plugin</i> from <i>the project pins a plugin that is broken</i>, which are the same
     * screen and completely different problems.
     *
     * <p>Rebuilt on every {@link #bind} and cleared by {@link #unbind}, exactly like the catalogs: it
     * describes the classpath currently bound and nothing else.
     */
    public static List<PluginLoader.PluginFailure> failures() {
        return failures;
    }

    private static void swap(PluginLoader opened, List<LoadedPlugin> bound) {
        Composition composed = compose(bound);
        ValueGrammar merged = composed.grammar();
        bound = composed.bound();
        if (!composed.rejected().isEmpty()) {
            // A plugin that cannot compose is dropped on its own, and every plugin beside it still binds.
            // Until 2026-09-19 one clash threw out of the fold and the whole project fell back to the
            // bundled set — which is empty, so a project pinning an SDK older than the release that moved
            // the nine JDK value types into plugin-basics, plus basics itself, lost *every* editor it had
            // and said so on stderr alone. Reported rather than printed for the reason `failures()` exists.
            failures = concat(failures, composed.rejected());
        }
        // Before anything is replaced, and before the outgoing loader is closed at the end of this method:
        // a plugin releasing a port or a nested display has to be able to run its own code to do it.
        if (serving) closeOutgoing(plugins);
        // After the outgoing set has been told, never before: one plugin may serve both projects, and being
        // handed the new one while it still believes it holds the old is how a release runs against the
        // wrong project's paths.
        openIncoming(bound, opening);

        PluginLoader previous = loader;
        loader = opened;
        plugins = bound;
        grammar = merged;
        ownedSlotEditors = mergeOwnedSlotEditors(bound);
        slotEditors = strip(ownedSlotEditors);
        ownedToolbarItems = mergeOwnedToolbarItems(bound);
        toolbarItems = strip(ownedToolbarItems, OwnedItem::item);
        managedValues = mergeManagedValues(bound);
        assistantTools = mergeAssistantTools(bound);
        assistantGuides = mergeAssistantGuides(bound);
        runOverlayParts = null;
        overlayParts = null;
        pluginFiles = PluginFiles.holdersOf(bound);
        catalog = null;
        catalogued = null;
        shipped = new ConcurrentHashMap<>();
        if (previous != null) previous.close();
    }

    /**
     * Tells the plugins that were serving the outgoing project that it is over.
     *
     * <p>Called from {@link #swap} once the binding is known to succeed, and deliberately at two points in
     * that method at once: <b>after</b> the merge that can still refuse the new set — a project that fails to
     * bind never displaced anything, so nothing has closed — and <b>before</b> the outgoing
     * {@link PluginLoader} is closed, because a plugin's release code is that plugin's own class and cannot
     * run on a dead classloader.
     *
     * <p>Total, like every other pass over plugin code here: one plugin that throws on the way out must not
     * stop the next plugin from being told, and must not stop the project that is opening.
     */
    /**
     * Tells the plugins about to serve a project which project it is.
     *
     * <p>The mirror of {@link #closeOutgoing}, and total in the same way: a plugin that throws on the way in
     * must not stop the next plugin from being told, and must not stop the project from opening. A plugin
     * that refuses the project simply has no project — its data surfaces answer nothing, which is what an
     * uninstalled plugin's do anyway.
     *
     * <p>Nobody is told on {@link #unbind}, and that is not an omission: there is no project to name, and
     * every plugin that was serving one has just had {@link StudioPlugin#projectClosing()}, which is the
     * statement that it is over. A plugin holds no project between binds.
     */
    static void openIncoming(List<LoadedPlugin> incoming, StudioServices services) {
        if (services == null) return;
        for (LoadedPlugin plugin : incoming) {
            try {
                plugin.plugin().projectOpened(services);
            } catch (RuntimeException | Error e) {
                System.err.println("Warning: plugin " + plugin.coordinate()
                        + " failed to take the opening project: " + e);
            }
        }
    }

    static void closeOutgoing(List<LoadedPlugin> outgoing) {
        for (LoadedPlugin plugin : outgoing) {
            try {
                plugin.plugin().projectClosing();
            } catch (RuntimeException | Error e) {
                System.err.println("Warning: plugin " + plugin.coordinate()
                        + " failed to release what it held for the closing project: " + e);
            }
        }
    }

    /**
     * The value grammar over every type the bound plugins declare — what reads a value out of a user's Java
     * and writes one back, for every plugin at once.
     *
     * <p>Two plugins declaring one type is refused at bind rather than resolved: first-wins would make a
     * project open differently depending on load order, which is the one failure a user could never
     * diagnose. See {@link #compose}.
     */
    public static ValueGrammar grammar() {
        return grammar;
    }

    /**
     * The grammar {@code plugins} would compose if they were bound — what an upgrade reads a bot through with the
     * target jar loaded beside the bound one (2026-10-11), so a value is read and written as that jar's classes.
     */
    public static ValueGrammar grammarOf(List<LoadedPlugin> plugins) {
        return compose(plugins).grammar();
    }

    /**
     * The loader the serving plugins came from — the open project's, or Studio's own with none bound. A
     * call or a type a plugin's editor asks about is loaded here, so the {@code Class} it compares against is
     * one that plugin could have named.
     */
    public static ClassLoader classLoader() {
        PluginLoader bound = loader;
        return bound != null ? bound.classLoader() : PluginHost.class.getClassLoader();
    }

    /**
     * Every loaded plugin's slot editors, in plugin order, for a value the host is about to render.
     *
     * <p>Consulted <b>after</b> the host's own built-in editors, which is the rule
     * {@link StudioPlugin#slotEditors()} states: a slot holding a project variable stays a variable no matter
     * what a plugin claims about its type.
     *
     * <p>Memoised for the same reason the value catalog is — this is asked once per row of the Parameters
     * window and once per slot the code editor draws — and cleared on {@link #bind}, since a plugin that has
     * just been unloaded must stop offering editors that would run on a dead classloader.
     */
    public static List<SlotEditor> slotEditors() {
        return slotEditors;
    }

    /**
     * One plugin's editor, with the plugin it came from.
     *
     * <p>{@link SlotEditor} carries no id — correctly, since an editor is a widget and not a contribution
     * record — so ownership is the merge's to remember. Nothing needed it until a user had to be shown
     * <em>which</em> plugin is drawing their value; see {@link EditorContest}.
     */
    public record OwnedEditor(PluginCoordinate plugin, String pluginName, SlotEditor editor) {

        /** The plugin as a verdict names it ({@link #preferredEditorFor}): {@code groupId:artifactId}. */
        public String pluginKey() {
            return plugin.toString();
        }
    }

    /** Every loaded plugin's slot editors with their owner, in plugin order. */
    public static List<OwnedEditor> ownedSlotEditors() {
        return ownedSlotEditors;
    }

    /**
     * Each plugin's {@link StudioPlugin#slotEditors()} first — the editors chosen by the call, and overrides
     * of somebody else's type, which are narrower matches — and then one editor per type it declares
     * <em>and draws</em>, an {@link EditableType}. A declared type is named once, in its {@code PluginType}, so
     * this is where it becomes an editor rather than the plugin listing it a second time. A plain
     * {@code PluginType} (2026-09-27) is drawn by another plugin's {@code forType} or the host's fallback.
     */
    static List<OwnedEditor> mergeOwnedSlotEditors(List<LoadedPlugin> set) {
        List<OwnedEditor> merged = new ArrayList<>();
        for (LoadedPlugin loaded : set) {
            StudioPlugin plugin = loaded.plugin();
            List<SlotEditor> offered = quietly(loaded, "offer slot editors", plugin::slotEditors);
            if (offered != null) {
                for (SlotEditor editor : offered) {
                    if (editor != null) merged.add(new OwnedEditor(loaded.coordinate(), loaded.name(), editor));
                }
            }
            List<PluginType<?>> types = quietly(loaded, "declare types", () -> typesOf(plugin));
            if (types == null) continue;
            for (PluginType<?> type : types) {
                if (!(type instanceof EditableType<?> drawn)) continue;   // a plain type: somebody else draws it
                Class<?> cls = quietly(loaded, "name a type", drawn::type);
                if (cls == null) continue;
                merged.add(new OwnedEditor(loaded.coordinate(), loaded.name(),
                        SlotEditor.forType(cls).draw(() -> drawn::editor, () -> drawn::preview)));
            }
        }
        return List.copyOf(merged);
    }

    /** A type a plugin declares, with that plugin — what the type chooser groups its list by. */
    public record OwnedType(PluginCoordinate plugin, String pluginName, Class<?> type) {}

    /** Every bound plugin's declared types, in plugin order; one that throws costs only its own. */
    public static List<OwnedType> ownedTypes() {
        List<OwnedType> out = new ArrayList<>();
        for (LoadedPlugin loaded : plugins()) {
            List<PluginType<?>> types = quietly(loaded, "declare types", () -> typesOf(loaded.plugin()));
            if (types == null) continue;
            for (PluginType<?> type : types) {
                Class<?> cls = type == null ? null : quietly(loaded, "name a type", type::type);
                if (cls != null) out.add(new OwnedType(loaded.coordinate(), loaded.name(), cls));
            }
        }
        return List.copyOf(out);
    }

    private static <O, T> List<T> strip(List<O> owned, java.util.function.Function<O, T> part) {
        return owned.stream().map(part).toList();
    }

    private static List<SlotEditor> strip(List<OwnedEditor> owned) {
        List<SlotEditor> editors = new ArrayList<>();
        for (OwnedEditor each : owned) editors.add(each.editor());
        return List.copyOf(editors);
    }

    /** Binds the open project's editor verdicts. Called wherever its settings become the current ones. */
    public static void preferEditors(Map<String, String> byTypeName) {
        preferredEditors = byTypeName == null ? Map.of() : Map.copyOf(byTypeName);
    }

    /**
     * The plugin coordinate ({@code groupId:artifactId}) the user chose to draw {@code typeName}, or {@code null}
     * for no verdict.
     *
     * @param typeName the fully qualified Java type — {@code ResolvedType.qualifiedName()} on the canvas,
     *                 {@code ValueType.javaName()} in the Parameters window, which is one key space on
     *                 purpose: a verdict given on a block applies to the row
     */
    public static String preferredEditorFor(String typeName) {
        return typeName == null || typeName.isBlank() ? null : preferredEditors.get(typeName);
    }

    /**
     * Every loaded plugin's toolbar items, sorted into the order the bar draws them.
     *
     * <p>Sorted here rather than at render time because the order is a property of the <em>set</em>, not of
     * the bar: group first, then the item's own {@code order}, then the contributing plugin's coordinate. That
     * last tie-break is what stops a bar built from two plugins depending on which one the loader happened to
     * find first — the same reasoning that makes the value catalog refuse a clash instead of
     * letting the winner be decided by load order.
     *
     * <p>Studio's own items are <b>not</b> here. They are added by the toolbar itself, which is why
     * {@link ToolbarGroup#STUDIO} is refused below: it is the host's section, and a plugin quietly re-homed
     * into it would sit where a user reads the application rather than their project.
     */
    public static List<ToolbarItem> toolbarItems() {
        return toolbarItems;
    }

    /**
     * The merged items in one group, in their merged order.
     *
     * <p>Two readers, which is why this exists rather than each filtering for itself: the main bar takes
     * every group but {@link ToolbarGroup#OVERLAY}, and {@code OverlayEditor} takes only that one. A
     * group nobody reads is an item <em>silently absent</em>, which is the class of failure every rule in
     * {@code ToolbarMergeTest} is about — so the split is one filter with two callers rather than two
     * conditions that can disagree about which groups exist.
     */
    public static List<ToolbarItem> itemsIn(ToolbarGroup group) {
        return itemsIn(toolbarItems(), group);
    }

    /** The same filter over an explicit list — the seam the merge test uses. */
    static List<ToolbarItem> itemsIn(List<ToolbarItem> items, ToolbarGroup group) {
        return items.stream().filter(item -> item.group() == group).toList();
    }

    /**
     * The Java a fresh value of the class named exactly {@code canonicalName} starts as — fully qualified — or
     * {@code null} when no bound plugin declares it.
     *
     * <p>Asked on every seed and never memoised: {@code PluginType.fresh()} may read the plugin's live
     * state, and the SDK's capture source is the project's <em>current</em> one. It was
     * {@code sourceSeeds()} until 2026-09-22 — the same answer as Java text a plugin typed, which javac
     * never looked at; now the plugin hands over a value and the grammar writes it.
     */
    public static String freshInitializer(String canonicalName) {
        return grammar.named(canonicalName).flatMap(grammar::freshInitializer).map(JavaValue::source).orElse(null);
    }

    /**
     * The values the bound plugins maintain through their own windows — what {@code LockResolver} asks
     * before letting the canvas edit a managed method or class. Empty with no project, so nothing
     * is locked by it.
     */
    public static List<ManagedValue<?>> managedValues() {
        return managedValues;
    }

    /**
     * The files the bound plugins own in a bot — each {@code plugins/<segment>/<Holder>.java} a plugin's
     * managed values name — which {@code LockResolver} locks on the canvas and the explorer marks. Empty with
     * no project, so no file is a plugin's.
     */
    public static List<com.botmaker.studio.project.PluginFiles.Holder> pluginFiles() {
        return pluginFiles;
    }

    /** A run overlay part with the plugin that offered it. */
    public record OwnedPart(PluginCoordinate plugin, RunOverlayPart part) {}

    /** Built on first ask after each bind, since listing parts loads the plugins' JavaFX classes. */
    private static volatile List<OwnedPart> runOverlayParts;

    /**
     * Every bound plugin's part of the run overlay, in plugin order; a part whose id its plugin already used
     * is dropped, and a plugin that cannot list its parts costs only itself.
     */
    public static List<OwnedPart> runOverlayParts() {
        List<OwnedPart> parts = runOverlayParts;
        if (parts == null) {
            parts = mergeRunOverlayParts(plugins);
            runOverlayParts = parts;
        }
        return parts;
    }

    /**
     * Whether the open project's bot can start now (2026-10-25): every bound plugin's {@link Readiness}, the least
     * ready shown, the first plugin's among equals; empty when none has anything to say. Blocking — each plugin
     * may read files or list windows — so never on the FX thread. A plugin whose check throws costs its own answer.
     */
    public static Optional<Readiness> readiness() {
        return leastReady(plugins);
    }

    static Optional<Readiness> leastReady(List<LoadedPlugin> set) {
        Readiness least = null;
        for (LoadedPlugin plugin : set) {
            Optional<Readiness> said;
            try {
                said = plugin.plugin().readiness();
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it has nothing to say.
                System.err.println("Warning: " + plugin.coordinate() + " could not say whether the bot can start: " + e);
                continue;
            }
            if (said == null || said.isEmpty()) continue;
            if (least == null || said.get().level().compareTo(least.level()) > 0) least = said.get();
        }
        return Optional.ofNullable(least);
    }

    static List<OwnedPart> mergeRunOverlayParts(List<LoadedPlugin> set) {
        List<OwnedPart> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            List<RunOverlayPart> offered;
            try {
                offered = plugin.plugin().runOverlayParts();
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply draws nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer run overlay parts: " + e);
                continue;
            }
            if (offered == null) continue;
            Set<String> ids = new LinkedHashSet<>();
            for (RunOverlayPart part : offered) {
                if (part != null && ids.add(part.id())) merged.add(new OwnedPart(plugin.coordinate(), part));
            }
        }
        return List.copyOf(merged);
    }

    /** An overlay editor part with the plugin that declared it. */
    public record OwnedOverlay(PluginCoordinate plugin, String pluginName, OverlayPart part) {}

    /** Built on first ask after each bind, since a part's supplier loads the plugin's JavaFX classes. */
    private static volatile List<OwnedOverlay> overlayParts;

    /**
     * Every bound plugin's part of the overlay editor, in plugin order. A plugin that declared none, or whose
     * supplier throws, costs only itself.
     */
    public static List<OwnedOverlay> overlayParts() {
        List<OwnedOverlay> parts = overlayParts;
        if (parts == null) {
            parts = mergeOverlayParts(plugins);
            overlayParts = parts;
        }
        return parts;
    }

    static List<OwnedOverlay> mergeOverlayParts(List<LoadedPlugin> set) {
        List<OwnedOverlay> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            Optional<OverlayPart> offered;
            try {
                offered = plugin.plugin().overlayPart();
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply adds nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer an overlay part: " + e);
                continue;
            }
            if (offered == null || offered.isEmpty() || offered.get().isEmpty()) continue;
            merged.add(new OwnedOverlay(plugin.coordinate(), plugin.name(), offered.get()));
        }
        return List.copyOf(merged);
    }

    /** An assistant tool with the plugin that offered it. */
    public record OwnedTool(PluginCoordinate plugin, String pluginName, AssistantTool<?> tool) {}

    private static volatile List<OwnedTool> assistantTools = List.of();

    /** Every bound plugin's assistant tools, in plugin order, asked for once per bind. */
    public static List<OwnedTool> assistantTools() {
        return assistantTools;
    }

    /** A plugin's guide to the bot it expects, with the plugin that wrote it. */
    public record OwnedGuide(String pluginName, String text) {}

    private static volatile List<OwnedGuide> assistantGuides = List.of();

    /** Every bound plugin's guide that says something, in plugin order, asked for once per bind. */
    public static List<OwnedGuide> assistantGuides() {
        return assistantGuides;
    }

    static List<OwnedGuide> mergeAssistantGuides(List<LoadedPlugin> set) {
        List<OwnedGuide> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            try {
                String text = plugin.plugin().assistantGuide();
                if (text != null && !text.isBlank()) merged.add(new OwnedGuide(plugin.name(), text.strip()));
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply says nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not give its assistant guide: " + e);
            }
        }
        return List.copyOf(merged);
    }

    static List<OwnedTool> mergeAssistantTools(List<LoadedPlugin> set) {
        List<OwnedTool> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            try {
                List<AssistantTool<?>> offered = plugin.plugin().assistantTools();
                if (offered == null) continue;
                for (var tool : offered) {
                    if (tool != null) merged.add(new OwnedTool(plugin.coordinate(), plugin.name(), tool));
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply offers none.
                System.err.println("Warning: " + plugin.coordinate() + " could not list assistant tools: " + e);
            }
        }
        return List.copyOf(merged);
    }

    /** Every plugin's entries, a throwing or malformed one costing only itself. */
    static List<ManagedValue<?>> mergeManagedValues(List<LoadedPlugin> set) {
        List<ManagedValue<?>> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            try {
                List<ManagedValue<?>> offered = plugin.plugin().managedValues();
                if (offered == null) continue;
                for (ManagedValue<?> value : offered) {
                    if (value != null && value.id() != null && !value.id().isBlank()) {
                        merged.add(value);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply manages nothing.
                System.err.println("Warning: " + plugin.coordinate() + " could not list managed values: " + e);
            }
        }
        return List.copyOf(merged);
    }

    // Package-private rather than private: the three rules below — the STUDIO refusal, the sort's tie-break
    // on the plugin's coordinate, and a throwing plugin costing only itself — have no visible symptom when they
    // are wrong. A bar in a slightly different order looks like somebody's preference, not like a bug.
    static List<ToolbarItem> mergeToolbarItems(List<LoadedPlugin> set) {
        return mergeOwnedToolbarItems(set).stream().map(OwnedItem::item).toList();
    }

    /**
     * A toolbar item with the plugin that offered it — what lets the bar draw one section per plugin
     * (2026-09-26) rather than mixing every plugin's buttons into the host's four groups.
     */
    public record OwnedItem(PluginCoordinate plugin, String pluginName, ToolbarItem item) {}

    /** Every loaded plugin's toolbar items with their owner, in {@link #toolbarItems()}' order. */
    public static List<OwnedItem> ownedToolbarItems() {
        return ownedToolbarItems;
    }

    /** {@link #mergeToolbarItems}, keeping the owner. */
    static List<OwnedItem> mergeOwnedToolbarItems(List<LoadedPlugin> set) {
        List<OwnedItem> merged = new ArrayList<>();
        for (LoadedPlugin plugin : set) {
            List<ToolbarItem> offered;
            try {
                offered = plugin.plugin().toolbarItems();
            } catch (RuntimeException | Error e) {
                // A plugin that cannot list its buttons must not cost the ones that can, nor the project.
                System.err.println("Warning: " + plugin.coordinate() + " could not offer toolbar items: " + e);
                continue;
            }
            if (offered == null) continue;
            for (ToolbarItem item : offered) {
                if (item == null || item.label() == null || item.onClick() == null) continue;
                if (item.group() == ToolbarGroup.STUDIO) {
                    System.err.println("Warning: " + plugin.coordinate() + " asked for the STUDIO toolbar group with '"
                            + item.id() + "'; that group is the host's own and the item is dropped.");
                    continue;
                }
                merged.add(new OwnedItem(plugin.coordinate(), plugin.name(), item));
            }
        }
        merged.sort(Comparator.comparing((OwnedItem o) -> o.item().group())
                .thenComparingInt(o -> o.item().order())
                .thenComparing(o -> o.plugin().toString()));
        return List.copyOf(merged);
    }

    // parameterGroups(String), parameterGroup(String, String), parameterRows(String) and
    // parameterEdited(ParameterEdit) stood here from 2026-09-10 to 2026-09-22, with a GROUPS cache beside
    // CACHE. The contract surface under them is deleted and nothing replaces them here.
    //
    // They asked each plugin for the rows of a section that plugin had declared. Nothing ever declared one:
    // the SDK's group was the only implementation, it declared no rows, and basics' ParameterStore.declare
    // had no caller, so what came back was a pre-2026-09-17 project's JSON and nothing else. A parameter is
    // a @Param field in the bot's own Java, which project/params/JavaParameters reads and writes off the
    // syntax tree -- including one in a file a plugin ships, which BotSources.scan already walks.
    //
    // parameterDeclared went first, on 2026-09-17, offering a declared row to each plugin until one owned it.

    /**
     * What {@code plugin} declares: its own {@code types()}, then one {@link FilledType} per {@code @Fills}
     * method in its jar whose class it does not declare itself. Throws what {@code types()} throws; a refused
     * {@code @Fills} method declares nothing ({@link #compose} warns of it, once per bind).
     */
    static List<PluginType<?>> typesOf(StudioPlugin plugin) {
        List<PluginType<?>> own = plugin.types();
        FilledTypes.Found found = FilledTypes.of(plugin);
        if (found.filled().isEmpty()) return own;
        List<PluginType<?>> types = new ArrayList<>(own == null ? List.of() : own);
        Set<String> declared = new HashSet<>();
        for (PluginType<?> type : types) if (type != null) declared.add(type.type().getName());
        for (FilledTypes.Filled filled : found.filled()) {
            if (declared.add(filled.type().getName())) types.add(FilledType.of(filled));
        }
        return types;
    }

    /**
     * Runs one plugin call, answering {@code null} when it throws.
     *
     * <p>The same containment every other pass over plugin code here uses: a plugin's failure costs that
     * plugin's contribution and nobody else's. It is a printed line rather than a dialog because the window
     * that asked has a section to draw and a user in front of it.
     */
    private static <T> T quietly(LoadedPlugin plugin, String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Warning: plugin " + plugin.coordinate() + " failed to " + what + ": " + e);
            return null;
        }
    }

    /**
     * What a set of plugins composes to: the grammar over what they declare, the plugins that are in it, and
     * a failure per plugin that was left out.
     *
     * @param grammar  what {@link #grammar()} serves
     * @param bound    {@code set} minus whatever could not compose, in the same order
     * @param rejected one entry per plugin left out, in the shape {@link #failures()} already reports
     */
    record Composition(ValueGrammar grammar, List<LoadedPlugin> bound,
                       List<PluginLoader.PluginFailure> rejected) {
    }

    /**
     * Folds the plugins' declarations into one grammar, <b>dropping a plugin that will not compose rather
     * than the whole set</b> (2026-09-19).
     *
     * <p>Two plugins declaring one type is genuinely unanswerable — the class <em>is</em> the identity, and
     * which plugin's {@code fresh()} and editor a project gets cannot depend on load order — so one of them
     * has to go. Which one is classpath order, which is Maven's own answer and the only one here that is not
     * a guess. Offering an editor for another plugin's type is not declaring it: that is
     * {@link StudioPlugin#slotEditors()}, and the user picks between the two.
     *
     * <p>Total, like every other pass over plugin code here: a plugin whose {@code types()} or
     * {@code componentTypes()} throws is a rejection and not a failed bind.
     */
    static Composition compose(List<LoadedPlugin> set) {
        List<PluginType<?>> types = new ArrayList<>();
        List<ComponentType<?>> components = new ArrayList<>();
        List<LoadedPlugin> kept = new ArrayList<>();
        List<PluginLoader.PluginFailure> rejected = new ArrayList<>();
        Map<String, String> claimedBy = new HashMap<>();
        for (LoadedPlugin loaded : set) {
            StudioPlugin plugin = loaded.plugin();
            List<PluginType<?>> offered;
            List<ComponentType<?>> parts;
            List<String> names = new ArrayList<>();
            try {
                offered = typesOf(plugin);
                for (String problem : FilledTypes.of(plugin).problems()) {
                    System.err.println("Warning: plugin " + loaded.coordinate() + ": " + problem);
                }
                parts = plugin.componentTypes();
                if (offered != null) {
                    for (PluginType<?> type : offered) if (type != null) names.add(JavaNames.canonical(type.type()));
                }
            } catch (RuntimeException | LinkageError e) {
                rejected.add(new PluginLoader.PluginFailure(loaded.name(), e));
                continue;
            }
            List<String> clashes = names.stream().filter(claimedBy::containsKey).distinct().toList();
            if (!clashes.isEmpty()) {
                rejected.add(new PluginLoader.PluginFailure(loaded.name(),
                        new IllegalStateException(clashMessage(clashes, claimedBy))));
                continue;
            }
            for (String name : names) claimedBy.putIfAbsent(name, loaded.name());
            if (offered != null) offered.stream().filter(t -> t != null).forEach(types::add);
            if (parts != null) parts.stream().filter(c -> c != null).forEach(components::add);
            kept.add(loaded);
        }
        return new Composition(ValueGrammar.of(types, components), List.copyOf(kept), List.copyOf(rejected));
    }

    /** Why a plugin was left out, naming the types and whoever already declared them. */
    private static String clashMessage(List<String> clashes, Map<String, String> claimedBy) {
        String owner = claimedBy.getOrDefault(clashes.getFirst(), "another plugin");
        return "it was left out: it declares " + String.join(", ", clashes)
                + ", which " + owner + " already declares (a method marked @Fills declares its return type too)."
                + " Two plugins cannot both own a type — install one of them, or move to a version of one that"
                + " no longer declares it.";
    }

    /** {@code first} then {@code second}, as one immutable list. */
    private static List<PluginLoader.PluginFailure> concat(List<PluginLoader.PluginFailure> first,
                                                           List<PluginLoader.PluginFailure> second) {
        List<PluginLoader.PluginFailure> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /**
     * The palette every bound plugin offers, merged. {@link PaletteCatalog#mergedWith} is additive on
     * purpose: a plugin curates its own surface and has no business removing another's.
     *
     * <p>No version argument since 2026-09-22. It took the SDK version the pom pinned, and no plugin ever
     * read it: the plugin answering is the one inside the pinned jar already, which is what {@link #bind}
     * is for.
     */
    public static PaletteCatalog catalogFor() {
        PaletteCatalog local = catalog;
        if (local == null) {
            local = merge(plugins);
            catalog = local;
        }
        return local;
    }

    /**
     * The same palette, read as "which names does a plugin own?" rather than "which members should we
     * offer". Two names for one answer, kept apart because the two questions are: recognition is asked with
     * no project in hand, and curation is not.
     */
    public static PaletteCatalog bundled() {
        return catalogFor();
    }

    /**
     * The packages the loaded plugins catalogue types in — what the type index treats as offerable library
     * surface, and everything outside it is scanned for resolution but kept out of the menus.
     *
     * <p><b>It was the literal {@code Set.of("com.botmaker.sdk.api")} until 2026-09-02</b>, on
     * {@code TypeSummaryManager}, which is the editor holding one plugin's package name on that plugin's
     * behalf — the same shape as every other thing that left this year, and the last of them with real
     * behaviour behind it. Asking the catalog is strictly better than a list: a plugin that puts its API in
     * two packages gets both, and a plugin nobody wrote yet gets its own without an edit here.
     *
     * <p>Derived from {@link #bundled()} rather than a pin, because the index is built once per project
     * <em>load</em> and outlives any one catalog lookup; a package set that changed under the index would
     * hide types that had already been indexed as visible. Empty when no plugin is loaded, which is the
     * honest answer — with nothing catalogued there is no curated surface, and the menus fall back to
     * everything the bot's own classpath resolves.
     */
    public static Set<String> cataloguedPackages() {
        PaletteCatalog now = bundled();
        Catalogued known = catalogued;
        if (known != null && known.of() == now) return known.packages();
        Set<String> packages = new LinkedHashSet<>();
        for (FacadeEntry facade : now.facades()) {
            String qualified = facade.qualifiedName();
            int dot = qualified.lastIndexOf('.');
            if (dot > 0) packages.add(qualified.substring(0, dot));
        }
        Set<String> answer = Set.copyOf(packages);
        catalogued = new Catalogued(now, answer);
        return answer;
    }

    /**
     * {@link #cataloguedPackages}, with the catalog it was read from: answered again only for that catalog, so a
     * lookup that read the old one while a bind ran cannot leave its answer for the new one. The type index asks
     * it on every lookup, and a block's parameter types are looked up on every render (2026-10-18).
     */
    private record Catalogued(PaletteCatalog of, Set<String> packages) { }
    private static volatile Catalogued catalogued;

    /**
     * The bundled facade with this simple name, if exactly one plugin offers it.
     *
     * <p>The import path's question, and the reason a catalog holds real {@link Class} objects rather than
     * names: this is what decides that {@code Point} in a bot's source means the SDK's and not
     * {@code java.awt}'s. Empty when no plugin owns the name — the honest answer, and the one that leaves an
     * unrecognised import alone rather than repointing it at a plausible guess.
     */
    public static Optional<FacadeEntry> ownerOf(String simpleName) {
        return simpleName == null || simpleName.isBlank()
                ? Optional.empty()
                : bundled().facadeBySimpleName(simpleName.trim());
    }

    /** The fully-qualified name a plugin owns for {@code simpleName}, or {@code null}. */
    public static String qualifiedName(String simpleName) {
        return ownerOf(simpleName).map(FacadeEntry::qualifiedName).orElse(null);
    }

    /**
     * True when {@code simpleClassName} names a facade — a class a call can be <em>made on</em>, hidden ones
     * included. This is <b>recognition</b>, not curation: it is what tells a placed {@code Mouse.click(…)}
     * apart from a call on a class the user wrote, so it reads the bundled catalog and never a project's pin.
     * A call whose facade this build has never heard of renders as a generic library call, which is right.
     */
    public static boolean isFacadeClass(String simpleClassName) {
        return ownerOf(simpleClassName).isPresent();
    }

    /** The facades the insert menus show, in declaration order — the bundled superset. */
    public static List<FacadeEntry> menuFacades() {
        return bundled().offeredFacades();
    }

    /**
     * Simple names of the offered facades, alphabetically — for the class dropdowns, which stay
     * {@code String}-valued on purpose: the scope they display can also be a class the user wrote, which no
     * catalog entry can name. Offered only since 2026-09-30: the dropdown listed every catalogued type, value
     * types and plumbing included, so {@code ActivityContext} and {@code Debug} sat beside {@code Mouse}.
     */
    public static List<String> facadeNames() {
        return menuFacades().stream()
                .map(FacadeEntry::simpleName)
                .toList();
    }

    /**
     * The jars and class directories the bound plugins were loaded from. A library class in one of them is
     * a plugin's, reached through its catalogue or not at all — the library lists leave it out (2026-09-30:
     * {@code Debug} and {@code BotSettings} came back through "Java &amp; Libraries" once the class dropdown
     * stopped listing them).
     */
    public static Set<Path> pluginJars() {
        Set<Path> jars = new LinkedHashSet<>();
        for (LoadedPlugin plugin : plugins) jars.add(plugin.jar());
        return jars;
    }

    /**
     * {@link #shipsInPlugin}'s answers for the plugins bound now, replaced on every bind. A palette's curation
     * asks it of every name it offers, each time a menu is built, and a name no plugin ships failed its
     * {@code Class.forName} anew every time: the most thrown exception in a profile of ordinary editing.
     */
    private static volatile Map<String, Boolean> shipped = new ConcurrentHashMap<>();

    /**
     * Whether the class {@code binaryName} ships in a bound plugin's jar. Looked up without being
     * initialised, once per name per bind; false for a name no plugin's loader can find.
     */
    public static boolean shipsInPlugin(String binaryName) {
        if (binaryName == null || binaryName.indexOf('.') < 0) return false;
        return shipped.computeIfAbsent(binaryName, name -> {
            try {
                return pluginOwning(Class.forName(name, false, classLoader())).isPresent();
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }

    private static PaletteCatalog merge(List<LoadedPlugin> set) {
        PaletteCatalog merged = PaletteCatalog.empty();
        for (LoadedPlugin plugin : set) {
            PaletteCatalog offered = quietly(plugin, "build its palette", () -> Palettes.of(plugin.plugin()));
            if (offered != null) merged = merged.mergedWith(offered);
        }
        return merged;
    }
}
