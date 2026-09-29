package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.shared.ipc.TelemetryFrame;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.CodeExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The channel through which a plugin reaches the open project's bot.
 *
 * <p>Two properties here are the ones that would otherwise be discovered late. A plugin that unregisters
 * must actually stop being called — the {@code EventBus} has no unsubscribe, so if the handle did not remove
 * the listener from Studio's own list, every project opened would leave a dead one behind. And between
 * projects the channel must answer {@link Runs#NONE}: an editor built for a project the user has since left
 * must not be able to start that project's bot.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class HostRunsTest {

    @AfterEach
    void clear() {
        HostRuns.clear();
    }

    @Test
    void between_projects_there_is_nothing_to_reach() {
        HostRuns.clear();

        assertSame(Runs.NONE, HostRuns.live(),
                "an editor outliving its project must not be able to start that project's bot");
        assertFalse(Runs.NONE.isRunning());
        assertEquals(OptionalLong.empty(), Runs.NONE.pid());
        HostRuns.status("dropped on the floor, not thrown");
    }

    /** The no-op host is total: registering and unregistering on it must be safe, not merely harmless. */
    @Test
    void the_empty_channel_registers_and_unregisters_without_complaint() throws Exception {
        AutoCloseable state = Runs.NONE.onStateChanged(running -> { });
        AutoCloseable telemetry = Runs.NONE.onTelemetry(frame -> { });

        assertNotNull(state);
        assertNotNull(telemetry);
        state.close();
        telemetry.close();
    }

    /**
     * A frame handed to a plugin is the bytes the bot wrote, which the plugin's own copy of the format reads
     * back: Studio relays what it does not read. Each listener gets its own copy, so one plugin cannot edit the
     * frame the next one reads.
     */
    @Test
    void a_telemetry_frame_crosses_as_the_bytes_the_bot_wrote() throws Exception {
        TelemetryEvent original = new TelemetryEvent.Click(
                new TelemetryEvent.Target("Diablo IV", 0, 0, 1920, 1080), 640, 480, 1, 12);
        byte[] frame = encode(original);
        EventBus bus = new EventBus();
        CodeExecutionService execution = new CodeExecutionService(null, null, null, bus);
        try {
            HostRuns.install(bus, execution, null);
            List<byte[]> first = new ArrayList<>();
            List<byte[]> second = new ArrayList<>();
            HostRuns.live().onTelemetry(bytes -> {
                first.add(bytes);
                bytes[0] = 99;
            });
            HostRuns.live().onTelemetry(second::add);

            bus.publish(new CoreApplicationEvents.TelemetryFrameEvent(frame));

            assertEquals(1, first.size());
            assertEquals(original, TelemetryFrame.decode(second.getFirst()),
                    "a plugin decodes the frame whatever another plugin did to its copy");
        } finally {
            execution.close();
        }
    }

    /** A debug line reaches onTrace as the contract's shape, and a closed handle stops it arriving. */
    @Test
    void a_trace_line_reaches_its_listeners_until_they_unregister() throws Exception {
        EventBus bus = new EventBus();
        CodeExecutionService execution = new CodeExecutionService(null, null, null, bus);
        try {
            HostRuns.install(bus, execution, null);
            List<TraceLine> heard = new ArrayList<>();
            AutoCloseable handle = HostRuns.live().onTrace(heard::add);
            TraceLine line = new TraceLine(Instant.EPOCH, TraceLine.Level.WARN, "Game", "slow", 1,
                    "com.example.Bot", "run", "com.example.Bot", OptionalInt.of(3), Optional.empty());

            bus.publish(new CoreApplicationEvents.TraceLineEvent(line));
            handle.close();
            bus.publish(new CoreApplicationEvents.TraceLineEvent(line));

            assertEquals(List.of(line), heard);
        } finally {
            execution.close();
        }
    }

    private static byte[] encode(TelemetryEvent event) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream(256);
        try (var out = new java.io.DataOutputStream(bytes)) {
            TelemetryFrame.write(out, event);
        }
        return bytes.toByteArray();
    }
}
