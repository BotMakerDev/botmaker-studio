package com.botmaker.studio.ui.app.run;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.studio.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the run overlay opens and closes, and what its controls publish. */
class RunOverlayTest {

    /** Windows the test reads instead of looking at a screen. */
    private static final class FakeSurface implements RunOverlay.Surface {
        final RunOverlay.Session session;
        List<TraceLine> tail = List.of();
        boolean closed;

        FakeSurface(RunOverlay.Session session) {
            this.session = session;
        }

        @Override
        public void trace(List<TraceLine> tail) {
            this.tail = tail;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final EventBus bus = new EventBus(false);
    private final List<FakeSurface> opened = new ArrayList<>();
    private final List<ApplicationEvent> requests = new ArrayList<>();
    private boolean enabled = true;

    private RunOverlay overlay() {
        bus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class, requests::add);
        bus.subscribe(CoreApplicationEvents.DebugStopRequestedEvent.class, requests::add);
        bus.subscribe(CoreApplicationEvents.ExecutionRequestedEvent.class, requests::add);
        return new RunOverlay(bus, () -> enabled, session -> {
            FakeSurface surface = new FakeSurface(session);
            opened.add(surface);
            return surface;
        }, false);
    }

    private static TraceLine line(String text) {
        return new TraceLine(Instant.EPOCH, TraceLine.Level.INFO, "", text, 1, "", "", "", OptionalInt.empty(),
                Optional.empty());
    }

    @Test
    void opensForARunAndClosesWhenItEnds() {
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            assertEquals(1, opened.size());
            assertFalse(opened.getFirst().session.debugging());

            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            assertEquals(1, opened.size(), "one overlay per run");

            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            assertTrue(opened.getFirst().closed);
        }
    }

    @Test
    void aDebugSessionOpensItAndItsStopIsTheDebugger() {
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.DebugSessionStartedEvent());
            FakeSurface surface = opened.getFirst();
            assertTrue(surface.session.debugging());

            surface.session.stop();
            assertInstanceOf(CoreApplicationEvents.DebugStopRequestedEvent.class, requests.getFirst());

            surface.session.runAgain();
            assertEquals(1, requests.size(), "a debug session is not run again from here");

            bus.publish(new CoreApplicationEvents.DebugSessionFinishedEvent());
            assertTrue(surface.closed);
        }
    }

    @Test
    void offInTheViewMenuOpensNothing() {
        enabled = false;
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            assertTrue(opened.isEmpty());
        }
    }

    @Test
    void stopAsksTheRunToStop() {
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            opened.getFirst().session.stop();
            assertEquals(List.of(new CoreApplicationEvents.StopRunRequestedEvent()), requests);
        }
    }

    @Test
    void runAgainStopsThenStartsOnceTheRunHasEnded() {
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            opened.getFirst().session.runAgain();
            assertEquals(List.of(new CoreApplicationEvents.StopRunRequestedEvent()), requests);

            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            assertInstanceOf(CoreApplicationEvents.ExecutionRequestedEvent.class, requests.getLast());

            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            assertEquals(2, requests.size(), "the next run's end starts nothing");
        }
    }

    @Test
    void hiddenStaysHiddenUntilTheNextRun() {
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            opened.getFirst().session.dismiss();
            assertTrue(opened.getFirst().closed);

            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            assertEquals(2, opened.size());
        }
    }

    @Test
    void theBarGetsTheTraceTailAndANewRunStartsItEmpty() {
        try (RunOverlay ignored = overlay()) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            for (String text : List.of("a", "b", "c", "d")) {
                bus.publish(new CoreApplicationEvents.TraceLineEvent(line(text)));
            }
            assertEquals(List.of("b", "c", "d"), opened.getFirst().tail.stream().map(TraceLine::text).toList());

            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            bus.publish(new CoreApplicationEvents.TraceLineEvent(line("e")));
            assertEquals(List.of("e"), opened.getLast().tail.stream().map(TraceLine::text).toList());
        }
    }

    @Test
    void closingTheProjectClosesTheWindowsAndHearsNoMore() {
        RunOverlay overlay = overlay();
        bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
        overlay.close();
        assertTrue(opened.getFirst().closed);
        bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
        assertEquals(1, opened.size());
    }
}
