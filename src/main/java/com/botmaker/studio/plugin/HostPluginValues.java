package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.managed.JavaManagedValues;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.managed.ManagedMethod;
import com.botmaker.studio.project.managed.ManagedSets;
import com.botmaker.studio.project.params.JavaParameterEdits;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Studio's own side of {@link PluginValues} — a plugin's values, read out of the bot's Java and written back
 * to it.
 *
 * <p><b>Nothing here is new capability.</b> The reading and the rewrite are
 * {@link JavaManagedValues}, the walk under them is {@code BotSources} with the open buffer preferred over
 * the disk, and the snapshot is {@link Checkpoints}. What this class adds is the shape: a plugin edits its
 * own value through {@link ValueContext}, the same interface a slot on the canvas and a row in the
 * Parameters window are edited through, so no second way to edit a value exists.
 *
 * <p><b>An open set</b> ({@code Pictures}) is {@link ManagedSets}' — planned there by binding and compiled as
 * the whole bot, written here after one Project History snapshot. That replaced {@code HostSources}, the
 * token find-and-replace that was the last text rewrite a plugin could ask for (2026-09-28).
 *
 * <p><b>Installed per project, static, one at a time</b>, exactly as {@link HostRuns} is and for the same
 * reason: {@link HostServices} is built ad hoc from a
 * {@code ProjectConfig} at call sites with no {@link ProjectState} in scope, and Studio holds one open
 * project. Between projects a plugin asking gets {@link PluginValues#NONE}, so a window left open over a
 * project the user has since left reads nothing rather than writing into the next one.
 *
 * <p><b>Every id in the project is listed, not only the asking plugin's.</b> The services object is one per
 * project rather than one per plugin, and inventing a per-plugin view would mean deciding here which ids
 * belong to whom — an answer the plugin already has, since it declared them
 * ({@code StudioPlugin.managedValues}). A plugin asks for its own id and gets its own value; nothing is
 * hidden because nothing here is secret.
 */
public final class HostPluginValues implements PluginValues {

    /** The label the snapshot before a write is recorded under. */
    private static final String HISTORY_LABEL = "Change a plugin's value";

    /** The live project's values, or null between projects. */
    private static volatile HostPluginValues current;

    private final ProjectConfig config;
    private final ProjectState state;
    private final StudioServices services;
    private final EventBus eventBus;

    private HostPluginValues(ProjectConfig config, ProjectState state, StudioServices services,
                             EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.services = services;
        this.eventBus = eventBus;
    }

    /**
     * Makes this project's values the ones a plugin reaches, replacing whatever was installed before.
     *
     * <p>{@code eventBus} may be null — it is only how the editor is told to redraw a file rewritten underneath
     * it, and a headless caller has no editor to redraw.
     */
    public static synchronized void install(ProjectConfig config, ProjectState state,
                                            StudioServices services, EventBus eventBus) {
        if (config == null || state == null) {
            clear();
            return;
        }
        current = new HostPluginValues(config, state, services, eventBus);
    }

    /** No project: a plugin asking now gets {@link PluginValues#NONE}. */
    public static synchronized void clear() {
        current = null;
    }

    /** The live values, or {@link PluginValues#NONE} when no project is open. Never null. */
    public static PluginValues live() {
        HostPluginValues live = current;
        return live == null ? PluginValues.NONE : live;
    }

    @Override
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (ManagedMethod value : JavaManagedValues.scan(config, state)) ids.add(value.id());
        return List.copyOf(ids);
    }

    /**
     * The value behind {@code id}, or empty when there is none a plugin may edit.
     *
     * <p>A method that is read-only — hand-edited, or typed with something nothing registers — answers
     * empty, which is the contract's three sentences collapsed into the one answer a plugin can act on. The
     * value itself is untouched and the user has lost nothing; what to tell them about it is the host's, and
     * {@code ManagedMethod.note} is the sentence.
     */
    @Override
    public Optional<ValueContext> open(String id) {
        Optional<ManagedMethod> found = JavaManagedValues.find(config, state, id);
        if (found.isEmpty() || !found.get().editable()) return Optional.empty();
        ManagedMethod value = found.get();
        return Optional.of(HostValueContext.of(value.form(), value.expression(), services,
                expression -> write(value, expression),
                () -> ManagedConstants.scan(config, state)));
    }

    /**
     * Writes the holder of {@code id} when the project has none ({@link ManagedHolders}), never over a file
     * that exists. The declaring plugin is found among the bound ones by the id it declared.
     */
    @Override
    public Optional<String> create(String id) {
        if (id == null || id.isBlank()) return Optional.of("No value was named.");
        if (JavaManagedValues.find(config, state, id).isPresent()) return Optional.empty();
        for (StudioPlugin plugin : PluginHost.plugins()) {
            List<ManagedValue<?>> declared;
            try {
                declared = plugin.managedValues();
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (declared == null) continue;
            for (ManagedValue<?> value : declared) {
                if (value == null || !id.equals(value.id())) continue;
                ManagedHolders.Plan plan = ManagedHolders.plan(config, plugin.id(), value, declared,
                        PluginHost.grammar());
                if (plan instanceof ManagedHolders.Plan.Refused refused) return Optional.of(refused.reason());
                ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan;
                try {
                    ProjectWrites.create(config, write.file(), write.source(), "Create " + write.relative());
                } catch (java.io.UncheckedIOException e) {
                    return Optional.of("Could not write " + write.relative() + ": " + e.getCause().getMessage());
                }
                return Optional.empty();
            }
        }
        return Optional.of("No plugin in this project declares the value a bot marks "
                + ManagedIds.spelled(id, PluginHost.managedValues()) + ".");
    }

    /**
     * Writes every holder a bound plugin declares and the open project lacks, and answers the files written
     * (project-relative). Called on every bind (2026-09-26): a plugin's {@code @Managed} file exists the
     * moment the plugin is in the project, rather than when the user finds the button that asks for it.
     *
     * <p>The rules {@link #create} keeps, plus two. A holder is <b>left alone</b> when any value it holds is
     * already declared anywhere in the bot, or when any source file already carries its class name — a user
     * who moved {@code Sdk.java} to another package has one, and a second would not compile. And a project
     * open read-only (an installed bot) is never written. Never over a file, never into {@code main}.
     */
    public static List<String> createMissing() {
        HostPluginValues live = current;
        if (live == null || live.state.isReaderMode()) return List.of();
        return live.writeMissing();
    }

    private List<String> writeMissing() {
        java.util.Map<String, List<ManagedValue<?>>> byPlugin = new java.util.LinkedHashMap<>();
        for (StudioPlugin plugin : PluginHost.plugins()) {
            try {
                List<ManagedValue<?>> declared = plugin.managedValues();
                if (declared != null && !declared.isEmpty()) byPlugin.put(plugin.id(), declared);
            } catch (RuntimeException | LinkageError e) {
                // A plugin that cannot say what it manages gets no file; every other plugin still does.
            }
        }
        if (byPlugin.isEmpty()) return List.of();
        java.util.Set<String> fileNames = new java.util.HashSet<>();
        com.botmaker.studio.services.BotSources.scan(config, state,
                (file, source) -> fileNames.add(file.getFileName().toString()));
        List<String> written = new ArrayList<>();
        for (ManagedHolders.Plan plan : ManagedHolders.missing(config, byPlugin, java.util.Set.copyOf(ids()),
                fileNames, PluginHost.grammar())) {
            if (plan instanceof ManagedHolders.Plan.Refused refused) {
                System.err.println("Warning: " + refused.reason());
                continue;
            }
            ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan;
            try {
                ProjectWrites.create(config, write.file(), write.source(), "Create " + write.relative());
                written.add(write.relative());
            } catch (java.io.UncheckedIOException e) {
                System.err.println("Warning: could not write " + write.relative() + ": "
                        + e.getCause().getMessage());
            }
        }
        return List.copyOf(written);
    }

    /**
     * One write: a snapshot, then the rewrite of one expression.
     *
     * <p>The snapshot is taken before the file is touched and per {@code set} call, so an editor that writes
     * on every keystroke would record every one of them. That is why the built-in editors commit on OK
     * rather than as the user types (2026-09-20), and why a plugin's should too.
     */
    private void write(ManagedMethod value, JavaValue expression) {
        snapshot();
        JavaManagedValues.setValue(config, state, value, expression);
    }

    private void snapshot() {
        Checkpoints.take(config.projectPath(), VersionOrigin.SAFETY, HISTORY_LABEL);
    }

    // ── an open set: ManagedSets planned, written here ────────────────────────────────────────────────────

    @Override
    public List<String> members(String id) {
        return ManagedSets.members(index(), id, PluginHost.grammar()).stream().map(ManagedSets.Member::name).toList();
    }

    /**
     * The constant's initialiser as a value. No {@code @Managed} constants are offered to the context: a
     * value equal to one would be written as that constant, and a constant's own initialiser naming itself
     * does not compile.
     */
    @Override
    public Optional<ValueContext> open(String id, String member) {
        return ManagedSets.member(index(), id, member, PluginHost.grammar())
                .filter(found -> found.initializer() != null)
                .map(found -> HostValueContext.of(found.form(), found.initializer(), services,
                        expression -> writeInitializer(found, expression)));
    }

    @Override
    public Optional<String> add(String id, String member, Object value) {
        if (value == null) return Optional.of("No value was given for " + member + ".");
        Optional<ManagedValue<?>> set = openSet(PluginHost.managedValues(), id);
        Optional<String> stranger = set.flatMap(declared -> notAnElement(declared, member, value));
        if (stranger.isPresent()) return stranger;
        // An enum's constant is its name: the value was only checked, and nothing of it is written.
        if (set.filter(ManagedValue::isEnum).isPresent()) return apply(ManagedSets.add(index(), id, member, null, null));
        Class<?> type = value instanceof Enum<?> constant ? constant.getDeclaringClass() : value.getClass();
        Optional<JavaValue> initializer = PluginHost.grammar().spellAny(value);
        if (initializer.isEmpty()) {
            return Optional.of("No plugin in this project declares " + type.getSimpleName() + ", so "
                    + member + " cannot be written.");
        }
        return apply(ManagedSets.add(index(), id, member, type, initializer.get()));
    }

    /**
     * Why {@code value} may not join the open set {@code id}, or empty when it may: the set's declaration
     * names the class of each constant, and a value of another class would compile into a set the plugin then
     * reads as the wrong type. Compared by binary name, the class or any supertype — the plugin's class loader
     * is not Studio's. A set no plugin declares with an element type is not checked here.
     */
    static Optional<String> notAnElement(List<ManagedValue<?>> declared, String id, String member, Object value) {
        return openSet(declared, id).flatMap(set -> notAnElement(set, member, value));
    }

    /** The same, against the set already found. */
    private static Optional<String> notAnElement(ManagedValue<?> set, String member, Object value) {
        if (set.type() == null || isA(value.getClass(), set.type().getName())) return Optional.empty();
        return Optional.of(member + " cannot join " + set.holder() + ": it is " + article(value.getClass())
                + ", and every constant there is " + article(set.type()) + ".");
    }

    /** The open set {@code id} among what the plugins declare, or empty. */
    private static Optional<ManagedValue<?>> openSet(List<ManagedValue<?>> declared, String id) {
        return declared.stream().filter(set -> set != null && set.isOpenSet() && set.id().equals(id)).findFirst();
    }

    private static boolean isA(Class<?> cls, String name) {
        if (cls == null) return false;
        if (cls.getName().equals(name) || isA(cls.getSuperclass(), name)) return true;
        for (Class<?> face : cls.getInterfaces()) if (isA(face, name)) return true;
        return false;
    }

    private static String article(Class<?> cls) {
        String name = cls.getSimpleName().isEmpty() ? cls.getName() : cls.getSimpleName();
        return ("AEIOU".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    @Override
    public List<Use> uses(String id, String member) {
        BotIndex index = index();
        return ManagedSets.member(index, id, member, PluginHost.grammar())
                .map(found -> ManagedSets.uses(index, found).stream()
                        .map(use -> new Use(use.file(), use.line(), use.text())).toList())
                .orElse(List.of());
    }

    @Override
    public Optional<String> rename(String id, String member, String newName) {
        BotIndex index = index();
        Optional<ManagedSets.Member> found = ManagedSets.member(index, id, member, PluginHost.grammar());
        if (found.isEmpty()) return Optional.of(missing(id, member));
        return apply(Refactor.rename(index, found.get().file(), found.get().start(), newName));
    }

    /** The mark is written only where the bot compiles {@code @Refactor}; elsewhere the repoint goes unmarked. */
    @Override
    public Optional<String> repoint(String id, String member, String replacement, String note) {
        BotIndex index = index();
        Optional<ManagedSets.Member> from = ManagedSets.member(index, id, member, PluginHost.grammar());
        Optional<ManagedSets.Member> to = ManagedSets.member(index, id, replacement, PluginHost.grammar());
        if (from.isEmpty()) return Optional.of(missing(id, member));
        if (to.isEmpty()) return Optional.of(missing(id, replacement));
        return apply(ManagedSets.repoint(index, from.get(), to.get(),
                ReviewMarker.available(state) ? note : null));
    }

    @Override
    public Optional<String> remove(String id, String member) {
        BotIndex index = index();
        Optional<ManagedSets.Member> found = ManagedSets.member(index, id, member, PluginHost.grammar());
        if (found.isEmpty()) return Optional.of(missing(id, member));
        return apply(ManagedSets.remove(index, found.get()));
    }

    private BotIndex index() {
        return BotIndex.of(config, state);
    }

    private static String missing(String id, String member) {
        return "The project has no " + member + " in the class marked "
                + ManagedIds.spelled(id, PluginHost.managedValues()) + ".";
    }

    /**
     * A plan written as one step in Project History: the snapshot first, then every file it rewrites, buffer
     * and disk, then the open file redrawn when it was one of them. Not the canvas's ↶ — a plugin's window
     * is not on the canvas, and the holder it changes is locked there.
     */
    private Optional<String> apply(Refactor.Outcome outcome) {
        return switch (outcome) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned plan -> {
                Checkpoints.take(config.projectPath(), VersionOrigin.SAFETY, "Before: " + plan.summary());
                BotSources.forEach(config, state, (file, source) -> plan.rewrites().get(file));
                redraw(plan.rewrites().keySet());
                yield Optional.empty();
            }
        };
    }

    private void writeInitializer(ManagedSets.Member member, JavaValue expression) {
        snapshot();
        Path file = member.file().toAbsolutePath().normalize();
        BotSources.forEach(config, state, (visited, source) -> visited.equals(file)
                ? JavaParameterEdits.setValue(source, member.className(), member.name(), expression) : null);
        redraw(java.util.Set.of(file));
    }

    /**
     * Tells the editor to re-render the file it shows when a write here changed it underneath — the buffer
     * really changed, so a stale view is the only thing wrong, which is the kind of bug that reads as data loss.
     */
    private void redraw(java.util.Collection<Path> changed) {
        if (eventBus == null) return;
        ProjectFile active = state.getActiveFile();
        if (active == null || active.getPath() == null
                || !changed.contains(active.getPath().toAbsolutePath().normalize())) return;
        eventBus.publish(new CoreApplicationEvents.UIRefreshRequestedEvent(active.getContent()));
    }
}
