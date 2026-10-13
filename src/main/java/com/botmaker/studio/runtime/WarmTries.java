package com.botmaker.studio.runtime;

import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.runtime.tryhost.TryHost;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A project's warm try JVM (2026-10-13): {@link TryHost} started once and kept between tries, so a try costs its
 * caller's compile and not a JVM, the SDK's start-up and a join of the private display. Each try's classes load
 * afresh ({@code TryHost}'s own loader); the JVM is started again when what it was started with changes — the
 * dependencies, the run properties, the trace plan, the display offered — or after it died.
 *
 * <p>Its output goes to one console for its whole life, its telemetry to one server: the SDK opens its channel once
 * per JVM. A stop interrupts the try; one that does not end within {@link #STOP_GRACE_MS} ends the JVM. A stop asked
 * while the JVM still starts is kept and sent once the try is.
 */
final class WarmTries implements AutoCloseable {

    static final long STOP_GRACE_MS = 2_000;

    /** Takes what the JVM prints, line by line, each line break included. */
    private final Consumer<String> output;
    /** Hands over what {@link #output} holds, at a try's end. */
    private final Runnable flush;
    /** Starts the JVM's telemetry server, telling the JVM where it is through the builder's environment. */
    private final Function<ProcessBuilder, TelemetryServer> telemetry;

    /** Read without the lock, so a stop from the FX thread never waits on a JVM starting. */
    private volatile Host host;
    private volatile Try current;
    /** A stop asked before the try was sent: {@link #run} sends it as soon as it has. */
    private volatile boolean stopAsked;

    WarmTries(Consumer<String> output, Runnable flush, Function<ProcessBuilder, TelemetryServer> telemetry) {
        this.output = output;
        this.flush = flush;
        this.telemetry = telemetry;
    }

    /** One started JVM, what it was started with, and the word that marks its tries' ends. */
    private record Host(Process process, String key, String mark, Writer commands, TelemetryServer telemetry) {

        /** Where a line holding this JVM's end marker has it, or -1. */
        int marker(String line) {
            return line.indexOf(TryHost.END + mark + " ");
        }
    }

    /** One try's end: a marker from each pipe of the JVM it runs {@code on}, or the JVM's exit. */
    private static final class Try {
        final Host on;
        final CountDownLatch ends = new CountDownLatch(2);
        volatile int code = Integer.MIN_VALUE;

        Try(Host on) {
            this.on = on;
        }

        void ended(int at) {
            if (code == Integer.MIN_VALUE) code = at;
            ends.countDown();
        }

        void died() {
            ended(-1);
            ended(-1);
        }
    }

    /**
     * Runs {@code mainClass} out of {@code folders} in the JVM {@code command} starts, starting it first when none
     * runs or it was started with another {@code command} or {@code key}; blocks until the try ends and returns its
     * code: 0 done, 1 failed, 130 stopped, -1 the JVM died.
     *
     * @param key what else the JVM depends on that the command does not spell out, such as the trace plan's text
     */
    int run(List<String> command, String key, Path directory, List<Path> folders, String mainClass)
            throws IOException, InterruptedException {
        try {
            Host live = host(command, key, directory);
            Try attempt = new Try(live);
            current = attempt;
            // A JVM that died before the try was set has no pipe left to say so.
            live.process().onExit().thenRun(attempt::died);
            try {
                String paths = folders.stream().map(Path::toString).collect(Collectors.joining("\t"));
                send(live, "run " + mainClass + "\t" + paths);
                if (stopAsked) stop();
            } catch (IOException died) {
                attempt.died();
            }
            attempt.ends.await();
            flush.run();
            if (attempt.code == -1) close();
            return attempt.code;
        } finally {
            current = null;
            stopAsked = false;
        }
    }

    /** Interrupts the running try, and ends the JVM when the try has not ended {@link #STOP_GRACE_MS} later. */
    void stop() {
        stopAsked = true;
        Try attempt = current;
        if (attempt == null) return;
        Host live = attempt.on;
        try {
            send(live, "stop");
        } catch (IOException gone) {
            // The JVM is gone already: its exit ends the try.
        }
        Thread grace = new Thread(() -> {
            try {
                if (!attempt.ends.await(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) end(live);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "try-stop-grace");
        grace.setDaemon(true);
        grace.start();
    }

    /** The JVM's pid while a try runs in it: what a pause stops. */
    OptionalLong runningPid() {
        Try attempt = current;
        return attempt != null && attempt.on.process().isAlive() ? OptionalLong.of(attempt.on.process().pid())
                : OptionalLong.empty();
    }

    /** The live JVM's telemetry server, where a running try's questions are answered; null when none. */
    TelemetryServer telemetry() {
        Host live = host;
        return live == null ? null : live.telemetry();
    }

    private static void send(Host live, String line) throws IOException {
        synchronized (live.commands()) {
            live.commands().write(line + "\n");
            live.commands().flush();
        }
    }

    private synchronized Host host(List<String> command, String key, Path directory) throws IOException {
        String wanted = String.join("\u0000", command) + "\u0000" + key;
        Host live = host;
        if (live != null && live.process().isAlive() && live.key().equals(wanted)) return live;
        close();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        TelemetryServer server = telemetry.apply(builder);
        Process process = builder.start();
        byte[] word = new byte[12];
        new SecureRandom().nextBytes(word);
        live = new Host(process, wanted, HexFormat.of().formatHex(word), process.outputWriter(StandardCharsets.UTF_8),
                server);
        host = live;
        pump(live, process.getInputStream(), "try-host-stdout");
        pump(live, process.getErrorStream(), "try-host-stderr");
        send(live, "mark " + live.mark());
        return live;
    }

    /**
     * Reads one pipe for {@code of}'s life: text to the console, a try's end to the try — the text before the marker
     * kept, when the try's last output had no line break. Only a try on {@code of} is ended by it.
     */
    private void pump(Host of, InputStream stream, String name) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    int at = of.marker(line);
                    if (at < 0) {
                        output.accept(line + "\n");
                        continue;
                    }
                    if (at > 0) output.accept(line.substring(0, at) + "\n");
                    Try attempt = current;
                    if (attempt != null && attempt.on == of) attempt.ended(code(line.substring(at)));
                }
            } catch (IOException ignored) {
                // Killed: the JVM's exit ends its try.
            }
        }, name);
        reader.setDaemon(true);
        reader.start();
    }

    /** The code at the end of a marker. */
    private static int code(String marker) {
        try {
            return Integer.parseInt(marker.substring(marker.lastIndexOf(' ') + 1).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Ends the JVM and everything it started; the next try starts another. */
    @Override
    public synchronized void close() {
        Host live = host;
        host = null;
        if (live != null) kill(live);
    }

    /** Ends {@code live}, forgetting it when it is still the JVM held. */
    private synchronized void end(Host live) {
        if (host == live) host = null;
        kill(live);
    }

    private static void kill(Host live) {
        List<ProcessHandle> descendants = live.process().descendants().toList();
        live.process().destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            live.commands().close();
        } catch (IOException ignored) {
            // The pipe is closing with the process.
        }
        if (live.telemetry() != null) live.telemetry().close();
    }
}
