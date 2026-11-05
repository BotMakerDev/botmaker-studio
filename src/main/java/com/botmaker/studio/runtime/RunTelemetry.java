package com.botmaker.studio.runtime;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.ipc.IpcEnv;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.shared.ipc.TelemetryFrame;
import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.core.event.CoreEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A run's telemetry channel, as Studio listens to it: the one place a Run and a Debug start the loopback server
 * and decide what each frame is ({@code docs/refactor/40-run-trace.md}).
 *
 * <p><b>Studio reads the debug lines and the bot's questions, and nothing else.</b> A line is a capability every
 * host has (it shows a log), so it becomes a contract {@link TraceLine}; a question ({@code Ask}) is answered by
 * Studio's own dialog and its {@link #answer} goes back on the same socket; every other frame is a match, a click or whatever a newer
 * runtime sends, which is the runtime's vocabulary, and crosses to plugins as the bytes it arrived as. Until
 * 2026-09-29 Studio decoded every frame, so it knew what a match was, and a frame from a newer SDK than
 * Studio's own shared build was dropped here before the plugin that could read it saw it.
 */
public final class RunTelemetry {

    private RunTelemetry() {}

    /**
     * Who hears the run's trace lines and the frames relayed to plugins, both in-process (plan phase 7: the Trace tab
     * and plugins' {@code Runs} listeners). A question is no frame for it: it is the protocol's
     * {@link CoreEvent.InputAsked}, which any client answers.
     */
    public interface Listener {
        Listener NONE = new Listener() {
            @Override
            public void trace(TraceLine line) {
            }

            @Override
            public void frame(byte[] payload) {
            }
        };

        void trace(TraceLine line);

        /** A frame the host does not read, without the wire's length prefix. */
        void frame(byte[] payload);
    }

    /**
     * Starts the server and tells the bot where it is through {@code pb}'s environment; null when it could not
     * bind, in which case the bot runs without telemetry (the SDK opens no socket when the variables are absent).
     * Both are told from the server's own thread; one that draws hops to its UI thread.
     */
    public static TelemetryServer start(Consumer<CoreEvent> events, Listener listener, ProcessBuilder pb) {
        try {
            String token = UUID.randomUUID().toString();
            TelemetryServer server = TelemetryServer.relaying(token, frame -> publish(events, listener, frame), null);
            pb.environment().put(IpcEnv.PORT, String.valueOf(server.port()));
            pb.environment().put(IpcEnv.TOKEN, token);
            return server;
        } catch (IOException e) {
            System.err.println("Telemetry server failed to start: " + e.getMessage());
            return null;
        }
    }

    /**
     * A debug line becomes a {@link Listener#trace} and stops there: a plugin reads it
     * through {@code onTrace}, so relaying it as bytes as well would hand every telemetry listener a frame it
     * only has to skip. A line this Studio cannot read is dropped, which costs one row of the trace.
     */
    static void publish(Consumer<CoreEvent> events, Listener listener, byte[] frame) {
        if (TelemetryFrame.isLog(frame)) {
            traceLine(frame).ifPresent(listener::trace);
            return;
        }
        Optional<TelemetryEvent.Ask> ask = TelemetryFrame.ask(frame);
        if (ask.isPresent()) {
            // A question is a client's to answer (it draws the dialog), so it is read here and never relayed.
            TelemetryEvent.Ask question = ask.get();
            events.accept(new CoreEvent.InputAsked(question.id(), question.kind(), question.prompt(),
                    question.choices(), question.line()));
        } else {
            listener.frame(payload(frame));
        }
    }

    /**
     * What a plugin's channel sent ({@code RunChannel.frame}), without the wire's length prefix: the run agent sends
     * a plugin's bytes as they are, and the plugin's window reads them back the same way.
     */
    static byte[] payload(byte[] frame) {
        return frame.length <= Integer.BYTES ? new byte[0] : Arrays.copyOfRange(frame, Integer.BYTES, frame.length);
    }

    /**
     * Sends the user's answer to question {@code id} back to the bot on {@code server}; false when there is no
     * run or no bot connected, so the answer is dropped.
     */
    public static boolean answer(TelemetryServer server, long id, String value) {
        return server != null && server.reply(new TelemetryEvent.Answer(id, value));
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
        Path file = sourceRoot.resolve(topLevel.replace('.', '/') + ".java").normalize();
        // A class name read from a log or an issue is a runner's: an absolute one would resolve past the root.
        if (!file.startsWith(sourceRoot.normalize())) return Optional.empty();
        return java.nio.file.Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }
}
