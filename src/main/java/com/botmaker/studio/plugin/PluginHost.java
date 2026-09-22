package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.toolbar.ToolbarGroup;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.ServiceLoader;
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
 * type no plugin declares is read as unknown and its value is preserved read-only. {@link #grammar()} must
 * never throw, whatever the state of the loader.
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

    /** The bound set's merged palette, built on first ask and dropped on every bind. */
    private static volatile PaletteCatalog catalog;

    /**
     * Memoised on the same reasoning as the palette — the merge walks every plugin and the value cells ask
     * on every keystroke — and rebuilt on every bind, which is why it is a field rather than a constant.
     */
    private static volatile ValueGrammar grammar = compose(BUNDLED).grammar();

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

    /** Memoised the same way. See {@link #managedValues()}. */
    private static volatile List<ManagedValue> managedValues = mergeManagedValues(BUNDLED);

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
     * serves a project whose own plugins would not load. It is what a plugin reads its own files out of; no
     * contribution surface takes a services argument, deliberately, because they are asked every time a
     * window is drawn.
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
        toolbarItems = mergeToolbarItems(bound);
        managedValues = mergeManagedValues(bound);
        catalog = null;
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

    /**
     * Each plugin's {@link StudioPlugin#slotEditors()} first — the editors chosen by the call, and overrides
     * of somebody else's type, which are narrower matches — and then one editor per type it declares, drawn
     * by {@link PluginType#editor}. A declared type is named once, in its {@code PluginType}, so this is
     * where it becomes an editor rather than the plugin listing it a second time.
     */
    static List<OwnedEditor> mergeOwnedSlotEditors(List<StudioPlugin> set) {
        List<OwnedEditor> merged = new ArrayList<>();
        for (StudioPlugin plugin : set) {
            String name = displayNameOf(plugin);
            List<SlotEditor> offered = quietly(plugin, "offer slot editors", plugin::slotEditors);
            if (offered != null) {
                for (SlotEditor editor : offered) {
                    if (editor != null) merged.add(new OwnedEditor(plugin.id(), name, editor));
                }
            }
            List<PluginType<?>> types = quietly(plugin, "declare types", plugin::types);
            if (types == null) continue;
            for (PluginType<?> type : types) {
                Class<?> cls = type == null ? null : quietly(plugin, "name a type", type::type);
                if (cls == null) continue;
                merged.add(new OwnedEditor(plugin.id(), name,
                        SlotEditor.forType(cls, type::editor, type::preview)));
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
     * The Java a fresh value of the type written {@code typeName} starts as — fully qualified — or
     * {@code null} when no bound plugin declares it.
     *
     * <p>Asked on every seed and never memoised: {@code PluginType.fresh()} may read the plugin's live
     * state, and the SDK's capture source is the project's <em>current</em> one. It was
     * {@code sourceSeeds()} until 2026-09-22 — the same answer as Java text a plugin typed, which javac
     * never looked at; now the plugin hands over a value and the grammar writes it.
     */
    public static String freshSource(String typeName) {
        return grammar.freshInitializer(ValueForm.of(typeName == null ? "" : typeName)).orElse(null);
    }

    /**
     * The values the bound plugins maintain through their own windows — what {@code LockResolver} asks
     * before letting the canvas edit a {@code @Managed} method or class. Empty with no project, so nothing
     * is locked by it.
     */
    public static List<ManagedValue> managedValues() {
        return managedValues;
    }

    /** Every plugin's entries, a throwing or malformed one costing only itself. */
    static List<ManagedValue> mergeManagedValues(List<StudioPlugin> set) {
        List<ManagedValue> merged = new ArrayList<>();
        for (StudioPlugin plugin : set) {
            try {
                List<ManagedValue> offered = plugin.managedValues();
                if (offered == null) continue;
                for (ManagedValue value : offered) {
                    if (value != null && value.id() != null && !value.id().isBlank()) merged.add(value);
                }
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it simply manages nothing.
                System.err.println("Warning: " + plugin.id() + " could not list managed values: " + e);
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

    /**
     * What a set of plugins composes to: the grammar over what they declare, the plugins that are in it, and
     * a failure per plugin that was left out.
     *
     * @param grammar  what {@link #grammar()} serves
     * @param bound    {@code set} minus whatever could not compose, in the same order
     * @param rejected one entry per plugin left out, in the shape {@link #failures()} already reports
     */
    record Composition(ValueGrammar grammar, List<StudioPlugin> bound,
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
    static Composition compose(List<StudioPlugin> set) {
        List<PluginType<?>> types = new ArrayList<>();
        List<ComponentType<?>> components = new ArrayList<>();
        List<StudioPlugin> kept = new ArrayList<>();
        List<PluginLoader.PluginFailure> rejected = new ArrayList<>();
        Map<String, String> claimedBy = new HashMap<>();
        for (StudioPlugin plugin : set) {
            List<PluginType<?>> offered;
            List<ComponentType<?>> parts;
            List<String> names = new ArrayList<>();
            try {
                offered = plugin.types();
                parts = plugin.componentTypes();
                if (offered != null) {
                    for (PluginType<?> type : offered) if (type != null) names.add(JavaNames.canonical(type.type()));
                }
            } catch (RuntimeException | LinkageError e) {
                rejected.add(new PluginLoader.PluginFailure(plugin.id(), e));
                continue;
            }
            List<String> clashes = names.stream().filter(claimedBy::containsKey).distinct().toList();
            if (!clashes.isEmpty()) {
                rejected.add(new PluginLoader.PluginFailure(plugin.id(),
                        new IllegalStateException(clashMessage(clashes, claimedBy))));
                continue;
            }
            for (String name : names) claimedBy.putIfAbsent(name, plugin.id());
            if (offered != null) offered.stream().filter(t -> t != null).forEach(types::add);
            if (parts != null) parts.stream().filter(c -> c != null).forEach(components::add);
            kept.add(plugin);
        }
        return new Composition(ValueGrammar.of(types, components), List.copyOf(kept), List.copyOf(rejected));
    }

    /** Why a plugin was left out, naming the types and whoever already declared them. */
    private static String clashMessage(List<String> clashes, Map<String, String> claimedBy) {
        String owner = claimedBy.getOrDefault(clashes.getFirst(), "another plugin");
        return "it was left out: it declares " + String.join(", ", clashes)
                + ", which " + owner + " already declares. Two plugins cannot both own a type — install one "
                + "of them, or move to a version of one that no longer declares it.";
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

    private static PaletteCatalog merge(List<StudioPlugin> set) {
        PaletteCatalog merged = PaletteCatalog.empty();
        for (StudioPlugin plugin : set) {
            PaletteCatalog offered = quietly(plugin, "build its palette", plugin::catalog);
            if (offered != null) merged = merged.mergedWith(offered);
        }
        return merged;
    }
}
