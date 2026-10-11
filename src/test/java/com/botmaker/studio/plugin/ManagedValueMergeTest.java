package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.project.managed.TestValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' managed values are gathered. */
class ManagedValueMergeTest {

    private record Fake(String id, List<ManagedValue<?>> managedValues) implements StudioPlugin {}

    /**
     * A retired shape is read only by an upgrade's conversion (2026-10-11): never locked, created or offered, so
     * it is not among the values the host serves.
     */
    @Test
    void aRetiredValueIsNotServed() {
        ManagedValue<String> live = ManagedValue.method(TestValue.Id.GREETING).in("Sdk").holds(String.class, "")
                .because("New.");
        ManagedValue<String> retired = ManagedValue.method(TestValue.Id.GREETING_V1).in("Sdk")
                .holds(String.class, "").because("Old.").retired();
        assertEquals(List.of(live), PluginHost.mergeManagedValues(List.of(new Fake("p", List.of(retired, live)))));
    }
}
