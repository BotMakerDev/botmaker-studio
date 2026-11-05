package com.botmaker.studio.services;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreMain;
import com.botmaker.core.server.CoreServer;
import com.botmaker.core.server.OpenProject;
import com.botmaker.core.services.MavenService;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.studio.core.EditorState;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Studio's bus to a core and back: a run asked as the toolbar asks it, heard as the windows hear it. */
class CoreRunsTest extends FxHeadlessTest {

    private static final String VERSION = "1.0";
    private static final String FILE = "com/mybot/Mybot.java";
    private static final String BOT = """
            package com.mybot;
            public class Mybot {
                public static void main(String[] args) throws Exception {
                    System.out.println("hello from the bot");
                    if (java.nio.file.Files.exists(java.nio.file.Path.of("loop"))) Thread.sleep(600_000);
                }
            }
            """;

    /** The project's libraries are this test's classpath; nothing is resolved or bound. */
    static final OpenProject.Libraries LIBRARIES = new OpenProject.Libraries() {
        @Override
        public MavenService.Resolution resolve(ProjectConfig config, ProjectState state) {
            return new MavenService.Resolution(
                    Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), List.of());
        }

        @Override
        public ProjectAnalyzer analyzer(List<String> classpath, ProjectState state) {
            return new ProjectAnalyzer(null, state);
        }

        @Override
        public void bind(ProjectConfig config, List<String> classpath) {
        }

        @Override
        public void unbind() {
        }

        @Override
        public void reindex(ProjectAnalyzer analyzer, List<String> classpath) {
        }
    };

    @Override
    public void start(Stage stage) {
        // No scene: FxThread only needs Platform.runLater to have somewhere to run.
    }

    @Test
    void aRunAskedOnTheBusIsHeardOnTheBusAndStops(@TempDir Path cache, @TempDir Path projects) throws Exception {
        Path project = projects.resolve("mybot");
        Path source = project.resolve("src/main/java").resolve(FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, BOT);
        ProjectConfig config = ProjectConfig.forDirectory(project);
        EditorState state = new EditorState();
        state.setSourcePath(config.sourceRoot());
        state.addFile(new ProjectFile(config.sourceRoot().resolve(FILE), BOT));
        state.setActiveFile(config.sourceRoot().resolve(FILE));

        EventBus bus = new EventBus();
        Queue<String> output = new ConcurrentLinkedQueue<>();
        CountDownLatch firstEnded = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(2);
        CountDownLatch secondEnded = new CountDownLatch(2);
        bus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class, event -> output.add(event.text()));
        bus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, event -> secondStarted.countDown());
        bus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, event -> {
            firstEnded.countDown();
            secondEnded.countDown();
        });

        CoreServer.Idle idle = new CoreServer.Idle(Duration.ofMinutes(10), Duration.ofSeconds(30));
        try (CoreMain.Running core = CoreMain.serve(cache, VERSION, idle, LIBRARIES, () -> { })) {
            // Finds the core just served: nothing is started.
            CoreLauncher.Address at = CoreLauncher.connect(cache, VERSION, CoreLauncher.process());
            try (CoreEdits edits = CoreEdits.open(at, VERSION, config, state, bus, EventBus.UiThread.INLINE);
                 CoreRuns runs = CoreRuns.attach(edits, new DiagnosticsManager(), state, config, bus)) {
                edits.heardBy(event -> { });
                edits.show(FILE);

                bus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
                assertTrue(runs.isRunning(), "asked and not ended: a second Run must not start a second bot");
                assertTrue(firstEnded.await(2, TimeUnit.MINUTES), "the run never ended on the bus");
                assertFalse(runs.isRunning());
                assertTrue(runs.pid().isEmpty(), "no pid outlives its run");
                awaitOutput(output);

                Files.writeString(project.resolve("loop"), "");
                bus.publish(new CoreApplicationEvents.ExecutionRequestedEvent());
                assertTrue(secondStarted.await(2, TimeUnit.MINUTES), "the second run never started");
                long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
                while (runs.pid().isEmpty()) {
                    assertTrue(System.nanoTime() < until, "the bot's process was never told");
                    Thread.sleep(20);
                }
                bus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
                assertTrue(secondEnded.await(1, TimeUnit.MINUTES), "Stop never ended the run");
                assertEquals(0, secondEnded.getCount());
            }
        }
    }

    /** Output reaches the bus on the FX thread, so after the run's end has. */
    private static void awaitOutput(Queue<String> output) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!String.join("", output).contains("hello from the bot")) {
            assertTrue(System.nanoTime() < until, () -> "the bot's output never reached the bus: " + output);
            Thread.sleep(20);
        }
    }
}
