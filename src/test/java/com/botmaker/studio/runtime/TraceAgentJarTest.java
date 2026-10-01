package com.botmaker.studio.runtime;

import com.botmaker.shared.Diag;
import com.botmaker.studio.runtime.agent.BotFixture;
import com.botmaker.studio.runtime.agent.TraceAgent;
import com.botmaker.studio.runtime.agent.TracePlan;
import com.botmaker.studio.runtime.agent.TracedFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent as a bot meets it: a jar Studio writes, on a JVM's command line. The jar must hold every class of the
 * agent's package, since the bot's classpath has none of Studio, and the package may name nothing but the JDK and
 * {@code Diag}, for the same reason.
 */
class TraceAgentJarTest {

    private static final Path AGENT_SOURCES = Path.of("src/main/java/com/botmaker/studio/runtime/agent");

    @Test
    void theJarListsEveryClassOfTheAgentPackage() throws URISyntaxException, IOException {
        Path classes = location(TraceAgent.class).resolve("com/botmaker/studio/runtime/agent");
        Set<String> compiled;
        try (Stream<Path> files = Files.list(classes)) {
            compiled = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".class"))
                    .map(n -> "com.botmaker.studio.runtime.agent." + n.substring(0, n.length() - ".class".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(compiled, new TreeSet<>(TraceAgentJar.CLASSES));
    }

    @Test
    void theAgentNamesOnlyTheJdkAndDiag() throws IOException {
        try (Stream<Path> files = Files.list(AGENT_SOURCES)) {
            for (Path file : files.toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.startsWith("import ")) continue;
                    String named = line.substring("import ".length()).replace("static ", "").replace(";", "").strip();
                    assertTrue(named.startsWith("java.") || named.equals(Diag.class.getName()),
                            file.getFileName() + " imports " + named);
                }
            }
        }
    }

    @Test
    void aBotRunWithTheJarTracesItsCalls(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("agent.jar");
        TraceAgentJar.write(jar);
        Path bot = location(BotFixture.class);
        Path plan = dir.resolve("plan.properties");
        new TracePlan(Set.of(bot), Set.of(TracedFixture.class.getName()), Set.of()).write(plan);
        // The bot's classpath: its classes and botmaker-shared, never Studio's own classes.
        String classpath = bot + File.pathSeparator + location(Diag.class);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(List.of(java.toString(), "-javaagent:" + jar + "=" + plan,
                "-cp", classpath, BotFixture.class.getName()))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("[TracedFixture] add(1, 2) → 3"), output);
        assertTrue(output.contains("[TracedFixture] fail() threw IllegalStateException: boom"), output);
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
