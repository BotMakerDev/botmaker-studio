package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.debug.DebugSnapshot;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Debug tab: a pause lists its frames with the innermost of the bot's own selected — without moving the
 * canvas, which the pause already did — resuming greys it, the end of the session empties it, and picking
 * another of the bot's frames reveals that frame's block.
 */
class DebugPanelTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {}

    private static final Path BOT = Path.of("/p/src/main/java/com/mybot/Bot.java");

    private static DebugSnapshot pause() {
        DebugSnapshot.Variable rounds = new DebugSnapshot.Variable("rounds", "int", "3", List.of());
        return new DebugSnapshot("main", List.of(
                new DebugSnapshot.Frame("println", "java.io.PrintStream", null, 0, List.of()),
                new DebugSnapshot.Frame("play", "com.mybot.Bot", BOT, 18, List.of(rounds)),
                new DebugSnapshot.Frame("main", "com.mybot.Bot", BOT, 8, List.of())));
    }

    @Test
    void aPauseFillsTheTabAndOnlyAPickRevealsABlock() {
        EventBus bus = new EventBus(false);
        List<DebugSnapshot.Frame> revealed = new ArrayList<>();
        AtomicReference<DebugPanel> panel = new AtomicReference<>();
        interact(() -> panel.set(new DebugPanel(bus, revealed::add)));

        interact(() -> bus.publish(new CoreApplicationEvents.DebugSnapshotEvent(pause())));
        assertEquals(List.of("rounds"), panel.get().shownVariables(), "the bot's innermost frame is selected");
        assertTrue(panel.get().summary().startsWith("Paused in thread \"main\""), panel.get().summary());
        assertTrue(revealed.isEmpty(), "the pause itself does not move the canvas again");

        interact(() -> panel.get().show(pause()));
        assertTrue(revealed.isEmpty());

        interact(() -> bus.publish(new CoreApplicationEvents.DebugSessionResumedEvent()));
        assertEquals("Running…", panel.get().summary());

        interact(() -> bus.publish(new CoreApplicationEvents.DebugSessionFinishedEvent()));
        assertEquals(List.of(), panel.get().shownVariables());
    }

    @Test
    void theFrameLabelIsClassMethodAndLine() {
        assertEquals("Bot.play:18", pause().frames().get(1).label());
        assertEquals("PrintStream.println", pause().frames().getFirst().label());
        assertFalse(pause().frames().getFirst().inBot());
    }
}
