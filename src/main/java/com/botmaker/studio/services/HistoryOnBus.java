package com.botmaker.studio.services;

import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.studio.events.CoreApplicationEvents;

/**
 * The core's history on Studio's bus (plan phase 6e; Studio's own {@code EditorHistory} until then). ↶ and ↷, whoever
 * asks (the menu, the toolbar, a plugin window, the assistant), are steps of the core's history; a plugin's value writes
 * are made and recorded in the core since phase 10c; and what the history became is the Undo menu's to show, and the
 * contract's {@code History}'s to read back.
 */
public final class HistoryOnBus {

    private HistoryOnBus() {
    }

    /** Sends the bus's undo and redo to the core, through {@code edits}. */
    public static void attach(EventBus bus, EditPort edits) {
        bus.subscribe(CoreApplicationEvents.UndoRequestedEvent.class, e -> edits.step(HistoryStep.UNDO), false);
        bus.subscribe(CoreApplicationEvents.RedoRequestedEvent.class, e -> edits.step(HistoryStep.REDO), false);
    }

    /** {@code history} as the bus's event. */
    public static void publish(EventBus bus, CoreEvent.HistoryChanged history) {
        bus.publish(new CoreApplicationEvents.HistoryStateChangedEvent(
                history.canUndo(), history.canRedo(), history.undoLabel(), history.redoLabel()));
    }
}
