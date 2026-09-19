package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.ManagedField;
import com.botmaker.plugin.api.ParameterEdit;
import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.plugin.api.SlotEditor;
import com.botmaker.plugin.api.SourceSeed;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.api.ToolbarItem;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.plugin.host.PluginLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The plugins this Studio has loaded, and the one place their contributions are composed.
 *
 * <p><b>Plugins are discovered, never named.</b> Nothing in this package writes down an implementation
 * class: {@link ServiceLoader} reads each jar's own {@code META-INF/services} declaration and hands back
 * instances typed as {@link StudioPlugin}, so every call from here on is javac-checked against the contract.
 * The SDK reaches this list exactly as a third-party plugin would — it is Studio's plugin #1 and gets no
 * back door.
 *
 * <p><b>The set is bound per project, and that is what makes the pin real.</b> {@link #bind} builds a
 * {@link PluginLoader} over the project's <em>resolved artifacts</em>, so the plugin answering for a bot
 * pinned to SDK 1.1.0 is the one inside that jar. That loader lived in this package until 2026-08-28 and is
 * now {@code botmaker-plugin-host}: Studio is no longer the only host, and the delegation split it makes is
 * the last code here that should exist in two copies. What stayed is everything below — the bundled
 * fallback, the swap, and the two catalogs — because all of it is about <em>Studio's</em> open project. With no project open — an import repair, a paste, the
 * project selection screen — the answer comes from {@link #bundled}, the plugins on Studio's own
 * classloader, and that is also the fallback for every way binding can fail.
 *
 * <p><b>Fail-open, in one direction only.</b> An empty classpath, a jar with no services file, a plugin
 * whose constructor throws: each is logged and answered with the bundled set. Menus widen, never empty; a
 * value type nothing registers resolves to {@code ValueType.unknown} and its stored value is preserved
 * read-only. {@code ActivityVariable}'s constructor reads {@link #valueTypes()} and must never see an
 * exception, whatever the state of the loader.
 *
 * <h2>Two catalogs, and the difference matters</h2>
 *
 * <p>{@link #catalogFor(String)} answers for <em>the version a project pins</em>: a bot compiles against the
 * SDK named in its pom, which is routinely older than the one this Studio bundles, and the palette must
 * describe that jar rather than this one. That is the inversion's rule — a bot gets its answers from its own
 * version — and it is why the pinned version is an argument at every level of this stack.
 *
 * <p>{@link #bundled()} answers for the plugin jars Studio itself was compiled against, and has exactly one
 * legitimate use: resolving a bare simple name to a fully-qualified one when there is no project in hand
 * (an import repair, a paste, a rewrite built from a bare {@link java.lang.String}). It replaced
 * {@code palette.SdkType}, a hand-mirrored enum of the SDK's class list which had the same scope and the
 * same job but no author but us — a class renamed in the SDK broke a menu at runtime instead of this build.
 *
 * <p>Both are memoised: building a catalog resolves every entry's method reference through a
 * {@code SerializedLambda}, which is cheap but not free, and the menus ask on every open.
 */
public final class PluginHost {

    private PluginHost() {}

    /**
     * The plugins on Studio's own classloader — the fallback, and the whole of the no-project path.
     *
     * <p>Built once and never rebound: it is a property of this build, not of anything a user does. It is
     * also why this phase needed no separate step for "discovery without a project" — the no-project path
     * <em>is</em> plain {@link ServiceLoader}, over the same declaration a project's jar carries.
     */
    private static final List<StudioPlugin> BUNDLED = discover(PluginHost.class.getClassLoader());

    /**
     * The plugin set answering right now: the bound project's, or {@link #BUNDLED}. Volatile because
     * {@link #bind} runs off the FX thread (the classpath resolve it follows does) while the readers below
     * are called from wherever a variable is constructed.
     */
    private static volatile List<StudioPlugin> plugins = BUNDLED;

    /** The loader behind a bound set, held only so it can be closed. Null whenever {@link #BUNDLED} is live. */
    private static volatile PluginLoader loader;

    /**
     * Whether the live set is serving an open project — which is <em>not</em> the same question as whether
     * {@link #loader} is non-null.
     *
     * <p>A project whose own plugins failed to load is served by {@link #BUNDLED} with no loader at all, and
     * that set is holding whatever it opened for that project exactly as a project's own would. So this is
     * what decides that {@link StudioPlugin#projectClosing()} is owed, rather than the loader: without it,
     * the fail-open case — the one where a leak is least likely to be noticed — would never be told.
     */
    private static boolean serving;

    /** pinned version → the merged catalog for it. Keyed by the raw pin, since that is what a caller holds. */
    private static final Map<String, PaletteCatalog> CACHE = new ConcurrentHashMap<>();

    /**
     * The blank pin — "whatever this jar is". Every plugin is asked to read it as its own version rather
     * than as an unknown one, which is what makes {@link #bundled()} a question a plugin can answer without
     * Studio having to know how that plugin spells its versions.
     */
    private static final String BUNDLED_PIN = "";

    /**
     * Memoised on the same reasoning as the palette caches — the merge walks every plugin and the variable
     * editors ask on every keystroke — and rebuilt on every bind, which is why it is a field rather than a
     * constant.
     */
    private static volatile ValueCatalog valueTypes = mergeValueTypes();

    /** Memoised beside the value catalog, and rebuilt on the same bind, for the same reason. */
    private static volatile List<OwnedEditor> ownedSlotEditors = mergeOwnedSlotEditors(BUNDLED);

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
    private static volatile List<ToolbarItem> toolbarItems = mergeToolbarItems(BUNDLED);

    /** Memoised the same way. See {@link #managedFields()}. */
    private static volatile List<ManagedField> managedFields = mergeManagedFields(BUNDLED);

    /** What the last {@link #bind} could not load. See {@link #failures()}. */
    private static volatile List<PluginLoader.PluginFailure> failures = List.of();

    /**
     * The project being bound, handed to every plugin that ends up serving it.
     *
     * <p>Held only across one {@link #bind} — {@link #swap} reads it and {@link #unbind} clears it — because
     * it is the argument of a call rather than state anybody here reads. It is a field only because
     * {@code swap} is reached from three places and is where the incoming set is finally known.
     */
    private static StudioServices opening;

    /** pinned version → the sections of the Parameters window at it. Cleared with {@link #CACHE}. */
    private static final Map<String, List<ParameterGroup>> GROUPS = new ConcurrentHashMap<>();

    public static List<StudioPlugin> plugins() {
        return plugins;
    }

    /**
     * Binds the plugins declared on {@code resolvedClasspath}, replacing whatever was bound before.
     *
     * <p>Called immediately after a project's classpath is resolved — on open, and again whenever the
     * libraries or the SDK pin change. Anything that goes wrong leaves {@link #BUNDLED} bound: see the
     * fail-open note in the class javadoc.
     *
     * <p>{@code services} names the project being bound, and every plugin that ends up serving it is told
     * through {@link StudioPlugin#projectOpened(StudioServices)} — including the bundled set, which is what
     * serves a project whose own plugins would not load. It is what a plugin answering
     * {@link StudioPlugin#parameterRows(String)} reads its own file out of; those surfaces take no services
     * argument, deliberately, because they are asked every time a window is drawn.
     */
    public static synchronized void bind(List<String> resolvedClasspath, StudioServices services) {
        opening = services;
        PluginLoader.Loaded loaded = PluginLoader.openReporting(resolvedClasspath);
        failures = loaded.failures();
        PluginLoader opened = loaded.loader();
        if (opened == null) {
            // Not unbind(): a classpath that would not open is still a project opening, and the bundled set
            // is about to serve it. Saying "no project" here would leave the next close with nobody to tell.
            swap(null, BUNDLED);
            serving = true;
            return;
        }
        swap(opened, opened.plugins());
        serving = true;
    }

    /**
     * Falls back to the bundled plugins and releases the project's jars.
     *
     * <p>Closing is required rather than tidy: an open {@code URLClassLoader} keeps every jar it read open,
     * and on Windows that makes the file unreplaceable — so a project switch that skipped this would break
     * the <em>next</em> project's dependency resolve rather than this one's.
     */
    public static synchronized void unbind() {
        opening = null;
        swap(null, BUNDLED);
        serving = false;
        failures = List.of();
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

    private static void swap(PluginLoader opened, List<StudioPlugin> bound) {
        Composition composed = compose(bound);
        ValueCatalog merged = composed.valueTypes();
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
        valueTypes = merged;
        ownedSlotEditors = mergeOwnedSlotEditors(bound);
        slotEditors = strip(ownedSlotEditors);
        toolbarItems = mergeToolbarItems(bound);
        managedFields = mergeManagedFields(bound);
        CACHE.clear();
        GROUPS.clear();
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
     * <p>The bundled set gets it too. It is never unloaded, so it is the set holding a project's resources
     * whenever a project's own plugins failed to bind — which is precisely when leaking them would be least
     * noticed.
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
    static void openIncoming(List<StudioPlugin> incoming, StudioServices services) {
        if (services == null) return;
        for (StudioPlugin plugin : incoming) {
            try {
                plugin.projectOpened(services);
            } catch (RuntimeException | Error e) {
                System.err.println("Warning: plugin '" + plugin.id()
                        + "' failed to take the opening project: " + e);
            }
        }
    }

    static void closeOutgoing(List<StudioPlugin> outgoing) {
        for (StudioPlugin plugin : outgoing) {
            try {
                plugin.projectClosing();
            } catch (RuntimeException | Error e) {
                System.err.println("Warning: plugin '" + plugin.id()
                        + "' failed to release what it held for the closing project: " + e);
            }
        }
    }

    /**
     * Every {@link StudioPlugin} {@code classLoader} can see, or an empty list.
     *
     * <p>Total by construction: a missing service file and a provider that will not instantiate are both
     * ordinary states here — a bot's classpath legitimately carries no plugin at all — and neither may take
     * Studio down on the way to a project screen.
     */
    private static List<StudioPlugin> discover(ClassLoader classLoader) {
        List<StudioPlugin> found = new ArrayList<>();
        try {
            // An explicit loop, not a stream: providers instantiate lazily and one that throws must not
            // cost the ones already found.
            for (StudioPlugin plugin : ServiceLoader.load(StudioPlugin.class, classLoader)) {
                found.add(plugin);
            }
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: could not discover bundled plugins: " + e);
        }
        return List.copyOf(found);
    }

    /**
     * Every value type every loaded plugin registers, merged by id.
     *
     * <p><b>This does not vary by pin, and that is not an oversight.</b> A palette entry is an <em>offer</em>
     * — a member this build knows about that an older jar may not contain — so it is narrowed against the
     * bot's own bytecode. A value type is a <em>reading</em>: the project file already says {@code DURATION},
     * and what that word means has to be answered whatever the pin, or the value cannot be shown at all. A
     * type an older jar never had simply never appears in an older project's file.
     *
     * <p>Two plugins claiming one id is refused loudly at startup rather than resolved. First-wins would make
     * a project open differently depending on load order, which is the one failure a user could never
     * diagnose; and the ids are what {@code activities.json} holds, so a silent winner silently retypes their
     * variables.
     */
    public static ValueCatalog valueTypes() {
        return valueTypes;
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
    public record OwnedEditor(String pluginId, String pluginName, SlotEditor editor) {}

    /** Every loaded plugin's slot editors with their owner, in plugin order. */
    public static List<OwnedEditor> ownedSlotEditors() {
        return ownedSlotEditors;
    }

    private static List<OwnedEditor> mergeOwnedSlotEditors(List<StudioPlugin> set) {
        List<OwnedEditor> merged = new ArrayList<>();
        for (StudioPlugin plugin : set) {
            List<SlotEditor> offered = plugin.slotEditors();
            if (offered == null) continue;
            String name = displayNameOf(plugin);
            for (SlotEditor editor : offered) {
                if (editor != null) merged.add(new OwnedEditor(plugin.id(), name, editor));
            }
        }
        return List.copyOf(merged);
    }

    /** A plugin's own name for itself, falling back to its id — a plugin that throws here costs only a label. */
    private static String displayNameOf(StudioPlugin plugin) {
        try {
            String name = plugin.displayName();
            return name == null || name.isBlank() ? plugin.id() : name;
        } catch (RuntimeException | Error e) {
            return plugin.id();
        }
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
     * The plugin id the user chose to draw {@code typeName}, or {@code null} for no verdict.
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
     * the bar: group first, then the item's own {@code order}, then the contributing plugin's id. That last
     * tie-break is what stops a bar built from two plugins depending on which one {@code ServiceLoader}
     * happened to find first — the same reasoning that makes the value catalog refuse a clash instead of
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
     * every group but {@link ToolbarGroup#OVERLAY}, and {@code ProgramShapeOverlay} takes only that one. A
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
     * The Java a fresh value of a plugin-owned type should be written as — asked on every seed, never
     * memoised, which is the one place this class deliberately does not cache.
     *
     * <p>{@link SourceSeed} is contributed as data, and a seed may read the project's live state: the SDK's
     * capture-source seed is the project's <em>current</em> default target. Memoising it beside the slot
     * editors would freeze a slot onto whatever that was at project open — the bug the SDK's own
     * {@code CaptureExpr.projectDefault()} exists to prevent. The lists are two entries long and a seed is
     * built when a user drops a block, so there is nothing here worth caching anyway.
     *
     * <p>A plugin that throws costs only itself, exactly as in {@link #mergeToolbarItems}: an uncompilable
     * default in one slot must not stop the others being seeded.
     */
    public static List<SourceSeed> sourceSeeds() {
        List<SourceSeed> merged = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            List<SourceSeed> offered;
            try {
                offered = plugin.sourceSeeds();
            } catch (RuntimeException | Error e) {
                System.err.println("Warning: " + plugin.id() + " could not offer source seeds: " + e);
                continue;
            }
            if (offered == null) continue;
            for (SourceSeed seed : offered) {
                if (seed != null && seed.typeName() != null && seed.expression() != null
                        && !seed.expression().isBlank()) {
                    merged.add(seed);
                }
            }
        }
        return List.copyOf(merged);
    }

    /**
     * The seed for a slot whose type is written {@code typeName}, or {@code null} for none.
     *
     * <p>First match wins, in plugin load order, which is the same rule the slot editors follow and for the
     * same reason: a plugin that ships a type ships the answer for it, and two plugins claiming one name is
     * a collision the host cannot adjudicate.
     */
    public static SourceSeed sourceSeedFor(String typeName) {
        for (SourceSeed seed : sourceSeeds()) {
            if (seed.claims(typeName)) return seed;
        }
        return null;
    }

    /**
     * The fields the bound plugins maintain through their own windows — what {@code LockResolver} asks
     * before letting the canvas edit a constant. Empty with no project, so nothing is locked by it.
     */
    public static List<ManagedField> managedFields() {
        return managedFields;
    }

    /** Every plugin's entries, a throwing or malformed one costing only itself. */
    static List<ManagedField> mergeManagedFields(List<StudioPlugin> set) {
        List<ManagedField> merged = new ArrayList<>();
        for (StudioPlugin plugin : set) {
            try {
                List<ManagedField> offered = plugin.managedFields();
                if (offered == null) continue;
                for (ManagedField field : offered) {
                    if (field != null && field.typeName() != null && !field.typeName().isBlank()) {
                        merged.add(field);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply manages nothing.
                System.err.println("Warning: " + plugin.id() + " could not list managed fields: " + e);
            }
        }
        return List.copyOf(merged);
    }

    // Package-private rather than private: the three rules below — the STUDIO refusal, the sort's tie-break
    // on the plugin id, and a throwing plugin costing only itself — have no visible symptom when they are
    // wrong. A bar in a slightly different order looks like somebody's preference, not like a bug.
    static List<ToolbarItem> mergeToolbarItems(List<StudioPlugin> set) {
        record Owned(String pluginId, ToolbarItem item) {}
        List<Owned> merged = new ArrayList<>();
        for (StudioPlugin plugin : set) {
            List<ToolbarItem> offered;
            try {
                offered = plugin.toolbarItems();
            } catch (RuntimeException | Error e) {
                // A plugin that cannot list its buttons must not cost the ones that can, nor the project.
                System.err.println("Warning: " + plugin.id() + " could not offer toolbar items: " + e);
                continue;
            }
            if (offered == null) continue;
            for (ToolbarItem item : offered) {
                if (item == null || item.label() == null || item.onClick() == null) continue;
                if (item.group() == ToolbarGroup.STUDIO) {
                    System.err.println("Warning: " + plugin.id() + " asked for the STUDIO toolbar group with '"
                            + item.id() + "'; that group is the host's own and the item is dropped.");
                    continue;
                }
                merged.add(new Owned(plugin.id(), item));
            }
        }
        merged.sort(Comparator.comparing((Owned o) -> o.item().group())
                .thenComparingInt(o -> o.item().order())
                .thenComparing(Owned::pluginId));
        List<ToolbarItem> out = new ArrayList<>(merged.size());
        for (Owned owned : merged) out.add(owned.item());
        return List.copyOf(out);
    }

    /**
     * The sections of the Parameters window, in plugin order — the default plugin's first.
     *
     * <p>One window, one section per group. Two plugins claiming one {@link ParameterGroup#className()} is a
     * composition error, and it is refused the same way a value-type id clash is: the later claimant is
     * dropped with a warning rather than the project being refused, because a user whose project will not
     * open has no way to act on the problem, while a section that is missing is visible and diagnosable.
     *
     * @param pinnedSdkVersion the version the open project's pom names; passed through, never interpreted
     */
    public static List<ParameterGroup> parameterGroups(String pinnedSdkVersion) {
        String pin = pinnedSdkVersion == null ? BUNDLED_PIN : pinnedSdkVersion;
        return GROUPS.computeIfAbsent(pin, PluginHost::buildGroups);
    }

    /** The group a variable's {@code group} id names, or null when no loaded plugin claims that id. */
    public static ParameterGroup parameterGroup(String pinnedSdkVersion, String groupId) {
        String wanted = groupId == null ? ParameterGroup.DEFAULT_ID : groupId.trim();
        for (ParameterGroup group : parameterGroups(pinnedSdkVersion)) {
            if (group.id().equals(wanted)) return group;
        }
        return null;
    }

    /**
     * The rows one section currently holds, asked of whichever plugin owns the group.
     *
     * <p><b>Asked on every draw and never cached</b>, which is the surface's own rule: the file is the truth
     * and the plugin is the only thing that can read it. The first plugin to answer anything owns the group —
     * two plugins claiming one id is already refused when the groups are merged, so the loser is not asked.
     *
     * <p>A plugin that throws costs its own section and nothing else. The window has to draw either way, and
     * the alternative is one broken plugin emptying a window several plugins contribute to.
     */
    public static List<ParameterRow> parameterRows(String groupId) {
        String wanted = groupId == null ? ParameterGroup.DEFAULT_ID : groupId.trim();
        for (StudioPlugin plugin : plugins) {
            List<ParameterRow> rows = quietly(plugin, "read its parameters",
                    () -> plugin.parameterRows(wanted));
            if (rows != null && !rows.isEmpty()) return List.copyOf(rows);
        }
        return List.of();
    }

    /**
     * A changed value, offered to each plugin until one takes it — the answer being the row as stored.
     *
     * <p>Empty is "nobody owns this", which the caller reads as *leave the screen alone*. It is deliberately
     * the same answer a plugin that threw produces: a row the user's edit did not reach must not be redrawn
     * as though it had.
     */
    public static Optional<ParameterRow> parameterEdited(ParameterEdit edit) {
        if (edit == null) return Optional.empty();
        for (StudioPlugin plugin : plugins) {
            Optional<ParameterRow> stored = quietly(plugin, "store a parameter value",
                    () -> plugin.parameterEdited(edit));
            if (stored != null && stored.isPresent()) return stored;
        }
        return Optional.empty();
    }

    // parameterDeclared stood here until 2026-09-17, offering a declared row to each plugin until one owned
    // it. The contract call is gone: a user parameter is a @Param field in the bot's own Java and the host
    // declares one by editing the syntax tree (project/params/ParameterSurface). What a plugin still owns it
    // declares in its own code, and the host only ever changes a value — parameterEdited, above.

    /**
     * Runs one plugin call, answering {@code null} when it throws.
     *
     * <p>The same containment every other pass over plugin code here uses: a plugin's failure costs that
     * plugin's contribution and nobody else's. It is a printed line rather than a dialog because the window
     * that asked has a section to draw and a user in front of it.
     */
    private static <T> T quietly(StudioPlugin plugin, String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Warning: plugin " + plugin.id() + " failed to " + what + ": " + e);
            return null;
        }
    }

    private static List<ParameterGroup> buildGroups(String pin) {
        List<ParameterGroup> merged = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> classNames = new LinkedHashSet<>();
        for (StudioPlugin plugin : plugins) {
            List<ParameterGroup> offered = plugin.parameters(pin);
            if (offered == null) continue;
            for (ParameterGroup group : offered) {
                if (!ids.add(group.id()) || !classNames.add(group.className())) {
                    System.err.println("Warning: plugin " + plugin.id() + " claims a parameter group ("
                            + group.id() + " / " + group.className() + ") another plugin already owns;"
                            + " its section is not shown.");
                    continue;
                }
                merged.add(group);
            }
        }
        return List.copyOf(merged);
    }

    private static ValueCatalog mergeValueTypes() {
        return compose(plugins).valueTypes();
    }

    /**
     * What a set of plugins composes to: the merged value vocabulary, the plugins that are in it, and a
     * failure per plugin that was left out.
     *
     * @param valueTypes what {@link #valueTypes()} serves
     * @param bound      {@code set} minus whatever could not compose, in the same order
     * @param rejected   one entry per plugin left out, in the shape {@link #failures()} already reports
     */
    record Composition(ValueCatalog valueTypes, List<StudioPlugin> bound,
                               List<PluginLoader.PluginFailure> rejected) {
    }

    /**
     * Folds the plugins into one value vocabulary, <b>dropping a plugin that will not compose rather than
     * the whole set</b> (2026-09-19).
     *
     * <p>Two plugins claiming one id is genuinely unanswerable — the id <em>is</em> the identity a project
     * file holds — so one of them has to go. Which one is classpath order, which is Maven's own answer and
     * the only one here that is not a guess. What changed is the blast radius: the fold used to throw, and
     * the caller fell back to the bundled set, so a clash between two of a project's plugins cost the user
     * every editor of every other plugin as well.
     *
     * <p>Total, like every other pass over plugin code here: a plugin whose own {@code valueTypes()} throws
     * is a rejection and not a failed bind.
     */
    static Composition compose(List<StudioPlugin> set) {
        ValueCatalog merged = ValueCatalog.empty();
        List<StudioPlugin> kept = new ArrayList<>();
        List<PluginLoader.PluginFailure> rejected = new ArrayList<>();
        Map<String, String> claimedBy = new HashMap<>();
        for (StudioPlugin plugin : set) {
            ValueCatalog offered;
            try {
                offered = plugin.valueTypes();
            } catch (RuntimeException | Error e) {
                rejected.add(new PluginLoader.PluginFailure(plugin.id(), e));
                continue;
            }
            if (offered == null) {
                kept.add(plugin);
                continue;
            }
            List<String> clashes = merged.clashesWith(offered);
            if (!clashes.isEmpty()) {
                rejected.add(new PluginLoader.PluginFailure(plugin.id(),
                        new IllegalStateException(clashMessage(clashes, claimedBy))));
                continue;
            }
            for (ValueType type : offered.types()) claimedBy.putIfAbsent(type.id(), plugin.id());
            merged = merged.merge(offered);
            kept.add(plugin);
        }
        return new Composition(merged, List.copyOf(kept), List.copyOf(rejected));
    }

    /** Why a plugin was left out, naming the ids and whoever already claimed them. */
    private static String clashMessage(List<String> clashes, Map<String, String> claimedBy) {
        String owner = clashes.stream().map(claimedBy::get).filter(Objects::nonNull).findFirst()
                .orElse("another plugin");
        return "it was left out: it registers the value type(s) " + String.join(", ", clashes)
                + ", which " + owner + " already claims. Two plugins cannot both own a type id — install one "
                + "of them, or move to a version of one that no longer registers it.";
    }

    /** {@code first} then {@code second}, as one immutable list. */
    private static List<PluginLoader.PluginFailure> concat(List<PluginLoader.PluginFailure> first,
                                                           List<PluginLoader.PluginFailure> second) {
        List<PluginLoader.PluginFailure> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /**
     * The palette every loaded plugin offers at the versions this project pins, merged.
     *
     * <p>Today one plugin means one version string. When a second plugin arrives this takes a map — the
     * merge itself already handles several catalogs, and {@link PaletteCatalog#mergedWith} is additive on
     * purpose: a plugin curates its own surface and has no business removing another's.
     *
     * @param pinnedSdkVersion the SDK version the open project's pom names; never interpreted here
     */
    public static PaletteCatalog catalogFor(String pinnedSdkVersion) {
        String pin = pinnedSdkVersion == null ? BUNDLED_PIN : pinnedSdkVersion;
        return CACHE.computeIfAbsent(pin, PluginHost::build);
    }

    /**
     * The palette of the plugin jars Studio itself bundles — the newest surface this build knows about.
     *
     * <p>Read it as "which names does a plugin own?", never as "which members should we offer": the second
     * question belongs to the project's own pin and is {@link #catalogFor}'s. Using this one for curation
     * would offer a bot on an older SDK members its jar has never had, which is the bug the pinned catalog
     * exists to prevent.
     */
    public static PaletteCatalog bundled() {
        return catalogFor(BUNDLED_PIN);
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
        Set<String> packages = new LinkedHashSet<>();
        for (FacadeEntry facade : bundled().facades()) {
            String qualified = facade.qualifiedName();
            int dot = qualified.lastIndexOf('.');
            if (dot > 0) packages.add(qualified.substring(0, dot));
        }
        return Set.copyOf(packages);
    }

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
     * Simple names of every facade, hidden ones included, in declaration order — for the class dropdowns,
     * which stay {@code String}-valued on purpose: the scope they display can also be a class the user wrote,
     * which no catalog entry can name.
     */
    public static List<String> facadeNames() {
        return bundled().facades().stream()
                .map(FacadeEntry::simpleName)
                .toList();
    }

    private static PaletteCatalog build(String pin) {
        PaletteCatalog merged = PaletteCatalog.empty();
        for (StudioPlugin plugin : plugins) {
            merged = merged.mergedWith(plugin.catalog(pin));
        }
        return merged;
    }
}
