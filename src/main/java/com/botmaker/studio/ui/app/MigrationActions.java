package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.managed.ManagedMigrations;
import com.botmaker.studio.project.managed.ManagedSets;
import com.botmaker.studio.project.managed.MigrationCleanup;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The steps after a migration, as Studio runs them (2026-10-12): uses switched to the copies, originals nothing
 * uses removed, migration methods nothing needs removed — {@link MigrationCleanup}'s plans, worked out on a worker
 * (each compiles the bot), a removal confirmed by the author, and written on the FX thread as one version after
 * a safety one. Shared by {@link MigrationWindow}, the Review tab and a managed value's right-click.
 */
final class MigrationActions {

    /** Where a step says what it is doing and what it did: a status line takes both, a dialog only the end. */
    @FunctionalInterface
    interface Told {

        void finished(String text);

        default void working(String text) {
            finished(text);
        }

        /** Only what a step did, never that it is working: a dialog shown once. */
        static Told atTheEnd(Consumer<String> shown) {
            return new Told() {
                @Override
                public void finished(String text) {
                    shown.accept(text);
                }

                @Override
                public void working(String text) {}
            };
        }
    }

    private final ProjectConfig config;
    private final ProjectState state;
    private final Supplier<Window> owner;
    private final Consumer<Path> redraw;
    private final Runnable after;

    /**
     * @param redraw told each file written
     * @param after  run once something was written: the Review list re-read
     */
    MigrationActions(ProjectConfig config, ProjectState state, Supplier<Window> owner, Consumer<Path> redraw,
                     Runnable after) {
        this.config = config;
        this.state = state;
        this.owner = owner;
        this.redraw = redraw;
        this.after = after;
    }

    /**
     * Switches the uses of {@code from}'s values named in {@code names} (all when empty) to their copies in
     * {@code to} — {@code null}: wherever the first named value's copy is. {@code told} gets what happened.
     */
    void switchUses(Class<?> from, Class<?> to, Set<String> names, Told told) {
        String note = leftNote(state, from);
        work(told, "Switching uses…", (index, grammar, declared) -> {
            Class<?> into = to != null ? to : names.isEmpty() ? null
                    : MigrationCleanup.copyTarget(index, grammar, declared, from, names.iterator().next()).orElse(null);
            if (into == null) {
                return Planned.refused("No copy of " + String.join(", ", names) + " was found: migrate it first.");
            }
            MigrationCleanup.Switched switched = MigrationCleanup.switchUses(index, grammar, declared, from, into,
                    names, note);
            String left = switched.left().isEmpty() ? "" : " Left as they are: " + listed(switched.left()) + ".";
            return new Planned(switched.outcome(), Set.of(), null, left);
        });
    }

    /** The {@code @Refactor} note a use left on an old value gets, or null where marks are not available. */
    static String leftNote(ProjectState state, Class<?> from) {
        return ReviewMarker.available(state) ? "Still uses a " + from.getSimpleName() + " value: its copy"
                + " does not fit here. Change what holds it, then switch its uses again." : null;
    }

    /** Removes {@code from}'s values whose copy is in {@code to} and that nothing uses, once the author agrees. */
    void removeOriginals(Class<?> from, Class<?> to, Told told) {
        work(told, "Looking for originals nothing uses…", (index, grammar, declared) -> {
            List<ManagedSets.Member> removable = MigrationCleanup.removable(index, grammar, declared, from, to);
            String ask = "Remove " + removable.size() + (removable.size() == 1 ? " original" : " originals") + " of "
                    + from.getSimpleName() + "? Each has its copy in " + to.getSimpleName() + " and nothing uses it:\n\n"
                    + listed(removable.stream().map(ManagedSets.Member::qualified).toList())
                    + "\n\nA version of the project is saved first.";
            return new Planned(MigrationCleanup.removeOriginals(index, removable), Set.of(), ask, "");
        });
    }

    /** Removes the migration methods whose old type has no value left, and a class they leave empty. */
    void removeMethods(Told told) {
        work(told, "Looking for migration methods nothing needs…", (index, grammar, declared) -> {
            List<ManagedMigrations.Method> unneeded = MigrationCleanup.unneeded(index, grammar);
            MigrationCleanup.Removal removal = MigrationCleanup.removeMethods(index, unneeded);
            String ask = "Remove " + listed(unneeded.stream().map(ManagedMigrations.Method::qualified).toList())
                    + "? No value of the type they migrate is left."
                    + (removal.deleted().isEmpty() ? "" : "\n\nThis deletes " + listed(removal.deleted().stream()
                            .map(path -> path.getFileName().toString()).toList()) + ", which holds nothing else.")
                    + "\n\nA version of the project is saved first.";
            return new Planned(removal.outcome(), removal.deleted(), ask, "");
        });
    }

    // --- running one ---------------------------------------------------------------------------------------

    /** What a worker worked out: the change, the files it deletes, the question before it, and what to add after. */
    private record Planned(Refactor.Outcome outcome, Set<Path> deleted, String ask, String note) {

