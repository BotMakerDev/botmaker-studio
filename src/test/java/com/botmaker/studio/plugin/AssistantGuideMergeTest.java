package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the bound plugins' guides to the bot they expect are gathered. */
class AssistantGuideMergeTest {

    private record Fake(String id, String guide) implements StudioPlugin {
        @Override
        public String displayName() {
            return id.toUpperCase(java.util.Locale.ROOT);
        }

        @Override
        public String assistantGuide() {
            return guide;
        }
    }

    private record Broken(String id) implements StudioPlugin {
        @Override
        public String assistantGuide() {
            throw new IllegalStateException("no");
        }
    }

    /** As a plugin built against an older contract fails, linked against one it does not match. */
    private record Older(String id) implements StudioPlugin {
        @Override
        public String assistantGuide() {
            throw new AbstractMethodError("assistantGuide");
        }
    }

    @Test
    void aPluginThatThrowsOrSaysNothingCostsOnlyItself() {
        List<PluginHost.OwnedGuide> merged = PluginHost.mergeAssistantGuides(List.of(
                new Broken("broken"),
                new Older("older"),
                new Fake("blank", "  "),
                new Fake("nulled", null),
                new Fake("sdk", "\nOne activity per chore.\n")));
        assertEquals(List.of(new PluginHost.OwnedGuide("SDK", "One activity per chore.")), merged);
    }
}
