package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' run overlay parts are gathered. */
class RunOverlayMergeTest {

    private static final RunOverlayPart.Factory NOTHING = context -> null;

    private static LoadedPlugin fake(String artifactId, List<RunOverlayPart> parts) {
        return TestPlugins.loaded(artifactId, StudioPlugin.declare().runOverlay(TestPlugins.holder(() -> parts)));
    }

    private static LoadedPlugin broken(String artifactId) {
        return TestPlugins.loaded(artifactId, StudioPlugin.declare().runOverlay(TestPlugins.holder(() -> {
            throw new IllegalStateException("no");
        })));
    }

    private static List<String> owners(List<PluginHost.OwnedPart> parts) {
        return parts.stream().map(p -> p.plugin().artifactId() + ":" + p.part().id()).toList();
    }

    @Test
    void partsKeepPluginOrderAndARepeatedIdIsDropped() {
        List<PluginHost.OwnedPart> merged = PluginHost.mergeRunOverlayParts(List.of(
                fake("a", List.of(RunOverlayPart.id("run").bar(NOTHING), RunOverlayPart.id("run").layer(NOTHING))),
                fake("b", List.of(RunOverlayPart.id("run").bar(NOTHING)))));
        assertEquals(List.of("a:run", "b:run"), owners(merged), "ids are unique within a plugin, not across");
    }

    @Test
    void aPluginThatThrowsOrOffersNothingCostsOnlyItself() {
        List<PluginHost.OwnedPart> merged = PluginHost.mergeRunOverlayParts(List.of(
                broken("broken"),
                fake("none", null),
                fake("ok", List.of(RunOverlayPart.id("bar").bar(NOTHING)))));
        assertEquals(List.of("ok:bar"), owners(merged));
    }
}
