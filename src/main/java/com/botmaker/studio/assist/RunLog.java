package com.botmaker.studio.assist;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.CoreApplicationEvents.DebugSessionFinishedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.DebugSessionStartedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ExecutionRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.OutputAppendedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.OutputClearedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ProgramStartedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ProgramStoppedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TraceLineEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TrialRunRequestedEvent;
import com.botmaker.studio.events.EventBus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * What the bot printed and traced, and whether anything runs now — the Run and Trace tabs as an assistant reads
 * them ({@code read_trace}, {@code run_state}), kept off the FX thread from the events those tabs show.
 *
 * <p>Kept from when the endpoint starts: a run before that is not in it. The last {@link #LIMIT} lines are kept;
 * a new run marks where it began rather than clearing, so an assistant that reads after a quick run still sees
 * the one before.
 */
public final class RunLog implements AutoCloseable {

    static final int LIMIT = 2000;
    private static final int READ_LIMIT = 400;

    private final Clock clock;
    private final BooleanSupplier launching;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private final Deque<String> lines = new ArrayDeque<>();
    /** Output not yet ended by a newline. */
    private final StringBuilder partial = new StringBuilder();

    /** What the next run that starts is: the bot, unless a try asked for it. */
    private String requested;
    /** What runs now; null when nothing does. */
    private String running;
    private Instant since;
    private Instant ended;
    private boolean debugging;

    /**
     * @param launching whether a run has been asked for and has not ended, its compile included —
     *                  {@code CodeExecutionService::isRunning}. The events say only when the bot itself starts,
     *                  and a run asked for again during the compile would be a second bot on the game.
     */
    public RunLog(EventBus bus, Clock clock, BooleanSupplier launching) {
        this.clock = clock;
        this.launching = launching;
        subscriptions.add(bus.subscribe(ExecutionRequestedEvent.class, e -> requested("the bot")));
        subscriptions.add(bus.subscribe(TrialRunRequestedEvent.class, e -> requested(e.label())));
        subscriptions.add(bus.subscribe(ProgramStartedEvent.class, e -> started()));
        subscriptions.add(bus.subscribe(ProgramStoppedEvent.class, e -> stopped()));
        subscriptions.add(bus.subscribe(DebugSessionStartedEvent.class, e -> debugging(true)));
        subscriptions.add(bus.subscribe(DebugSessionFinishedEvent.class, e -> debugging(false)));
        subscriptions.add(bus.subscribe(OutputAppendedEvent.class, e -> output(e.text())));
        subscriptions.add(bus.subscribe(OutputClearedEvent.class, e -> mark("── run output cleared ──")));
        subscriptions.add(bus.subscribe(TraceLineEvent.class, e -> trace(e.line())));
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
    public synchronized String tail(int count) {
        int wanted = Math.max(1, Math.min(count, READ_LIMIT));
        List<String> all = new ArrayList<>(lines);
        if (!partial.isEmpty()) all.add(partial.toString());
        if (all.isEmpty()) return "No output yet.";
        return String.join("\n", all.subList(Math.max(0, all.size() - wanted), all.size()));
    }

    @Override
    public void close() {
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
    }

    private synchronized void requested(String what) {
        requested = what;
    }

    private synchronized void started() {
        running = requested == null ? "the bot" : requested;
        requested = null;
        since = clock.instant();
        mark("── started " + running + " ──");
    }

    private synchronized void stopped() {
        if (running != null) mark("── stopped " + running + " after " + seconds(since, clock.instant()) + " ──");
        running = null;
        ended = clock.instant();
    }

    private synchronized void debugging(boolean on) {
        debugging = on;
        if (!on) ended = clock.instant();
    }

    private synchronized void output(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                add(partial.toString());
                partial.setLength(0);
            } else if (c != '\r') {
                partial.append(c);
            }
        }
    }

    private synchronized void trace(TraceLine line) {
        String source = line.source().isEmpty() ? "" : " " + line.source();
        String count = line.count() > 1 ? " (×" + line.count() + ")" : "";
        String at = line.className().isEmpty() || line.line().isEmpty() ? ""
                : " @" + line.className().substring(line.className().lastIndexOf('.') + 1) + ":" + line.line().getAsInt();
        add("[" + line.level().displayName() + source + "] " + line.text() + count + at);
    }

    /** A line of Studio's own, ending any output line it interrupts. */
    private void mark(String text) {
        if (!partial.isEmpty()) {
            add(partial.toString());
            partial.setLength(0);
        }
        add(text);
    }

    private void add(String line) {
        lines.addLast(line);
        while (lines.size() > LIMIT) lines.removeFirst();
    }

    private static String seconds(Instant from, Instant to) {
        long s = from == null ? 0 : Math.max(0, Duration.between(from, to).toSeconds());
        return s + "s";
    }
}
