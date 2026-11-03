package com.botmaker.studio.runtime.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * What the run agent does in one run, read from the file Studio names on the agent's command line: the calls it
 * traces ({@link TracePlan}), where the bot's runs keep their folders, and the agent's inner jar.
 *
 * @param trace the calls traced; nothing traced when its {@code traced} is empty
 * @param runs  the folder each run's own folder is made under ({@code <bot>/target/botmaker-runs}); empty when no
 *              log is kept
 * @param inner the agent's inner jar ({@code runtime.agent.run}, with the wire, the log writer, Jackson and the
 *              plugin-pom reader); empty when Studio could not write it, and the run gets the trace only
 */
public record RunPlan(TracePlan trace, Optional<Path> runs, Optional<Path> inner) {

    private static final String RUNS = "runs";
    private static final String INNER = "inner";

    public void write(Path file) throws IOException {
        Properties properties = new Properties();
        trace.into(properties);
        runs.ifPresent(p -> properties.setProperty(RUNS, p.toAbsolutePath().toString()));
        inner.ifPresent(p -> properties.setProperty(INNER, p.toAbsolutePath().toString()));
        TracePlan.store(properties, file);
    }

    public static RunPlan read(Path file) throws IOException {
        Properties properties = TracePlan.load(file);
        return new RunPlan(TracePlan.from(properties), path(properties, RUNS), path(properties, INNER));
    }

    private static Optional<Path> path(Properties properties, String key) {
        String value = properties.getProperty(key, "").strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(Path.of(value));
    }
}
