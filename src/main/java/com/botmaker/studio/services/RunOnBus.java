package com.botmaker.studio.services;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.runtime.RunCheck;
import com.botmaker.studio.runtime.RunTelemetry;
import com.botmaker.studio.ui.FxThread;
import com.botmaker.studio.validation.DiagnosticsManager;
import org.eclipse.lsp4j.Diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The run on Studio's bus (plan phase 7): the menu's and the toolbar's compile, run, try and stop reach
 * {@link CodeExecutionService}, and what it tells as {@link CoreEvent}s becomes the events Studio's windows already
 * listen to. A line of output or of status reaches the bus on the FX thread, as it did when the service posted it
 * there itself; a run's start, end and question arrive on the thread that saw them.
 */
public final class RunOnBus {

    private RunOnBus() {
    }

    /** The project's run service, its requests taken from {@code bus} and its events told on it. */
    public static CodeExecutionService service(DiagnosticsManager diagnostics, ProjectConfig config,
                                               ProjectState state, EventBus bus) {
        CodeExecutionService service = new CodeExecutionService(config, state, FxThread.INSTANCE,
                check(diagnostics, bus), events(bus), telemetry(bus));
        requests(bus, service, state);
        return service;
    }

    /** Sends the bus's compile, run, try, stop and answer to {@code service}, the project read as each is asked. */
    public static void requests(EventBus bus, CodeExecutionService service, ProjectState state) {
        bus.subscribe(CoreApplicationEvents.CompilationRequestedEvent.class,
                e -> service.compileCode(state.snapshot()), false);
        bus.subscribe(CoreApplicationEvents.ExecutionRequestedEvent.class,
                e -> service.runCode(state.snapshot()), false);
        bus.subscribe(CoreApplicationEvents.EntryRunRequestedEvent.class,
                e -> service.runEntry(state.snapshot(), e.title(), e.mainClass()), false);
        bus.subscribe(CoreApplicationEvents.TrialRunRequestedEvent.class,
                e -> service.tryCode(state.snapshot(), e.caller(), e.label()), false);
        bus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class, e -> service.stopRunningProgram(), false);
        bus.subscribe(CoreApplicationEvents.InputAnsweredEvent.class, e -> service.answer(e.id(), e.value()), false);
    }

    /** The core's run events as the bus's. */
    public static Consumer<CoreEvent> events(EventBus bus) {
        return event -> publish(bus, event);
    }

    private static void publish(EventBus bus, CoreEvent event) {
        switch (event) {
            case CoreEvent.Status status ->
                    FxThread.INSTANCE.later(() -> bus.publish(new CoreApplicationEvents.StatusMessageEvent(status.message())));
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
            default -> {
            }
        }
    }

    /** The run's trace lines and plugin frames as the bus's, for the Trace tab and plugins' {@code Runs}. */
    public static RunTelemetry.Listener telemetry(EventBus bus) {
        return new RunTelemetry.Listener() {
            @Override
            public void trace(TraceLine line) {
                bus.publish(new CoreApplicationEvents.TraceLineEvent(line));
            }

            @Override
            public void frame(byte[] payload) {
                bus.publish(new CoreApplicationEvents.TelemetryFrameEvent(payload));
            }
        };
    }

    /**
     * What Studio's diagnostics say before a run, shown in the Errors tab whether or not they stop it. A slot still
     * waiting on one of the user's variables (a red "Choose a variable…" chip) would only surface as a raw javac
     * error, so it stops the run here; every value slot seeds a compiling default, so it fires only for positions
     * that need a name (see UnfilledSlot). Deprecated SDK calls ride along but never stop it: they compile and work
     * today, and the warning is the deprecation cycle.
     */
    static RunCheck check(DiagnosticsManager diagnostics, EventBus bus) {
        return () -> {
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
        };
    }
}
