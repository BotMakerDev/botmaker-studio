package com.botmaker.studio.services;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.events.FileEdit;
import com.botmaker.core.run.CodeExecutionService;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.core.plugin.HostHistory;
import com.botmaker.core.plugin.HostPluginValues;
import com.botmaker.core.plugin.HostRuns;

import java.util.List;

/**
 * Studio's bridge between its bus and what a plugin reaches of the project ({@link HostRuns}, {@link HostHistory},
 * {@link HostPluginValues}), as {@link RunOnBus} is for the run (2026-11-05): those ask through ports and are told
 * through methods, and this turns the ports' requests into the bus events Studio's listeners already take, and the
 * bus's events into what they are told. Each subscription is made off the FX thread ({@code false}), as the classes
 * made them themselves before.
 */
public final class PluginsOnBus {

    private PluginsOnBus() {
    }

    /** Installs the project's bot for plugins, its start, stop and status on {@code bus}, its properties in {@code settings}. */
    public static HostRuns runs(EventBus bus, CodeExecutionService execution, ProjectSettingsService settings) {
        if (bus == null) {
            HostRuns.clear();
            return null;
        }
        HostRuns runs = HostRuns.install(new HostRuns.Requests() {
            @Override
            public void start() {
                bus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
            }

            @Override
            public void stop() {
                bus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
            }

            @Override
            public void status(String message) {
                bus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
            }
        }, execution, settings == null ? null : new HostRuns.Properties() {
            @Override
            public String get(String name) {
                return settings.current().runProperties().get(name);
            }

            @Override
            public void set(String name, String value) {
                settings.saveAndPublish(settings.current().withRunProperty(name, value));
            }
        });
        if (runs == null) return null;
        bus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> runs.ran(true), false);
        bus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> runs.ran(false), false);
        bus.subscribe(CoreApplicationEvents.TelemetryFrameEvent.class, e -> runs.telemetry(e.frame()), false);
        bus.subscribe(CoreApplicationEvents.TraceLineEvent.class, e -> runs.trace(e.line()), false);
        return runs;
    }

    /** Installs the project's history for plugins: ↶ and ↷ asked on {@code bus}, its state heard from it. */
    public static HostHistory history(EventBus bus) {
        if (bus == null) {
            HostHistory.clear();
            return null;
        }
        // What the history became reaches it from the core's event (HostHistory.told), not through the bus.
        return HostHistory.install(new HostHistory.Requests() {
            @Override
            public void undo() {
                bus.publish(new CoreApplicationEvents.UndoRequestedEvent());
            }

            @Override
            public void redo() {
                bus.publish(new CoreApplicationEvents.RedoRequestedEvent());
            }

            @Override
            public void step(String label, List<FileEdit> edits) {
                bus.publish(new CoreApplicationEvents.FilesRewrittenEvent(label, edits));
            }
        });
    }

    /** What a plugin's value write tells the editor: its undo step and the open file's redraw, on {@code bus}. */
    public static HostPluginValues.Edits edits(EventBus bus) {
        return new HostPluginValues.Edits() {
            @Override
            public void step(String label, List<FileEdit> edits) {
                bus.publish(new CoreApplicationEvents.FilesRewrittenEvent(label, edits));
            }

            @Override
            public void redraw(String content) {
                bus.publish(new CoreApplicationEvents.UIRefreshRequestedEvent(content));
            }
        };
    }
}
