package com.botmaker.studio.assist;

import com.botmaker.plugin.api.TraceLine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * What the bot printed and traced, and whether anything runs now — the Run and Trace tabs as an assistant reads
 * them ({@code read_trace}, {@code run_state}), kept off the FX thread from the events those tabs show.
 *
 * <p>Kept from when the endpoint starts: a run before that is not in it. The last {@link #LIMIT} lines are kept;
 * a new run marks where it began rather than clearing, so an assistant that reads after a quick run still sees
 * the one before — and {@link Read#thisRun} reads from the latest mark only, so it need not page through it.
 */
public final class RunLog implements AutoCloseable {

    static final int LIMIT = 2000;
    private static final int READ_LIMIT = 400;
    /** How long a wait gives a run asked for to show it started, before taking "nothing runs" as its end. */
    private static final Duration START_GRACE = Duration.ofSeconds(2);
    private static final long POLL_MS = 200;

    /** How long the output must stay quiet after a run's end before a wait answers: the pump lags the stop. */
    private static final Duration SETTLE = Duration.ofMillis(300);
    private static final Duration LONGEST_SETTLE = Duration.ofMillis(1500);

    /**
     * An output line that reads as a failure or a warning: an exception, its stack, or a line that says error,
     * warning or failed, in any case. A handled exception named in a line is kept too: a false alarm costs the
     * assistant a line, a missed failure a wrong conclusion.
     */
    private static final Pattern PROBLEM = Pattern.compile(
            "(?i)(exception|\\berror|\\bwarn(ing)?\\b|\\bfail(ed|ure)?\\b)|^\\s+at \\S|^caused by:");

    /** How {@link #read} picks lines. */
    public record Read(int count, boolean thisRun, boolean problemsOnly) {}

    /** How far back a trace line looks for the printed copy of itself that came first. */
    private static final int PAIR_REACH = 128;
    /** At most this many trace lines wait for their printed copy; past it the oldest is forgotten, and shows twice. */
    private static final int MOST_UNPAIRED = 256;

    /**
     * One line kept, numbered from the endpoint's start; {@code problem} when a filter for failures keeps it,
     * {@code printed} when it is the bot's output rather than its trace.
     */
    private record Line(long seq, String text, boolean problem, boolean printed) {}

    private final Clock clock;
    private final BooleanSupplier launching;
    /** What tells this log, closed with it: a client's subscriptions to its own events. */
    private final List<AutoCloseable> sources = new ArrayList<>();
    private boolean closed;
    private final Deque<Line> lines = new ArrayDeque<>();
    /** Output not yet ended by a newline. */
    private final StringBuilder partial = new StringBuilder();
    /**
     * Trace lines whose printed copy has not come yet, by that copy's text ({@code [Source] text}) and how many: a
     * traced line is also printed, and the copy that comes second is not kept — the assistant reads each line once.
     */
    private final Map<String, Integer> unpaired = new LinkedHashMap<>();

    /** What the next run that starts is: the bot, unless a try asked for it. */
    private String requested;
    /** What runs now; null when nothing does. */
    private String running;
    private Instant since;
    private Instant ended;
    private boolean debugging;
    private long nextSeq;
    /** The seq of the latest run's start mark; -1 before any run. */
    private long runStart = -1;
    /** How many runs and debug sessions have ended, so a wait can tell its own end from an earlier one. */
    private long ends;
    /** How many have started, so a wait can tell a run that never started (a failed compile) from one that ran. */
    private long starts;

    /**
     * @param launching whether a run has been asked for and has not ended, its compile included —
     *                  {@code CodeExecutionService::isRunning}. What it is told says only when the bot itself
     *                  starts, and a run asked for again during the compile would be a second bot on the game.
     */
    public RunLog(Clock clock, BooleanSupplier launching) {
        this.clock = clock;
        this.launching = launching;
    }

    /** Closes {@code source} when this log closes: what tells it, such as a client's bridge from its events. */
    public void closeWith(AutoCloseable source) {
        synchronized (this) {
            if (!closed) {
                sources.add(source);
                return;
            }
        }
        // Closed already: nothing would close it later.
        closeQuietly(source);
    }

    /** Whether the bot, a try or a debug session is on the game now. */
    public synchronized boolean busy() {
        return running != null || debugging || launching.getAsBoolean();
    }

    /** Whether what is on the game is a debug session, which is stopped apart from a run. */
    public synchronized boolean debugging() {
        return debugging;
    }

    /** What runs now and for how long, or how long ago the last run ended — one sentence. */
    public synchronized String state() {
        Instant now = clock.instant();
        if (debugging) return "A debug session runs the bot.";
        if (running != null) return "Running " + running + " for " + seconds(since, now) + ".";
        if (launching.getAsBoolean()) {
            return "Starting " + (requested == null ? "the bot" : requested) + ": it is compiling.";
        }
        if (ended != null) return "Nothing runs. The last run ended " + seconds(ended, now) + " ago.";
        return "Nothing runs, and nothing has run since the assistant connected.";
    }

    /** The last {@code count} lines of output and trace, oldest first; at most {@value #READ_LIMIT}. */
    public String tail(int count) {
        return read(new Read(count, false, false));
    }

    /** The lines {@code how} picks, the last {@code count} of them, oldest first; at most {@value #READ_LIMIT}. */
    public synchronized String read(Read how) {
        int wanted = Math.max(1, Math.min(how.count(), READ_LIMIT));
        List<String> picked = new ArrayList<>();
        long from = how.thisRun() ? Math.max(runStart, 0) : 0;
        for (Line line : lines) {
            if (line.seq() >= from && (!how.problemsOnly() || line.problem())) picked.add(line.text());
        }
        if (!partial.isEmpty() && (!how.problemsOnly() || problem(partial))) picked.add(partial.toString());
        if (picked.isEmpty()) {
            return how.problemsOnly() ? "No errors or warnings" + (how.thisRun() ? " in the last run." : ".")
                    : "No output yet.";
        }
        String omitted = picked.size() > wanted ? "(" + (picked.size() - wanted) + " earlier lines not shown)\n" : "";
        return omitted + String.join("\n", picked.subList(Math.max(0, picked.size() - wanted), picked.size()));
    }

    /** Where the runs stand, read before asking for one: what {@link #awaitEnd} counts from. */
    public synchronized Mark mark() {
        return new Mark(starts, ends);
    }

    /** The count of runs started and ended at a moment. */
    public record Mark(long starts, long ends) {}

    /** How a wait ended. */
    public enum Waited {
        /** A run started after the mark and has ended. */
        ENDED,
        /** Nothing started: the compile failed, or the start was refused. */
        NEVER_STARTED,
        /** Still running, or still compiling, when the time was up. */
        TIMED_OUT
    }

    /**
     * Waits until a run that started after {@code before} ends, or nothing runs once {@link #START_GRACE} has
     * passed with nothing started — a compile that fails ends without the bot starting —, or {@code timeout}
     * passes. Then waits for the output to settle, since the last lines reach the log after the stop. Polls,
     * because the compile's end raises no event.
     */
    public Waited awaitEnd(Mark before, Duration timeout) throws InterruptedException {
        Instant start = clock.instant();
        Instant deadline = start.plus(timeout);
        while (true) {
            Instant now = clock.instant();
            Waited waited = null;
            synchronized (this) {
                if (ends > before.ends() && starts > before.starts() && !busy()) {
                    waited = Waited.ENDED;
                } else if (starts == before.starts() && !busy()
                        && Duration.between(start, now).compareTo(START_GRACE) >= 0) {
                    waited = Waited.NEVER_STARTED;
                }
            }
            if (waited != null) {
                settle();
                return waited;
            }
            if (!now.isBefore(deadline)) return Waited.TIMED_OUT;
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * Waits, holding no lock so the events keep landing, until no output has come for {@link #SETTLE}; at most
     * {@link #LONGEST_SETTLE}.
     */
    private void settle() throws InterruptedException {
        Instant giveUp = clock.instant().plus(LONGEST_SETTLE);
        long seen = -1;
        while (clock.instant().isBefore(giveUp)) {
            long now;
            synchronized (this) {
                now = nextSeq * 1_000_003L + partial.length();
            }
            if (now == seen) return;
            seen = now;
            Thread.sleep(SETTLE.toMillis());
        }
    }

    @Override
    public void close() {
        List<AutoCloseable> closing;
        synchronized (this) {
            closed = true;
            closing = List.copyOf(sources);
            sources.clear();
        }
        closing.forEach(RunLog::closeQuietly);
    }

    private static void closeQuietly(AutoCloseable source) {
        try {
            source.close();
        } catch (Exception e) {
            // Closing is best-effort: a source that will not close only keeps telling a log nobody reads.
            System.err.println("Warning: a run log's source would not close: " + e.getMessage());
        }
    }

    /** A run of {@code what} was asked for: the bot, an entry's title or a try's label; the next start is it. */
    public synchronized void requested(String what) {
        requested = what;
    }

    /** The run asked for started. */
    public synchronized void started() {
        running = requested == null ? "the bot" : requested;
        requested = null;
        since = clock.instant();
        runStart = nextSeq;
        starts++;
        unpaired.clear();
        mark("── started " + running + " ──");
    }

    /** The run ended. */
    public synchronized void stopped() {
        if (running != null) mark("── stopped " + running + " after " + seconds(since, clock.instant()) + " ──");
        running = null;
        ended = clock.instant();
        ends++;
    }

    /** A debug session started ({@code on}) or finished. */
    public synchronized void debugging(boolean on) {
        debugging = on;
        if (on) {
            runStart = nextSeq;
            starts++;
        } else {
            ended = clock.instant();
            ends++;
        }
    }

    /** The user cleared the run's output: marked, not cleared here. */
    public synchronized void cleared() {
        mark("── run output cleared ──");
    }

    /** What the bot printed, any part of a line. */
    public synchronized void output(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                flushPrinted();
            } else if (c != '\r') {
                partial.append(c);
            }
        }
    }

    /**
     * A trace line, as {@code [Source] text (×n) @Class:line} — its level named only when it is not debug detail,
     * which is what nearly every line is. Its printed copy, when that came first, is taken out.
     */
    public synchronized void trace(TraceLine line) {
        String label = line.level() == TraceLine.Level.DEBUG ? line.source()
                : line.source().isEmpty() ? line.level().displayName()
                : line.level().displayName() + " " + line.source();
        String count = line.count() > 1 ? " (×" + line.count() + ")" : "";
        String at = line.className().isEmpty() || line.line().isEmpty() ? ""
                : " @" + line.className().substring(line.className().lastIndexOf('.') + 1) + ":" + line.line().getAsInt();
        boolean problem = line.level() == TraceLine.Level.WARN || line.level() == TraceLine.Level.ERROR
                || problem(line.text());
        String copy = printedCopy(line);
        if (!dropPrinted(copy)) {
            if (unpaired.size() >= MOST_UNPAIRED) unpaired.remove(unpaired.keySet().iterator().next());
            unpaired.merge(copy, 1, Integer::sum);
        }
        add((label.isEmpty() ? "" : "[" + label + "] ") + line.text() + count + at, problem, false);
    }

    /**
     * The line the bot printed for {@code line}, as shared's {@code Diag} prints it: {@code [Source] text}, the
     * count after two spaces, and of a text carrying a stack trace its first line only — the stack is printed
     * apart, line by line, and stays.
     */
    private static String printedCopy(TraceLine line) {
        String text = line.text().lines().findFirst().orElse("");
        String printed = line.source().isEmpty() ? text : "[" + line.source() + "] " + text;
        return line.count() > 1 ? printed + "  (×" + line.count() + ")" : printed;
    }

    /** Takes out the latest printed line reading {@code copy}, among the last few; whether there was one. */
    private boolean dropPrinted(String copy) {
        Iterator<Line> back = lines.descendingIterator();
        for (int i = 0; i < PAIR_REACH && back.hasNext(); i++) {
            Line line = back.next();
            if (line.printed() && line.text().equals(copy)) {
                back.remove();
                return true;
            }
        }
        return false;
    }

    /** Ends the output line in progress: kept, unless it is the printed copy of a trace line already kept. */
    private void flushPrinted() {
        String text = partial.toString();
        partial.setLength(0);
        Integer waiting = unpaired.get(text);
        if (waiting != null) {
            if (waiting == 1) unpaired.remove(text);
            else unpaired.put(text, waiting - 1);
            return;
        }
        add(text, problem(text), true);
    }

    /** A line of Studio's own, ending any output line it interrupts. Kept by a filter for failures too. */
    private void mark(String text) {
        if (!partial.isEmpty()) flushPrinted();
        add(text, true, false);
    }

    private void add(String text, boolean problem, boolean printed) {
        lines.addLast(new Line(nextSeq++, text, problem, printed));
        while (lines.size() > LIMIT) lines.removeFirst();
    }

    private static boolean problem(CharSequence text) {
        return PROBLEM.matcher(text).find();
    }

    private static String seconds(Instant from, Instant to) {
        long s = from == null ? 0 : Math.max(0, Duration.between(from, to).toSeconds());
        return s + "s";
    }
}
