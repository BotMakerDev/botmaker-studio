package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishedBotPlugins;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each plugin card names the version the new project gets (a checklist row until 2026-10-24). */
class NewProjectPluginsLabelTest {

    private static PluginRegistry.Plugin plugin(String id, String name, String verified) {
        return new PluginRegistry.Plugin(name, "com.github.BotMakerDev:" + id, "LiQiyeDev/" + id, "", null,
                null, null, verified);
    }

    @Test
    void anOrdinaryCardShowsTheVerifiedVersion() {
        assertEquals("v1.2.3", NewProjectPlugins.version(plugin("sdk", "SDK", "1.2.3"), null));
        assertEquals("v0.1.1", NewProjectPlugins.version(plugin("basics", "Basics", "v0.1.1"), null));
    }

    @Test
    void aBotsOwnCardShowsItsPinNotTheRegistrys() {
        PublishedBotPlugins.Pin pin = new PublishedBotPlugins.Pin("basics", "v0.0.8");

        assertEquals("v0.0.8", NewProjectPlugins.version(plugin("basics", "Basics", "v0.1.1"), pin));
    }

    /** Community cards are hidden unless shown (2026-10-23), but a locked plugin stays whatever its rank. */
    @Test
    void aLockedCommunityPluginIsShownWithTheToggleOff() {
        PluginRegistry.Plugin official = new PluginRegistry.Plugin("SDK", "g:sdk", "o/sdk", "", null, null,
                null, "", "v1", PublisherRank.ADMIN, 0);
        PluginRegistry.Plugin farm = plugin("farm", "Farm", "v1");   // the 9-argument row: community
        PluginRegistry.Plugin other = plugin("other", "Other", "v1");
        List<PluginRegistry.Plugin> rows = List.of(other, farm, official);

        assertEquals(List.of(official), NewProjectPlugins.visible(rows, new ShowCommunity(false), Set.of()));
        assertEquals(List.of(official, farm),
                NewProjectPlugins.visible(rows, new ShowCommunity(false), Set.of("LiQiyeDev/farm")));
        assertEquals(List.of(official, farm, other),
                NewProjectPlugins.visible(rows, new ShowCommunity(true), Set.of("LiQiyeDev/farm")));
    }

    @Test
    void aVersionNobodyPinnedIsLeftOut() {
        assertEquals("", NewProjectPlugins.version(plugin("extra", "Extra", ""), null));
        assertEquals("", NewProjectPlugins.version(plugin("basics", "Basics", "v1"),
                new PublishedBotPlugins.Pin("basics", "")));
    }

    @Test
    void aNamelessPluginIsNamedByItsCoordinate() {
        assertEquals("com.github.BotMakerDev:extra", NewProjectPlugins.name(plugin("extra", "", "v1")));
    }

    @Test
    void theBotsPinsAreItsLockedRows() {
        PublishedBotPlugins.Pin basics = new PublishedBotPlugins.Pin("basics", "v0.0.8");

        assertEquals(Map.of("basics", basics), NewProjectPlugins.locked(List.of(basics, basics)));
        assertEquals(Map.of(), NewProjectPlugins.locked(List.of()));
    }
}