        static Planned refused(String reason) {
            return new Planned(new Refactor.Refused(reason), Set.of(), null, "");
        }
    }

    private interface Plan {
        Planned plan(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared);
    }

    /** The buffers read here, {@code plan} on a worker, the answer asked and written back here. */
    private void work(Told told, String working, Plan plan) {
        if (state.isReaderMode()) {
            told.finished("This project is open read-only.");
            return;
        }
        Supplier<BotIndex> index = BotIndex.prepare(config, state);
        ValueGrammar grammar = PluginHost.grammar();
        List<ManagedValue<?>> declared = PluginHost.managedValues();
        told.working(working);
        Thread worker = new Thread(() -> {
            Planned planned;
            Map<Path, String> basis = new HashMap<>();
            try {
                BotIndex bot = index.get();
                planned = plan.plan(bot, grammar, declared);
                if (planned.outcome() instanceof Refactor.Planned change) {
                    basis.putAll(basis(bot, change, planned.deleted()));
                }
            } catch (RuntimeException | LinkageError e) {
                planned = Planned.refused("It could not be worked out: " + e);
            }
            Planned done = planned;
            Platform.runLater(() -> finish(done, basis, told));
        }, "migration-cleanup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Asks, then writes {@code planned} — unless a file it changes is no longer the text it was worked out from
     * ({@code basis}): an edit made meanwhile would be lost under it, so nothing is written and the step is asked
     * for again.
     */
    private void finish(Planned planned, Map<Path, String> basis, Told told) {
        if (!(planned.outcome() instanceof Refactor.Planned change)) {
            told.finished(((Refactor.Refused) planned.outcome()).reason() + planned.note());
            return;
        }
        if (planned.ask() != null) {
            Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, planned.ask());
            Window window = owner.get();
            if (window != null) confirm.initOwner(window);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                told.finished("Nothing was changed.");
                return;
            }
        }
        Wrote wrote = write(change, planned.deleted(), basis);
        told.finished(wrote.done() ? wrote.text() + planned.note() : wrote.text());
        if (wrote.done()) after.run();
    }

    /** What {@link #write} did: whether it wrote, and what to say. */
    record Wrote(boolean done, String text) {}

    /**
     * Writes {@code change} and deletes {@code deleted} as one version after a safety one — unless a file it touches
     * is no longer the text it was worked out from ({@code basis}). Also the assistant's {@code switch_uses} and
     * {@code remove_migrated}, which ask nothing on screen. FX thread.
     */
    Wrote write(Refactor.Planned change, Set<Path> deleted, Map<Path, String> basis) {
        List<String> changed = changedSince(config, state, basis);
        if (!changed.isEmpty()) {
            return new Wrote(false, String.join(", ", changed) + " changed while this was worked out, so nothing was"
                    + " changed. Try again.");
        }
        Checkpoints.take(config.projectPath(), state.snapshot(), VersionOrigin.SAFETY, "Before: " + change.summary());
        BotSources.forEach(config, state, (file, source) -> change.rewrites().get(file));
        List<String> undeleted = deleted(deleted);
        change.rewrites().keySet().forEach(redraw);
        return new Wrote(true, change.summary() + "."
                + (undeleted.isEmpty() ? "" : " " + String.join("; ", undeleted) + "."));
    }

    /** The text of each file {@code change} rewrites and each of {@code deleted}, as {@code index} read them. */
    static Map<Path, String> basis(BotIndex index, Refactor.Planned change, Set<Path> deleted) {
        Map<Path, String> basis = new HashMap<>();
        change.rewrites().keySet().forEach(file -> basis.put(file, index.sources().get(file)));
        deleted.forEach(file -> basis.put(file, index.sources().get(file)));
        return basis;
    }

    /** The names of the files whose text is no longer what {@code basis} holds for them: edited while planned. */
    static List<String> changedSince(ProjectConfig config, ProjectState state, Map<Path, String> basis) {
        List<String> changed = new ArrayList<>();
        BotSources.scan(config, state, (file, source) -> {
            if (basis.containsKey(file) && !source.equals(basis.get(file))) changed.add(file.getFileName().toString());
        });
        return changed;
    }

    /**
     * Deletes {@code files} and drops their buffers, moving the editor off one; each one that could not be deleted
     * says why, and the others are deleted all the same.
     */
    private List<String> deleted(Set<Path> files) {
        ProjectFile active = state.getActiveFile();
        List<String> failed = new ArrayList<>();
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
                state.removeFile(file);
            } catch (IOException e) {
                failed.add(file.getFileName() + " could not be deleted: " + e.getMessage());
            }
        }
        if (active != null && files.contains(active.getPath()) && !Files.exists(active.getPath())) {
            state.setActiveFile(config.entrySourceFile());
        }
        return failed;
    }

    /** At most eight, then how many more. */
    private static String listed(List<String> items) {
        List<String> shown = items.stream().limit(8).toList();
        return String.join(", ", shown) + (items.size() > shown.size() ? " and " + (items.size() - shown.size())
                + " more" : "");
    }
}
