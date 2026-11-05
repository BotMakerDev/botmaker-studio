package com.botmaker.studio.services;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.assist.RunLog;
import com.botmaker.studio.events.CoreApplicationEvents.DebugSessionFinishedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.DebugSessionStartedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.EntryRunRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ExecutionRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.OutputAppendedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.OutputClearedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ProgramStartedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ProgramStoppedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TraceLineEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TrialRunRequestedEvent;

/**
 * Tells a {@link RunLog} what Studio's bus says of runs: what was asked for, its start and stop, a debug session, the
 * output and trace the Run and Trace tabs show (plan phase 9). The log closes the subscriptions when it closes.
 */
public final class RunLogOnBus {

    private RunLogOnBus() {}

    /** {@code log}, told from now on what {@code bus} says of runs. */
    public static RunLog listen(EventBus bus, RunLog log) {
        log.closeWith(bus.subscribe(ExecutionRequestedEvent.class, e -> log.requested("the bot")));
        log.closeWith(bus.subscribe(EntryRunRequestedEvent.class, e -> log.requested(e.title())));
        log.closeWith(bus.subscribe(TrialRunRequestedEvent.class, e -> log.requested(e.label())));
        log.closeWith(bus.subscribe(ProgramStartedEvent.class, e -> log.started()));
        log.closeWith(bus.subscribe(ProgramStoppedEvent.class, e -> log.stopped()));
        log.closeWith(bus.subscribe(DebugSessionStartedEvent.class, e -> log.debugging(true)));
        log.closeWith(bus.subscribe(DebugSessionFinishedEvent.class, e -> log.debugging(false)));
        log.closeWith(bus.subscribe(OutputAppendedEvent.class, e -> log.output(e.text())));
        log.closeWith(bus.subscribe(OutputClearedEvent.class, e -> log.cleared()));
        log.closeWith(bus.subscribe(TraceLineEvent.class, e -> log.trace(e.line())));
        return log;
    }
}
