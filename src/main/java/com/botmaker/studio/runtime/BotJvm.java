package com.botmaker.studio.runtime;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.StudioProjectSettings;
import com.botmaker.runlog.RunLog;
import com.botmaker.studio.runtime.agent.RunPlan;
import com.botmaker.studio.runtime.agent.TracePlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The options every JVM Studio starts a bot in takes, in one place because there are two such JVMs — the
 * run ({@link CodeExecutionService}) and the debug ({@code services.DebuggingService}) — and a bot that
 * warns under one and not the other is a difference nobody intended.
 *
 * <p>These are not Studio's own options: Studio's are in {@code pom.xml} (the {@code javafx:run} plugin and
 * jpackage) and in its jar manifest. A bot runs in a JVM of its own, launched from Java, so its command
 * line is built here.
 */
public final class BotJvm {

    /**
     * <b>{@code --enable-native-access=ALL-UNNAMED}</b>: the SDK loads OpenCV's natives through
     * {@code System.loadLibrary} from the classpath (an unnamed module), which JDK 24 warns about by name
     * and a later release will refuse outright. The bot did nothing wrong and the warning is the first
     * thing its author sees in the output pane, so it is granted rather than explained.
     */
    public static final List<String> OPTIONS = List.of("--enable-native-access=ALL-UNNAMED");

    /**
     * {@link #OPTIONS}, then {@code -D<name>=<value>} for each of this checkout's run properties
     * ({@code StudioProjectSettings.runProperties}, set by a plugin through {@code Runs.setProperty}) — what this
     * machine starts the bot with, such as the SDK's {@code botmaker.launch.target} (2026-09-27). One argument
     * per property, so a value with spaces reaches the bot whole; a blank name is skipped.
     */
    public static List<String> options(StudioProjectSettings settings) {
        return options(settings == null ? Map.of() : settings.runProperties());
    }

    /**
     * {@code -javaagent:<jar>=<plan>}: the run agent ({@code runtime.agent}, one since 2026-11-03), the host in the
     * bot's JVM. It keeps the run's log under {@code <project>/target/botmaker-runs}, hands each plugin declared on
     * the bot's classpath its run channel, and traces each call the bot's own classes
     * ({@code config.compiledOutputPath()}) make into an offered class of the project's plugins, minus what the
     * Trace tab hides ({@code hiddenTraceWriters}). The plan is a file beside the classes, rewritten every run.
     * Empty only when the agent cannot be written: a run never fails over it.
     */
    public static List<String> runAgent(ProjectConfig config, StudioProjectSettings settings) {
        if (config == null) return List.of();
        List<String> offered = PluginHost.menuFacades().stream().map(f -> f.type().getName()).toList();
        try {
            RunAgentJar.Jars jars = RunAgentJar.jars();
            Path plan = runPlan(config);
            TracePlan trace = new TracePlan(Set.of(config.compiledOutputPath()), Set.copyOf(offered),
                    settings == null ? Set.of() : Set.copyOf(settings.hiddenTraceWriters()));
            new RunPlan(trace, Optional.of(config.projectPath().resolve(RunLog.FOLDER)), jars.inner())
                    .write(plan);
            return List.of("-javaagent:" + jars.agent() + "=" + plan);
        } catch (IOException | RuntimeException e) {
            System.err.println("Runs this time without its log and call trace: " + e.getMessage());
            return List.of();
        }
    }

    /** Where {@link #runAgent} writes the plan the agent reads once, at the JVM's start. */
    static Path runPlan(ProjectConfig config) {
        return config.compiledOutputPath().resolveSibling("botmaker-run.properties");
    }

    static List<String> options(Map<String, String> runProperties) {
        List<String> out = new ArrayList<>(OPTIONS);
        if (runProperties != null) {
            runProperties.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null) out.add("-D" + name.trim() + "=" + value);
            });
        }
        return List.copyOf(out);
    }

    private BotJvm() {
    }
}
