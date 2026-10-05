package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.overlay.OverlayPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' overlay editor parts are gathered. */
class OverlayPartMergeTest {

    /** A target type of this test's own: any interface will do. */
    private interface Body {
        void run();
    }

    private record Fake(String id, Optional<OverlayPart> part) implements StudioPlugin {
        @Override
        public Optional<OverlayPart> overlayPart() {
            return part;
        }
    }

    private record Broken(String id) implements StudioPlugin {
        @Override
        public Optional<OverlayPart> overlayPart() {
            throw new IllegalStateException("no");
        }
    }

    @Test
    void aPluginThatThrowsOrDeclaresNothingCostsOnlyItself() {
        OverlayPart targets = OverlayPart.of().targets(Body.class, "Activities");
        List<PluginHost.OwnedOverlay> merged = PluginHost.mergeOverlayParts(List.of(
                new Broken("broken"),
                new Fake("none", Optional.empty()),
                new Fake("empty", Optional.of(OverlayPart.of())),
                new Fake("nulled", null),
                new Fake("ok", Optional.of(targets))));
        assertEquals(List.of("ok"), merged.stream().map(PluginHost.OwnedOverlay::pluginId).toList());
        assertEquals(targets, merged.getFirst().part());
    }
}
