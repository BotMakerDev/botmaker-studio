package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.run.RunOverlayContext;

import java.util.ArrayList;
import java.util.List;

/** One part's {@link RunOverlayContext} for one opening of the overlay. FX thread. */
final class PartContext implements RunOverlayContext {

    private final StudioServices services;
    private final Mode mode;
    private final List<Runnable> onClosed = new ArrayList<>();
    private boolean closed;

    PartContext(StudioServices services, Mode mode) {
        this.services = services;
        this.mode = mode;
    }

    @Override
    public StudioServices services() {
        return services;
    }

    @Override
    public Mode mode() {
        return mode;
    }

    @Override
    public void onClosed(Runnable action) {
        if (action == null) return;
        if (closed) {
            run(action);
            return;
        }
        onClosed.add(action);
    }

    /** Runs every action once; one that throws costs only itself. */
    void close() {
        if (closed) return;
        closed = true;
        for (Runnable action : onClosed) run(action);
        onClosed.clear();
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: a run overlay part's close action threw: " + e);
        }
    }
}
