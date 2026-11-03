package com.botmaker.studio.runtime;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.plugin.api.run.RunChannel;
import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.api.LogLine;
import com.botmaker.shared.Diag;
import com.botmaker.studio.runtime.agent.BotFixture;
import com.botmaker.studio.runtime.agent.RunPlan;
import com.botmaker.studio.runtime.agent.TracePlan;
import com.botmaker.studio.runtime.agent.TracedFixture;
import com.botmaker.plugin.api.DeclaredPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run agent as a bot meets it: two jars Studio writes, on a JVM's command line. The agent's own jar is the
 * bootstrap package alone, which names only the JDK, since the bot's classpath has none of Studio; everything else
 * (the log, the wire, plugin discovery) is in the inner jar, loaded child-first so the bot's versions never meet it.
 */
class RunAgentJarTest {

    private static final Path AGENT_SOURCES = Path.of("src/main/java/com/botmaker/studio/runtime/agent");
    private static final List<String> INNER_NAMES = List.of("java.", "com.botmaker.runlog.", "com.botmaker.shared.ipc.",
            "com.fasterxml.jackson.", "com.botmaker.plugin.host.", "com.botmaker.plugin.api.",
            "com.botmaker.studio.runtime.agent.");

    /** A plugin whose bot half says one line and one fact through its run channel. */
    public static final class StubPlugin extends DeclaredPlugin {
        public StubPlugin() {
            super(StudioPlugin.declare().onBotRun(StubPlugin::started));
        }

        static void started(RunChannel run) {
            // Details first: given after the log opened (as the warning opens it) they would be a line, not facts.
            run.details(Map.of("flow", "F"));
            run.line(new TraceLine(Instant.now(), TraceLine.Level.WARN, "Stub", "stub says hi", 1, "", "", "",
                    OptionalInt.empty(), Optional.empty()), null);
        }
    }

    @Test
    void theAgentJarIsTheBootstrapPackageAlone(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("agent.jar");
        RunAgentJar.writeAgent(jar);
        List<String> classes = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            file.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).forEach(classes::add);
        }
        assertTrue(classes.contains(RunAgentJar.AGENT + "RunAgent.class"), classes.toString());
        assertTrue(classes.stream().allMatch(n -> n.startsWith(RunAgentJar.AGENT)
                && n.indexOf('/', RunAgentJar.AGENT.length()) < 0), classes.toString());
    }

    @Test
    void theBootstrapNamesOnlyTheJdkAndTheInnerHalfOnlyWhatItsJarHolds() throws IOException {
        try (Stream<Path> files = Files.list(AGENT_SOURCES)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                imports(file).forEach(named -> assertTrue(named.startsWith("java."),
                        file.getFileName() + " imports " + named));
            }
        }
        try (Stream<Path> files = Files.list(AGENT_SOURCES.resolve("run"))) {
            for (Path file : files.toList()) {
                imports(file).forEach(named -> assertTrue(INNER_NAMES.stream().anyMatch(named::startsWith),
                        file.getFileName() + " imports " + named));
            }
        }
    }

    @Test
    void aBotRunTracesItsCallsAndHandsEachPluginItsChannel(@TempDir Path dir) throws Exception {
        RunAgentJar.Jars jars = RunAgentJar.jars();
        Path tests = location(BotFixture.class);
        Path stub = stubPlugin(dir.resolve("stub"), tests);
        Path runs = dir.resolve("bot").resolve(RunLog.FOLDER);
        Path plan = dir.resolve("plan.properties");
        new RunPlan(new TracePlan(Set.of(tests), Set.of(TracedFixture.class.getName()), Set.of()),
                Optional.of(runs), jars.inner()).write(plan);
        assertTrue(jars.inner().isPresent());
        // The bot's classpath: its classes, the plugin, the contract and botmaker-shared, never Studio's own classes.
        String classpath = String.join(File.pathSeparator, tests.toString(), stub.toString(),
                location(StudioPlugin.class).toString(), location(Diag.class).toString(),
                location(RunLog.class).toString());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(List.of(java.toString(), "-javaagent:" + jars.agent() + "=" + plan,
                "-cp", classpath, BotFixture.class.getName()))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("stub says hi"), output);

        Path log;
        try (Stream<Path> folders = Files.list(runs)) {
            log = folders.findFirst().orElseThrow().resolve(RunLog.FILE);
        }
        RunLogs.Readable read = assertInstanceOf(RunLogs.Readable.class, RunLogs.read(log), output);
        List<String> texts = read.lines().stream().map(LogLine::text).toList();
        assertTrue(texts.contains("stub says hi"), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith("add(1, 2) → 3")),
                "debugging starts on, so every traced call is written: " + texts);
        assertEquals("F", read.facts().details().get("flow"));
        assertFalse(output.contains("keeps no log"), output);
    }

    /** {@code <module>/target/classes} holding the stub plugin's class, beside a pom that declares it. */
    private static Path stubPlugin(Path module, Path tests) throws IOException {
        Path classes = module.resolve("target/classes");
        String name = StubPlugin.class.getName().replace('.', '/') + ".class";
        Files.createDirectories(classes.resolve(name).getParent());
        Files.copy(tests.resolve(name), classes.resolve(name));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId><artifactId>stub</artifactId><version>1</version>
                <name>Stub</name>
                <properties><botmaker.plugin>%s</botmaker.plugin></properties></project>"""
                .formatted(StubPlugin.class.getName()));
        return classes;
    }

    private static List<String> imports(Path file) throws IOException {
        return Files.readAllLines(file).stream().filter(l -> l.startsWith("import "))
                .map(l -> l.substring("import ".length()).replace("static ", "").replace(";", "").strip())
                .toList();
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
