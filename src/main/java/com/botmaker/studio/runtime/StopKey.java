package com.botmaker.studio.runtime;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.shared.input.InputListenerFactory;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import javafx.application.Platform;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Stops the project's bot when the {@link StopKeyPreference stop key} is pressed anywhere on the desktop, so a
 * run can be ended without bringing Studio back over whatever the bot drives.
 *
 * <p>It listens only while Studio has a run or a debug session going, through the same passive listener the
 * recorder uses ({@link InputListenerFactory}: X11 XRecord). The key press publishes the stop the toolbar's
 * button publishes, {@link CoreApplicationEvents.StopRunRequestedEvent} for a run and
 * {@link CoreApplicationEvents.DebugStopRequestedEvent} for a debug session, once per session however long
 * the key is held. A run started from the phone pilot goes through the same events, so the key stops it too.
 *
 * <p>Two limits come with the listener. It is passive, so the focused program receives the key as well; and
 * XRecord sees X11 and Xwayland windows only, so a key typed into a native Wayland window is not seen. A bot
 * that presses the stop key itself stops itself.
 *
 * <p>One per open project: built with the project's {@link EventBus}, closed with the project.
 */
public final class StopKey implements AutoCloseable {

    private final EventBus eventBus;
    private final Supplier<InputListener> listeners;
    private final LongSupplier keysym;
    private final boolean supported;
    /** Where a stop is published: the FX thread, since the press arrives on the listener's own thread. */
    private final Consumer<Runnable> fx;
    private final List<EventBus.Subscription> subscriptions;

    private boolean running;
    private boolean debugging;
    private InputListener listener;
    /** Whether this session's stop was already sent: a held key repeats its press. */
    private volatile boolean fired;

    /** The project's stop key, over the real listener and the real preference. */
    public static StopKey install(EventBus eventBus) {
        return new StopKey(eventBus, InputListenerFactory::create, StopKeyPreference::keysym,
                InputListenerFactory.isSupported(), Platform::runLater);
    }

    StopKey(EventBus eventBus, Supplier<InputListener> listeners, LongSupplier keysym, boolean supported,
            Consumer<Runnable> fx) {
        this.eventBus = eventBus;
        this.listeners = listeners;
        this.keysym = keysym;
        this.supported = supported;
        this.fx = fx;
        this.subscriptions = List.of(
                eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> runChanged(true)),
                eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> runChanged(false)),
                eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> debugChanged(true)),
                eventBus.subscribe(CoreApplicationEvents.DebugSessionFinishedEvent.class,
                        e -> debugChanged(false)));
    }

    private synchronized void runChanged(boolean now) {
        running = now;
        update();
    }

    private synchronized void debugChanged(boolean now) {
        debugging = now;
        update();
    }

    /** Listens while anything is live, and not otherwise. */
    private void update() {
        boolean live = running || debugging;
        if (live && listener == null && supported) {
            fired = false;
            try {
                InputListener started = listeners.get();
                started.start(this::onEvent);
                listener = started;
                status("Press " + StopKeyPreference.nameOf(keysym.getAsLong()) + " to stop the bot.");
            } catch (RuntimeException | Error e) {
                status("The stop key is unavailable this run: " + e.getMessage());
            }
        } else if (!live && listener != null) {
            closeListener();
        }
    }

    /** Called on the listener's thread. */
    private void onEvent(InputEvent event) {
        if (fired || !(event instanceof InputEvent.KeyPress press) || press.keysym() != keysym.getAsLong()) {
            return;
        }
        fired = true;
        fx.accept(this::stop);
    }

    private void stop() {
        boolean debug;
        synchronized (this) {
            if (!running && !debugging) return;
            debug = debugging;
        }
        eventBus.publish(debug ? new CoreApplicationEvents.DebugStopRequestedEvent()
                : new CoreApplicationEvents.StopRunRequestedEvent());
        status("Stopped by " + StopKeyPreference.nameOf(keysym.getAsLong()) + ".");
    }

    private void status(String message) {
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }

    private void closeListener() {
        try {
            listener.close();
        } catch (RuntimeException ignored) {
            // idempotent close; nothing to recover
        }
        listener = null;
    }

    @Override
    public synchronized void close() {
        subscriptions.forEach(EventBus.Subscription::close);
        if (listener != null) closeListener();
        running = false;
        debugging = false;
    }
}
