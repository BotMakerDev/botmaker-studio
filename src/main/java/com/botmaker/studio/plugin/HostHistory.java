package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.History;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Studio's own side of {@link History} — the editor's undo, {@code services/EditorHistory}, as a plugin's window
 * and the assistant reach it.
 *
 * <p><b>Nothing here is new capability.</b> ↶ and ↷ are the events the menu publishes, the state is the one
 * {@link CoreApplicationEvents.HistoryStateChangedEvent} broadcasts, and a batch is {@link WriteSteps}. It is
 * held as the last broadcast rather than asked for, because the history is the editor's and this class sees
 * only the bus; the editor broadcasts on every change, synchronously, so the state read right after an
 * {@link #undo()} is the one it left.
 *
 * <p><b>Installed per project, static, one at a time</b>, as {@link HostPluginValues} is and for the same reason.
 * Between projects a plugin asking gets {@link History#NONE}.
 */
public final class HostHistory implements History {

    private static volatile HostHistory current;

    private final EventBus eventBus;
    private final EventBus.Subscription subscription;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile CoreApplicationEvents.HistoryStateChangedEvent last =
            new CoreApplicationEvents.HistoryStateChangedEvent(false, false, "", "");

    private HostHistory(EventBus eventBus) {
        this.eventBus = eventBus;
        this.subscription = eventBus.subscribe(CoreApplicationEvents.HistoryStateChangedEvent.class, event -> {
            last = event;
            // After the state is held, so a listener reading canUndo() reads the step it is told about.
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (RuntimeException failed) {
                    // One plugin's listener failing is not every other window's ↶ going stale.
                    System.err.println("A history listener failed: " + failed);
                }
            }
        }, false);
    }

    /** Makes this project's history the one a plugin reaches, replacing whatever was installed before. */
    public static synchronized void install(EventBus eventBus) {
        clear();
        if (eventBus != null) current = new HostHistory(eventBus);
    }

    /** No project: a plugin asking now gets {@link History#NONE}. */
    public static synchronized void clear() {
        HostHistory old = current;
        current = null;
        if (old != null) {
            old.subscription.close();
            // A window left open over the old project hears nothing more; it is stopped with that project.
            old.listeners.clear();
        }
    }

    /** The live history, or {@link History#NONE} when no project is open. Never null. */
    public static History live() {
        HostHistory live = current;
        return live == null ? History.NONE : live;
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
        return last.canUndo() && last.undoLabel() != null ? last.undoLabel() : "";
    }

    @Override
    public String redoLabel() {
        return last.canRedo() && last.redoLabel() != null ? last.redoLabel() : "";
    }

    @Override
    public void undo() {
        eventBus.publish(new CoreApplicationEvents.UndoRequestedEvent());
    }

    @Override
    public void redo() {
        eventBus.publish(new CoreApplicationEvents.RedoRequestedEvent());
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
        }, eventBus::publish);
    }
}
