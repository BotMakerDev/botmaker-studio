package com.botmaker.studio.runtime.agent.run;

import com.botmaker.runlog.RunFacts;
import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;
import com.botmaker.shared.ipc.TelemetryClient;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.runtime.agent.RunLines;
import com.botmaker.studio.runtime.agent.RunPlan;
import com.botmaker.studio.runtime.agent.TracePlan;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The run agent's inner half: the host in the bot's JVM (spec {@code
 * docs/superpowers/specs/2026-11-03-run-reports-design.md} §2, §7). Every line of the run comes through here —
 * each plugin's channel ({@link PluginChannel}), the call trace ({@link RunLines}) and shared's {@code Diag}
 * ({@link DiagBridge}) — and is attributed to the bot's own line by one stack walk, printed, sent to Studio and
 * kept in the run's {@code run.jsonl}.
 *
 * <p><b>One debug switch per run</b>, seeded from {@code -Dbotmaker.debug} (on when unset) and mirrored to the
 * bot's {@code Diag}. With it on every line is shown and written as it comes. With it off a debug line is neither
 * shown nor sent: the last {@value #RING} are held, with the info lines, and written before the next warning or
 * error, so the log keeps the calls leading to a problem whatever the switch said.
 *
 * <p><b>The log opens late</b>, when the facts are known: at the first plugin's {@code details}, the first problem,
 * {@value #PENDING} lines in, or the run's end. Lines before it wait in memory. Details a plugin gives once the log
 * is open are written as an info line, since the facts line is already written.
 *
 * <p>Nothing here throws into the bot: a log that cannot be written says so once on standard error.
 */
public final class AgentRun implements RunLines {

    /** Debug lines held while debugging is off, written before the next problem. */
    static final int RING = 50;
    /** Lines held before the log opens; the next one opens it. */
    static final int PENDING = 500;

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final Path folder;
    private final Path botFolder;
    private final TelemetryClient client;
    private final long started;
    private final ClassValue<Boolean> botClass;
    /** The facts any host knows (git, the bot's pom, the jars), read off the bot's threads as the run starts. */
    private final CompletableFuture<RunFacts> facts;
    private volatile boolean debug;
    private volatile DiagBridge diag = DiagBridge.NONE;

    // Guarded by this.
    private final Deque<LogLine> ring = new ArrayDeque<>();
    private final List<LogLine> pending = new ArrayList<>();
    private final Map<String, Map<String, String>> details = new LinkedHashMap<>();
    private RunLog log;
    private boolean opened;

    AgentRun(TracePlan trace, Path folder, Path botFolder, TelemetryClient client, boolean debug, long started) {
        this.folder = folder;
        this.botFolder = botFolder;
        this.client = client;
        this.debug = debug;
        this.started = started;
        this.botClass = new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                CodeSource source = type.getProtectionDomain().getCodeSource();
                URL location = source == null ? null : source.getLocation();
                return trace.isBotClass(location);
            }
        };
        this.facts = folder == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> BotFacts.of(botFolder, Map.of(), started));
    }

    /**
     * Starts the run: its folder under the plan's runs root, the wire to Studio when Studio listens, shared's
     * lines, and each declared plugin's channel. Called by {@code RunAgent.premain}, reflectively, before
     * {@code main}.
     */
    public static RunLines start(RunPlan plan) {
        Path folder = null;
        Path botFolder = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        if (plan.runs().isPresent()) {
            Path runs = plan.runs().get().toAbsolutePath();
            if (runs.endsWith(RunLog.FOLDER)) botFolder = runs.getParent().getParent();
            try {
                folder = RunLog.newFolder(runs, Clock.systemDefaultZone());
            } catch (IOException | RuntimeException e) {
                System.err.println("[BotMaker] this run keeps no log: " + e.getMessage());
            }
        }
        AgentRun run = new AgentRun(plan.trace(), folder, botFolder, TelemetryClient.fromEnvironment(),
                debugAtStart(), System.currentTimeMillis());
        run.diag = DiagBridge.attach(run);
        BotPlugins.start(run);
        return run;
    }

    /** What {@code -Dbotmaker.debug} says, on when it says nothing else. */
    static boolean debugAtStart() {
        String value = System.getProperty("botmaker.debug");
        return value == null || !value.strip().toLowerCase(Locale.ROOT).equals("false");
    }

    // ---- RunLines: the call trace ----

    @Override
    public boolean recorded() {
        return debug || folder != null;
    }

    @Override
    public void call(String source, String text, int count, String writerClass, String writerMethod) {
        accept(new LogLine(System.currentTimeMillis(), Level.DEBUG, source, text, count, null, writerClass,
                writerMethod, "", -1), null, true);
    }

    @Override
    public synchronized void ended() {
        if (folder == null) return;
        // Opened, never closed: each line is flushed as written, and a plugin's own shutdown hook (the SDK's time on
        // each screen) may say its last lines after this one ran; the JVM's exit closes the file.
        open();
    }

    // ---- what the channels ask ----

    boolean debug() {
        return debug;
    }

    void debug(boolean on) {
        debug = on;
        diag.set(on);
        if (on) {
            synchronized (this) {
                drainRing();
            }
        }
    }

    Optional<Path> folder() {
        return Optional.ofNullable(folder);
    }

    void frame(byte[] frame) {
        if (client != null) client.sendFrame(frame);
    }

    /** {@code plugin}'s facts of the run, replacing what it said before; opens the log when it is not yet. */
    synchronized void details(String plugin, Map<String, String> given) {
        if (folder == null || given == null) return;
        Map<String, String> copy = new LinkedHashMap<>(given);
        if (!opened) {
            details.put(plugin, copy);
            open();
        } else {
            write(new LogLine(System.currentTimeMillis(), Level.INFO, "RunLog", plugin + " " + copy, 1, null, "",
                    "", "", -1));
        }
    }

    /**
     * Asks the person running the bot through Studio's dialog; empty when Studio does not listen.
     *
     * @throws CancellationException when they cancelled, the wire dropped, or the run stopped while asking
     */
    Optional<String> ask(String kind, String prompt, List<String> choices) {
        if (client == null) return Optional.empty();
        int line = botFrame().map(StackWalker.StackFrame::getLineNumber).orElse(-1);
        String answer;
        try {
            answer = client.ask(kind, prompt, choices, line).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("the run stopped while asking");
        } catch (ExecutionException e) {
            throw new CancellationException(String.valueOf(e.getCause()));
        }
        if (answer == null) throw new CancellationException("cancelled");
        return Optional.of(answer);
    }

    // ---- every line ----

    /**
     * One line from anywhere: attributed, then printed when {@code print} (a {@code Diag} line was printed by
     * {@code Diag}), sent and kept when shown, and kept for the log either way.
     */
    void accept(LogLine given, Throwable cause, boolean print) {
        boolean shown = given.level() != Level.DEBUG || debug;
        // A quiet run's debug line only waits in the ring, mostly to be dropped: no stack walk for it, as Diag.
        LogLine line = attributed(given, cause, shown);
        if (shown) {
            if (print) print(given, cause);
            send(line);
        }
        keep(line);
    }

    private synchronized void keep(LogLine line) {
        if (folder == null) return;
        if (line.level().problem()) {
            drainRing();
            write(line);
            open();
        } else if (debug || line.level() == Level.INFO && !line.values().isEmpty()) {
            // An info a plugin gave values is what its report views count (a local run's Report.lines): kept.
            write(line);
        } else {
            ring.addLast(line);
            if (ring.size() > RING) ring.removeFirst();
        }
    }

    /** Writes the held lines, oldest first. Guarded by this. */
    private void drainRing() {
        while (!ring.isEmpty()) write(ring.removeFirst());
    }

    /** Writes {@code line} to the log, or holds it until the log opens. Guarded by this. */
    private void write(LogLine line) {
        if (log != null) {
            log.write(line);
        } else if (!opened) {
            pending.add(line);
            if (pending.size() >= PENDING) open();
        }
    }

    /** Opens the log with the facts known now, and writes what waited. Guarded by this. */
    private void open() {
        if (opened) return;
        opened = true;
        Map<String, String> merged = new LinkedHashMap<>();
        details.values().forEach(merged::putAll);
        try {
            RunFacts base = facts.join();
            log = RunLog.in(folder, new RunFacts(base.bot(), base.tag(), base.commit(), base.dirty(), base.versions(),
                    base.os(), base.startMillis(), merged));
        } catch (IOException | RuntimeException e) {
            System.err.println("[BotMaker] this run keeps no log: " + e.getMessage());
        }
        if (log != null) pending.forEach(log::write);
        pending.clear();
    }

    /**
     * {@code line} with the bot's own class and line when it names none and {@code walk}, and the cause's trace after
     * its text.
     */
    private LogLine attributed(LogLine line, Throwable cause, boolean walk) {
        String className = line.className();
        int at = line.line();
        if (walk && className.isEmpty()) {
            Optional<StackWalker.StackFrame> frame = botFrame();
            if (frame.isPresent()) {
                className = frame.get().getClassName();
                at = frame.get().getLineNumber();
            }
        }
        String text = cause == null ? line.text() : line.text() + "\n" + trace(cause);
        return new LogLine(line.atMillis(), line.level(), line.source(), text, line.count(), line.rect(),
                line.writerClass(), line.writerMethod(), className, at, line.values());
    }

    /** The innermost frame of the bot's own classes on this thread. */
    private Optional<StackWalker.StackFrame> botFrame() {
        return WALKER.walk(frames -> frames.filter(f -> botClass.get(f.getDeclaringClass())).findFirst());
    }

    private static String trace(Throwable cause) {
        StringWriter out = new StringWriter();
        cause.printStackTrace(new PrintWriter(out));
        return out.toString().stripTrailing();
    }

    /** As a run with no host prints it ({@code RunChannel.console()}): errors to stderr, the rest to stdout. */
    private static void print(LogLine line, Throwable cause) {
        String printed = line.source().isEmpty() ? line.text() : "[" + line.source() + "] " + line.text();
        if (line.count() > 1) printed = printed + "  (×" + line.count() + ")";
        (line.level() == Level.ERROR ? System.err : System.out).println(printed);
        if (cause != null) cause.printStackTrace(System.err);
    }

    private void send(LogLine line) {
        if (client == null) return;
        LogLine.Rect r = line.rect();
        client.send(new TelemetryEvent.Log(line.level().id(), line.source(), line.text(), line.count(),
                line.atMillis(), r == null ? null : new TelemetryEvent.Rect(r.x(), r.y(), r.width(), r.height()),
                line.writerClass(), line.writerMethod(), line.className(), line.line()));
    }
}
