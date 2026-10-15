package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.plugin.api.assist.Key;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.botmaker.studio.assist.FileTools.FILE;
import static com.botmaker.studio.assist.McpTools.given;
import static com.botmaker.studio.assist.McpTools.said;

/** The tools that run the bot, one activity or one statement, and read what they printed ({@link RunLog}). */
final class RunTools {

    private RunTools() {}

    /**
     * The longest a run tool waits for its run's end: below the 60 s an MCP client commonly gives a call (the
     * TypeScript SDK's default), the settling of the output included.
     */
    private static final int MAX_WAIT_SECONDS = 50;
    /** The latest run's last lines a waited run answers with. */
    private static final int WAIT_TRACE_LINES = 40;

    private static final String WAIT = "seconds to wait for it to end, up to " + MAX_WAIT_SECONDS + "; the answer "
            + "then holds its trace. 0 or not given: answer once it starts";

    record Run(@Describe(value = WAIT, optional = true) @Key("wait") Integer seconds) {
    }

    record Activity(@Describe(StudioTools.TARGET) String target,
                    @Describe(value = WAIT, optional = true) @Key("wait") Integer seconds) {
    }

    record Statement(@Describe(value = FILE, optional = true) String file,
                     @Describe("the statement id, from read_tree") String blockId,
                     @Describe(value = "earlier locals the statement reads, by name, each a value of its type as "
                             + "a slot takes it", optional = true) Map<String, String> values,
                     @Describe(value = WAIT, optional = true) @Key("wait") Integer seconds) {
    }

    record Trace(@Describe(value = "how many lines, 50 when not given", optional = true) Integer lines,
                 @Describe(value = "only the latest run's lines; true when not given", optional = true)
                 Boolean this_run,
                 @Describe(value = "only warnings, errors, exceptions and the run markers", optional = true)
                 Boolean errors_only) {
    }

    static List<AssistantTool<?>> of(StudioDriver driver, RunLog log) {
        return List.of(
                said("run_bot", "Run the whole bot, as the toolbar's ▶ Run does. It acts on the game.",
                        Run.class, call -> started(log, call.seconds(), driver::runBot)),
                said("run_activity",
                        "Run one target's method on its own, without the rest of the bot. It acts on the game.",
                        Activity.class, call -> started(log, call.seconds(), () -> driver.runActivity(call.target()))),
                said("try_statement",
                        "Run one statement on its own, with the earlier values it reads: from the last run, computed "
                                + "when that only looks at the screen, or given in values. It acts on the game.",
                        Statement.class, call -> started(log, call.seconds(), () -> driver.tryStatement(given(call.file()),
                                call.blockId(), call.values() == null ? Map.of() : call.values()))),
                said("stop", "Stop the run, the try or the debug session.", driver::stop),
                said("quit_studio", "Close BotMaker Studio; through the botmaker mcp-bridge, start_studio opens it "
                        + "again.", driver::quit),
                said("run_state", "Say whether something runs now, and for how long.", log::state),
                said("read_trace",
                        "Read the last lines the bot printed and traced, oldest first, with markers where a run "
                                + "started and stopped.",
                        Trace.class, call -> log.read(new RunLog.Read(call.lines() == null ? 50 : call.lines(),
                                call.this_run() == null || call.this_run(), Boolean.TRUE.equals(call.errors_only())))));
    }

    /** Runs {@code start} only while nothing is on the game, else refuses saying what is. */
    static String idle(RunLog log, Supplier<String> start) {
        if (log.busy()) throw new IllegalArgumentException(log.state() + " Stop it first (stop), or wait for it.");
        return start.get();
    }

    /**
     * {@link #idle}, then, given {@code wait}, the run's end and what it printed — one call where an assistant
     * would otherwise poll {@code run_state} and then {@code read_trace}.
     */
    private static String started(RunLog log, Integer seconds, Supplier<String> start) {
        int wait = seconds == null ? 0 : Math.min(Math.max(seconds, 0), MAX_WAIT_SECONDS);
        RunLog.Mark before = log.mark();
        String answer = idle(log, start);
        if (wait == 0) return answer;
        try {
            return answer + "\n" + switch (log.awaitEnd(before, Duration.ofSeconds(wait))) {
                case ENDED -> log.state() + "\n" + log.read(new RunLog.Read(WAIT_TRACE_LINES, true, false));
                // The latest run's lines would be an earlier run's: the last lines are what the compile said.
                case NEVER_STARTED -> "It did not start: the compile failed or the start was refused. The last "
                        + "lines:\n" + log.read(new RunLog.Read(WAIT_TRACE_LINES / 2, false, false));
                case TIMED_OUT -> "Still going after " + wait + "s: " + log.state()
                        + " stop ends it; read_trace reads on.\n"
                        + log.read(new RunLog.Read(WAIT_TRACE_LINES, true, false));
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return answer + "\nThe wait was interrupted. " + log.state();
        }
    }
}
