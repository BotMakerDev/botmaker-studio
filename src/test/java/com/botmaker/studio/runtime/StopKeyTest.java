package com.botmaker.studio.runtime;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.studio.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopKeyTest {

    private static final long KEY = StopKeyPreference.PAUSE;

    /** A listener the test drives by hand. */
    private static final class FakeListener implements InputListener {
        Consumer<InputEvent> sink;
        boolean closed;

        @Override
        public void start(Consumer<InputEvent> sink) {
            this.sink = sink;
        }

        @Override
        public void close() {
            closed = true;
        }

        void press(long keysym) {
            sink.accept(new InputEvent.KeyPress(0, keysym, 0));
        }
    }

    private final EventBus bus = new EventBus(false);
    private final List<FakeListener> made = new ArrayList<>();
    private final List<ApplicationEvent> stops = new ArrayList<>();

    private StopKey stopKey(boolean supported) {
        bus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class, stops::add);
        bus.subscribe(CoreApplicationEvents.DebugStopRequestedEvent.class, stops::add);
        return new StopKey(bus, () -> {
            FakeListener listener = new FakeListener();
            made.add(listener);
            return listener;
        }, () -> KEY, supported, Runnable::run);
    }

    @Test
    void listensOnlyWhileARunIsLive() {
        try (StopKey ignored = stopKey(true)) {
            assertTrue(made.isEmpty(), "nothing runs yet");

            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            assertEquals(1, made.size());
            assertFalse(made.getFirst().closed);

            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            assertTrue(made.getFirst().closed);
        }
    }

    @Test
    void theKeyStopsTheRunOnceHoweverLongItIsHeld() {
        try (StopKey ignored = stopKey(true)) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());

            made.getFirst().press(KEY);
            made.getFirst().press(KEY);

            assertEquals(1, stops.size());
            assertTrue(stops.getFirst() instanceof CoreApplicationEvents.StopRunRequestedEvent);
        }
    }

    @Test
    void otherKeysAndReleasesDoNothing() {
        try (StopKey ignored = stopKey(true)) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            FakeListener listener = made.getFirst();

            listener.press(0xFFC5); // F8
            listener.sink.accept(new InputEvent.KeyRelease(0, KEY, 0));

            assertTrue(stops.isEmpty());
        }
    }

    @Test
    void aDebugSessionIsStoppedAsADebugSession() {
        try (StopKey ignored = stopKey(true)) {
            bus.publish(new CoreApplicationEvents.DebugSessionStartedEvent());

            made.getFirst().press(KEY);

            assertEquals(1, stops.size());
            assertTrue(stops.getFirst() instanceof CoreApplicationEvents.DebugStopRequestedEvent);
        }
    }

    @Test
    void theNextRunCanBeStoppedAgain() {
        try (StopKey ignored = stopKey(true)) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            made.getFirst().press(KEY);
            bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());

            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
            made.get(1).press(KEY);

            assertEquals(2, stops.size());
        }
    }

    @Test
    void aPlatformWithoutTheListenerDoesNothing() {
        try (StopKey ignored = stopKey(false)) {
            bus.publish(new CoreApplicationEvents.ProgramStartedEvent());

            assertTrue(made.isEmpty());
        }
    }

    @Test
    void closingTheProjectClosesTheListenerAndStopsListening() {
        StopKey stopKey = stopKey(true);
        bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
        FakeListener listener = made.getFirst();

        stopKey.close();
        bus.publish(new CoreApplicationEvents.ProgramStartedEvent());

        assertTrue(listener.closed);
        assertEquals(1, made.size(), "a closed stop key starts no new listener");
    }

    @Test
    void escapeAndModifiersAreNeverOffered() {
        assertTrue(StopKeyPreference.offerable(StopKeyPreference.PAUSE));
        assertTrue(StopKeyPreference.offerable(0xFFC5)); // F8
        assertFalse(StopKeyPreference.offerable(StopKeyPreference.ESCAPE));
        assertFalse(StopKeyPreference.offerable(0xFFE3)); // Control_L
        assertFalse(StopKeyPreference.offerable(0xFFE1)); // Shift_L
        assertFalse(StopKeyPreference.offerable(0xFFEB)); // Super_L
        assertFalse(StopKeyPreference.offerable(0xFE03)); // AltGr
        assertFalse(StopKeyPreference.offerable(0));
    }

    @Test
    void pauseIsNamedByXOrByItsCode() {
        String name = StopKeyPreference.nameOf(StopKeyPreference.PAUSE);
        assertTrue(name.equals("Pause") || name.equals("key 0xFF13"), name);
    }
}
