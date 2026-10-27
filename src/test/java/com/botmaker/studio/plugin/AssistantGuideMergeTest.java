package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' guides to the bot they expect are gathered. */
class AssistantGuideMergeTest {

    private static LoadedPlugin guiding(String artifactId, String guide) {
        return TestPlugins.loaded(artifactId, artifactId.toUpperCase(java.util.Locale.ROOT),
                TestPlugins.plugin(StudioPlugin.declare().assistantGuide(guide)));
    }

    @Test
    void aPluginThatSaysNothingCostsOnlyItself() {
        List<PluginHost.OwnedGuide> merged = PluginHost.mergeAssistantGuides(List.of(
                guiding("blank", "  "),
                guiding("nulled", null),
                TestPlugins.loaded("silent", StudioPlugin.declare()),
                guiding("sdk", "\nOne activity per chore.\n")));
        assertEquals(List.of(new PluginHost.OwnedGuide("SDK", "One activity per chore.")), merged);
    }
}
