package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import javafx.beans.value.ChangeListener;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Opens a small window over the desktop while the project's bot runs, so the run can be followed, paused and
 * stopped without bringing Studio back over whatever the bot drives. A running bot is not necessarily a game:
 * this knows only the run, its trace and the screens. What else is shown is each plugin's
 * {@link com.botmaker.plugin.api.run.RunOverlayPart}.
 *
 * <p>Opens on {@link CoreApplicationEvents.ProgramStartedEvent} or {@link
 * CoreApplicationEvents.DebugSessionStartedEvent} while View ▸ Show Run Overlay is on, and closes when the run
 * ends. The windows themselves are a {@link Surface}, so the rules here are tested without a screen.
 *
 * <p>One per open project: built with the project's {@link EventBus}, closed with the project. FX thread.
 */
public final class RunOverlay implements AutoCloseable {

    /** How many trace lines the bar shows. */
    static final int TRACE_LINES = 3;

    /** The windows of one opening. */
    interface Surface {
        /** The trace's tail changed. */
        void trace(List<TraceLine> tail);

        /** Takes the windows down and tells every part it is closed. */
        void close();
    }

    /** What a surface can ask of the run it shows. */
    interface Session {
        /** Whether this is a debug session, which pauses through its debugger and cannot be run again here. */
        boolean debugging();

        void stop();

        /** Stops the run, then starts it again once it has ended. Plain runs only. */
        void runAgain();

        /** Takes the overlay down until the next run. */
        void dismiss();
    }

    /** Opens the windows for one run. */
    interface Surfaces {
        Surface open(Session session);
    }

    private final EventBus eventBus;
    private final BooleanSupplier enabled;
    private final Surfaces surfaces;
    private final TraceTail tail = new TraceTail(TRACE_LINES);
    private final List<EventBus.Subscription> subscriptions;
    private final ChangeListener<Boolean> shownListener = (obs, was, now) -> {
        if (!now) closeSurface();
    };

    private boolean running;
    private boolean debugging;
    /** Set by Run again: the next end of the run starts it once more. */
    private boolean restart;
    private Surface surface;

    /** The project's overlay, over the real windows and the View menu's preference. */
    public static RunOverlay install(EventBus eventBus, ProjectConfig config) {
        RunOverlay overlay = new RunOverlay(eventBus, RunOverlayPreference::shown,
                session -> RunOverlayWindows.open(session, config, eventBus), true);
        RunOverlayPreference.shownProperty().addListener(overlay.shownListener);
        return overlay;
    }

    /** {@code onFx}: deliver the run's events on the FX thread, as the windows need; false for a test. */
    RunOverlay(EventBus eventBus, BooleanSupplier enabled, Surfaces surfaces, boolean onFx) {
        this.eventBus = eventBus;
        this.enabled = enabled;
        this.surfaces = surfaces;
        this.subscriptions = List.of(
                eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> runChanged(true), onFx),
                eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> runChanged(false), onFx),
                eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class,
                        e -> debugChanged(true), onFx),
                eventBus.subscribe(CoreApplicationEvents.DebugSessionFinishedEvent.class,
                        e -> debugChanged(false), onFx),
                eventBus.subscribe(CoreApplicationEvents.TraceLineEvent.class, e -> traced(e.line()), onFx));
    }

    private void runChanged(boolean now) {
        running = now;
        if (now) {
            open();
            return;
        }
        if (!debugging) closeSurface();
        if (restart) {
            restart = false;
            eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
        }
    }

    private void debugChanged(boolean now) {
        debugging = now;
        if (now) open();
        else if (!running) closeSurface();
    }

    private void open() {
        if (surface != null || !enabled.getAsBoolean()) return;
        tail.clear();
        boolean debug = debugging;
        surface = surfaces.open(new Session() {
            @Override
            public boolean debugging() {
                return debug;
            }

            @Override
            public void stop() {
                RunOverlay.this.stop();
            }

            @Override
            public void runAgain() {
                if (debug || !running) return;
                restart = true;
                RunOverlay.this.stop();
            }

            @Override
            public void dismiss() {
                closeSurface();
            }
        });
    }

    private void stop() {
        if (debugging) eventBus.publish(new CoreApplicationEvents.DebugStopRequestedEvent());
        else if (running) eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
    }

    private void traced(TraceLine line) {
        tail.add(line);
        if (surface != null) surface.trace(tail.lines());
    }

    private void closeSurface() {
        Surface open = surface;
        surface = null;
        if (open != null) open.close();
    }

    @Override
    public void close() {
        subscriptions.forEach(EventBus.Subscription::close);
        RunOverlayPreference.shownProperty().removeListener(shownListener);
        closeSurface();
        running = false;
        debugging = false;
        restart = false;
    }
}
