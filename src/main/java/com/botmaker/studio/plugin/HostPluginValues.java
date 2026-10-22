package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.plugin.api.value.Ref;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ShortNames;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.managed.BotMethods;
import com.botmaker.studio.project.managed.HolderFiles;
import com.botmaker.studio.project.managed.JavaManagedValues;
import com.botmaker.studio.project.managed.NestedClasses;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.managed.ManagedMethod;
import com.botmaker.studio.project.managed.ManagedMigrations;
import com.botmaker.studio.project.managed.ManagedSets;
import com.botmaker.studio.project.params.JavaParameter;
import com.botmaker.studio.project.params.JavaParameterEdits;
import com.botmaker.studio.project.params.JavaParameters;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.BotSources;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
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
    public Optional<ValueContext> open(String id) {
        Optional<ManagedMethod> found = JavaManagedValues.find(config, state, id);
        if (found.isEmpty() || !found.get().editable()) return Optional.empty();
        ManagedMethod value = found.get();
        return Optional.of(HostValueContext.of(value.form(), value.expression(), services,
                expression -> write(value, imported(expression)), this::constants));
    }

    /**
     * Writes the holder of {@code id} when the project has none ({@link ManagedHolders}), never over a file
     * that exists; when the bot has the holder but not the value, adds the value to it (2026-10-12: a
     * {@code Sdk.java} an older SDK wrote had no {@code run()}, and the flow editor had nowhere to save). The
     * declaring plugin is found among the bound ones by the id it declared.
     */
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
            declared = declared.stream().filter(java.util.Objects::nonNull).toList();
            for (ManagedValue<?> value : declared) {
                if (!id.equals(value.id())) continue;
                // A method value its holder lacks, or a parameter (2026-10-12: a conditional one, now that its
                // condition holds), is written into the holder the bot has.
                java.util.Map<Path, List<ManagedValue<?>>> kept = value instanceof ManagedOpenSet<?>
                        ? java.util.Map.of()
                        : ManagedHolders.valuesToKeep(index(), java.util.Map.of(plugin.id(), List.of(value)));
                if (!kept.isEmpty()) {
                    if (state.isReaderMode()) return Optional.of("This project is open read-only.");
                    return keep(kept).isEmpty() ? Optional.of("BotMaker cannot write a starting value for "
                            + ManagedIds.spelled(id, PluginHost.managedValues()) + ".") : Optional.empty();
                }
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
     * (project-relative). Called on every bind (2026-09-26): a plugin's managed file exists the
     * moment the plugin is in the project, rather than when the user finds the button that asks for it.
     *
     * <p>The rules {@link #create} keeps, plus two. A holder is <b>left alone</b> when any value it holds is
     * already declared anywhere in the bot, or when any source file already carries its class name — a user
     * who moved {@code Sdk.java} to another package has one, and a second would not compile. And a project
     * open read-only (an installed bot) is never written. Never over a file, never into {@code main}.
     *
     * <p>One edit inside a file that exists (2026-10-11): a plugin's parameter ({@code ManagedValue.param}) is
     * kept as declared in the holder the bot has — written when missing, marked when a field of its name predates
     * the declaration, its {@code @Param} members refreshed — after a safety snapshot.
     */
    public static List<String> createMissing() {
        HostPluginValues live = current;
        if (live == null || live.state.isReaderMode()) return List.of();
        return live.writeMissing();
    }

    private List<String> writeMissing() {
        java.util.Map<String, List<ManagedValue<?>>> byPlugin = new java.util.LinkedHashMap<>();
        // A holder is found by every value its plugin declares: one the user moved may carry only the marks of
        // conditional parameters that no longer apply.
        java.util.Map<String, List<ManagedValue<?>>> allByPlugin = new java.util.LinkedHashMap<>();
        // A conditional parameter that does not apply to this bot is not written (2026-10-12):
        // ManagedParam.ConditionStep.when, read against the bot as it stands, once for every plugin.
        HostParamScope scope = HostParamScope.over(this);
        for (StudioPlugin plugin : PluginHost.plugins()) {
            try {
                List<ManagedValue<?>> offered = plugin.managedValues();
                List<ManagedValue<?>> all = offered == null ? List.of()
                        : offered.stream().filter(java.util.Objects::nonNull).toList();
                List<ManagedValue<?>> declared = all.stream()
                        .filter(value -> !(value instanceof ManagedParam<?> param) || param.appliesIn(scope))
                        .toList();
                if (!declared.isEmpty()) byPlugin.put(plugin.id(), declared);
                if (!all.isEmpty()) allByPlugin.put(plugin.id(), all);
            } catch (RuntimeException | LinkageError e) {
                // A plugin that cannot say what it manages gets no file; every other plugin still does.
            }
        }
        if (byPlugin.isEmpty()) return List.of();
        java.util.Set<HolderFiles.Key> present = index().read(units -> HolderFiles.find(units, allByPlugin)).keySet();
        List<String> written = new ArrayList<>();
        for (ManagedHolders.Plan plan : ManagedHolders.missing(config, byPlugin, java.util.Set.copyOf(ids()),
                present, PluginHost.grammar())) {
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
        // A plugin's parameter is kept as the plugin declares it (2026-10-11): one the holder lacks goes back in,
        // since the bot's main reads it by name, and a changed category, note or audience is written over the
        // old one. A method value the holder lacks is not: the user may have deleted it, and create() writes it
        // when a plugin window asks for it (2026-10-12).
        java.util.Map<String, List<ManagedValue<?>>> params = new java.util.LinkedHashMap<>();
        byPlugin.forEach((plugin, declared) ->
                params.put(plugin, declared.stream().filter(ManagedParam.class::isInstance).toList()));
        keep(ManagedHolders.valuesToKeep(index(), params))
                .forEach(file -> written.add(config.projectPath().relativize(file).toString()));
        return List.copyOf(written);
    }

    /**
     * Writes each file's values as their plugins declare them ({@code ManagedHolders.keptValues}), on the live
     * buffer, after one snapshot an undo can return to, and answers the files changed.
     */
    private java.util.Set<Path> keep(java.util.Map<Path, List<ManagedValue<?>>> kept) {
        java.util.Set<Path> changed = new java.util.LinkedHashSet<>();
        if (kept.isEmpty()) return changed;
        recorded(() -> BotSources.forEach(config, state, (file, source) -> {
            List<ManagedValue<?>> values = kept.get(file);
            if (values == null) return null;
            String after = ManagedHolders.keptValues(source, values, PluginHost.grammar());
            if (after.equals(source)) return null;
            if (changed.isEmpty()) snapshot();
            changed.add(file);
            return after;
        }));
        if (!changed.isEmpty()) redraw(changed);
        return changed;
    }

    /**
     * One write: a snapshot, then the rewrite of one expression.
     *
     * <p>The snapshot is taken before the file is touched and per {@code set} call, so an editor that writes
     * on every keystroke would record every one of them. That is why the built-in editors commit on OK
     * rather than as the user types (2026-09-20), and why a plugin's should too.
     */
    private void write(ManagedMethod value, JavaValue expression) {
        recorded(() -> {
            snapshot();
            JavaManagedValues.setValue(config, state, value, expression);
        });
    }

    private void snapshot() {
        checkpoint(HISTORY_LABEL);
    }

    /**
     * The Project History snapshot before a write: one per undo step, so a batch takes one, not one per write, and
     * one per plugin tool call ({@link ToolCalls}).
     */
    private void checkpoint(String label) {
        WriteSteps.Step open = WriteSteps.open();
        if (open != null && !open.firstCheckpoint()) return;
        if (!ToolCalls.firstCheckpoint()) return;
        Checkpoints.take(config.projectPath(), VersionOrigin.SAFETY, label);
    }

    /**
     * Runs {@code writes} as one step of the editor's undo ({@link WriteSteps}), or as part of the plugin's batch
     * when one is open — so ↶ takes a plugin's write back like an edit on the canvas.
     */
    private void recorded(Runnable writes) {
        WriteSteps.step(HISTORY_LABEL, () -> {
            writes.run();
            return null;
        }, step -> {
            if (eventBus != null) eventBus.publish(step);
        });
    }

    // ── the contract's operations, by declaration: each the spelled one below ────────────────────────────

    @Override
    public Optional<ValueContext> open(ManagedValue<?> value) {
        if (value == null) return Optional.empty();
        return value instanceof ManagedParam<?> ? openParam(value) : open(value.id());
    }

    /** A plugin's parameter field: the file it is in and its class and name. */
    private record MarkedParam(Path file, ManagedHolders.FieldName field) {}

    /** The field carrying the parameter {@code value}'s mark, editable or not; empty when the bot has none. */
    private Optional<MarkedParam> markedParam(ManagedValue<?> value) {
        List<ManagedValue<?>> known = PluginHost.managedValues();
        MarkedParam[] found = {null};
        BotSources.forEach(config, state, (visited, source) -> {
            if (found[0] == null) {
                ManagedHolders.markedParam(source, value, known)
                        .ifPresent(field -> found[0] = new MarkedParam(visited, field));
            }
            return null;
        });
        return Optional.ofNullable(found[0]);
    }

    /**
     * A plugin's own parameter, the {@code @Param} field carrying its mark, as a value — read and written as a row
     * of the Parameters window is (2026-10-12: the SDK's {@code launchTargets} and {@code vm}, which its game dialog
     * and ⚙ Bot Settings write). Empty when the bot has no such field, or its initialiser is not one the grammar
     * rewrites.
     */
    private Optional<ValueContext> openParam(ManagedValue<?> value) {
        Optional<MarkedParam> marked = markedParam(value);
        if (marked.isEmpty()) return Optional.empty();
        Path[] file = {marked.get().file()};
        ManagedHolders.FieldName[] named = {marked.get().field()};
        return JavaParameters.find(config, state, named[0].className(), named[0].name(), PluginHost.grammar())
                .filter(JavaParameter::editable)
                .map(parameter -> HostValueContext.of(parameter.form(), parameter.initializer(), services,
                        expression -> {
                            recorded(() -> {
                                snapshot();
                                JavaParameters.setValue(config, state, parameter, imported(expression));
                            });
                            redraw(java.util.Set.of(file[0]));
                        }, this::constants));
    }

    /** The bot's managed constants as they stand now, which a value of one of them is read and written as. */
    private List<ManagedConstants.Constant> constants() {
        return ManagedConstants.scan(config, state);
    }

    @Override
    public Optional<String> create(ManagedValue<?> value) {
        // A parameter the bot already declares is there to open, or written by hand and left alone; only a missing
        // one is written. create(String) finds methods only.
        if (value instanceof ManagedParam<?>) {
            if (openParam(value).isPresent()) return Optional.empty();
            if (markedParam(value).isPresent()) {
                return Optional.of(ManagedIds.spelled(value.id(), PluginHost.managedValues())
                        + " is written by hand, so BotMaker leaves it alone.");
            }
        }
        return create(value == null ? null : value.id());
    }

    @Override
    public List<Member> members(ManagedValue<?> set) {
        if (set == null) return List.of();
        return members(set.id()).stream().flatMap(spelled -> Member.read(spelled).stream()).toList();
    }

    @Override
    public Optional<ValueContext> open(ManagedValue<?> set, Member member) {
        if (set == null || member == null) return Optional.empty();
        if (set instanceof ManagedClasses<?> classes && !member.grouped()) return openSetup(classes, member.name());
        return open(set.id(), member.toString());
    }

    /** What the setup of the class whose constant is {@code constant} returns, as a value of the setup's type. */
    private Optional<ValueContext> openSetup(ManagedClasses<?> set, String constant) {
        String setup = set.setup().getName();
        return NestedClasses.named(index(), set.id(), constant, PluginHost.grammar(), setup)
                .filter(nested -> nested.setup() != null)
                .map(nested -> HostValueContext.of(set.setup().getGenericReturnType(), nested.setup(), services,
                        expression -> {
                            Path file = nested.file().toAbsolutePath().normalize();
                            recorded(() -> {
                                snapshot();
                                BotSources.forEach(config, state, (visited, source) -> visited.equals(file)
                                        ? NestedClasses.setupSet(source, nested.holder(), nested.constant(), setup,
                                                imported(expression)) : null);
                            });
                            redraw(java.util.Set.of(file));
                        }, this::constants));
    }

    @Override
    public Optional<String> addClass(ManagedValue<?> declared, String member, Object setup) {
        if (!(declared instanceof ManagedClasses<?> set)) {
            return Optional.of("No plugin in this project declares that set as a set of classes.");
        }
        if (setup == null) return Optional.of("No setup was given for " + member + ".");
        Class<?> type = set.setup().getReturnType();
        if (!type.isInstance(setup)) {
            return Optional.of(member + "'s setup is " + article(setup.getClass()) + ", not " + article(type) + ".");
        }
        ManagedConstants.Lookup names = new ManagedConstants.Lookup(constants(), PluginHost.grammar());
        Optional<JavaValue> written = PluginHost.grammar().write(setup.getClass(), setup, names);
        if (written.isEmpty()) {
            return Optional.of("No plugin in this project declares " + type.getSimpleName() + ", so " + member
                    + " cannot be written.");
        }
        return apply(NestedClasses.add(index(), set.id(), member, set.type(), set.setup(), imported(written.get()),
                PluginHost.grammar()));
    }

    @Override
    public <E> Optional<String> add(ManagedValue<E> set, Member member, E value) {
        if (set == null || member == null) return Optional.of("No constant was named.");
        return add(set.id(), member.toString(), value);
    }

    @Override
    public List<Use> uses(ManagedValue<?> set, Member member) {
        return set == null || member == null ? List.of() : uses(set.id(), member.toString());
    }

    @Override
    public Optional<String> rename(ManagedValue<?> set, Member member, String newName) {
        if (set == null || member == null) return Optional.of("No constant was named.");
        return rename(set.id(), member.toString(), newName);
    }

    @Override
    public Optional<String> repoint(ManagedValue<?> set, Member member, Member replacement, String note) {
        if (set == null || member == null || replacement == null) return Optional.of("No constant was named.");
        return repoint(set.id(), member.toString(), replacement.toString(), note);
    }

    @Override
    public Optional<String> remove(ManagedValue<?> set, Member member) {
        if (set == null || member == null) return Optional.of("No constant was named.");
        return remove(set.id(), member.toString());
    }

    @Override
    public Optional<String> moveBefore(ManagedValue<?> set, Member member, Member before) {
        if (set == null || member == null || before == null) return Optional.of("No constant was named.");
        return moveBefore(set.id(), member.toString(), before.toString());
    }

    @Override
    public List<String> groups(ManagedValue<?> set) {
        if (set == null) return List.of();
        if (set instanceof ManagedClasses<?>) {
            return NestedClasses.all(index(), set.id(), PluginHost.grammar(), null).stream()
                    .map(NestedClasses.Nested::constant).toList();
        }
        return groups(set.id());
    }

    @Override
    public Optional<String> addGroup(ManagedValue<?> set, String group, List<String> constants) {
        return set == null ? Optional.of("No set was named.") : addGroup(set.id(), group, constants);
    }

    @Override
    public Optional<String> renameGroup(ManagedValue<?> set, String group, String newName) {
        if (set == null) return Optional.of("No set was named.");
        if (set instanceof ManagedClasses<?>) {
            BotIndex index = index();
            return NestedClasses.named(index, set.id(), group, PluginHost.grammar(), null)
                    .map(nested -> apply(NestedClasses.rename(index, nested, newName)))
                    .orElseGet(() -> Optional.of(missing(set.id(), group)));
        }
        return renameGroup(set.id(), group, newName);
    }

    @Override
    public Optional<String> removeGroup(ManagedValue<?> set, String group) {
        if (set == null) return Optional.of("No set was named.");
        if (set instanceof ManagedClasses<?>) {
            BotIndex index = index();
            return NestedClasses.named(index, set.id(), group, PluginHost.grammar(), null)
                    .map(nested -> apply(NestedClasses.remove(index, nested)))
                    .orElseGet(() -> Optional.of(missing(set.id(), group)));
        }
        return removeGroup(set.id(), group);
    }

    @Override
    public List<String> methods(ManagedValue<?> set, String group) {
        return set == null || group == null ? List.of() : methods(set.id(), group);
    }

    @Override
    public List<String> methods(MethodShape shape) {
        return switch (shape) {
            case VOID -> methods((String) null, null);
            case BOOLEAN -> BotMethods.returning(index(), BotMethods.BOOLEAN);
            case null -> List.of();
        };
    }

    @Override
    public MethodWrite createMethod(ManagedValue<?> set, String group, String className, String method) {
        if (set == null || group == null) return new MethodWrite(null, "No enum was named.");
        return createMethod(set.id(), group, className, method);
    }

    @Override
    public List<String> classes() {
        return BotMethods.classes(index(), config.mainPackageDir().resolve("plugins"));
    }

    @Override
    public MethodWrite createMethod(MethodShape shape, String className, String method) {
        return switch (shape) {
            case VOID -> createMethod((String) null, null, className, method);
            case BOOLEAN -> written(index(), className, method, BotMethods.BOOLEAN, null, null);
            case null -> new MethodWrite(null, "No kind of method was named.");
        };
    }

    // ── a call written into the bot's method ──────────────────────────────────────────────────────────────

    @Override
    public Optional<String> appendCall(String method, Ref call, Argument... arguments) {
        if (call == null) return Optional.of("No call was named.");
        Executable resolved;
        try {
            resolved = Ref.resolve(call);
        } catch (RuntimeException notAReference) {
            return Optional.of("The call is not a method reference BotMaker can read.");
        }
        if (!(resolved instanceof Method target) || !Modifier.isStatic(target.getModifiers())
                || !Modifier.isPublic(target.getModifiers())) {
            return Optional.of(resolved.getName() + " is no public static method, so nothing was written.");
        }
        Argument[] given = arguments == null ? new Argument[0] : arguments;
        Type[] parameters = target.getGenericParameterTypes();
        if (given.length != parameters.length) {
            return Optional.of(target.getName() + " takes " + parameters.length + " argument(s), not "
                    + given.length + ".");
        }
        ManagedConstants.Lookup names = new ManagedConstants.Lookup(constants(), PluginHost.grammar());
        List<JavaValue> written = new ArrayList<>();
        for (int i = 0; i < given.length; i++) {
            Argument argument = given[i];
            Optional<JavaValue> value;
            if (argument.member() != null) {
                value = names.member(argument.set().id(), argument.member().toString())
                        .map(ManagedConstants.Lookup::reference);
                if (value.isEmpty()) return Optional.of("The bot declares no " + argument.member() + " to name.");
            } else {
                value = ConstantValues.write(PluginHost.grammar(), this::constants, parameters[i], argument.value());
                if (value.isEmpty()) return Optional.of(ConstantValues.refusal(PluginHost.grammar(), parameters[i],
                        argument.value()));
            }
            written.add(imported(value.get()));
        }
        return apply(BotMethods.appendCall(index(), method, target.getDeclaringClass().getCanonicalName(),
                target.getName(), written));
    }

    // ── an open set: ManagedSets planned, written here ────────────────────────────────────────────────────

    public List<String> members(String id) {
        return ManagedSets.members(index(), id, PluginHost.grammar()).stream()
                .map(ManagedSets.Member::memberName).toList();
    }

    // ── a set of nested enums ─────────────────────────────────────────────────────────────────────────────

    public List<String> groups(String id) {
        return ManagedSets.groups(index(), id).stream().map(ManagedSets.Group::name).toList();
    }

    public Optional<String> addGroup(String id, String group, List<String> constants) {
        Optional<ManagedOpenSet<?>> set = openSet(PluginHost.managedValues(), id)
                .filter(ManagedOpenSet.EnumGroups.class::isInstance);
        if (set.isEmpty()) return Optional.of(notGrouped(id));
        return apply(ManagedSets.addGroup(index(), id, group, constants, set.get().type()));
    }

    public Optional<String> renameGroup(String id, String group, String newName) {
        BotIndex index = index();
        Optional<ManagedSets.Group> found = ManagedSets.group(index, id, group);
        if (found.isEmpty()) return Optional.of(missing(id, group));
        return apply(Refactor.rename(index, found.get().file(), found.get().start(), newName));
    }

    public Optional<String> removeGroup(String id, String group) {
        BotIndex index = index();
        Optional<ManagedSets.Group> found = ManagedSets.group(index, id, group);
        if (found.isEmpty()) return Optional.of(missing(id, group));
        return apply(ManagedSets.removeGroup(index, found.get()));
    }

    private static String notGrouped(String id) {
        return "No plugin in this project declares " + ManagedIds.spelled(id, PluginHost.managedValues())
                + " as a set of enums.";
    }

    // ── the bot's methods ─────────────────────────────────────────────────────────────────────────────────

    public List<String> methods(String id, String group) {
        BotIndex index = index();
        if (id == null && group == null) return BotMethods.returning(index, null);
        return ManagedSets.group(index, id, group)
                .map(found -> BotMethods.returning(index, canonical(index, found)))
                .orElse(List.of());
    }

    public MethodWrite createMethod(String id, String group, String className, String method) {
        BotIndex index = index();
        String returns = null;
        String imported = null;
        String first = null;
        if (id != null || group != null) {
            Optional<ManagedSets.Group> found = ManagedSets.group(index, id, group);
            if (found.isEmpty()) return new MethodWrite(null, missing(id, group));
            if (found.get().members().isEmpty()) {
                return new MethodWrite(null, found.get().qualified() + " has no constant to return.");
            }
            returns = found.get().qualified();
            String canonical = canonical(index, found.get());
            imported = canonical.substring(0, canonical.length() - group.length() - 1);
            first = found.get().members().getFirst().name();
        }
        return written(index, className, method, returns, imported, first);
    }

    /** {@link BotMethods#create}'s method written into the bot, creating its class when there is none. */
    private MethodWrite written(BotIndex index, String className, String method, String returns, String imported,
                                String first) {
        Path file = className == null ? null : config.mainPackageDir().resolve(className + ".java");
        Refactor.Outcome planned = BotMethods.create(index, config.mainPackage(), file, className, method, returns,
                imported, first);
        if (planned instanceof Refactor.Planned plan && file != null && plan.rewrites().containsKey(file)
                && !index.sources().containsKey(file)) {
            // A new class: nothing in the bot has that file yet, so it is created rather than rewritten.
            if (java.nio.file.Files.exists(file)) {
                // A file the bot's sources do not declare the class in: never written over.
                return new MethodWrite(null, className + ".java already exists but declares no class "
                        + className + ", so nothing was written.");
            }
            checkpoint("Before: " + plan.summary());
            try {
                ProjectWrites.create(config, file, plan.rewrites().get(file), plan.summary());
            } catch (java.io.UncheckedIOException e) {
                return new MethodWrite(null, "Could not write " + className + ".java: " + e.getCause().getMessage());
            }
            redraw(java.util.Set.of(file.toAbsolutePath().normalize()));
            return new MethodWrite(className + "::" + method, null);
        }
        Optional<String> refused = apply(planned);
        return refused.isPresent() ? new MethodWrite(null, refused.get())
                : new MethodWrite(className + "::" + method, null);
    }

    /** The canonical name of a nested enum — {@code com.bot.plugins.sdk.Activities.Collect}. */
    private static String canonical(BotIndex index, ManagedSets.Group group) {
        String pkg = index.read(units -> {
            var unit = units.get(group.file());
            return unit == null || unit.getPackage() == null ? ""
                    : unit.getPackage().getName().getFullyQualifiedName();
        });
        return (pkg.isEmpty() ? "" : pkg + ".") + group.qualified();
    }

    /**
     * The constant's initialiser as a value. No managed constants are offered to the context: a
     * value equal to one would be written as that constant, and a constant's own initialiser naming itself
     * does not compile.
     */
    public Optional<ValueContext> open(String id, String member) {
        return ManagedSets.member(index(), id, member, PluginHost.grammar())
                .filter(found -> found.initializer() != null)
                // The bot's constants too (2026-10-12): a flow names its activities as Activities.Collect.class,
                // which nothing but them spells, so without them every flow with an activity was refused.
                .map(found -> HostValueContext.of(found.form(), found.initializer(), services,
                        expression -> writeInitializer(found, imported(expression)), this::constants));
    }

    public Optional<String> add(String id, String member, Object value) {
        if (value == null) return Optional.of("No value was given for " + member + ".");
        Optional<ManagedOpenSet<?>> set = openSet(PluginHost.managedValues(), id);
        Optional<String> stranger = set.flatMap(declared -> notAnElement(declared, member, value));
        if (stranger.isPresent()) return stranger;
        // An enum's constant is its name: the value was only checked, and nothing of it is written.
        if (set.filter(declared -> declared instanceof ManagedOpenSet.OneEnum<?>
                || declared instanceof ManagedOpenSet.EnumGroups<?>).isPresent()) {
            return apply(ManagedSets.add(index(), id, member, null, null));
        }
        Class<?> type = value instanceof Enum<?> constant ? constant.getDeclaringClass() : value.getClass();
        // Each part another constant holds is written as that constant: a screen's Way.of(HOME, …) names HOME, which
        // nothing but the bot's own names can write (2026-10-14).
        ManagedConstants.Lookup names = new ManagedConstants.Lookup(constants(), PluginHost.grammar());
        Optional<JavaValue> initializer = PluginHost.grammar().write(type, value, names);
        if (initializer.isEmpty()) {
            return Optional.of("No plugin in this project declares " + type.getSimpleName() + ", so "
                    + member + " cannot be written.");
        }
        // A link the writer could not write is left out of the call; a new record must read back whole, or it is
        // not added half-written (2026-10-15, a screen's way in lost).
        if (value instanceof Record && !PluginHost.grammar().read(type, initializer.get().written(), names)
                .map(value::equals).orElse(false)) {
            return Optional.of(member + " cannot be written whole: a part of it is of a type nothing writes, or "
                    + "names a constant the bot does not have. Nothing was added.");
        }
        return apply(ManagedSets.add(index(), id, member, type, imported(initializer.get())));
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
    private static Optional<String> notAnElement(ManagedOpenSet<?> set, String member, Object value) {
        // A set of classes takes fields here, each of its field type; a whole class is addClass's.
        boolean classes = set instanceof ManagedClasses<?>;
        Class<?> element = set instanceof ManagedClasses<?> nested ? nested.fieldType() : set.type();
        if (classes && !member.contains(".")) {
            return Optional.of(member + " names a whole class of " + set.holder() + ", which is added with its setup.");
        }
        if (element == null || isA(value.getClass(), element.getName())) return Optional.empty();
        return Optional.of(member + " cannot join " + set.holder() + ": it is " + article(value.getClass())
                + ", and every " + (classes ? "field" : "constant") + " there is " + article(element) + ".");
    }

    /** The open set {@code id} among what the plugins declare, or empty. */
    private static Optional<ManagedOpenSet<?>> openSet(List<ManagedValue<?>> declared, String id) {
        return declared.stream()
                .<ManagedOpenSet<?>>flatMap(value -> value instanceof ManagedOpenSet<?> set && set.id().equals(id)
                        ? java.util.stream.Stream.of(set) : java.util.stream.Stream.empty())
                .findFirst();
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

    public List<Use> uses(String id, String member) {
        BotIndex index = index();
        return ManagedSets.member(index, id, member, PluginHost.grammar())
                .map(found -> ManagedSets.uses(index, found).stream()
                        .map(use -> new Use(use.file(), use.line(), use.text())).toList())
                .orElse(List.of());
    }

    public Optional<String> rename(String id, String member, String newName) {
        BotIndex index = index();
        Optional<ManagedSets.Member> found = ManagedSets.member(index, id, member, PluginHost.grammar());
        if (found.isEmpty()) return Optional.of(missing(id, member));
        // A nested enum's constant is named "Collect.WON", and its new name may be given the same way; it stays
        // in its enum, so only the part after the group is the new identifier.
        String group = found.get().group();
        String name = group != null && newName != null && newName.startsWith(group + ".")
                ? newName.substring(group.length() + 1) : newName;
        return apply(Refactor.rename(index, found.get().file(), found.get().start(), name));
    }

    /** The mark is written only where the bot compiles {@code @Refactor}; elsewhere the repoint goes unmarked. */
    public Optional<String> repoint(String id, String member, String replacement, String note) {
        BotIndex index = index();
        Optional<ManagedSets.Member> from = ManagedSets.member(index, id, member, PluginHost.grammar());
        Optional<ManagedSets.Member> to = ManagedSets.member(index, id, replacement, PluginHost.grammar());
        if (from.isEmpty()) return Optional.of(missing(id, member));
        if (to.isEmpty()) return Optional.of(missing(id, replacement));
        return apply(ManagedSets.repoint(index, from.get(), to.get(),
                ReviewMarker.available(state) ? note : null));
    }

    public Optional<String> remove(String id, String member) {
        BotIndex index = index();
        Optional<ManagedSets.Member> found = ManagedSets.member(index, id, member, PluginHost.grammar());
        if (found.isEmpty()) return Optional.of(missing(id, member));
        return apply(ManagedSets.remove(index, found.get()));
    }

    public Optional<String> moveBefore(String id, String member, String before) {
        BotIndex index = index();
        List<ManagedSets.Member> members = ManagedSets.members(index, id, PluginHost.grammar());
        Optional<ManagedSets.Member> moving = members.stream().filter(m -> m.memberName().equals(member)).findFirst();
        Optional<ManagedSets.Member> anchor = members.stream().filter(m -> m.memberName().equals(before)).findFirst();
        if (moving.isEmpty()) return Optional.of(missing(id, member));
        if (anchor.isEmpty()) return Optional.of(missing(id, before));
        Refactor.Outcome planned = ManagedSets.moveBefore(index, moving.get(), anchor.get());
        // Already above: nothing to write, and no snapshot either.
        if (planned instanceof Refactor.Planned plan && plan.rewrites().isEmpty()) return Optional.empty();
        return apply(planned);
    }

    private BotIndex index() {
        return BotIndex.of(config, state);
    }

    /**
     * {@code value} as it may be written into any of the bot's files: a part a plugin hands over as the Java it
     * is written as, {@code Research::body}, imports its class, and a full name is written short (2026-10-13).
     */
    private JavaValue imported(JavaValue value) {
        return ShortNames.imported(value, BotMethods.topLevelTypes(index()));
    }

    private static String missing(String id, String member) {
        return "The project has no " + member + " in the class marked "
                + ManagedIds.spelled(id, PluginHost.managedValues()) + ".";
    }

    /**
     * A plan written as one step in Project History: the snapshot first, then every file it rewrites, buffer
     * and disk, then the open file redrawn when it was one of them. It is one step of the editor's ↶ as well
     * (2026-10-15), or part of the plugin's batch.
     */
    private Optional<String> apply(Refactor.Outcome outcome) {
        return switch (outcome) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned plan -> {
                recorded(() -> {
                    checkpoint("Before: " + plan.summary());
                    BotSources.forEach(config, state, (file, source) -> plan.rewrites().get(file));
                });
                redraw(plan.rewrites().keySet());
                yield Optional.empty();
            }
        };
    }

    private void writeInitializer(ManagedSets.Member member, JavaValue expression) {
        Path file = member.file().toAbsolutePath().normalize();
        // A field of a nested class (ManagedClasses) is in the class whose instance its group holds.
        boolean nested = member.group() != null;
        recorded(() -> {
            snapshot();
            // An edit makes a migrated value the author's: a rerun of the migration leaves it alone from now on.
            BotSources.forEach(config, state, (visited, source) -> !visited.equals(file) ? null
                    : nested ? NestedClasses.fieldSet(source, member.className(), member.group(), member.name(),
                            expression)
                    : ManagedMigrations.unmarked(JavaParameterEdits.setValue(source, member.className(),
                            member.name(), expression), member.className(), member.name()));
        });
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
