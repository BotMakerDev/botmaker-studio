package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.managed.JavaManagedValues;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.managed.ManagedMethod;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;

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
 * <p><b>Installed per project, static, one at a time</b>, exactly as {@link HostSources} and
 * {@link HostRuns} are and for the same reason: {@link HostServices} is built ad hoc from a
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

    private HostPluginValues(ProjectConfig config, ProjectState state, StudioServices services) {
        this.config = config;
        this.state = state;
        this.services = services;
    }

    /** Makes this project's values the ones a plugin reaches, replacing whatever was installed before. */
    public static synchronized void install(ProjectConfig config, ProjectState state,
                                            StudioServices services) {
        if (config == null || state == null) {
            clear();
            return;
        }
        current = new HostPluginValues(config, state, services);
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
            List<ManagedValue> declared;
            try {
                declared = plugin.managedValues();
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (declared == null) continue;
            for (ManagedValue value : declared) {
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
        return Optional.of("No plugin in this project declares a value called \"" + id + "\".");
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
}
