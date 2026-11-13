package com.botmaker.studio.ui.app;

import com.botmaker.core.events.EventBus;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.ui.FxThread;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The Run tab's chips: Output is what the bot printed, Trace its trace lines, Only warnings their warnings and
 * errors; and a trace line, clicked, lands on the line of the bot that wrote it.
 */
class RunConsoleTraceTest extends FxHeadlessTest {

    @TempDir
    static Path project;

    private final EventBus bus = new EventBus(false, FxThread.INSTANCE);
    private final AtomicReference<Map.Entry<Path, Integer>> revealed = new AtomicReference<>();
    private RunConsole console;
    private TracePanel trace;
    private Path botFile;

    @Override
    public void start(Stage stage) {
        Path sources = project.resolve("src/main/java");
        botFile = sources.resolve("com/example/Bot.java");
        try {
            Files.createDirectories(botFile.getParent());
            Files.writeString(botFile, "package com.example; class Bot {}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        console = new RunConsole(bus, () -> { });
        trace = new TracePanel(bus, project, sources, null, (file, line) -> revealed.set(Map.entry(file, line)));
        console.trace(trace);
        stage.setScene(new Scene((Parent) console.node(), 900, 300));
        stage.show();
    }

    private static TraceLine line(TraceLine.Level level, String text, int at) {
        return new TraceLine(Instant.EPOCH, level, "Bot", text, 1, "", "", "com.example.Bot",
                OptionalInt.of(at), Optional.empty());
    }

    private void publishAll() {
        bus.publish(new CoreApplicationEvents.OutputAppendedEvent("printed\n"));
        bus.publish(new CoreApplicationEvents.TraceLineEvent(line(TraceLine.Level.INFO, "went home", 3)));
        bus.publish(new CoreApplicationEvents.TraceLineEvent(line(TraceLine.Level.WARN, "no gold", 12)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Object center() {
        return ((BorderPane) console.node()).getCenter();
    }

    @Test
    void eachChipListsItsOwnLines() {
        publishAll();
        assertEquals(RunConsole.View.OUTPUT, console.shown());
        assertInstanceOf(TextArea.class, center());
        assertEquals("printed\n", console.text());

        clickOn(RunConsole.View.TRACE.displayName());
        assertSame(trace.node(), center());
        assertEquals(List.of("went home", "no gold"), trace.shown().stream().map(TraceLine::text).toList());

        clickOn(RunConsole.View.ONLY_WARNINGS.displayName());
        assertEquals(List.of("no gold"), trace.shown().stream().map(TraceLine::text).toList());

        // Pressing the chip that is on leaves it on.
        clickOn(RunConsole.View.ONLY_WARNINGS.displayName());
        assertEquals(RunConsole.View.ONLY_WARNINGS, console.shown());

        clickOn(RunConsole.View.OUTPUT.displayName());
        assertInstanceOf(TextArea.class, center());
        clickOn(RunConsole.View.TRACE.displayName());
        assertEquals(2, trace.shown().size(), "leaving Only warnings shows every level again");
    }

    @Test
    void aTraceLineClickedLandsOnTheLineThatWroteIt() {
        publishAll();
        interact(() -> console.show(RunConsole.View.ONLY_WARNINGS));
        ListCell<?> row = lookup(".trace-cell").<ListCell<?>>queryAll().stream()
                .filter(c -> c.getText() != null && c.getText().contains("no gold")).findFirst().orElseThrow();
        clickOn(row);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(Map.entry(botFile, 12), revealed.get());
    }
}
