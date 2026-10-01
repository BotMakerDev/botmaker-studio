package com.botmaker.studio.runtime.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

/**
 * The trace agent Studio starts every bot with ({@code -javaagent:<jar>=<plan file>}, 2026-09-30): it traces each
 * call the bot makes into an offered plugin class, so a plugin writes no {@code Debug.log} for the user to see
 * what the bot did ({@code docs/refactor/40-run-trace.md}).
 *
 * <p>This package runs in the bot's JVM, not in Studio's. Studio writes it to a small jar of its own
 * ({@code runtime.TraceAgentJar}), so it may name only the JDK and {@code botmaker-shared}'s {@code Diag}, which
 * it finds on the bot's classpath or does without ({@code TraceAgentSourcesTest}).
 */
public final class TraceAgent {

    private TraceAgent() {}

    /** The agent's entry point: reads the plan Studio wrote, and rewrites the bot's classes as they load. */
    public static void premain(String planFile, Instrumentation instrumentation) {
        if (planFile == null || planFile.isBlank()) return;
        TracePlan plan;
        try {
            plan = TracePlan.read(Path.of(planFile));
        } catch (IOException | RuntimeException e) {
            System.err.println("[Trace] no trace this run: " + e.getMessage());
            return;
        }
        if (plan.traced().isEmpty()) return;
        instrumentation.addTransformer(new CallSites(plan));
        Runtime.getRuntime().addShutdownHook(new Thread(TracedCalls::reportOpen, "trace-report"));
    }
}
