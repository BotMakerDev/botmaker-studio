package com.botmaker.studio.runtime.agent.run;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;
import com.botmaker.studio.runtime.agent.TracePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the run's log keeps (spec §2): the calls before a problem whatever the switch, every line with it on. */
class AgentRunTest {

    @Test
    void aQuietRunWritesItsLastFiftyLinesBeforeAProblem(@TempDir Path bot) {
        AgentRun run = run(bot, false);
        IntStream.rangeClosed(1, 60).forEach(i -> run.accept(line(Level.DEBUG, "d" + i), null, false));
        run.accept(line(Level.WARN, "stuck"), null, false);
        run.ended();

        List<String> texts = texts(bot);
        assertEquals(51, texts.size(), texts.toString());
        assertEquals("d11", texts.getFirst());
        assertEquals("stuck", texts.getLast());
    }

    @Test
    void aQuietRunWithNoProblemKeepsOnlyItsFacts(@TempDir Path bot) {
        AgentRun run = run(bot, false);
        run.accept(line(Level.DEBUG, "d"), null, false);
        run.details("com.example:p", Map.of("flow", "Farm"));
        run.ended();

        RunLogs.Readable log = read(bot);
        assertTrue(log.lines().isEmpty(), log.lines().toString());
        assertEquals("Farm", log.facts().details().get("flow"));
    }

    @Test
    void withDebuggingOnEveryLineIsWritten(@TempDir Path bot) {
        AgentRun run = run(bot, true);
        run.accept(line(Level.DEBUG, "a"), null, false);
        run.accept(line(Level.INFO, "b"), null, false);
        run.ended();

        assertEquals(List.of("a", "b"), texts(bot));
    }

    @Test
    void turningDebuggingOnWritesWhatWasHeld(@TempDir Path bot) {
        AgentRun run = run(bot, false);
        run.accept(line(Level.DEBUG, "held"), null, false);
        run.debug(true);
        run.accept(line(Level.DEBUG, "after"), null, false);
        run.ended();

        assertEquals(List.of("held", "after"), texts(bot));
    }

    @Test
    void aPluginsLineKeepsItsValuesInTheLog(@TempDir Path bot) {
        AgentRun run = run(bot, false);
        new PluginChannel(run, "com.example:p").line(new TraceLine(Instant.ofEpochMilli(1), TraceLine.Level.WARN,
                "Screens", "stuck", 1, "x.Screens", "goTo", "com.example.Collect", OptionalInt.of(57),
                Optional.empty(), Map.of("screen", "Title")), null);
        run.ended();

        LogLine kept = read(bot).lines().getFirst();
        assertEquals(Map.of("screen", "Title"), kept.values());
        assertEquals("Title", read(bot).report().occurrences().getFirst().value("plugin.screen"));
    }

    @Test
    void aRunWithNoFolderKeepsNothingAndRecordsNothingQuiet() {
        AgentRun run = new AgentRun(TracePlan.of(Set.of(), Set.of()), null, Path.of("."), null, false, 0);
        assertFalse(run.recorded());
        assertTrue(run.folder().isEmpty());
        run.accept(line(Level.ERROR, "lost"), null, false);
        run.ended();
    }

    private static AgentRun run(Path bot, boolean debug) {
        Path folder = bot.resolve(RunLog.FOLDER).resolve("run");
        return new AgentRun(TracePlan.of(Set.of(), Set.of()), folder, bot, null, debug, 0);
    }

    private static LogLine line(Level level, String text) {
        return new LogLine(1, level, "Test", text, 1, null, "", "", "", -1);
    }

    private static RunLogs.Readable read(Path bot) {
        Path file = bot.resolve(RunLog.FOLDER).resolve("run").resolve(RunLog.FILE);
        assertTrue(Files.isRegularFile(file));
        return assertInstanceOf(RunLogs.Readable.class, RunLogs.read(file));
    }

    private static List<String> texts(Path bot) {
        return read(bot).lines().stream().map(LogLine::text).toList();
    }

    @Test
    void factsComeFromTheBotFolder(@TempDir Path bot) throws IOException {
        Files.writeString(bot.resolve("bot.json"), "{\"tag\":\"v1.2.0\",\"commit\":\"abc123\",\"libs\":[]}");
        Files.writeString(bot.resolve("pom.xml"), """
                <project><parent><groupId>com.github.BotMakerDev</groupId>
                <artifactId>botmaker-plugin-parent</artifactId></parent>
                <groupId>com.example</groupId><artifactId>farm-bot</artifactId></project>""");
        assertEquals(Map.of("tag", "v1.2.0", "commit", "abc123"), BotFacts.source(bot));
        assertEquals("com.example:farm-bot", BotFacts.coordinate(bot), "the bot's coordinate, not its parent's");
    }

    @Test
    void aFolderWithNeitherBotJsonNorGitSaysNothing(@TempDir Path bot) {
        assertTrue(BotFacts.source(bot).isEmpty());
    }
}
