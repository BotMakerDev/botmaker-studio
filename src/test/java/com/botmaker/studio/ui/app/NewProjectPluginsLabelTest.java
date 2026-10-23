package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishedBotPlugins;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each checklist row names the version the new project gets. */
class NewProjectPluginsLabelTest {

    private static PluginRegistry.Plugin plugin(String id, String name, String verified) {
        return new PluginRegistry.Plugin(id, name, "com.github.BotMakerDev:" + id, "LiQiyeDev/" + id, "", null,
                null, null, verified);
    }

    @Test
    void anOrdinaryRowShowsTheVerifiedVersion() {
        assertEquals("SDK  v1.2.3", NewProjectPlugins.label(plugin("sdk", "SDK", "1.2.3"), null));
        assertEquals("Basics  v0.1.1", NewProjectPlugins.label(plugin("basics", "Basics", "v0.1.1"), null));
    }

    @Test
    void aBotsOwnRowShowsItsPinNotTheRegistrys() {
        PublishedBotPlugins.Pin pin = new PublishedBotPlugins.Pin("basics", "v0.0.8");

        assertEquals("Basics  v0.0.8  — comes with the bot",
                NewProjectPlugins.label(plugin("basics", "Basics", "v0.1.1"), pin));
    }

    @Test
    void aVersionNobodyPinnedIsLeftOut() {
        assertEquals("Extra", NewProjectPlugins.label(plugin("extra", "Extra", ""), null));
        assertEquals("Basics  — comes with the bot", NewProjectPlugins.label(plugin("basics", "Basics", "v1"),
                new PublishedBotPlugins.Pin("basics", "")));
    }

    @Test
    void theBotsPinsAreItsLockedRows() {
        PublishedBotPlugins.Pin basics = new PublishedBotPlugins.Pin("basics", "v0.0.8");

        assertEquals(Map.of("basics", basics), NewProjectPlugins.locked(List.of(basics, basics)));
        assertEquals(Map.of(), NewProjectPlugins.locked(List.of()));
    }
}
