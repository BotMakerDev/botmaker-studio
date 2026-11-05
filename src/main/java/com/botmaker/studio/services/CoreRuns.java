package com.botmaker.studio.services;

import com.botmaker.core.edit.EditResult;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.run.debug.FollowPacer;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.Request;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.core.EditorState;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.ui.FxThread;
import com.botmaker.studio.validation.DiagnosticsManager;
import org.eclipse.lsp4j.Diagnostic;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The bot's runs, which happen in the core (plan phase 10b), on Studio's bus: the menu's and the toolbar's compile, run,
 * try, debug, stop and answer become requests, and what the core tells of them becomes the events Studio's windows
 * listen to.
 *
 * <p>A request never holds the thread that published it, usually the FX one: the core may be busy. Starting and steering
 * have a thread each, so a Stop never queues behind a Run still waiting for its answer. Events arrive on one thread of
 * their own ({@link CoreEdits#heardByRuns}), never the channel's reader: a plugin's listener may ask the core, and its
 * answer comes through that reader.
 */
public final class CoreRuns implements AutoCloseable {

    private final CoreEdits core;
    private final EventBus bus;
    private final ProjectState state;
    private final Path sourceRoot;
    private final DebugOnBus debugView;
    private final Supplier<Optional<String>> check;
    private final ExecutorService starting = thread("core-run-asks");
    private final ExecutorService steering = thread("core-run-controls");

    /** A run was asked for and its end not heard, its compile included. A refused ask clears only this. */
    private volatile boolean asked;
    /** The core said a bot runs and has not said it ended, whoever asked. */
    private volatile boolean started;
    private volatile OptionalLong pid = OptionalLong.empty();
    private volatile boolean debugging;

    private CoreRuns(CoreEdits core, EventBus bus, ProjectState state, Path sourceRoot, DebugOnBus debugView,
                     Supplier<Optional<String>> check) {
        this.core = core;
        this.bus = bus;
        this.state = state;
        this.sourceRoot = sourceRoot;
        this.debugView = debugView;
        this.check = check;
    }

    /** The project's runs: asked from {@code bus} through {@code core}, and what the core tells of them on {@code bus}. */
    public static CoreRuns attach(CoreEdits core, DiagnosticsManager diagnostics, EditorState state, ProjectConfig config,
                                  EventBus bus) {
        CoreRuns runs = new CoreRuns(core, bus, state, config.sourceRoot(), new DebugOnBus(state, bus),
                () -> refusal(diagnostics, bus));
        runs.subscribe();
        core.heardByRuns(runs::heard);
        return runs;
    }

    private void subscribe() {
        bus.subscribe(CoreApplicationEvents.CompilationRequestedEvent.class,
                e -> start(CoreClient::compile), false);
        bus.subscribe(CoreApplicationEvents.ExecutionRequestedEvent.class,
                e -> run(CoreClient::run), false);
        bus.subscribe(CoreApplicationEvents.EntryRunRequestedEvent.class,
                e -> run(client -> client.runEntry(e.title(), e.mainClass())), false);
        bus.subscribe(CoreApplicationEvents.TrialRunRequestedEvent.class,
                e -> run(client -> client.tryCaller(e.caller().className(), e.caller().text(), e.label())), false);
        bus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class,
                e -> steer(CoreClient::stop), false);
        bus.subscribe(CoreApplicationEvents.InputAnsweredEvent.class, e -> later(steering,
                // A question nobody waits for any more is no news to the user.
                () -> core.request(client -> client.answer(e.id(), e.value()))), false);
        bus.subscribe(CoreApplicationEvents.DebugControlRequest.class, e -> {
            switch (e) {
                case CoreApplicationEvents.DebugStartRequestedEvent ignored -> debug(false);
                case CoreApplicationEvents.FollowStartRequestedEvent ignored -> debug(true);
                case CoreApplicationEvents.DebugStepOverRequestedEvent ignored -> debugging(Request.Debugging.Step.STEP_OVER);
                case CoreApplicationEvents.DebugContinueRequestedEvent ignored -> debugging(Request.Debugging.Step.CONTINUE);
                case CoreApplicationEvents.DebugStopRequestedEvent ignored -> debugging(Request.Debugging.Step.STOP);
            }
        }, false);
    }

    /** Whether a run was asked for and has not ended. */
    public boolean isRunning() {
        return asked || started;
    }

    /** The bot's process, while it has one: what a plugin's pause signals. */
    public OptionalLong pid() {
        return pid;
    }

    /** A run, once Studio's own check lets it: a refusal is told here, and a bot already running goes on. */
    private void run(CoreEdits.Ask ask) {
        Optional<String> refused = check.get();
        if (refused.isPresent()) {
            status(refused.get());
            return;
        }
        asked = true;
        later(starting, () -> {
            if (core.request(ask) instanceof EditResult.Refused refusal) {
                // A bot already running still ends on its own, and one asked for by an ask that timed out is told when
                // it starts.
                asked = false;
                status(refusal.reason());
            }
        });
    }

    private void start(CoreEdits.Ask ask) {
        later(starting, () -> say(core.request(ask)));
    }

    private void steer(CoreEdits.Ask ask) {
        later(steering, () -> say(core.request(ask)));
    }

    private void say(EditResult result) {
        if (result instanceof EditResult.Refused refusal) status(refusal.reason());
    }

    /** The marks are read here, on the thread that drew them. */
    private void debug(boolean follow) {
        Map<String, Set<String>> marks = new HashMap<>();
        state.breakpoints().forEach((file, nodes) -> marks.put(
                sourceRoot.relativize(file).toString().replace(File.separatorChar, '/'), Set.copyOf(nodes)));
        start(client -> client.debug(follow, marks));
    }

    private void debugging(Request.Debugging.Step step) {
        steer(client -> client.debugging(step));
    }

    private static void later(ExecutorService thread, Runnable work) {
        try {
            thread.execute(work);
        } catch (RejectedExecutionException closed) {
            // The project closed: the core ends what runs for it.
        }
    }

    private void status(String message) {
        FxThread.INSTANCE.later(() -> bus.publish(new CoreApplicationEvents.StatusMessageEvent(message)));
    }

    /**
     * What the core told of a run, in order; it tells one end per run. An end with nothing going is not passed on: a core
     * gone says so whether or not anything ran.
     */
    private void heard(CoreEvent event) {
        switch (event) {
            case CoreEvent.RunProcess process -> pid = OptionalLong.of(process.pid());
            case CoreEvent.RunState told when told.phase() == CoreEvent.RunState.Phase.ENDED -> {
                boolean going = isRunning();
                asked = false;
                started = false;
                pid = OptionalLong.empty();
                if (going) publish(bus, told);
            }
            case CoreEvent.RunState told -> {
                if (told.phase() == CoreEvent.RunState.Phase.RUNNING) started = true;
                publish(bus, told);
            }
            case CoreEvent.DebugSession session -> session(session);
            // On the FX thread, where the canvas is: the block for a line is looked up in what is drawn by then.
            case CoreEvent.DebugPaused paused -> {
                FollowPacer.Hit hit = paused.file().isEmpty() ? null : new FollowPacer.Hit(path(paused.file()), paused.line());
                FxThread.INSTANCE.later(() -> {
                    if (debugging) debugView.paused(hit, paused.line(), paused.snapshot(), () -> debugging);
                });
            }
            case CoreEvent.DebugFollowed followed -> {
                Set<FollowPacer.Hit> hits = new LinkedHashSet<>();
                followed.hits().forEach(place -> hits.add(hit(place)));
                FollowPacer.Frame frame = new FollowPacer.Frame(hit(followed.latest()), hits, followed.looping());
                FxThread.INSTANCE.later(() -> {
                    if (debugging) debugView.followed(frame, () -> debugging);
                });
            }
            default -> publish(bus, event);
        }
    }

    private void session(CoreEvent.DebugSession session) {
        switch (session.phase()) {
            case STARTED -> {
                debugging = true;
                FxThread.INSTANCE.later(debugView::started);
            }
            case RESUMED -> FxThread.INSTANCE.later(debugView::resumed);
            case FINISHED -> {
                if (!debugging) return;
                debugging = false;
                FxThread.INSTANCE.later(debugView::finished);
            }
        }
    }

    private FollowPacer.Hit hit(CoreEvent.DebugFollowed.Place place) {
        return new FollowPacer.Hit(path(place.file()), place.line());
    }

    private Path path(String file) {
        return sourceRoot.resolve(file);
    }

    /** The core's run events as the bus's. */
    public static Consumer<CoreEvent> onBus(EventBus bus) {
        return event -> publish(bus, event);
    }

    /** A line of output reaches the bus on the FX thread; a run's start, end and question on the thread they arrive on. */
    private static void publish(EventBus bus, CoreEvent event) {
        switch (event) {
            case CoreEvent.RunOutput output ->
                    FxThread.INSTANCE.later(() -> bus.publish(new CoreApplicationEvents.OutputAppendedEvent(output.text())));
            case CoreEvent.RunState state -> {
                switch (state.phase()) {
                    case COMPILING -> FxThread.INSTANCE.later(() -> bus.publish(new CoreApplicationEvents.OutputClearedEvent()));
                    case RUNNING -> bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
                    case ENDED -> bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
                    case PAUSED -> {
                    }
                }
            }
            case CoreEvent.RunLogged logged -> bus.publish(new CoreApplicationEvents.BotRunEndedEvent(
                    logged.folder().isEmpty() ? null : Path.of(logged.folder())));
            case CoreEvent.InputAsked ask -> bus.publish(new CoreApplicationEvents.InputRequestedEvent(
                    new TelemetryEvent.Ask(ask.id(), ask.kind(), ask.prompt(), ask.choices(), ask.line())));
            case CoreEvent.RunTraced traced -> bus.publish(new CoreApplicationEvents.TraceLineEvent(traced.traceLine()));
            case CoreEvent.RunFrame frame -> bus.publish(new CoreApplicationEvents.TelemetryFrameEvent(frame.payload()));
            default -> {
            }
        }
    }

    /**
     * What Studio's diagnostics say before a run, shown in the Errors tab whether or not they stop it, and why they stop
     * it if they do. A slot still waiting on one of the user's variables (a red "Choose a variable…" chip) would only
     * surface as a raw javac error, so it stops the run here; every value slot seeds a compiling default, so it fires
     * only for positions that need a name (see UnfilledSlot). Deprecated SDK calls ride along but never stop it: they
     * compile and work today, and the warning is the deprecation cycle.
     */
    private static Optional<String> refusal(DiagnosticsManager diagnostics, EventBus bus) {
        List<Diagnostic> emptySlots = diagnostics.validateBlocks();
        List<Diagnostic> shown = new ArrayList<>(emptySlots);
        shown.addAll(diagnostics.deprecationWarnings());
        shown.addAll(diagnostics.entryPointHints());
        // Always published: an empty list clears the slot errors shown before.
        bus.publish(new CoreApplicationEvents.DiagnosticsUpdatedEvent(shown));
        if (!emptySlots.isEmpty()) {
            return Optional.of("Run aborted: choose a variable for the highlighted slot(s) — see the Errors tab.");
        }
        return diagnostics.hasErrors() ? Optional.of("Run aborted due to errors.") : Optional.empty();
    }

    private static ExecutorService thread(String name) {
        return Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Stops what runs, here and now, whether or not another client stays on the project: closing it in Studio ends its
     * bot, before the plugins are told the project is over. Then asks nothing more.
     */
    @Override
    public void close() {
        if (isRunning()) core.request(CoreClient::stop);
        if (debugging) core.request(client -> client.debugging(Request.Debugging.Step.STOP));
        starting.shutdown();
        steering.shutdown();
    }
}
