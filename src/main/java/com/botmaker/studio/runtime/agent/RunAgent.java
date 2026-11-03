package com.botmaker.studio.runtime.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/**
 * The one agent Studio starts every bot with ({@code -javaagent:<jar>=<plan file>}): the host inside the bot's JVM
 * (spec {@code docs/superpowers/specs/2026-11-03-run-reports-design.md} §7, plan phase 3c). Before the bot's
 * {@code main} it reads the plan, starts the run's inner half (the log, the channel each declared plugin gets, the
 * wire to Studio) and installs the call trace ({@code docs/refactor/40-run-trace.md}).
 *
 * <p><b>Two halves.</b> This package runs on the bot's class path, which every bot class links to through
 * {@link TracedCalls}, so it names only the JDK ({@code RunAgentJarTest}). The inner half,
 * {@code runtime.agent.run}, names the wire, runlog, Jackson and plugin-host; Studio writes it to a jar of its own
 * with those packages ({@code runtime.RunAgentJar}), loaded child first ({@link InnerLoader}), so the bot's own
 * versions of them never meet Studio's.
 *
 * <p>A run never fails over the agent: a part that cannot start says so on standard error, and the bot runs
 * without it.
 */
public final class RunAgent {

    private static final String INNER = "com.botmaker.studio.runtime.agent.run.AgentRun";

    private RunAgent() {}

    /** The agent's entry point: reads the plan Studio wrote, starts the run's host, and traces calls. */
    public static void premain(String planFile, Instrumentation instrumentation) {
        if (planFile == null || planFile.isBlank()) return;
        RunPlan plan;
        try {
            plan = RunPlan.read(Path.of(planFile));
        } catch (IOException | RuntimeException e) {
            System.err.println("[BotMaker] this run has no log and no trace: " + e.getMessage());
            return;
        }
        RunLines lines = plan.inner().map(jar -> inner(jar, plan)).orElse(null);
        TracedCalls.to(lines);
        if (!plan.trace().traced().isEmpty()) instrumentation.addTransformer(new CallSites(plan.trace()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TracedCalls.reportOpen();
            if (lines != null) lines.ended();
        }, "botmaker-run-end"));
    }

    /** Starts the inner half from {@code jar}; null when it cannot, and the run keeps only the trace on stderr. */
    private static RunLines inner(Path jar, RunPlan plan) {
        try {
            InnerLoader loader = new InnerLoader(jar.toUri().toURL(), RunAgent.class.getClassLoader());
            return (RunLines) Class.forName(INNER, true, loader).getMethod("start", RunPlan.class)
                    .invoke(null, plan);
        } catch (InvocationTargetException e) {
            System.err.println("[BotMaker] this run keeps no log: " + e.getCause());
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            System.err.println("[BotMaker] this run keeps no log: " + e);
        }
        return null;
    }
}
