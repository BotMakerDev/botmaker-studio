package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.ProjectSettingsService;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Studio's side of {@link Runs} — the open project's bot, as a plugin is allowed to see it.
 *
 * <p>Nothing here is new capability. Starting and stopping are the events the Run and Stop buttons already
 * publish, the pid is {@link CodeExecutionService#runningBotPid()}, and the two listener channels are
 * {@link EventBus} subscriptions Studio already makes. What the class adds is the shape: a plugin gets the
 * bot's process without seeing a Studio type, which is what lets the Remote Pilot be a plugin's feature
 * rather than the host's.
 *
 * <p><b>Installed per project, static, one at a time</b> — the same shape as {@link PluginHost}, and for the
 * same reason: {@code HostServices} is built ad hoc from a {@code ProjectConfig} at three call sites that
 * have no event bus in scope, and Studio holds one open project. {@link #install} is called from the
 * composition root and {@link #clear} from the project's close, so between projects a plugin asking gets
 * {@link Runs#NONE} rather than a channel into the project the user just left.
 *
 * <h2>Two things about the listeners that are not incidental</h2>
 *
 * <p><b>The {@code EventBus} subscription is made once and never removed</b> — it has no unsubscribe — so
 * what a plugin registers goes in a list of Studio's own, and the handle it gets back removes it from
 * <em>that</em>. Registering per plugin listener directly on the bus would accumulate one dead handler per
 * project opened, which is the leak the handle exists to prevent.
 *
 * <p><b>Telemetry crosses as the bytes it arrived as</b> (2026-09-29; decoded and re-encoded before). Studio
 * does not read a match or a click — that is the runtime's vocabulary — so {@link
 * com.botmaker.studio.runtime.RunTelemetry} relays each frame whole and the plugin decodes it with its own
 * copy of the format. A debug line is the exception, because showing a log is a capability of any host: it
 * arrives as a {@link TraceLine} and goes to {@link #onTrace} listeners only ({@code
 * docs/refactor/40-run-trace.md}).
 */
public final class HostRuns implements Runs {

    /** The live channel, or null between projects. */
    private static volatile HostRuns current;

    private final EventBus eventBus;
    private final CodeExecutionService execution;
    /** Where the run properties live — the checkout's own {@code .botmaker/settings.json}. */
    private final ProjectSettingsService settings;

    /** Plugin listeners, held here rather than on the bus, which cannot unsubscribe. */
    private final List<Consumer<Boolean>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<byte[]>> telemetryListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TraceLine>> traceListeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;

    private HostRuns(EventBus eventBus, CodeExecutionService execution, ProjectSettingsService settings) {
        this.eventBus = eventBus;
        this.execution = execution;
        this.settings = settings;
        // false: these must not be marshalled onto the FX thread. A plugin is told in the contract that the
        // thread is not promised and that it must hop for UI itself, and a pilot pushing a frame has no
        // business queueing behind the editor's rendering.
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> fireState(true), false);
        eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> fireState(false), false);
        eventBus.subscribe(CoreApplicationEvents.TelemetryFrameEvent.class, e -> fireTelemetry(e.frame()), false);
        eventBus.subscribe(CoreApplicationEvents.TraceLineEvent.class, e -> fireTrace(e.line()), false);
    }

    /** Makes this project's bot the one a plugin reaches, replacing whatever was installed before. */
    public static synchronized void install(EventBus eventBus, CodeExecutionService execution,
                                            ProjectSettingsService settings) {
        if (eventBus == null || execution == null) {
            clear();
            return;
        }
        current = new HostRuns(eventBus, execution, settings);
    }

    /** No project: a plugin asking now gets {@link Runs#NONE}. */
    public static synchronized void clear() {
        current = null;
    }

    /** The live channel, or {@link Runs#NONE} when no project is open. Never null. */
    public static Runs live() {
        HostRuns live = current;
        return live == null ? Runs.NONE : live;
    }

    /** Says one line in the status bar, or does nothing when no project is open. */
    public static void status(String message) {
        HostRuns live = current;
        if (live == null || message == null) return;
        live.eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }

    @Override
    public void start() {
        eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
    }

    @Override
    public void stop() {
        eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public OptionalLong pid() {
        return execution.runningBotPid();
    }

    /** This checkout's run property {@code name}, kept in {@code .botmaker/settings.json}; null when unset. */
    @Override
    public String property(String name) {
        if (settings == null || name == null) return null;
        return settings.current().runProperties().get(name);
    }

    /**
     * Sets it for every later run — {@code BotJvm.options} passes each as {@code -D}. Written at once rather than
     * through the asynchronous update, so a plugin that sets a property and reads it back in the same breath (the
     * emulator picker, then the setup checklist) sees what it wrote.
     */
    @Override
    public void setProperty(String name, String value) {
        if (settings == null || name == null || name.isBlank()) return;
        settings.saveNow(settings.current().withRunProperty(name.trim(), value));
    }

    @Override
    public AutoCloseable onStateChanged(Consumer<Boolean> listener) {
        if (listener == null) return () -> { };
        stateListeners.add(listener);
        return () -> stateListeners.remove(listener);
    }

    @Override
    public AutoCloseable onTelemetry(Consumer<byte[]> listener) {
        if (listener == null) return () -> { };
        telemetryListeners.add(listener);
        return () -> telemetryListeners.remove(listener);
    }

    @Override
    public AutoCloseable onTrace(Consumer<TraceLine> listener) {
        if (listener == null) return () -> { };
        traceListeners.add(listener);
        return () -> traceListeners.remove(listener);
    }

    private void fireState(boolean nowRunning) {
        running = nowRunning;
        for (Consumer<Boolean> listener : stateListeners) {
            deliver(() -> listener.accept(nowRunning));
        }
    }

    /** Each listener gets its own copy: a byte array is mutable, and one plugin must not edit another's frame. */
    private void fireTelemetry(byte[] frame) {
        if (frame == null) return;
        for (Consumer<byte[]> listener : telemetryListeners) {
            deliver(() -> listener.accept(frame.clone()));
        }
    }

    private void fireTrace(TraceLine line) {
        if (line == null) return;
        for (Consumer<TraceLine> listener : traceListeners) {
            deliver(() -> listener.accept(line));
        }
    }

    /**
     * Runs one listener, total.
     *
     * <p>The same rule the {@link EventBus} keeps for its own handlers, for the same reason: a plugin that
     * throws while being told a bot started must not stop the next plugin being told, and must not surface as
     * the Run button appearing to fail.
     */
    private static void deliver(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: a plugin's run listener threw: " + e);
        }
    }
}
