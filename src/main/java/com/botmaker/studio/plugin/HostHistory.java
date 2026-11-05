package com.botmaker.studio.plugin;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.plugin.api.History;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The host's side of {@link History} — the project's undo, the core's history, as a plugin's window and the
 * assistant reach it.
 *
 * <p><b>Nothing here is new capability.</b> ↶ and ↷ go to the history through {@link Requests}, the state is the
 * last {@link CoreEvent.HistoryChanged} the history told ({@link #heard}), and a batch is {@link WriteSteps}. It is
 * held as the last one told rather than asked for, because the history tells every change, synchronously, so the
 * state read right after an {@link #undo()} is the one it left. Studio fills the port and tells the state from its
 * bus ({@code services.PluginsOnBus}, 2026-11-05).
 *
 * <p><b>Installed per project, static, one at a time</b>, as {@link HostPluginValues} is and for the same reason.
 * Between projects a plugin asking gets {@link History#NONE}.
 */
public final class HostHistory implements History {

    /** What a plugin's ↶, ↷ and batch ask of the project's history. */
    public interface Requests extends WriteSteps.Recorded {
        void undo();

        void redo();
    }

    private static volatile HostHistory current;

    private final Requests requests;
    /** What tells this history its changes, stopped with the project ({@link #clear}). */
    private volatile AutoCloseable hearing = () -> { };
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile CoreEvent.HistoryChanged last = new CoreEvent.HistoryChanged(false, false, "", "");

    private HostHistory(Requests requests) {
        this.requests = requests;
    }

    /**
     * Makes this project's history the one a plugin reaches, replacing whatever was installed before; the instance,
     * which is told each change by {@link #heard}.
     */
    public static synchronized HostHistory install(Requests requests) {
        clear();
        if (requests == null) return null;
        current = new HostHistory(requests);
        return current;
    }

    /** No project: a plugin asking now gets {@link History#NONE}. */
    public static synchronized void clear() {
        HostHistory old = current;
        current = null;
        if (old == null) return;
        // A window left open over the old project hears nothing more; it is stopped with that project.
        old.listeners.clear();
        try {
            old.hearing.close();
        } catch (Exception failed) {
            System.err.println("Couldn't stop hearing the old project's history: " + failed);
        }
    }

    /** What tells this history its changes ({@link #heard}); closed when the project closes. */
    public void hearing(AutoCloseable source) {
        if (source != null) hearing = source;
    }

    /** The live history, or {@link History#NONE} when no project is open. Never null. */
    public static History live() {
        HostHistory live = current;
        return live == null ? History.NONE : live;
    }

    /** What the history became, told after each change; then every plugin listener hears it. */
    public void heard(CoreEvent.HistoryChanged changed) {
        if (changed == null) return;
        last = changed;
        // After the state is held, so a listener reading canUndo() reads the step it is told about.
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException failed) {
                // One plugin's listener failing is not every other window's ↶ going stale.
                System.err.println("A history listener failed: " + failed);
            }
        }
    }

    @Override
    public boolean canUndo() {
        return last.canUndo();
    }

    @Override
    public boolean canRedo() {
        return last.canRedo();
    }

    @Override
    public String undoLabel() {
        return last.canUndo() ? last.undoLabel() : "";
    }

    @Override
    public String redoLabel() {
        return last.canRedo() ? last.redoLabel() : "";
    }

    @Override
    public void undo() {
        requests.undo();
    }

    @Override
    public void redo() {
        requests.redo();
    }

    @Override
    public AutoCloseable onChanged(Runnable listener) {
        if (listener == null) return () -> {
        };
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Takes one step back ({@code back}) or does one again on {@code history}, and says which, and what is next
     * each way: the assistant's {@code undo} and {@code redo}, which name the step so a model knows whose change
     * it took back.
     */
    public static String step(History history, boolean back) {
        if (back && !history.canUndo()) return "Nothing to undo. " + nextRedo(history);
        if (!back && !history.canRedo()) return "Nothing to redo. " + nextUndo(history);
        String label = back ? history.undoLabel() : history.redoLabel();
        if (back) history.undo();
        else history.redo();
        return (back ? "Took back: " : "Did again: ") + label + ". " + nextUndo(history) + " " + nextRedo(history);
    }

    private static String nextUndo(History history) {
        return history.canUndo() ? "Next undo: " + history.undoLabel() + "." : "Nothing more to undo.";
    }

    private static String nextRedo(History history) {
        return history.canRedo() ? "Next redo: " + history.redoLabel() + "." : "Nothing more to redo.";
    }

    @Override
    public void batch(String label, Runnable writes) {
        if (writes == null) return;
        WriteSteps.step(label, () -> {
            writes.run();
            return null;
        }, requests);
    }
}
