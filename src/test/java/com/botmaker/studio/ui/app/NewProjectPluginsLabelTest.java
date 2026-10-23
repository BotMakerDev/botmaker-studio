package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishedBotPlugins;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
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

    /** Community rows are hidden unless shown (2026-10-23), but a bot's own plugin stays whatever its rank. */
    @Test
    void aBotsOwnCommunityPluginIsShownWithTheToggleOff() {
        PluginRegistry.Plugin official = new PluginRegistry.Plugin("sdk", "SDK", "g:sdk", "o/sdk", "", null, null,
                null, "", "v1", PublisherRank.ADMIN, 0);
        PluginRegistry.Plugin farm = plugin("farm", "Farm", "v1");   // the 9-argument row: community
        PluginRegistry.Plugin other = plugin("other", "Other", "v1");
        List<PluginRegistry.Plugin> rows = List.of(other, farm, official);
        Map<String, PublishedBotPlugins.Pin> locked = NewProjectPlugins.locked(
                List.of(new PublishedBotPlugins.Pin("farm", "v1")));

        assertEquals(List.of(official), NewProjectPlugins.visible(rows, new ShowCommunity(false), Map.of()));
        assertEquals(List.of(official, farm), NewProjectPlugins.visible(rows, new ShowCommunity(false), locked));
        assertEquals(List.of(official, farm, other), NewProjectPlugins.visible(rows, new ShowCommunity(true), locked));
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
