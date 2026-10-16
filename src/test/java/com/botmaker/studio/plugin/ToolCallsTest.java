package com.botmaker.studio.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin tool's call takes one Project History version, whatever threads its writes are made on. */
class ToolCallsTest {

    @Test
    void aCallsWritesTakeOneVersionWhateverTheirThread() {
        List<Boolean> taken = ToolCalls.once(() -> {
            List<Boolean> each = new ArrayList<>();
            each.add(ToolCalls.firstCheckpoint());
            // Another thread, as a write the plugin hops to the FX thread for.
            each.add(CompletableFuture.supplyAsync(ToolCalls::firstCheckpoint).join());
            each.add(ToolCalls.firstCheckpoint());
            return each;
        });
        assertEquals(List.of(true, false, false), taken);
        assertTrue(ToolCalls.firstCheckpoint(), "outside a call, each write takes its version");
        assertTrue(ToolCalls.once(ToolCalls::firstCheckpoint), "the next call takes its own");
    }
}
