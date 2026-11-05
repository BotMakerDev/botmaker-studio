package com.botmaker.studio.services;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.plugin.HostHistory;
import com.botmaker.core.plugin.HostPluginValues;
import com.botmaker.core.plugin.HostServices;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin of Studio's writes through the core (plan phase 10c): Studio's copy forwards each write over the channel, the
 * core makes it and records it, a batch is one step of the core's history under the plugin's label, and ↶/↷ through the
 * contract put the files back as they were and as they became, Studio's mirror following.
 */
class CorePluginsTest extends FxHeadlessTest {

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
    void aPluginsBatchIsOneStepOfTheCoresHistory(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = projects.resolve("mybot");
        ProjectConfig config = ProjectConfig.forDirectory(project);
        Path bot = write(config, FILE, BOT);
        Path a = write(config, "com/mybot/A.java", "package com.mybot;\nclass A { int x = 1; }\n");
        Path b = write(config, "com/mybot/B.java", "package com.mybot;\nclass B { int y = 1; }\n");
        EditorState state = new EditorState();
        state.setSourcePath(config.sourceRoot());
        for (Path file : new Path[]{bot, a, b}) state.addFile(new ProjectFile(file, Files.readString(file)));
        state.setActiveFile(bot);

        EventBus bus = new EventBus();
        AtomicInteger wrote = new AtomicInteger();
        bus.subscribe(CoreApplicationEvents.PluginWroteEvent.class, e -> wrote.incrementAndGet(), false);
        CoreServer.Idle idle = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
        try (CoreMain.Running core = CoreMain.serve(cache, VERSION, idle, CoreRunsTest.LIBRARIES, () -> { })) {
            try (CoreEdits edits = CoreEdits.open(cache, VERSION, CoreLauncher.process(), config, state, bus,
                    EventBus.UiThread.INLINE)) {
                edits.heardBy(event -> {
                    if (event instanceof CoreEvent.HistoryChanged changed) HostHistory.told(changed);
                });
                edits.show(FILE);
                HistoryOnBus.attach(bus, edits);
                // As BotProject installs them: this copy of the plugins forwards, the core writes.
                HostPluginValues.installForwarding(config, state, HostServices.forProject(config), edits::pluginWrite);
                PluginsOnBus.history(bus, edits);
                History history = HostHistory.live();

                assertFalse(history.canUndo());
                history.batch("Move 2 cards", () -> {
                    rewrite(a, "x = 1", "x = 2");
                    rewrite(b, "y = 1", "y = 2");
                    history.batch("an inner batch is the outer one's", () -> rewrite(a, "x = 2", "x = 3"));
                });
                assertTrue(history.canUndo());
                assertEquals("Move 2 cards", history.undoLabel());
                assertTrue(Files.readString(a).contains("x = 3"), "the core wrote it");
                assertTrue(state.getFile(a).orElseThrow().getContent().contains("x = 3"), "the mirror read it");
                assertEquals(3, wrote.get(), "each write relabels the toolbar");

                rewrite(b, "y = 2", "y = 4");
                assertNotEquals("Move 2 cards", history.undoLabel(), "a lone write is a step of its own");
                String lone = history.undoLabel();

                history.batch("Nothing", () -> { });
                assertEquals(lone, history.undoLabel(), "a batch that changed nothing is no step");

                history.undo();
                assertTrue(Files.readString(b).contains("y = 2"), Files.readString(b));
                assertEquals("Move 2 cards", history.undoLabel());
                history.undo();
                assertTrue(Files.readString(a).contains("x = 1"), "a file written twice goes back to its first");
                assertTrue(Files.readString(b).contains("y = 1"));
                assertFalse(history.canUndo());

                history.redo();
                assertTrue(Files.readString(a).contains("x = 3"), "and forward to its last");
                assertTrue(Files.readString(b).contains("y = 2"));
            }
        }
    }

    @Test
    void betweenProjectsThereIsNoHistory() {
        PluginsOnBus.history(new EventBus(false), null);
        assertSame(History.NONE, HostHistory.live());
    }

    /** A plugin's write of {@code file}'s text, as a rename's plan writes it: the one write with no plugin bound. */
    private static void rewrite(Path file, String from, String to) {
        try {
            String text = Files.readString(file);
            assertTrue(HostPluginValues.applyRename(new Refactor.Planned("Change " + from,
                    Map.of(file, text.replace(from, to)), Map.of())).isEmpty());
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
