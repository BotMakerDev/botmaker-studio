package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Run tab: the bot's output lands there without its colour escapes, a run or a debug session raises the
 * tab, and Stop asks whichever of the two is running to stop.
 */
class RunConsoleTest extends FxHeadlessTest {

    private final EventBus bus = new EventBus();
    private final AtomicInteger raised = new AtomicInteger();
    private final List<ApplicationEvent> stops = new CopyOnWriteArrayList<>();
    private RunConsole console;

    @Override
    public void start(Stage stage) {
        console = new RunConsole(bus, raised::incrementAndGet);
        bus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class, stops::add);
        bus.subscribe(CoreApplicationEvents.DebugStopRequestedEvent.class, stops::add);
        stage.setScene(new Scene((javafx.scene.Parent) console.node(), 600, 300));
        stage.show();
    }

    private void publish(ApplicationEvent event) {
        bus.publish(event);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void outputArrivesWithoutColourEscapes() {
        publish(new CoreApplicationEvents.OutputAppendedEvent("\u001B[32mINFO\u001B[0m started\n"));
        publish(new CoreApplicationEvents.OutputAppendedEvent("\u001B]0;title\u0007plain\n"));
        assertEquals("INFO started\nplain\n", console.text());
    }

    @Test
    void textWithoutAnEscapeIsLeftAlone() {
        String line = "BM-INPUT is a marker, [32m without ESC is text\n";
        assertEquals(line, RunConsole.stripEscapes(line));
    }

    @Test
    void aLongRunKeepsOnlyItsTail() {
        publish(new CoreApplicationEvents.OutputAppendedEvent("x".repeat(RunConsole.LIMIT + 1)));
        publish(new CoreApplicationEvents.OutputAppendedEvent("end\n"));
        String text = console.text();
        assertTrue(text.startsWith(RunConsole.TRIMMED), text.substring(0, 40));
        assertTrue(text.endsWith("end\n"));
        assertEquals(RunConsole.TRIMMED.length() + RunConsole.KEEP + 4, text.length());
    }

    @Test
    void stopStopsTheRun() {
        assertFalse(console.canStop(), "nothing is running yet");
        publish(new CoreApplicationEvents.ProgramStartedEvent());
        assertEquals(1, raised.get(), "starting a run raises the Run tab");
        assertTrue(console.canStop());

        clickOn(stopButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, stops.size());
        assertTrue(stops.getFirst() instanceof CoreApplicationEvents.StopRunRequestedEvent);

        publish(new CoreApplicationEvents.ProgramStoppedEvent());
        assertFalse(console.canStop());
    }

    @Test
    void stopStopsTheDebugSession() {
        publish(new CoreApplicationEvents.DebugSessionStartedEvent());
        assertEquals(1, raised.get());
        clickOn(stopButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(stops.getFirst() instanceof CoreApplicationEvents.DebugStopRequestedEvent);
        publish(new CoreApplicationEvents.DebugSessionFinishedEvent());
        assertFalse(console.canStop());
    }

    @Test
    void clearEmptiesTheConsoleAndDisposeUnsubscribes() {
        publish(new CoreApplicationEvents.OutputAppendedEvent("line\n"));
        clickOn(lookup(".button").<Button>queryAll().stream()
                .filter(b -> "Clear".equals(b.getText())).findFirst().orElseThrow());
        assertEquals("", console.text());

        interact(console::dispose);
        publish(new CoreApplicationEvents.OutputAppendedEvent("after\n"));
        assertEquals("", console.text(), "a disposed console hears nothing");
    }

    private Button stopButton() {
        return lookup(".button").<Button>queryAll().stream()
                .filter(b -> b.getText().contains("Stop")).findFirst().orElseThrow();
    }
}
