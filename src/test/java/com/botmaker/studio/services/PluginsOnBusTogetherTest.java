// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.services;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.plugin.HostHistory;
import com.botmaker.core.plugin.HostPluginValues;
import com.botmaker.core.plugin.HostServices;
import com.botmaker.core.plugin.PluginWrite;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreMain;
import com.botmaker.core.server.CoreServer;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.plugin.api.History;
import com.botmaker.studio.core.EditorState;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin of Studio's together (2026-11-21): its writes are queued in Studio and sent to the core in one request, never
 * one forwarded write each, which the core makes as one step; a together that throws sends nothing, and a read inside
 * one sees the bot as it was before it.
 */
class PluginsOnBusTogetherTest extends FxHeadlessTest {

    private static final String VERSION = "1.0";
    private static final String FILE = "com/mybot/Mybot.java";
    private static final String BOT = "package com.mybot;\npublic class Mybot {\n}\n";

    @Override
    public void start(Stage stage) {
        // No scene: FxThread only needs Platform.runLater to have somewhere to run.
    }

    @AfterEach
    void noProject() {
        HostPluginValues.clear();
        HostHistory.clear();
    }

    @Test
    void aTogetherIsOneRequest(@TempDir Path cache, @TempDir Path projects) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 63; i++) names.add("com/mybot/C" + i + ".java");
        withCore(cache, projects, names, (studio, files) -> {
            Optional<String> refused = studio.history.together("Tidy", () -> {
                for (Path file : files) rewrite(file, "x = 1", "x = 2");
            });
            assertTrue(refused.isEmpty(), refused.toString());
            assertEquals(0, studio.forwarded.get(), "no write went on its own");
            assertEquals(1, studio.wrote.get(), "one request, drawn once");
            for (Path file : files) assertTrue(Files.readString(file).contains("x = 2"), file.toString());
            assertEquals("Tidy", studio.history.undoLabel());
            studio.history.undo();
            for (Path file : files) assertTrue(Files.readString(file).contains("x = 1"), "one ↶ takes all back");
            assertFalse(studio.history.canUndo());
        });
    }

    @Test
    void aWriteThatThrowsSendsNothing(@TempDir Path cache, @TempDir Path projects) throws Exception {
        withCore(cache, projects, List.of("com/mybot/A.java"), (studio, files) -> {
            Path a = files.getFirst();
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> studio.history.together("Half", () -> {
                        rewrite(a, "x = 1", "x = 2");
                        throw new IllegalStateException("the plugin failed");
                    }));
            assertEquals("the plugin failed", thrown.getMessage());
            assertTrue(Files.readString(a).contains("x = 1"), "nothing was written");
            assertEquals(0, studio.forwarded.get() + studio.wrote.get(), "nothing was sent");
            assertFalse(studio.history.canUndo());
        });
    }

    @Test
    void aReadInsideSeesTheBotAsBefore(@TempDir Path cache, @TempDir Path projects) throws Exception {
        withCore(cache, projects, List.of("com/mybot/A.java"), (studio, files) -> {
            Path a = files.getFirst();
            List<String> read = new ArrayList<>();
            studio.history.together("Read inside", () -> {
                rewrite(a, "x = 1", "x = 2");
                read.add(text(a));
            });
            assertTrue(read.getFirst().contains("x = 1"), "inside, the write is not made yet");
            assertTrue(Files.readString(a).contains("x = 2"), "after, it is");
        });
    }

    @Test
    void aTogetherInsideATogetherIsOneRequest(@TempDir Path cache, @TempDir Path projects) throws Exception {
        withCore(cache, projects, List.of("com/mybot/A.java", "com/mybot/B.java"), (studio, files) -> {
            Optional<String> refused = studio.history.together("Outer", () -> {
                rewrite(files.get(0), "x = 1", "x = 2");
                assertTrue(studio.history.together("Inner", () -> rewrite(files.get(1), "x = 1", "x = 3")).isEmpty());
            });
            assertTrue(refused.isEmpty(), refused.toString());
            assertEquals(0, studio.forwarded.get());
            assertEquals(1, studio.wrote.get());
            assertTrue(Files.readString(files.get(1)).contains("x = 3"));
            assertEquals("Outer", studio.history.undoLabel());
        });
    }

    @Test
    void aRefusedTogetherLeavesTheBotAsItWas(@TempDir Path cache, @TempDir Path projects) throws Exception {
        withCore(cache, projects, List.of("com/mybot/A.java"), (studio, files) -> {
            Path a = files.getFirst();
            PluginWrite changeA = new PluginWrite.ApplyPlan("Change a",
                    Map.of(a.toString(), text(a).replace("x = 1", "x = 2")), Map.of());
            PluginWrite gone = new PluginWrite.SetValue("com.example:none:GONE", null);
            Optional<String> refused = studio.edits.together("Half", List.of(changeA, gone));
            assertTrue(refused.isPresent(), "the second write is refused");
            assertTrue(Files.readString(a).contains("x = 1"), "the first is taken back");
            assertFalse(studio.history.canUndo(), "and nothing is recorded");
        });
    }

    /** What a test sees of Studio's plugins: their history, the writes forwarded alone, the writes Studio drew. */
    private record Studio(History history, CoreEdits edits, AtomicInteger forwarded, AtomicInteger wrote) {
    }

    private interface Body {
        void run(Studio studio, List<Path> files) throws Exception;
    }

    /** A project of {@code names}, each {@code class Cn { int x = 1; }}, open in a core and in Studio, as BotProject wires them. */
    private static void withCore(Path cache, Path projects, List<String> names, Body body) throws Exception {
        ProjectConfig config = ProjectConfig.forDirectory(projects.resolve("mybot"));
        Path bot = write(config, FILE, BOT);
        List<Path> files = new ArrayList<>();
        for (String name : names) {
            String type = name.substring(name.lastIndexOf('/') + 1, name.length() - ".java".length());
            files.add(write(config, name, "package com.mybot;\nclass " + type + " { int x = 1; }\n"));
        }
        EditorState state = new EditorState();
        state.setSourcePath(config.sourceRoot());
        state.addFile(new ProjectFile(bot, Files.readString(bot)));
        for (Path file : files) state.addFile(new ProjectFile(file, Files.readString(file)));
        state.setActiveFile(bot);

        EventBus bus = new EventBus();
        AtomicInteger wrote = new AtomicInteger();
        AtomicInteger forwarded = new AtomicInteger();
        bus.subscribe(CoreApplicationEvents.PluginWroteEvent.class, e -> wrote.incrementAndGet(), false);
        CoreServer.Idle idle = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
        try (CoreMain.Running core = CoreMain.serve(cache, VERSION, idle, CoreRunsTest.LIBRARIES, () -> { });
             CoreEdits edits = CoreEdits.open(cache, VERSION, CoreLauncher.process(), config, state, bus,
                     EventBus.UiThread.INLINE)) {
            edits.heardBy(event -> {
                if (event instanceof CoreEvent.HistoryChanged changed) HostHistory.told(changed);
            });
            edits.show(FILE);
            HistoryOnBus.attach(bus, edits);
            HostPluginValues.installForwarding(config, state, HostServices.forProject(config), write -> {
                forwarded.incrementAndGet();
                return edits.pluginWrite(write);
            });
            PluginsOnBus.history(bus, edits);
            body.run(new Studio(HostHistory.live(), edits, forwarded, wrote), files);
        }
    }

    /** A plugin's write of {@code file}'s text, as a rename's plan writes it, built from the file as it is on disk. */
    private static void rewrite(Path file, String from, String to) {
        String text = text(file);
        assertTrue(HostPluginValues.applyRename(new Refactor.Planned("Change " + from,
                Map.of(file, text.replace(from, to)), Map.of())).isEmpty());
    }

    private static String text(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Path write(ProjectConfig config, String name, String source) throws IOException {
        Path file = config.sourceRoot().resolve(name).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
