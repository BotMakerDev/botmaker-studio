package com.botmaker.studio.runtime;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.shared.ipc.TelemetryFrame;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * What Studio does with a frame from the bot ({@code docs/refactor/40-run-trace.md}): a debug line becomes the
 * contract's {@link TraceLine}, and everything else is relayed as the bytes it arrived as.
 */
class RunTelemetryTest {

    @Test
    void aDebugLineBecomesATraceLineAndEveryOtherFrameStaysBytes() throws IOException {
        EventBus bus = new EventBus();
        List<Object> published = new ArrayList<>();
        bus.subscribe(CoreApplicationEvents.TraceLineEvent.class, published::add);
        bus.subscribe(CoreApplicationEvents.TelemetryFrameEvent.class, published::add);
        byte[] log = frame(new TelemetryEvent.Log("warn", "Vision", "no ore", 3, 1_000L,
                new TelemetryEvent.Rect(1, 2, 3, 4), "com.example.Collect", 14));
        byte[] click = frame(new TelemetryEvent.Click(new TelemetryEvent.Target(null, 0, 0, 0, 0), 5, 6, 1));

        RunTelemetry.publish(bus, log);
        RunTelemetry.publish(bus, click);

        TraceLine line = assertInstanceOf(CoreApplicationEvents.TraceLineEvent.class, published.get(0)).line();
        assertEquals(new TraceLine(Instant.ofEpochMilli(1_000L), TraceLine.Level.WARN, "Vision", "no ore", 3,
                "com.example.Collect", OptionalInt.of(14), Optional.of(new TraceLine.Region(1, 2, 3, 4))), line);
        assertArrayEquals(click,
                assertInstanceOf(CoreApplicationEvents.TelemetryFrameEvent.class, published.get(1)).frame());
        assertEquals(2, published.size(), "a debug line goes to the trace only, not to telemetry as well");
    }

    @Test
    void aLineWithNoSourceLineSaysSoAndAnUnknownLevelIsKept() throws IOException {
        TraceLine line = RunTelemetry.traceLine(frame(new TelemetryEvent.Log("verbose", "", "hi", 1, 0L, null,
                "", -1))).orElseThrow();

        assertEquals(TraceLine.Level.UNKNOWN, line.level());
        assertEquals(OptionalInt.empty(), line.line());
        assertEquals(Optional.empty(), line.where());
    }

    @Test
    void aNestedClassIsFoundInItsTopLevelFileAndAnUnknownOneNowhere(@TempDir Path root) throws IOException {
        Path file = root.resolve("com/example/Collect.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Collect {}");

        assertEquals(Optional.of(file), RunTelemetry.sourceFile(root, "com.example.Collect"));
        assertEquals(Optional.of(file), RunTelemetry.sourceFile(root, "com.example.Collect$1"));
        assertEquals(Optional.empty(), RunTelemetry.sourceFile(root, "com.botmaker.sdk.api.interaction.Mouse"));
        assertEquals(Optional.empty(), RunTelemetry.sourceFile(root, ""));
    }

    private static byte[] frame(TelemetryEvent event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TelemetryFrame.write(new DataOutputStream(bytes), event);
        return bytes.toByteArray();
    }
}
