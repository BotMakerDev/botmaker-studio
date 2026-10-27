package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' overlay editor parts are gathered. */
class OverlayPartMergeTest {

    /** A target type of this test's own: any interface will do. */
    private interface Body {
        void run();
    }

    private static LoadedPlugin offering(String artifactId, Supplier<OverlayPart> part) {
        return TestPlugins.loaded(artifactId, StudioPlugin.declare().overlay(part));
    }

    @Test
    void aPluginThatThrowsOrDeclaresNothingCostsOnlyItself() {
        OverlayPart targets = OverlayPart.of().targets(Body.class, "Activities");
        List<PluginHost.OwnedOverlay> merged = PluginHost.mergeOverlayParts(List.of(
                offering("broken", () -> {
                    throw new IllegalStateException("no");
                }),
                TestPlugins.loaded("none", StudioPlugin.declare()),
                offering("empty", OverlayPart::of),
                offering("nulled", () -> null),
                offering("ok", () -> targets)));
        assertEquals(List.of(TestPlugins.coordinate("ok")),
                merged.stream().map(PluginHost.OwnedOverlay::plugin).toList());
        assertEquals(targets, merged.getFirst().part());
    }
}
