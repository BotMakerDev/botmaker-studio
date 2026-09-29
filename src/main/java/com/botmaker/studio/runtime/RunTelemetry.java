package com.botmaker.studio.runtime;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.ipc.IpcEnv;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.shared.ipc.TelemetryFrame;
import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * A run's telemetry channel, as Studio listens to it: the one place a Run and a Debug start the loopback server
 * and decide what each frame is ({@code docs/refactor/40-run-trace.md}).
 *
 * <p><b>Studio reads the debug lines and nothing else.</b> A line is a capability every host has (it shows a
 * log), so it becomes a contract {@link TraceLine}; every other frame is a match, a click or whatever a newer
 * runtime sends, which is the runtime's vocabulary, and crosses to plugins as the bytes it arrived as. Until
 * 2026-09-29 Studio decoded every frame, so it knew what a match was, and a frame from a newer SDK than
 * Studio's own shared build was dropped here before the plugin that could read it saw it.
 */
public final class RunTelemetry {

    private RunTelemetry() {}

    /**
     * Starts the server and tells the bot where it is through {@code pb}'s environment; null when it could not
     * bind, in which case the bot runs without telemetry (the SDK opens no socket when the variables are absent).
     * Events are published from the server's own thread; a subscriber that draws asks for the FX thread.
     */
    public static TelemetryServer start(EventBus eventBus, ProcessBuilder pb) {
        try {
            String token = UUID.randomUUID().toString();
            TelemetryServer server = TelemetryServer.relaying(token, frame -> publish(eventBus, frame), null);
            pb.environment().put(IpcEnv.PORT, String.valueOf(server.port()));
            pb.environment().put(IpcEnv.TOKEN, token);
            return server;
        } catch (IOException e) {
            System.err.println("Telemetry server failed to start: " + e.getMessage());
            return null;
        }
    }

    /**
     * A debug line becomes a {@link CoreApplicationEvents.TraceLineEvent} and stops there: a plugin reads it
     * through {@code onTrace}, so relaying it as bytes as well would hand every telemetry listener a frame it
     * only has to skip. A line this Studio cannot read is dropped, which costs one row of the trace.
     */
    static void publish(EventBus eventBus, byte[] frame) {
        if (TelemetryFrame.isLog(frame)) {
            traceLine(frame).ifPresent(line -> eventBus.publish(new CoreApplicationEvents.TraceLineEvent(line)));
        } else {
            eventBus.publish(new CoreApplicationEvents.TelemetryFrameEvent(frame));
        }
    }

    /** The trace line {@code frame} carries, or empty when it is not one this Studio can read. */
    static Optional<TraceLine> traceLine(byte[] frame) {
        return TelemetryFrame.log(frame).map(RunTelemetry::traceLine);
    }

    private static TraceLine traceLine(TelemetryEvent.Log log) {
        TelemetryEvent.Rect r = log.rect();
        return new TraceLine(Instant.ofEpochMilli(log.atMillis()), TraceLine.Level.fromId(log.level()),
                log.source(), log.text(), log.count(), log.writerClass(), log.writerMethod(), log.className(),
                log.line() > 0 ? OptionalInt.of(log.line()) : OptionalInt.empty(),
                r == null ? Optional.empty()
                        : Optional.of(new TraceLine.Region(r.x(), r.y(), r.width(), r.height())));
    }

    /**
     * The source file of {@code className} under {@code sourceRoot}, or empty when there is no such file. A
     * nested or anonymous class ({@code Collect$1}) is in its top-level class's file.
     */
    public static Optional<Path> sourceFile(Path sourceRoot, String className) {
        if (sourceRoot == null || className == null || className.isBlank()) return Optional.empty();
        int nested = className.indexOf('$');
        String topLevel = nested < 0 ? className : className.substring(0, nested);
        Path file = sourceRoot.resolve(topLevel.replace('.', '/') + ".java");
        return java.nio.file.Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }
}
