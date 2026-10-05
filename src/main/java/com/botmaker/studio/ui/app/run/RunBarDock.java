package com.botmaker.studio.ui.app.run;

import javafx.scene.layout.Pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where the run bar goes instead of its own window: the overlay editor's header, while that panel is open.
 * One bar, so a run followed from the panel is not followed from a second floating window too.
 *
 * <p>The panel {@link #offer offers} a pane when it opens and {@link #withdraw withdraws} it when it closes; a
 * run's bar moves into the pane and back out to its own window as that happens. FX thread.
 */
public final class RunBarDock {

    private static Pane slot;
    private static final List<Runnable> listeners = new ArrayList<>();

    private RunBarDock() {}

    /** Draws the run bar in {@code pane} from now on, until {@link #withdraw}. */
    public static void offer(Pane pane) {
        slot = pane;
        fire();
    }

    /** Takes {@code pane} back; the bar returns to its own window. A pane not offered is ignored. */
    public static void withdraw(Pane pane) {
        if (slot != pane) return;
        slot = null;
        fire();
    }

    static Optional<Pane> slot() {
        return Optional.ofNullable(slot);
    }

    /** Runs {@code listener} whenever the slot changes, until the answer is closed. */
    static AutoCloseable listen(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private static void fire() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }
}
