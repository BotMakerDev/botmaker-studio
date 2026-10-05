package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.RunOverlayPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' run overlay parts are gathered. */
class RunOverlayMergeTest {

    private static final RunOverlayPart.Factory NOTHING = context -> null;

    private record Fake(String id, List<RunOverlayPart> parts) implements StudioPlugin {
        @Override
        public List<RunOverlayPart> runOverlayParts() {
            return parts;
        }
    }

    private record Broken(String id) implements StudioPlugin {
        @Override
        public List<RunOverlayPart> runOverlayParts() {
            throw new IllegalStateException("no");
        }
    }

    private static List<String> owners(List<PluginHost.OwnedPart> parts) {
        return parts.stream().map(p -> p.pluginId() + ":" + p.part().id()).toList();
    }

    @Test
    void partsKeepPluginOrderAndARepeatedIdIsDropped() {
        List<PluginHost.OwnedPart> merged = PluginHost.mergeRunOverlayParts(List.of(
                new Fake("a", List.of(RunOverlayPart.id("run").bar(NOTHING), RunOverlayPart.id("run").layer(NOTHING))),
                new Fake("b", List.of(RunOverlayPart.id("run").bar(NOTHING)))));
        assertEquals(List.of("a:run", "b:run"), owners(merged), "ids are unique within a plugin, not across");
    }

    @Test
    void aPluginThatThrowsOrOffersNothingCostsOnlyItself() {
        List<PluginHost.OwnedPart> merged = PluginHost.mergeRunOverlayParts(List.of(
                new Broken("broken"),
                new Fake("none", null),
                new Fake("ok", java.util.Arrays.asList(null, RunOverlayPart.id("bar").bar(NOTHING)))));
        assertEquals(List.of("ok:bar"), owners(merged));
    }
}
